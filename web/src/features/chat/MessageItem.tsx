import { memo } from 'react'
import type { ContentPart, Message, MessageRole, Usage } from '../../domain'
import { formatUsd } from '../providers/format'
import { Button } from '../../ui/primitives'
import { describeChatError } from './chatErrorCopy'
import { messageItemPropsEqual, type MessageItemProps } from './messageItemProps'
import './chat.css'

const ROLE_LABELS: Record<MessageRole, string> = {
  system: 'System',
  user: 'You',
  assistant: 'Assistant',
  tool: 'Tool',
}

function formatTime(value: string): string {
  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    return ''
  }

  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function formatUsage(usage: Usage): string {
  const parts: string[] = []

  if (usage.inputTokens !== null) {
    parts.push(`${usage.inputTokens} in`)
  }
  if (usage.outputTokens !== null) {
    parts.push(`${usage.outputTokens} out`)
  }
  if (usage.totalTokens !== null) {
    parts.push(`${usage.totalTokens} total`)
  }
  if (usage.costUsd !== null) {
    parts.push(formatUsd(usage.costUsd))
  }

  return parts.join(' · ')
}

function textParts(message: Message): string[] {
  return message.parts
    .filter((part): part is Extract<ContentPart, { type: 'text' }> => part.type === 'text')
    .map((part) => part.text)
    .filter((text) => text !== '')
}

function reasoningParts(message: Message): string[] {
  return message.parts
    .filter((part): part is Extract<ContentPart, { type: 'reasoning' }> => part.type === 'reasoning')
    .map((part) => part.text)
    .filter((text) => text !== '')
}

function MessageItemComponent({
  message,
  showTokenUsage,
  onRetry,
  isActiveStream = false,
}: MessageItemProps) {
  const roleLabel = ROLE_LABELS[message.role]
  const text = textParts(message)
  const reasoning = reasoningParts(message)
  const hasText = text.some((part) => part.trim() !== '')
  const errorCopy = message.error === null ? null : describeChatError(message.error)
  const usage = message.usage === null ? '' : formatUsage(message.usage)
  const isInterrupted = message.status === 'streaming' && !isActiveStream

  return (
    <article
      className={`message message--${message.role}`}
      data-role={message.role}
      data-status={message.status}
      aria-label={`${roleLabel} message`}
    >
      <header className="message__header">
        <span className="message__role">{roleLabel}</span>
        <time className="message__time" dateTime={message.createdAt}>
          {formatTime(message.createdAt)}
        </time>
      </header>

      <div className="message__content">
        {reasoning.length > 0 && (
          <details className="message__reasoning">
            <summary>Reasoning</summary>
            {reasoning.map((part, index) => (
              <p key={index} className="message__text">
                {part}
              </p>
            ))}
          </details>
        )}

        {text.map((part, index) => (
          <p key={index} className="message__text">
            {part}
          </p>
        ))}

        {!hasText && message.status === 'streaming' && isActiveStream && (
          <p className="message__typing" aria-label="Assistant is typing">
            <span aria-hidden="true">…</span>
          </p>
        )}

        {!hasText && message.status === 'complete' && (
          <p className="message__empty">No content.</p>
        )}

        {message.status === 'cancelled' && (
          <p className="message__status">Response cancelled. Partial content is preserved.</p>
        )}

        {isInterrupted && (
          <p className="message__status">
            Interrupted. The app closed before this response finished.
          </p>
        )}

        {errorCopy && (
          <div className="message__error" role="alert">
            <strong className="message__error-title">{errorCopy.title}</strong>
            <p className="message__error-message">{errorCopy.message}</p>
            {onRetry && (
              <Button
                variant="secondary"
                size="sm"
                onClick={() => onRetry(message)}
                aria-label="Retry response"
              >
                Retry
              </Button>
            )}
            {errorCopy.details && (
              <details className="message__error-details">
                <summary>Details</summary>
                <pre>{errorCopy.details}</pre>
              </details>
            )}
          </div>
        )}
      </div>

      <footer className="message__meta">
        {message.role === 'assistant' && (message.modelLabel || message.modelId) && (
          <span className="message__model">
            {message.modelLabel ?? message.modelId}
            {message.providerId ? ` · ${message.providerId}` : ''}
          </span>
        )}
        {showTokenUsage && usage !== '' && <span className="message__usage">{usage}</span>}
      </footer>
    </article>
  )
}

/**
 * One message row.
 *
 * Memoization is keyed by message id plus the content revision fields that can
 * change after persistence. Streaming rows pass a new message object on every
 * flush, so only the streaming row re-renders.
 */
export const MessageItem = memo(MessageItemComponent, messageItemPropsEqual)
