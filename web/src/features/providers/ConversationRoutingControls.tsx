import { useEffect, useState } from 'react'
import type { Conversation } from '../../domain'
import { useProviderManagement } from './providerManagementContext'
import {
  DEFAULT_ROUTING_PREFERENCES,
  routingSummary,
  type RoutingPreferences,
  type RoutingSort,
} from './routing'
import './providers.css'

export interface ConversationRoutingControlsProps {
  conversationId: string
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * Curated OpenRouter routing controls for one conversation.
 *
 * Values are stored in `conversation.params.extra.openrouter.provider` and
 * persisted immediately. The summary is shown only when routing is active.
 */
export function ConversationRoutingControls({
  conversationId,
}: ConversationRoutingControlsProps) {
  const service = useProviderManagement()
  const [conversation, setConversation] = useState<Conversation | null | undefined>(undefined)
  const [preferences, setPreferences] = useState<RoutingPreferences>(
    DEFAULT_ROUTING_PREFERENCES,
  )
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    service
      .getConversation(conversationId)
      .then(async (value) => {
        if (!value) {
          if (!cancelled) {
            setConversation(null)
          }
          return
        }

        const routing = await service.getConversationRouting(conversationId)

        if (!cancelled) {
          setConversation(value)
          setPreferences(routing)
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
        }
      })

    return () => {
      cancelled = true
    }
  }, [conversationId, service])

  async function update(next: RoutingPreferences) {
    if (!conversation) {
      return
    }

    setPreferences(next)
    setError(null)

    try {
      const updated = await service.setConversationRouting(conversation.id, next)
      setConversation(updated)
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }

  if (conversation === undefined) {
    if (error) {
      return (
        <p className="model-picker__error" role="alert">
          {error}
        </p>
      )
    }

    return <p role="status">Loading routing settings…</p>
  }

  if (conversation === null) {
    return <p role="alert">Conversation not found.</p>
  }

  const summary = routingSummary(preferences)

  return (
    <fieldset className="routing-controls">
      <legend>OpenRouter routing</legend>

      <label className="routing-controls__checkbox">
        <input
          type="checkbox"
          checked={preferences.enabled}
          onChange={(event) =>
            update({ ...preferences, enabled: event.target.checked })
          }
        />
        <span>Use custom routing preferences</span>
      </label>

      <label className="routing-controls__checkbox">
        <input
          type="checkbox"
          checked={preferences.allowFallbacks}
          disabled={!preferences.enabled}
          onChange={(event) =>
            update({ ...preferences, allowFallbacks: event.target.checked })
          }
        />
        <span>Allow provider fallbacks</span>
      </label>

      <div className="routing-controls__field">
        <label htmlFor="routing-sort">Sort providers by</label>
        <select
          id="routing-sort"
          value={preferences.sort ?? ''}
          disabled={!preferences.enabled}
          onChange={(event) =>
            update({
              ...preferences,
              sort: event.target.value === '' ? null : (event.target.value as RoutingSort),
            })
          }
        >
          <option value="">OpenRouter default</option>
          <option value="price">Price</option>
          <option value="throughput">Throughput</option>
          <option value="latency">Latency</option>
        </select>
      </div>

      <label className="routing-controls__checkbox">
        <input
          type="checkbox"
          checked={preferences.denyDataCollection}
          disabled={!preferences.enabled}
          onChange={(event) =>
            update({ ...preferences, denyDataCollection: event.target.checked })
          }
        />
        <span>Do not use providers that may train on prompts</span>
      </label>

      <p className="routing-controls__warning">
        Denying data collection can make some models unavailable and may exclude free
        endpoints.
      </p>

      {summary && (
        <p className="routing-controls__summary" role="status">
          {summary}
        </p>
      )}

      {error && (
        <p className="model-picker__error" role="alert">
          {error}
        </p>
      )}
    </fieldset>
  )
}
