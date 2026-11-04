import { createContext, useContext } from 'react'
import type { ChatServiceLike } from './chatService'

export const ChatServiceContext = createContext<ChatServiceLike | null>(null)

export function useChatService(): ChatServiceLike {
  const service = useContext(ChatServiceContext)

  if (service === null) {
    throw new Error('useChatService must be used inside ChatServiceProvider')
  }

  return service
}
