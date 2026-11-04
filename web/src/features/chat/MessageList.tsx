import {
  useCallback,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type UIEvent,
} from 'react'
import type { Conversation, Message } from '../../domain'
import { Button } from '../../ui/primitives'
import { MessageItem } from './MessageItem'
import { StreamingMessage } from './StreamingMessage'
import { useWindowedList } from './useWindowedList'
import './chat.css'

const ESTIMATE_HEIGHT = 120
const OVERSCAN = 4
const NEAR_BOTTOM_PX = 80
const LOAD_OLDER_PX = 120

export interface MessageListProps {
  conversation: Conversation
  messages: Message[]
  streamingMessageId: string | null
  isStreaming: boolean
  nextCursor: number | null
  loadingOlder: boolean
  onLoadOlder: () => void
  showTokenUsage: boolean
  onRetry?: (message: Message) => void
  onRegenerate?: (message: Message) => void
  onEditLastUser?: (message: Message, text: string) => void
}

interface MessageRowProps {
  itemKey: string
  onMeasure: (key: string, height: number) => void
  children: ReactNode
}

function MessageRow({ itemKey, onMeasure, children }: MessageRowProps) {
  const ref = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const element = ref.current

    if (!element) {
      return
    }

    const measure = () => {
      onMeasure(itemKey, element.getBoundingClientRect().height)
    }

    measure()

    const observer = new ResizeObserver(measure)
    observer.observe(element)

    return () => {
      observer.disconnect()
    }
  }, [itemKey, onMeasure])

  return (
    <div ref={ref} className="message-list__row" data-message-id={itemKey}>
      {children}
    </div>
  )
}

/**
 * Windowed conversation history with older-message paging and scroll anchoring.
 *
 * The list never subscribes to streaming text. It renders `StreamingMessage`,
 * which owns that subscription, so token flushes do not re-render history.
 */
export function MessageList({
  conversation,
  messages,
  streamingMessageId,
  isStreaming,
  nextCursor,
  loadingOlder,
  onLoadOlder,
  showTokenUsage,
  onRetry,
  onRegenerate,
  onEditLastUser,
}: MessageListProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const nearBottomRef = useRef(true)
  const pendingAnchorRef = useRef<number | null>(null)
  const [scrollTop, setScrollTop] = useState(0)

  const visibleMessages = useMemo(
    () =>
      isStreaming && streamingMessageId !== null
        ? messages.filter((message) => message.id !== streamingMessageId)
        : messages,
    [isStreaming, messages, streamingMessageId],
  )
  const keys = useMemo(() => visibleMessages.map((message) => message.id), [visibleMessages])
  const lastAssistantId = useMemo(() => {
    for (let index = visibleMessages.length - 1; index >= 0; index -= 1) {
      if (visibleMessages[index].role === 'assistant') {
        return visibleMessages[index].id
      }
    }

    return null
  }, [visibleMessages])
  const lastUserId = useMemo(() => {
    for (let index = visibleMessages.length - 1; index >= 0; index -= 1) {
      if (visibleMessages[index].role === 'user') {
        return visibleMessages[index].id
      }
    }

    return null
  }, [visibleMessages])
  const persistedStreaming =
    streamingMessageId !== null && messages.some((message) => message.id === streamingMessageId)

  const { start, end, topPadding, bottomPadding, measureItem } = useWindowedList({
    keys,
    estimateHeight: ESTIMATE_HEIGHT,
    overscan: OVERSCAN,
    containerRef,
    scrollTop,
  })

  const scrollToBottom = useCallback(() => {
    const element = containerRef.current

    if (element) {
      element.scrollTop = element.scrollHeight
    }
  }, [])

  useLayoutEffect(() => {
    const element = containerRef.current

    if (!element) {
      return
    }

    if (pendingAnchorRef.current !== null) {
      element.scrollTop = element.scrollHeight - pendingAnchorRef.current
      pendingAnchorRef.current = null
      return
    }

    if (nearBottomRef.current) {
      element.scrollTop = element.scrollHeight
    }
  }, [isStreaming, visibleMessages])

  useLayoutEffect(() => {
    const element = contentRef.current

    if (!element) {
      return
    }

    const observer = new ResizeObserver(() => {
      if (nearBottomRef.current) {
        scrollToBottom()
      }
    })
    observer.observe(element)

    return () => {
      observer.disconnect()
    }
  }, [scrollToBottom])

  const handleScroll = useCallback(
    (event: UIEvent<HTMLDivElement>) => {
      const element = event.currentTarget
      setScrollTop(element.scrollTop)
      nearBottomRef.current =
        element.scrollHeight - element.scrollTop - element.clientHeight < NEAR_BOTTOM_PX

      if (element.scrollTop < LOAD_OLDER_PX && nextCursor !== null && !loadingOlder) {
        pendingAnchorRef.current = element.scrollHeight - element.scrollTop
        onLoadOlder()
      }
    },
    [loadingOlder, nextCursor, onLoadOlder],
  )

  const handleLoadOlder = useCallback(() => {
    const element = containerRef.current

    if (element) {
      pendingAnchorRef.current = element.scrollHeight - element.scrollTop
    }

    onLoadOlder()
  }, [onLoadOlder])

  return (
    <div
      ref={containerRef}
      className="message-list"
      role="log"
      aria-label="Conversation messages"
      aria-live="off"
      aria-busy={isStreaming}
      onScroll={handleScroll}
    >
      <div ref={contentRef} className="message-list__content">
        {nextCursor !== null && (
          <div className="message-list__older">
            <Button
              variant="secondary"
              size="sm"
              onClick={handleLoadOlder}
              disabled={loadingOlder}
            >
              {loadingOlder ? 'Loading older messages…' : 'Load older messages'}
            </Button>
          </div>
        )}

        <div style={{ height: topPadding }} aria-hidden="true" />

        {visibleMessages.slice(start, end).map((message) => (
          <MessageRow key={message.id} itemKey={message.id} onMeasure={measureItem}>
            <MessageItem
              message={message}
              showTokenUsage={showTokenUsage}
              onRetry={onRetry}
              onRegenerate={onRegenerate}
              onEditLastUser={onEditLastUser}
              canRegenerate={!isStreaming && message.id === lastAssistantId}
              canEditLastUser={!isStreaming && message.id === lastUserId}
            />
          </MessageRow>
        ))}

        <div style={{ height: bottomPadding }} aria-hidden="true" />

        {streamingMessageId !== null && (
          <StreamingMessage
            conversation={conversation}
            showTokenUsage={showTokenUsage}
            onRetry={onRetry}
            persisted={persistedStreaming}
          />
        )}
      </div>
    </div>
  )
}
