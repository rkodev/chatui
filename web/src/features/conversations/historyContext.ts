import { createContext, useContext } from 'react'
import type { ConversationHistoryServiceLike } from './historyService'

export const ConversationHistoryContext = createContext<ConversationHistoryServiceLike | null>(null)

export function useConversationHistoryService(): ConversationHistoryServiceLike {
  const service = useContext(ConversationHistoryContext)

  if (service === null) {
    throw new Error('useConversationHistoryService must be used inside ConversationHistoryProvider')
  }

  return service
}
