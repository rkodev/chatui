import { nowIso, type Message } from '../../domain'
import {
  createTestConversation,
  createTestModel,
  createTestProvider,
} from '../providers/testSupport'
import type { ChatServiceLike } from './chatService'
import type { ChatSession } from './useChatSession'

export function createTestMessage(overrides: Partial<Message> = {}): Message {
  const now = nowIso()

  return {
    id: 'message-1',
    conversationId: 'conversation-1',
    sequence: 1,
    role: 'user',
    parts: [{ type: 'text', text: 'Hello' }],
    status: 'complete',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'OpenAI: GPT-4o mini',
    usage: null,
    finishReason: null,
    error: null,
    latencyMs: null,
    firstTokenMs: null,
    createdAt: now,
    updatedAt: now,
    deletedAt: null,
    ...overrides,
  }
}

/**
 * In-memory chat service fake for component and route tests.
 *
 * It mirrors the real service's observable behavior without IndexedDB or
 * network access. Individual tests override only the methods they exercise.
 */
export function createFakeChatService(
  overrides: Partial<ChatServiceLike> = {},
): ChatServiceLike {
  const conversation = createTestConversation()
  const model = createTestModel()
  const provider = createTestProvider()
  const message = createTestMessage()

  return {
    getConversation: async () => conversation,
    listMessages: async () => ({ items: [], nextCursor: null }),
    getAvailability: async () => ({ ok: true, model, provider }),
    getShowTokenUsage: async () => true,
    send: async () => ({ ok: true, message }),
    retry: async () => ({ ok: true, message }),
    cancel: async () => null,
    isStreaming: () => false,
    ...overrides,
  }
}

export function createTestChatSession(overrides: Partial<ChatSession> = {}): ChatSession {
  const conversation = createTestConversation()
  const model = createTestModel()
  const provider = createTestProvider()

  return {
    conversationId: conversation.id,
    status: 'ready',
    conversation,
    messages: [],
    nextCursor: null,
    loadingOlder: false,
    error: null,
    availability: { ok: true, model, provider },
    showTokenUsage: true,
    streamingMessageId: null,
    isStreaming: false,
    announcement: '',
    loadOlder: async () => undefined,
    send: async () => undefined,
    cancel: async () => undefined,
    retry: async () => undefined,
    reload: async () => undefined,
    ...overrides,
  }
}
