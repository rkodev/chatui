import type { Conversation } from '../../domain'
import { createTestConversation } from '../providers/testSupport'
import type { ConversationHistoryServiceLike } from './historyService'
import type { RecoveryResult, RecoveryServiceLike } from './recoveryService'

/**
 * In-memory history service fake for sidebar, hook, and root-redirect tests.
 *
 * Individual tests override only the methods they exercise.
 */
export function createFakeConversationHistoryService(
  overrides: Partial<ConversationHistoryServiceLike> = {},
): ConversationHistoryServiceLike {
  const conversation = createTestConversation()

  return {
    listConversations: async () => ({ items: [conversation], nextCursor: null }),
    searchConversations: async (query: string) => ({
      query,
      groups: [],
      truncated: false,
      scannedConversations: 0,
      scannedMessages: 0,
    }),
    createConversation: async () => ({ ok: true, conversation }),
    renameConversation: async (id: string, title: string): Promise<Conversation> => ({
      ...conversation,
      id,
      title,
    }),
    deleteConversation: async () => undefined,
    getLastActiveConversation: async () => conversation,
    setLastActiveConversation: async () => undefined,
    ...overrides,
  }
}

export function createFakeRecoveryService(
  overrides: Partial<RecoveryServiceLike> = {},
): RecoveryServiceLike {
  return {
    recoverInterruptedMessages: async (): Promise<RecoveryResult> => ({
      recovered: [],
      failed: [],
    }),
    ...overrides,
  }
}
