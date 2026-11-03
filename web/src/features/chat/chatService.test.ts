import { describe, expect, it, vi } from 'vitest'
import type { Conversation, Message } from '../../domain'
import {
  createTestConversation,
  createTestModel,
  createTestProvider,
} from '../providers/testSupport'
import { ChatService, type ChatRunnerLike } from './chatService'
import { createStreamingState } from './streamingStore'
import { createTestMessage } from './testSupport'

function createHarness(
  overrides: {
    conversation?: Conversation
    messages?: Message[]
    provider?: ReturnType<typeof createTestProvider> | null
    model?: ReturnType<typeof createTestModel> | null
    apiKey?: string | null
    runner?: Partial<ChatRunnerLike>
  } = {},
) {
  const conversation = overrides.conversation ?? createTestConversation()
  const provider = overrides.provider === undefined ? createTestProvider() : overrides.provider
  const model = overrides.model === undefined ? createTestModel() : overrides.model
  const messages = overrides.messages ?? [
    createTestMessage({
      id: 'm1',
      sequence: 1,
      role: 'user',
      parts: [{ type: 'text', text: 'Hello' }],
    }),
  ]
  const assistant = createTestMessage({
    id: 'm2',
    sequence: 2,
    role: 'assistant',
    parts: [{ type: 'text', text: 'Hi there' }],
  })
  const state = createStreamingState({
    conversationId: conversation.id,
    messageId: assistant.id,
    startedAt: 1,
  })
  const runner: ChatRunnerLike = {
    send: vi.fn(async () => ({ ok: true as const, message: assistant, state })),
    regenerate: vi.fn(async () => ({ ok: true as const, message: assistant, state })),
    cancel: vi.fn(async () => null),
    isStreaming: vi.fn(() => false),
    ...overrides.runner,
  }
  const appendMessage = vi.fn(async (message: Message) => {
    messages.push(message)
    return {
      message,
      conversation: { ...conversation, messageCount: conversation.messageCount + 1 },
    }
  })
  const service = new ChatService({
    conversations: {
      get: async () => conversation,
      appendMessage,
    },
    messages: {
      listByConversation: async () => ({ items: [...messages], nextCursor: null }),
    },
    providers: {
      get: async () => provider,
      list: async () => (provider === null ? [] : [provider]),
    },
    models: {
      get: async () => model,
    },
    secrets: {
      getSecret: async () => (overrides.apiKey === undefined ? 'sk-test' : overrides.apiKey),
    },
    settings: {
      getShowTokenUsage: async () => true,
      getSendKey: async () => 'enter',
    },
    runner,
    runtime: { fetch: globalThis.fetch },
  })

  return { service, conversation, provider, model, messages, assistant, runner, appendMessage }
}

describe('ChatService', () => {
  it('reports no provider when none is enabled', async () => {
    const { service, conversation } = createHarness({ provider: null })

    await expect(service.getAvailability(conversation)).resolves.toEqual({
      ok: false,
      reason: 'no_provider',
      message: 'No provider is configured. Add a provider before starting a chat.',
    })
  })

  it('reports a missing model as unavailable', async () => {
    const { service, conversation } = createHarness({ model: null })

    const availability = await service.getAvailability(conversation)

    expect(availability.ok).toBe(false)
    if (!availability.ok) {
      expect(availability.reason).toBe('model_unavailable')
      expect(availability.message).toMatch(/unavailable/i)
    }
  })

  it('appends the user message and sends the full history', async () => {
    const { service, conversation, runner, appendMessage } = createHarness()
    const userMessage = createTestMessage({
      id: 'm2',
      sequence: 2,
      role: 'user',
      parts: [{ type: 'text', text: 'How are you?' }],
    })

    const result = await service.send(conversation, userMessage)

    expect(result.ok).toBe(true)
    expect(appendMessage).toHaveBeenCalledWith(userMessage)
    expect(runner.send).toHaveBeenCalledWith(
      expect.objectContaining({
        conversation: expect.objectContaining({ id: conversation.id, messageCount: 1 }),
        context: expect.objectContaining({ apiKey: 'sk-test' }),
        request: expect.objectContaining({
          model: 'openai/gpt-4o-mini',
          messages: [
            { role: 'user', parts: [{ type: 'text', text: 'Hello' }] },
            { role: 'user', parts: [{ type: 'text', text: 'How are you?' }] },
          ],
        }),
        sequence: 2,
      }),
    )
  })

  it('does not call the runner when the key is missing', async () => {
    const { service, conversation, runner } = createHarness({ apiKey: null })

    const result = await service.send(conversation)

    expect(result).toEqual({
      ok: false,
      reason: 'no_key',
      message: 'The API key for this provider is unavailable. Re-enter the key and try again.',
    })
    expect(runner.send).not.toHaveBeenCalled()
  })

  it('regenerates with history before the target only', async () => {
    const target = createTestMessage({
      id: 'm2',
      sequence: 2,
      role: 'assistant',
      parts: [{ type: 'text', text: 'Broken answer' }],
    })
    const { service, conversation, runner } = createHarness({
      messages: [
        createTestMessage({
          id: 'm1',
          sequence: 1,
          role: 'user',
          parts: [{ type: 'text', text: 'Hello' }],
        }),
        target,
      ],
    })

    const result = await service.retry(conversation, target)

    expect(result.ok).toBe(true)
    expect(runner.regenerate).toHaveBeenCalledWith(
      expect.objectContaining({
        target,
        sequence: 2,
        request: expect.objectContaining({
          messages: [{ role: 'user', parts: [{ type: 'text', text: 'Hello' }] }],
        }),
      }),
    )
  })

  it('maps a busy runner result to a send failure', async () => {
    const { service, conversation } = createHarness({
      runner: {
        send: vi.fn(async () => ({ ok: false as const, reason: 'busy' as const })),
      },
    })

    await expect(service.send(conversation)).resolves.toEqual({
      ok: false,
      reason: 'busy',
      message: 'A response is already streaming for this conversation.',
    })
  })

  it('delegates cancel and streaming state to the runner', async () => {
    const { service, runner } = createHarness({
      runner: {
        cancel: vi.fn(async () => null),
        isStreaming: vi.fn(() => true),
      },
    })

    await service.cancel('conversation-1')

    expect(runner.cancel).toHaveBeenCalledWith('conversation-1')
    expect(service.isStreaming('conversation-1')).toBe(true)
  })
})
