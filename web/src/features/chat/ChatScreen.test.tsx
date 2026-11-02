import { RouterProvider } from '@tanstack/react-router'
import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { createTestRouter } from '../providers/testRouter'
import { createTestConversation, createTestModel, createTestProvider } from '../providers/testSupport'
import { ChatScreen } from './ChatScreen'
import { createStreamingState, useStreamingStore } from './streamingStore'
import { createTestChatSession, createTestMessage } from './testSupport'
import type { ChatSession } from './useChatSession'

function renderScreen(session: ChatSession) {
  const router = createTestRouter(
    () => <ChatScreen session={session} />,
    'chat/$conversationId',
    '/chat/conversation-1',
    ['/', 'settings/providers/new', 'settings/models'],
  )

  render(<RouterProvider router={router} />)
}

afterEach(() => {
  useStreamingStore.getState().clearAll()
})

describe('ChatScreen', () => {
  it('renders the loading state', async () => {
    renderScreen(createTestChatSession({ status: 'loading', conversation: null }))

    expect(await screen.findByText('Loading conversation…')).toBeInTheDocument()
  })

  it('renders the not-found state', async () => {
    renderScreen(createTestChatSession({ status: 'not_found', conversation: null }))

    expect(await screen.findByRole('heading', { name: 'Conversation not found' })).toBeInTheDocument()
  })

  it('renders the load error state', async () => {
    renderScreen(
      createTestChatSession({
        status: 'error',
        conversation: null,
        error: 'IndexedDB unavailable',
      }),
    )

    expect(await screen.findByRole('heading', { name: 'Could not load the conversation' })).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toHaveTextContent('IndexedDB unavailable')
  })

  it('renders the no-provider state with an onboarding link', async () => {
    renderScreen(
      createTestChatSession({
        availability: {
          ok: false,
          reason: 'no_provider',
          message: 'No provider is configured. Add a provider before starting a chat.',
        },
      }),
    )

    expect(await screen.findByText(/no provider is configured/i)).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Add OpenRouter' })).toHaveAttribute(
      'href',
      '/settings/providers/new',
    )
  })

  it('renders the model-unavailable state', async () => {
    renderScreen(
      createTestChatSession({
        availability: {
          ok: false,
          reason: 'model_unavailable',
          message: 'Model model-1 is unavailable. Choose another model before sending.',
        },
      }),
    )

    expect(await screen.findByText(/model-1 is unavailable/i)).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Choose another model' })).toHaveAttribute(
      'href',
      '/settings/models',
    )
  })

  it('renders the empty conversation state', async () => {
    renderScreen(createTestChatSession())

    expect(await screen.findByRole('heading', { name: 'Start the conversation' })).toBeInTheDocument()
    expect(await screen.findByTestId('chat-composer-slot')).toBeInTheDocument()
  })

  it('renders complete history and the live-region announcement', async () => {
    const conversation = createTestConversation()
    const model = createTestModel()
    const provider = createTestProvider()
    const message = createTestMessage({
      id: 'm1',
      sequence: 1,
      role: 'assistant',
      parts: [{ type: 'text', text: 'Complete answer' }],
    })

    renderScreen(
      createTestChatSession({
        conversation,
        messages: [message],
        availability: { ok: true, model, provider },
        announcement: 'Response complete',
      }),
    )

    expect(await screen.findByText('Complete answer')).toBeInTheDocument()
    expect(await screen.findByTestId('chat-announcement')).toHaveTextContent('Response complete')
  })

  it('renders the streaming state with a typing placeholder', async () => {
    const conversation = createTestConversation()
    const model = createTestModel()
    const provider = createTestProvider()

    act(() => {
      useStreamingStore.getState().setStream({
        ...createStreamingState({
          conversationId: conversation.id,
          messageId: 'stream-1',
          startedAt: 1,
        }),
        started: true,
      })
    })

    renderScreen(
      createTestChatSession({
        conversation,
        messages: [],
        availability: { ok: true, model, provider },
        streamingMessageId: 'stream-1',
        isStreaming: true,
        announcement: 'Response started',
      }),
    )

    expect(await screen.findByTestId('chat-announcement')).toHaveTextContent('Response started')
    expect(await screen.findByRole('log')).toHaveAttribute('aria-busy', 'true')
    expect(await screen.findByLabelText('Assistant is typing')).toBeInTheDocument()
  })

  it('renders cancelled and error message states', async () => {
    const conversation = createTestConversation()
    const model = createTestModel()
    const provider = createTestProvider()
    const cancelled = createTestMessage({
      id: 'm1',
      sequence: 1,
      role: 'assistant',
      status: 'cancelled',
      parts: [{ type: 'text', text: 'Partial' }],
    })
    const failed = createTestMessage({
      id: 'm2',
      sequence: 2,
      role: 'assistant',
      status: 'error',
      parts: [{ type: 'text', text: 'Partial error' }],
      error: {
        code: 'network',
        message: 'network failed',
        providerId: 'provider-1',
        status: null,
        retryable: true,
        retryAfterSeconds: null,
        requestId: null,
        details: null,
        at: '2026-01-01T00:00:00.000Z',
      },
    })

    renderScreen(
      createTestChatSession({
        conversation,
        messages: [cancelled, failed],
        availability: { ok: true, model, provider },
      }),
    )

    expect(await screen.findByText(/response cancelled/i)).toBeInTheDocument()
    expect(await screen.findByText('Could not reach the provider')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Retry response' })).toBeInTheDocument()
  })
})
