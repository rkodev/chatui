import { useContext, useEffect, useState, type ReactNode } from 'react'
import { useConfig } from '../../app/config'
import { ChatServiceContext } from './chatServiceContext'
import { createChatService } from './services'
import type { ChatServiceLike } from './chatService'

export interface ChatServiceProviderProps {
  children: ReactNode
  /** Test or story override. When omitted, the provider builds the real service. */
  service?: ChatServiceLike
}

/**
 * Supplies the chat service to the chat route.
 *
 * The real service opens IndexedDB lazily. Tests can pass a fake service and
 * avoid storage and network entirely.
 */
export function ChatServiceProvider({ children, service }: ChatServiceProviderProps) {
  const config = useConfig()
  const existing = useContext(ChatServiceContext)
  const [created, setCreated] = useState<ChatServiceLike | null>(null)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    if (service || existing) {
      return
    }

    let cancelled = false

    createChatService(config)
      .then((value) => {
        if (!cancelled) {
          setCreated(value)
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause : new Error(String(cause)))
        }
      })

    return () => {
      cancelled = true
    }
  }, [config, existing, service])

  const value = service ?? existing ?? created

  if (value === null) {
    if (error) {
      return <p role="alert">ChatUI could not open the chat: {error.message}</p>
    }

    return <p role="status">Preparing chat…</p>
  }

  return <ChatServiceContext.Provider value={value}>{children}</ChatServiceContext.Provider>
}
