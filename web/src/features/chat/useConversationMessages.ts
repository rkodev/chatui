import { useCallback, useEffect, useRef, useState } from 'react'
import type { Message } from '../../domain'
import type { MessagePage } from '../../storage'
import type { ChatServiceLike } from './chatService'

export interface UseConversationMessagesOptions {
  conversationId: string
  service: Pick<ChatServiceLike, 'listMessages'>
  pageSize?: number
}

export interface UseConversationMessagesResult {
  messages: Message[]
  nextCursor: number | null
  status: 'loading' | 'ready' | 'error'
  loadingOlder: boolean
  error: string | null
  loadNewest: () => Promise<void>
  loadOlder: () => Promise<void>
  appendOptimistic: (message: Message) => void
}

function mergeMessages(existing: readonly Message[], incoming: readonly Message[]): Message[] {
  const byId = new Map<string, Message>()

  for (const message of existing) {
    byId.set(message.id, message)
  }
  for (const message of incoming) {
    byId.set(message.id, message)
  }

  return [...byId.values()].sort((left, right) => left.sequence - right.sequence)
}

/**
 * Load a conversation's newest message page and page older messages on demand.
 *
 * The cursor is the oldest loaded sequence. Pages are merged by message id and
 * sorted by sequence, so a refresh or an overlapping page cannot duplicate or
 * reorder history. State is tagged with the conversation it belongs to, so a
 * conversation switch shows an empty loading list until the new page arrives.
 */
export function useConversationMessages({
  conversationId,
  service,
  pageSize = 50,
}: UseConversationMessagesOptions): UseConversationMessagesResult {
  const [loadedConversationId, setLoadedConversationId] = useState<string | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [nextCursor, setNextCursor] = useState<number | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const generation = useRef(0)
  const loadingOlderRef = useRef(false)

  const fetchNewest = useCallback(
    () => service.listMessages(conversationId, { limit: pageSize }),
    [conversationId, pageSize, service],
  )

  const applyPage = useCallback(
    (page: MessagePage) => {
      setMessages(page.items)
      setNextCursor(page.nextCursor)
      setStatus('ready')
      setError(null)
      setLoadedConversationId(conversationId)
    },
    [conversationId],
  )

  const applyError = useCallback(
    (cause: unknown) => {
      setError(cause instanceof Error ? cause.message : String(cause))
      setStatus('error')
      setLoadedConversationId(conversationId)
    },
    [conversationId],
  )

  const loadNewest = useCallback(async () => {
    const current = generation.current + 1
    generation.current = current

    try {
      const page = await fetchNewest()

      if (current !== generation.current) {
        return
      }

      applyPage(page)
    } catch (cause) {
      if (current !== generation.current) {
        return
      }

      applyError(cause)
    }
  }, [applyError, applyPage, fetchNewest])

  const loadOlder = useCallback(async () => {
    if (nextCursor === null || loadingOlderRef.current) {
      return
    }

    loadingOlderRef.current = true
    setLoadingOlder(true)
    setError(null)
    const generationAtStart = generation.current

    try {
      const page = await service.listMessages(conversationId, {
        limit: pageSize,
        beforeSequence: nextCursor,
      })

      if (generationAtStart !== generation.current) {
        return
      }

      setMessages((current) => mergeMessages(current, page.items))
      setNextCursor(page.nextCursor)
    } catch (cause) {
      if (generationAtStart === generation.current) {
        setError(cause instanceof Error ? cause.message : String(cause))
      }
    } finally {
      loadingOlderRef.current = false
      setLoadingOlder(false)
    }
  }, [conversationId, nextCursor, pageSize, service])

  const appendOptimistic = useCallback((message: Message) => {
    setMessages((current) => mergeMessages(current, [message]))
  }, [])

  useEffect(() => {
    let cancelled = false
    const current = generation.current + 1
    generation.current = current

    fetchNewest().then(
      (page) => {
        if (!cancelled && current === generation.current) {
          applyPage(page)
        }
      },
      (cause: unknown) => {
        if (!cancelled && current === generation.current) {
          applyError(cause)
        }
      },
    )

    return () => {
      cancelled = true
    }
  }, [applyError, applyPage, fetchNewest])

  const isCurrent = loadedConversationId === conversationId

  return {
    messages: isCurrent ? messages : [],
    nextCursor: isCurrent ? nextCursor : null,
    status: isCurrent ? status : 'loading',
    loadingOlder: isCurrent ? loadingOlder : false,
    error: isCurrent ? error : null,
    loadNewest,
    loadOlder,
    appendOptimistic,
  }
}
