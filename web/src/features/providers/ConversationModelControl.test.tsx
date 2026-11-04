import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfigContext } from '../../app/config'
import { ConversationModelControl } from './ConversationModelControl'
import { ProviderManagementProvider } from './ProviderManagementProvider'
import {
  createFakeProviderManagementService,
  createTestConversation,
  createTestModel,
  testConfig,
} from './testSupport'

const first = createTestModel()
const second = createTestModel({
  id: 'model-2',
  remoteId: 'anthropic/claude-sonnet',
  label: 'Anthropic: Claude Sonnet',
})

function renderControl(service = createFakeProviderManagementService()) {
  render(
    <ConfigContext.Provider value={testConfig}>
      <ProviderManagementProvider service={service}>
        <ConversationModelControl conversationId="conversation-1" />
      </ProviderManagementProvider>
    </ConfigContext.Provider>,
  )
  return service
}

describe('ConversationModelControl', () => {
  it('switches the conversation model immediately', async () => {
    const user = userEvent.setup()
    let conversation = createTestConversation()
    const setConversationModel = vi.fn(async (_id: string, modelId: string) => {
      conversation = {
        ...conversation,
        modelId,
        modelLabel: modelId === second.id ? second.label : first.label,
      }
      return conversation
    })
    renderControl(
      createFakeProviderManagementService({
        getConversation: async () => conversation,
        listModels: async () => [first, second],
        setConversationModel,
      }),
    )

    await user.click(await screen.findByRole('button', { name: /OpenAI: GPT-4o mini/ }))
    await user.click(screen.getByRole('button', { name: /Anthropic: Claude Sonnet/ }))

    await waitFor(() =>
      expect(setConversationModel).toHaveBeenCalledWith('conversation-1', second.id),
    )
    expect(await screen.findByRole('button', { name: /Anthropic: Claude Sonnet/ })).toBeInTheDocument()
  })

  it('warns when the conversation model is unavailable', async () => {
    renderControl(
      createFakeProviderManagementService({
        listModels: async () => [],
      }),
    )

    expect(await screen.findByText(/no longer available/i)).toBeInTheDocument()
  })
})
