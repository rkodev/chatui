import { useCallback, useEffect, useState } from 'react'
import { newId, nowIso, type Conversation, type Message } from '../../domain'
import { useChatService } from './chatServiceContext'
import type { ChatAvailability } from './chatService'
import { describeChatError } from './chatErrorCopy'
import { useConversationMessages } from './useConversationMessages'
import { useStreamingStore } from './streamingStore'

export type ChatSessionStatus = 'loading' | 'ready' | 'not_found' | 'error'

export interface ChatSession {
  conversationId: string
  status: ChatSessionStatus
  conversation: Conversation | null
  messages: Message[]
  nextCursor: number | null
  loadingOlder: boolean
  error: string | null
  availability: ChatAvailability | null
  showTokenUsage: boolean
  streamingMessageId: string | null
  isStreaming: boolean
  announcement: string
  loadOlder: () => Promise<void>
  send: (text: string) => Promise<void>
  cancel: () => Promise<void>
  retry: (message: Message) => Promise<void>
  reload: () => Promise<void>
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * Load one conversation, its newest message page, and its model availability.
 *
 * Streaming text is intentionally not part of this hook's state. The hook only
 * selects the stream's message id, active flag, and completion flag, so token
 * flushes do not re-render the screen or history. The streaming item subscribes
 * to the full stream itself. Announcements are driven by a store subscription
 * that ignores token-only updates.
 */
export function useChatSession(conversationId: string): ChatSession {
  const service = useChatService()
  const {
    messages,
    nextCursor,
    loadingOlder,
    error: messagesError,
    loadNewest,
    loadOlder,
    appendOptimistic,
  } = useConversationMessages({ conversationId, service })
  const [loadedConversationId, setLoadedConversationId] = useState<string | null>(null)
  const [conversation, setConversation] = useState<Conversation | null>(null)
  const [status, setStatus] = useState<ChatSessionStatus>('loading')
  const [error, setError] = useState<string | null>(null)
  const [availability, setAvailability] = useState<ChatAvailability | null>(null)
  const [showTokenUsage, setShowTokenUsage] = useState(true)
  const [announcement, setAnnouncement] = useState('')

  const streamingMessageId = useStreamingStore(
    (state) => state.streams[conversationId]?.messageId ?? null,
  )
  const isStreaming = useStreamingStore((state) => {
    const stream = state.streams[conversationId]
    return stream !== undefined && !stream.done
  })
  const fetchSession = useCallback(async () => {
    const value = await service.getConversation(conversationId)

    if (!value) {
      return { conversation: null, availability: null, showTokenUsage: true }
    }

    const [nextAvailability, nextShowTokenUsage] = await Promise.all([
      service.getAvailability(value),
      service.getShowTokenUsage(),
    ])

    return {
      conversation: value,
      availability: nextAvailability,
      showTokenUsage: nextShowTokenUsage,
    }
  }, [conversationId, service])

  const applySession = useCallback(
    (result: Awaited<ReturnType<typeof fetchSession>>): boolean => {
      if (!result.conversation) {
        setConversation(null)
        setStatus('not_found')
        setLoadedConversationId(conversationId)
        return false
      }

      setConversation(result.conversation)
      setAvailability(result.availability)
      setShowTokenUsage(result.showTokenUsage)

      if (!service.isStreaming(conversationId)) {
        useStreamingStore.getState().clearStream(conversationId)
      }

      return true
    },
    [conversationId, service],
  )

  const load = useCallback(async () => {
    try {
      const result = await fetchSession()

      if (!applySession(result)) {
        return
      }

      await loadNewest()
      setStatus('ready')
      setError(null)
      setLoadedConversationId(conversationId)
    } catch (cause) {
      setError(errorMessage(cause))
      setStatus('error')
      setLoadedConversationId(conversationId)
    }
  }, [applySession, conversationId, fetchSession, loadNewest])

  useEffect(() => {
    let cancelled = false

    fetchSession().then(
      (result) => {
        if (cancelled || !applySession(result)) {
          return
        }

        loadNewest().then(() => {
          if (!cancelled) {
            setStatus('ready')
            setError(null)
            setLoadedConversationId(conversationId)
          }
        })
      },
      (cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
          setStatus('error')
          setLoadedConversationId(conversationId)
        }
      },
    )

    return () => {
      cancelled = true
    }
  }, [applySession, conversationId, fetchSession, loadNewest])

  useEffect(() => {
    let previous = useStreamingStore.getState().streams[conversationId]?.status ?? null

    return useStreamingStore.subscribe((state) => {
      const stream = state.streams[conversationId]
      const next = stream?.status ?? null

      if (next === previous) {
        return
      }

      previous = next

      if (next === 'complete' || next === 'error' || next === 'cancelled') {
        void loadNewest()
      }

      if (next === 'streaming') {
        setAnnouncement('Response started')
        return
      }

      if (next === 'complete') {
        setAnnouncement('Response complete')
        return
      }

      if (next === 'error') {
        setAnnouncement(
          stream?.error
            ? `Response failed. ${describeChatError(stream.error).message}`
            : 'Response failed.',
        )
      }
    })
  }, [conversationId, loadNewest])

  const send = useCallback(
    async (text: string) => {
      if (!conversation) {
        return
      }

      const trimmed = text.trim()

      if (trimmed === '') {
        return
      }

      const timestamp = nowIso()
      const userMessage: Message = {
        id: newId(),
        conversationId,
        sequence: conversation.messageCount + 1,
        role: 'user',
        parts: [{ type: 'text', text: trimmed }],
        status: 'complete',
        providerId: conversation.providerId,
        modelId: conversation.modelId,
        modelLabel: conversation.modelLabel,
        usage: null,
        finishReason: null,
        error: null,
        latencyMs: null,
        firstTokenMs: null,
        createdAt: timestamp,
        updatedAt: timestamp,
        deletedAt: null,
      }

      appendOptimistic(userMessage)
      setError(null)

      try {
        const result = await service.send(conversation, userMessage)

        if (!result.ok) {
          setError(result.message)
          await loadNewest()
          return
        }

        await load()
      } catch (cause) {
        setError(errorMessage(cause))
        await loadNewest()
      }
    },
    [appendOptimistic, conversation, conversationId, load, loadNewest, service],
  )

  const cancel = useCallback(async () => {
    try {
      await service.cancel(conversationId)
      await load()
    } catch (cause) {
      setError(errorMessage(cause))
      await loadNewest()
    }
  }, [conversationId, load, loadNewest, service])

  const retry = useCallback(
    async (message: Message) => {
      if (!conversation) {
        return
      }

      setError(null)

      try {
        const result = await service.retry(conversation, message)

        if (!result.ok) {
          setError(result.message)
          await loadNewest()
          return
        }

        await load()
      } catch (cause) {
        setError(errorMessage(cause))
        await loadNewest()
      }
    },
    [conversation, load, loadNewest, service],
  )

  const reload = useCallback(async () => {
    await load()
  }, [load])

  const isCurrent = loadedConversationId === conversationId

  return {
    conversationId,
    status: isCurrent ? status : 'loading',
    conversation: isCurrent ? conversation : null,
    messages,
    nextCursor,
    loadingOlder,
    error: isCurrent ? (error ?? messagesError) : null,
    availability: isCurrent ? availability : null,
    showTokenUsage,
    streamingMessageId,
    isStreaming,
    announcement,
    loadOlder,
    send,
    cancel,
    retry,
    reload,
  }
}
