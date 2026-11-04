import { useContext, useEffect, useState, type ReactNode } from 'react'
import { useConfig } from '../../app/config'
import { ConversationHistoryContext } from './historyContext'
import {
  createConversationHistoryService,
  type ConversationHistoryServiceLike,
} from './historyService'
import type { RecoveryServiceLike } from './recoveryService'

export interface ConversationHistoryProviderProps {
  children: ReactNode
  /** Test or story override. When omitted, the provider builds the real service. */
  service?: ConversationHistoryServiceLike
  /** Test or story override for the startup recovery pass. */
  recoveryService?: RecoveryServiceLike
}

/**
 * Supplies conversation history and runs startup recovery before children.
 *
 * Recovery must finish before the chat route renders so a message left in
 * `streaming` is never shown as an active response after a reload. The
 * provider is mounted by the root layout; tests can inject a fake service and
 * recovery pass, or wrap the router with an outer provider.
 */
export function ConversationHistoryProvider({
  children,
  service,
  recoveryService,
}: ConversationHistoryProviderProps) {
  const config = useConfig()
  const existing = useContext(ConversationHistoryContext)
  const [created, setCreated] = useState<ConversationHistoryServiceLike | null>(null)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    if (existing) {
      return
    }

    let cancelled = false

    const prepare = async () => {
      if (service) {
        if (recoveryService) {
          await recoveryService.recoverInterruptedMessages()
        }

        if (!cancelled) {
          setCreated(service)
        }
        return
      }

      const built = await createConversationHistoryService(config)
      await built.recovery.recoverInterruptedMessages()

      if (!cancelled) {
        setCreated(built.service)
      }
    }

    prepare().catch((cause: unknown) => {
      if (!cancelled) {
        setError(cause instanceof Error ? cause : new Error(String(cause)))
      }
    })

    return () => {
      cancelled = true
    }
  }, [config, existing, recoveryService, service])

  if (existing) {
    return <>{children}</>
  }

  if (error) {
    return <p role="alert">ChatUI could not prepare conversation history: {error.message}</p>
  }

  if (created === null) {
    return <p role="status">Preparing conversation history…</p>
  }

  return (
    <ConversationHistoryContext.Provider value={created}>
      {children}
    </ConversationHistoryContext.Provider>
  )
}
