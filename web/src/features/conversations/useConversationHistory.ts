import { useCallback, useEffect, useRef, useState } from 'react'
import type { Conversation } from '../../domain'
import type { ConversationHistoryServiceLike } from './historyService'

/** Undo window required by spec 3.2. */
export const DEFAULT_UNDO_WINDOW_MS = 5000

const PAGE_SIZE = 100
const MAX_PAGES = 100

export interface UseConversationHistoryOptions {
  service: ConversationHistoryServiceLike
  activeConversationId?: string | null
  undoWindowMs?: number
  /** Show the archive view instead of the active history list. */
  showArchived?: boolean
  /** Called after a buffered delete is committed to storage. */
  onDeleteCommitted?: (conversation: Conversation) => void
}

export interface UseConversationHistoryResult {
  conversations: Conversation[]
  status: 'loading' | 'ready' | 'error'
  error: string | null
  pendingDelete: Conversation | null
  refresh: () => Promise<void>
  createNewChat: () => Promise<Conversation | null>
  rename: (id: string, title: string) => Promise<void>
  setPinned: (id: string, pinned: boolean) => Promise<void>
  setArchived: (id: string, archived: boolean) => Promise<void>
  confirmDelete: (conversation: Conversation) => void
  undoDelete: () => void
  commitPendingDelete: () => Promise<void>
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

function timestamp(conversation: Conversation): number {
  const last = Date.parse(conversation.lastMessageAt)
  if (!Number.isNaN(last)) {
    return last
  }

  const updated = Date.parse(conversation.updatedAt)
  return Number.isNaN(updated) ? 0 : updated
}

/**
 * Sort active history with pinned conversations first, then newest-first.
 *
 * Pin state is per conversation, so it applies across date groups as well.
 */
export function sortConversations(items: readonly Conversation[]): Conversation[] {
  return [...items].sort((left, right) => {
    if (left.pinned !== right.pinned) {
      return left.pinned ? -1 : 1
    }

    return timestamp(right) - timestamp(left)
  })
}

async function loadAllConversations(
  service: ConversationHistoryServiceLike,
  archived: boolean,
): Promise<Conversation[]> {
  const all: Conversation[] = []
  let cursor: string | null = null

  for (let page = 0; page < MAX_PAGES; page += 1) {
    const result = await service.listConversations({ limit: PAGE_SIZE, cursor, archived })
    all.push(...result.items)

    if (result.nextCursor === null) {
      break
    }

    cursor = result.nextCursor
  }

  return sortConversations(all)
}

/**
 * Load and mutate the conversation history list.
 *
 * Deletes are buffered for the undo window: the conversation disappears from
 * the list immediately, but the storage delete only runs when the window
 * expires, the user starts another delete, or the hook unmounts. Undo cancels
 * the buffered delete and restores the item without touching storage.
 */
export function useConversationHistory({
  service,
  activeConversationId = null,
  undoWindowMs = DEFAULT_UNDO_WINDOW_MS,
  showArchived = false,
  onDeleteCommitted,
}: UseConversationHistoryOptions): UseConversationHistoryResult {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState<string | null>(null)
  const [pendingDelete, setPendingDelete] = useState<Conversation | null>(null)
  const mountedRef = useRef(true)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pendingRef = useRef<Conversation | null>(null)
  const activeIdRef = useRef<string | null>(activeConversationId)
  const onDeleteCommittedRef = useRef(onDeleteCommitted)

  useEffect(() => {
    activeIdRef.current = activeConversationId
  }, [activeConversationId])

  useEffect(() => {
    onDeleteCommittedRef.current = onDeleteCommitted
  }, [onDeleteCommitted])

  const applyLoaded = useCallback((items: Conversation[]) => {
    if (!mountedRef.current) {
      return
    }

    setConversations(items)
    setStatus('ready')
    setError(null)
  }, [])

  const applyError = useCallback((cause: unknown) => {
    if (!mountedRef.current) {
      return
    }

    setError(errorMessage(cause))
    setStatus('error')
  }, [])

  const refresh = useCallback(async () => {
    try {
      applyLoaded(await loadAllConversations(service, showArchived))
    } catch (cause) {
      applyError(cause)
    }
  }, [applyError, applyLoaded, service, showArchived])

  useEffect(() => {
    let cancelled = false

    const run = async () => {
      try {
        const items = await loadAllConversations(service, showArchived)

        if (!cancelled) {
          applyLoaded(items)
        }
      } catch (cause) {
        if (!cancelled) {
          applyError(cause)
        }
      }
    }

    void run()

    return () => {
      cancelled = true
    }
  }, [applyError, applyLoaded, service, showArchived])

  const commitDelete = useCallback(
    async (conversation: Conversation) => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }

      if (pendingRef.current?.id === conversation.id) {
        pendingRef.current = null

        if (mountedRef.current) {
          setPendingDelete(null)
        }
      }

      try {
        await service.deleteConversation(conversation.id)

        if (mountedRef.current) {
          setConversations((current) => current.filter((item) => item.id !== conversation.id))
          setError(null)
        }

        onDeleteCommittedRef.current?.(conversation)
      } catch (cause) {
        if (mountedRef.current) {
          setError(errorMessage(cause))
          setConversations((current) =>
            current.some((item) => item.id === conversation.id)
              ? current
              : sortConversations([conversation, ...current]),
          )
        }
      }
    },
    [service],
  )

  const createNewChat = useCallback(async (): Promise<Conversation | null> => {
    try {
      const result = await service.createConversation()

      if (!result.ok) {
        if (mountedRef.current) {
          setError(result.message)
        }
        return null
      }

      await service.setLastActiveConversation(result.conversation.id)

      if (mountedRef.current) {
        setConversations((current) =>
          sortConversations([
            result.conversation,
            ...current.filter((item) => item.id !== result.conversation.id),
          ]),
        )
        setError(null)
      }

      return result.conversation
    } catch (cause) {
      if (mountedRef.current) {
        setError(errorMessage(cause))
      }
      return null
    }
  }, [service])

  const rename = useCallback(
    async (id: string, title: string) => {
      try {
        const updated = await service.renameConversation(id, title)

        if (mountedRef.current) {
          setConversations((current) => current.map((item) => (item.id === id ? updated : item)))
          setError(null)
        }
      } catch (cause) {
        if (mountedRef.current) {
          setError(errorMessage(cause))
        }
      }
    },
    [service],
  )

  const setPinned = useCallback(
    async (id: string, pinned: boolean) => {
      try {
        const updated = await service.setConversationPinned(id, pinned)

        if (mountedRef.current) {
          setConversations((current) =>
            sortConversations(current.map((item) => (item.id === id ? updated : item))),
          )
          setError(null)
        }
      } catch (cause) {
        if (mountedRef.current) {
          setError(errorMessage(cause))
        }
      }
    },
    [service],
  )

  const setArchived = useCallback(
    async (id: string, archived: boolean) => {
      try {
        const updated = await service.setConversationArchived(id, archived)

        if (mountedRef.current) {
          setConversations((current) => {
            if (updated.archived !== showArchived) {
              return current.filter((item) => item.id !== id)
            }

            return sortConversations(current.map((item) => (item.id === id ? updated : item)))
          })
          setError(null)
        }
      } catch (cause) {
        if (mountedRef.current) {
          setError(errorMessage(cause))
        }
      }
    },
    [service, showArchived],
  )

  const confirmDelete = useCallback(
    (conversation: Conversation) => {
      const previous = pendingRef.current

      if (previous) {
        void commitDelete(previous)
      }

      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }

      pendingRef.current = conversation
      setPendingDelete(conversation)
      setConversations((current) => current.filter((item) => item.id !== conversation.id))

      if (activeIdRef.current === conversation.id) {
        void service.setLastActiveConversation(null).catch(() => undefined)
      }

      timerRef.current = setTimeout(() => {
        timerRef.current = null
        void commitDelete(conversation)
      }, undoWindowMs)
    },
    [commitDelete, service, undoWindowMs],
  )

  const undoDelete = useCallback(() => {
    const pending = pendingRef.current

    if (!pending) {
      return
    }

    if (timerRef.current !== null) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }

    pendingRef.current = null
    setPendingDelete(null)
    setConversations((current) =>
      current.some((item) => item.id === pending.id)
        ? current
        : sortConversations([pending, ...current]),
    )

    if (activeIdRef.current === pending.id) {
      void service.setLastActiveConversation(pending.id).catch(() => undefined)
    }
  }, [service])

  const commitPendingDelete = useCallback(async () => {
    const pending = pendingRef.current

    if (pending) {
      await commitDelete(pending)
    }
  }, [commitDelete])

  useEffect(() => {
    mountedRef.current = true

    return () => {
      mountedRef.current = false

      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }

      const pending = pendingRef.current

      if (pending) {
        pendingRef.current = null
        void service.deleteConversation(pending.id).catch(() => undefined)
      }
    }
  }, [service])

  return {
    conversations,
    status,
    error,
    pendingDelete,
    refresh,
    createNewChat,
    rename,
    setPinned,
    setArchived,
    confirmDelete,
    undoDelete,
    commitPendingDelete,
  }
}
