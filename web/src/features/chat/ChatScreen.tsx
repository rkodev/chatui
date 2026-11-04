import type { ReactNode } from 'react'
import { Link } from '@tanstack/react-router'
import { ModelChip } from '../providers'
import { MessageList } from './MessageList'
import type { ChatSession } from './useChatSession'
import './chat.css'

export interface ChatScreenProps {
  session: ChatSession
  /** Composer extension point owned by M1-04.3. */
  composer?: ReactNode
}

function LoadingState() {
  return (
    <section className="chat-screen" aria-busy="true">
      <p role="status">Loading conversation…</p>
    </section>
  )
}

function NotFoundState() {
  return (
    <section className="chat-screen">
      <h2>Conversation not found</h2>
      <p>This conversation may have been deleted.</p>
      <Link to="/" className="chat-screen__link">
        Back to chats
      </Link>
    </section>
  )
}

function ErrorState({ message }: { message: string }) {
  return (
    <section className="chat-screen">
      <h2>Could not load the conversation</h2>
      <div className="chat-screen__error" role="alert">
        {message}
      </div>
      <Link to="/" className="chat-screen__link">
        Back to chats
      </Link>
    </section>
  )
}

/**
 * Chat route screen.
 *
 * The screen renders history, model availability, and chat states. It does not
 * subscribe to streaming text; `MessageList` delegates that to the isolated
 * streaming row. The composer is a slot so M1-04.3 can mount it without
 * changing the route.
 */
export function ChatScreen({ session, composer }: ChatScreenProps) {
  if (session.status === 'loading') {
    return <LoadingState />
  }

  if (session.status === 'not_found') {
    return <NotFoundState />
  }

  if (session.status === 'error' || session.conversation === null) {
    return <ErrorState message={session.error ?? 'The conversation could not be loaded.'} />
  }

  const conversation = session.conversation
  const availability = session.availability
  const unavailable = availability !== null && !availability.ok ? availability : null
  const modelLabel = conversation.modelLabel ?? conversation.modelId ?? 'No model selected'
  const isEmpty = session.messages.length === 0 && !session.isStreaming

  return (
    <section className="chat-screen" aria-labelledby="chat-title">
      <header className="chat-screen__header">
        <div className="chat-screen__heading">
          <h2 id="chat-title">{conversation.title}</h2>
          <p className="chat-screen__subtitle">{modelLabel}</p>
        </div>
        <div className="chat-screen__actions">
          <ModelChip
            label={modelLabel}
            warning={unavailable?.reason === 'model_unavailable' ? unavailable.message : null}
          />
          <Link
            to="/chat/$conversationId/settings"
            params={{ conversationId: conversation.id }}
            className="chat-screen__link"
          >
            Conversation settings
          </Link>
        </div>
      </header>

      {unavailable !== null && unavailable.reason === 'no_provider' && (
        <div className="chat-screen__notice" role="status">
          <p>No provider is configured. Add a provider before starting a chat.</p>
          <Link to="/settings/providers/new" className="chat-screen__link">
            Add OpenRouter
          </Link>
        </div>
      )}

      {unavailable !== null && unavailable.reason === 'model_unavailable' && (
        <div className="chat-screen__notice" role="status">
          <p>{unavailable.message}</p>
          <Link to="/settings/models" className="chat-screen__link">
            Choose another model
          </Link>
        </div>
      )}

      {session.error && (
        <div className="chat-screen__error" role="alert">
          {session.error}
        </div>
      )}

      <div className="chat-screen__body">
        {isEmpty ? (
          <div className="chat-screen__empty">
            <h3>Start the conversation</h3>
            <p>Send a message to {modelLabel}.</p>
          </div>
        ) : (
          <MessageList
            conversation={conversation}
            messages={session.messages}
            streamingMessageId={session.streamingMessageId}
            isStreaming={session.isStreaming}
            nextCursor={session.nextCursor}
            loadingOlder={session.loadingOlder}
            onLoadOlder={session.loadOlder}
            showTokenUsage={session.showTokenUsage}
            onRetry={session.retry}
            onRegenerate={session.regenerate}
            onEditLastUser={session.editLastUser}
          />
        )}
      </div>

      <div className="chat-screen__composer" data-testid="chat-composer-slot">
        {composer}
      </div>

      <div
        className="sr-only"
        role="status"
        aria-live="polite"
        aria-atomic="true"
        data-testid="chat-announcement"
      >
        {session.announcement}
      </div>
    </section>
  )
}
