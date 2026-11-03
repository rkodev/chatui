import { Link } from '@tanstack/react-router'
import './conversations.css'

export interface NoConversationStateProps {
  message?: string
}

/**
 * Fallback shown when the root redirect cannot open or create a conversation.
 *
 * This usually means onboarding is complete but no enabled provider/model is
 * available, so the primary action is to add or repair a provider.
 */
export function NoConversationState({ message }: NoConversationStateProps) {
  return (
    <section className="conversation-empty">
      <h2>No conversation selected</h2>
      <p>{message ?? 'Add a provider and start a new chat.'}</p>
      <Link to="/settings/providers/new" className="conversation-empty__link">
        Add OpenRouter
      </Link>
    </section>
  )
}
