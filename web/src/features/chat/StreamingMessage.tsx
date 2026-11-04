import type { ContentPart, Conversation, Message } from '../../domain'
import { MessageItem } from './MessageItem'
import { useStreamingStream, type StreamingState } from './streamingStore'

export interface StreamingMessageProps {
  conversation: Conversation
  showTokenUsage: boolean
  onRetry?: (message: Message) => void
  /** True when the persisted message with the same id is already in history. */
  persisted: boolean
}

function toMessage(conversation: Conversation, stream: StreamingState): Message {
  const parts: ContentPart[] = []

  if (stream.reasoning !== '') {
    parts.push({ type: 'reasoning', text: stream.reasoning })
  }
  if (stream.text !== '') {
    parts.push({ type: 'text', text: stream.text })
  }

  return {
    id: stream.messageId,
    conversationId: stream.conversationId,
    sequence: 0,
    role: 'assistant',
    parts,
    status: stream.status,
    providerId: conversation.providerId,
    modelId: conversation.modelId,
    modelLabel: conversation.modelLabel,
    usage: stream.usage,
    finishReason: stream.finishReason,
    error: stream.error,
    latencyMs: stream.completedAt === null ? null : stream.completedAt - stream.startedAt,
    firstTokenMs: stream.firstTokenAt === null ? null : stream.firstTokenAt - stream.startedAt,
    createdAt: new Date(stream.startedAt).toISOString(),
    updatedAt: new Date(stream.updatedAt).toISOString(),
    deletedAt: null,
  }
}

/**
 * The only component that subscribes to the full streaming snapshot.
 *
 * Token flushes re-render this row alone. Once the final message is persisted
 * and present in history, the row disappears so the persisted message is the
 * single source of truth.
 */
export function StreamingMessage({
  conversation,
  showTokenUsage,
  onRetry,
  persisted,
}: StreamingMessageProps) {
  const stream = useStreamingStream(conversation.id)

  if (stream === null) {
    return null
  }

  if (stream.done && persisted) {
    return null
  }

  return (
    <MessageItem
      message={toMessage(conversation, stream)}
      showTokenUsage={showTokenUsage}
      onRetry={onRetry}
      isActiveStream={!stream.done}
    />
  )
}
