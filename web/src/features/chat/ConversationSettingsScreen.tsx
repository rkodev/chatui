import { useEffect, useState, type ChangeEvent } from 'react'
import { Link } from '@tanstack/react-router'
import type { ChatParams, Conversation, Model, Provider, ReasoningEffort } from '../../domain'
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from '../../ui/primitives'
import {
  ConversationModelControl,
  ConversationRoutingControls,
  extraNamespaceForPreset,
  parseProviderExtras,
  readProviderExtras,
  stringifyProviderExtras,
  unsupportedParameterNote,
  withProviderExtras,
} from '../providers'
import { useProviderManagement } from '../providers/providerManagementContext'
import './chat.css'

export interface ConversationSettingsScreenProps {
  conversationId: string
  /** Called after a confirmed delete persists. The route navigates away. */
  onDeleted?: () => void
}

type SettingsPatch = { systemPrompt?: string; params?: ChatParams }

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

function nullableNumber(value: string, min: number, max: number): number | null {
  if (value.trim() === '') {
    return null
  }

  const parsed = Number(value)

  if (!Number.isFinite(parsed)) {
    return null
  }

  return Math.min(max, Math.max(min, parsed))
}

function stopText(params: ChatParams): string {
  return params.stop.join('\n')
}

function parseStopSequences(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
}

/**
 * Full conversation settings surface.
 *
 * Every field persists through the conversation record as it changes. Model
 * switches use the provider-management service so stale provider extras are
 * pruned, while core parameter values are preserved for the next send.
 */
export function ConversationSettingsScreen({
  conversationId,
  onDeleted,
}: ConversationSettingsScreenProps) {
  const service = useProviderManagement()
  const [conversation, setConversation] = useState<Conversation | null | undefined>(undefined)
  const [models, setModels] = useState<Model[]>([])
  const [providers, setProviders] = useState<Provider[]>([])
  const [systemPrompt, setSystemPrompt] = useState('')
  const [params, setParams] = useState<ChatParams | null>(null)
  const [stops, setStops] = useState('')
  const [extrasText, setExtrasText] = useState('')
  const [extrasError, setExtrasError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)

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

        const [modelList, providerList] = await Promise.all([
          service.listModels(value.providerId),
          service.listProviders(),
        ])

        if (cancelled) {
          return
        }

        setConversation(value)
        setModels(modelList)
        setProviders(providerList)
        setSystemPrompt(value.systemPrompt)
        setParams(value.params)
        setStops(stopText(value.params))
        setExtrasText(
          stringifyProviderExtras(
            value.params,
            extraNamespaceForPreset(
              providerList.find((provider) => provider.id === value.providerId)?.presetId ??
                'openrouter',
            ),
          ),
        )
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

  async function persist(patch: SettingsPatch) {
    setError(null)

    try {
      const updated = await service.setConversationSettings(conversationId, patch)
      setConversation(updated)
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }

  function updateParams(patch: Partial<ChatParams>) {
    if (!params) {
      return
    }

    const next: ChatParams = {
      ...params,
      ...patch,
      stop: patch.stop ? [...patch.stop] : [...params.stop],
      extra: patch.extra ? { ...patch.extra } : { ...params.extra },
    }
    setParams(next)
    void persist({ params: next })
  }

  function handleSystemPrompt(event: ChangeEvent<HTMLTextAreaElement>) {
    const value = event.target.value
    setSystemPrompt(value)
    void persist({ systemPrompt: value })
  }

  function handleStopChange(event: ChangeEvent<HTMLTextAreaElement>) {
    const value = event.target.value
    setStops(value)
    updateParams({ stop: parseStopSequences(value) })
  }

  function handleExtrasChange(event: ChangeEvent<HTMLTextAreaElement>) {
    const value = event.target.value
    setExtrasText(value)

    const parsed = parseProviderExtras(value)

    if (!parsed.ok) {
      setExtrasError(parsed.message ?? 'Provider extras are invalid.')
      return
    }

    setExtrasError(null)

    if (!params) {
      return
    }

    const currentProvider = providers.find((candidate) => candidate.id === conversation?.providerId)
    const namespace = extraNamespaceForPreset(currentProvider?.presetId ?? 'openrouter')

    updateParams({
      extra: withProviderExtras(params, namespace, parsed.value ?? {}).extra,
    })
  }

  function handleConversationChange(updated: Conversation) {
    setConversation(updated)

    const provider = providers.find((candidate) => candidate.id === updated.providerId)
    const namespace = extraNamespaceForPreset(provider?.presetId ?? 'openrouter')

    setParams(updated.params)
    setStops(stopText(updated.params))

    if (updated.providerId !== conversation?.providerId) {
      setExtrasText(stringifyProviderExtras(updated.params, namespace))
      setExtrasError(null)
    }
  }

  async function handleDelete() {
    setError(null)

    try {
      await service.deleteConversation(conversationId)
      setConfirmDelete(false)
      onDeleted?.()
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }

  if (conversation === undefined || (conversation !== null && params === null)) {
    if (error) {
      return (
        <p className="chat-screen__error" role="alert">
          {error}
        </p>
      )
    }

    return (
      <section className="conversation-settings" aria-busy="true">
        <p role="status">Loading conversation settings…</p>
      </section>
    )
  }

  if (conversation === null) {
    return (
      <section className="conversation-settings">
        <h2>Conversation not found</h2>
        <p>This conversation may have been deleted.</p>
        <Link to="/" className="chat-screen__link">
          Back to chats
        </Link>
      </section>
    )
  }

  if (params === null) {
    return null
  }

  const provider = providers.find((candidate) => candidate.id === conversation.providerId) ?? null
  const model = models.find((candidate) => candidate.id === conversation.modelId) ?? null
  const namespace = extraNamespaceForPreset(provider?.presetId ?? 'openrouter')
  const supportNote = unsupportedParameterNote(params, model?.supportedParameters ?? [])
  const maxOutputHint =
    model?.maxOutputTokens === null || model?.maxOutputTokens === undefined
      ? null
      : `Model maximum: ${model.maxOutputTokens.toLocaleString()} tokens.`

  return (
    <section className="conversation-settings" aria-labelledby="conversation-settings-title">
      <header className="conversation-settings__header">
        <div>
          <h2 id="conversation-settings-title">Conversation settings</h2>
          <p className="conversation-settings__muted">{conversation.title}</p>
        </div>
        <Link
          to="/chat/$conversationId"
          params={{ conversationId: conversation.id }}
          className="chat-screen__link"
        >
          Back to chat
        </Link>
      </header>

      {error && (
        <p className="chat-screen__error" role="alert">
          {error}
        </p>
      )}

      <section className="conversation-settings__panel" aria-labelledby="settings-model-heading">
        <h3 id="settings-model-heading">Model</h3>
        <ConversationModelControl
          conversationId={conversation.id}
          onConversationChange={handleConversationChange}
        />
        <p className="conversation-settings__muted">
          Model changes apply to the next message and never rewrite history.
        </p>
      </section>

      <form className="conversation-settings__panel" onSubmit={(event) => event.preventDefault()}>
        <h3>System prompt and parameters</h3>

        <label className="conversation-settings__field" htmlFor="conversation-system-prompt">
          <span>System prompt</span>
          <textarea
            id="conversation-system-prompt"
            rows={4}
            value={systemPrompt}
            placeholder="Optional instructions sent before the conversation history"
            onChange={handleSystemPrompt}
          />
        </label>

        <div className="conversation-settings__grid">
          <label className="conversation-settings__field" htmlFor="conversation-temperature">
            <span>Temperature</span>
            <input
              id="conversation-temperature"
              type="number"
              min={0}
              max={2}
              step={0.1}
              value={params.temperature ?? ''}
              placeholder="Model default"
              onChange={(event) =>
                updateParams({ temperature: nullableNumber(event.target.value, 0, 2) })
              }
            />
          </label>

          <label className="conversation-settings__field" htmlFor="conversation-top-p">
            <span>Top P</span>
            <input
              id="conversation-top-p"
              type="number"
              min={0}
              max={1}
              step={0.05}
              value={params.topP ?? ''}
              placeholder="Model default"
              onChange={(event) => updateParams({ topP: nullableNumber(event.target.value, 0, 1) })}
            />
          </label>

          <label className="conversation-settings__field" htmlFor="conversation-max-output">
            <span>Max output tokens</span>
            <input
              id="conversation-max-output"
              type="number"
              min={1}
              step={1}
              value={params.maxOutputTokens ?? ''}
              placeholder="Model default"
              onChange={(event) =>
                updateParams({
                  maxOutputTokens: nullableNumber(event.target.value, 1, Number.MAX_SAFE_INTEGER),
                })
              }
            />
            {maxOutputHint && <span className="conversation-settings__hint">{maxOutputHint}</span>}
          </label>

          <label className="conversation-settings__field" htmlFor="conversation-reasoning">
            <span>Reasoning effort</span>
            <select
              id="conversation-reasoning"
              value={params.reasoningEffort ?? ''}
              onChange={(event) =>
                updateParams({
                  reasoningEffort:
                    event.target.value === '' ? null : (event.target.value as ReasoningEffort),
                })
              }
            >
              <option value="">Provider default</option>
              <option value="low">Low</option>
              <option value="medium">Medium</option>
              <option value="high">High</option>
            </select>
          </label>
        </div>

        <label className="conversation-settings__field" htmlFor="conversation-stop">
          <span>Stop sequences</span>
          <textarea
            id="conversation-stop"
            rows={2}
            value={stops}
            placeholder="One sequence per line"
            onChange={handleStopChange}
          />
        </label>

        <label className="conversation-settings__checkbox">
          <input
            type="checkbox"
            checked={params.streaming}
            onChange={(event) => updateParams({ streaming: event.target.checked })}
          />
          <span>Stream responses</span>
        </label>

        {supportNote && (
          <p className="conversation-settings__note" role="status">
            {supportNote}
          </p>
        )}
      </form>

      {provider?.presetId === 'openrouter' && (
        <section className="conversation-settings__panel">
          <h3>Routing</h3>
          <ConversationRoutingControls conversationId={conversation.id} />
        </section>
      )}

      <section className="conversation-settings__panel" aria-labelledby="settings-extras-heading">
        <h3 id="settings-extras-heading">Provider extras</h3>
        <p className="conversation-settings__muted">
          Advanced JSON for {provider?.label ?? 'this provider'}, stored under{' '}
          <code>params.extra.{namespace}</code>. Core fields are read-only here.
        </p>
        <label className="conversation-settings__field" htmlFor="conversation-extras">
          <span>{namespace} extras</span>
          <textarea
            id="conversation-extras"
            rows={6}
            spellCheck={false}
            value={extrasText}
            placeholder={'{\n  "key": "value"\n}'}
            aria-invalid={extrasError !== null}
            aria-describedby={extrasError ? 'conversation-extras-error' : undefined}
            onChange={handleExtrasChange}
          />
        </label>
        {extrasError && (
          <p id="conversation-extras-error" className="chat-screen__error" role="alert">
            {extrasError}
          </p>
        )}
        <p className="conversation-settings__hint">
          Existing extras are {JSON.stringify(readProviderExtras(params, namespace))}.
        </p>
      </section>

      <section className="conversation-settings__panel" aria-labelledby="settings-data-heading">
        <h3 id="settings-data-heading">Conversation data</h3>
        <div className="conversation-settings__actions">
          <Button variant="secondary" disabled>
            Export conversation (M2-04)
          </Button>
          <Button variant="danger" onClick={() => setConfirmDelete(true)}>
            Delete conversation
          </Button>
        </div>
        <p className="conversation-settings__muted">
          Per-conversation export arrives in M2-04. Delete removes this conversation and its
          messages from this device.
        </p>
      </section>

      <Dialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <DialogContent>
          <DialogTitle>Delete conversation?</DialogTitle>
          <DialogDescription>
            “{conversation.title}” and its messages will be deleted from this device.
          </DialogDescription>
          <div className="conversation-settings__actions">
            <DialogClose asChild>
              <Button variant="secondary">Cancel</Button>
            </DialogClose>
            <Button variant="danger" onClick={handleDelete}>
              Delete
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </section>
  )
}
