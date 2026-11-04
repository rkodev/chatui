import { RouterProvider } from '@tanstack/react-router'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConfigContext } from '../../app/config'
import type { ChatParams, Conversation } from '../../domain'
import { ProviderManagementProvider } from '../providers/ProviderManagementProvider'
import { createTestRouter } from '../providers/testRouter'
import {
  createFakeProviderManagementService,
  createTestConversation,
  createTestModel,
  createTestProvider,
  testConfig,
} from '../providers/testSupport'
import { ConversationSettingsScreen } from './ConversationSettingsScreen'

const model = createTestModel({
  supportedParameters: ['temperature', 'top_p', 'max_tokens', 'stop'],
})
const provider = createTestProvider()

const baseParams: ChatParams = {
  temperature: 0.7,
  topP: 0.9,
  maxOutputTokens: 512,
  stop: ['END'],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

function renderSettings(
  conversation: Conversation,
  serviceOverrides: Parameters<typeof createFakeProviderManagementService>[0] = {},
) {
  const service = createFakeProviderManagementService({
    getConversation: async () => conversation,
    listModels: async () => [model],
    listProviders: async () => [provider],
    ...serviceOverrides,
  })

  const router = createTestRouter(
    () => (
      <ConfigContext.Provider value={testConfig}>
        <ProviderManagementProvider service={service}>
          <ConversationSettingsScreen conversationId={conversation.id} />
        </ProviderManagementProvider>
      </ConfigContext.Provider>
    ),
    'chat/$conversationId/settings',
    '/chat/conversation-1/settings',
    ['/', 'chat/$conversationId'],
  )

  render(<RouterProvider router={router} />)
  return service
}

describe('ConversationSettingsScreen', () => {
  it('loads the conversation settings route content', async () => {
    renderSettings(createTestConversation())

    expect(
      await screen.findByRole('heading', { name: 'Conversation settings' }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Back to chat' })).toHaveAttribute(
      'href',
      '/chat/conversation-1',
    )
  })

  it('persists the system prompt and generation parameters', async () => {
    const conversation = createTestConversation()
    const setConversationSettings = vi.fn(async (_id: string, patch) => ({
      ...conversation,
      ...patch,
    }))
    renderSettings(conversation, { setConversationSettings })

    fireEvent.change(await screen.findByLabelText('System prompt'), {
      target: { value: 'Answer briefly.' },
    })

    await waitFor(() =>
      expect(setConversationSettings).toHaveBeenCalledWith('conversation-1', {
        systemPrompt: 'Answer briefly.',
      }),
    )

    fireEvent.change(screen.getByLabelText('Temperature'), { target: { value: '0.4' } })

    await waitFor(() =>
      expect(setConversationSettings).toHaveBeenCalledWith(
        'conversation-1',
        expect.objectContaining({
          params: expect.objectContaining({ temperature: 0.4 }),
        }),
      ),
    )
  })

  it('explains unsupported values while keeping them in the conversation', async () => {
    const conversation = createTestConversation({
      params: { ...baseParams, reasoningEffort: 'high' },
    })
    renderSettings(conversation, {
      listModels: async () => [createTestModel({ supportedParameters: ['temperature'] })],
    })

    expect(
      await screen.findByText(
        'This model ignores these values, so they are not sent: Top P, Max output tokens, Stop sequences, Reasoning effort.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Top P')).toHaveValue(0.9)
    expect(screen.getByLabelText(/^Max output tokens/)).toHaveValue(512)
  })

  it('validates provider extras and rejects protected fields', async () => {
    const conversation = createTestConversation()
    const setConversationSettings = vi.fn(async (_id: string, patch) => ({
      ...conversation,
      ...patch,
    }))
    renderSettings(conversation, { setConversationSettings })

    const editor = await screen.findByLabelText('openrouter extras')

    fireEvent.change(editor, { target: { value: '{nope' } })
    expect(await screen.findByText('Provider extras must be valid JSON.')).toBeInTheDocument()

    fireEvent.change(editor, { target: { value: '{"model":"other","stream":false}' } })
    expect(
      await screen.findByText('Provider extras cannot override protected fields: model, stream.'),
    ).toBeInTheDocument()
    expect(setConversationSettings).not.toHaveBeenCalled()

    fireEvent.change(editor, { target: { value: '{"provider":{"sort":"price"}}' } })

    await waitFor(() =>
      expect(setConversationSettings).toHaveBeenCalledWith('conversation-1', {
        params: expect.objectContaining({
          extra: { openrouter: { provider: { sort: 'price' } } },
        }),
      }),
    )
  })

  it('shows routing controls for OpenRouter conversations', async () => {
    renderSettings(createTestConversation())

    expect(await screen.findByText('OpenRouter routing')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Export conversation/ })).toBeDisabled()
  })
})
