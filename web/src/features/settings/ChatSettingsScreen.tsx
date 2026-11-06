import { useCallback, useEffect, useState, type ChangeEvent } from 'react'
import { Link } from '@tanstack/react-router'
import type { ChatParams, Model, Provider, ReasoningEffort, SendKey } from '../../domain'
import { ProviderManagementProvider, useProviderManagement } from '../providers'
import type { SettingsDefaults } from './settingsService'
import { useSettings } from './settingsContext'
import './settings.css'

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

function parseStopSequences(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
}

/**
 * `/settings/chat` chat defaults.
 *
 * Default provider, model, system prompt, and generation parameters live in
 * typed settings and seed every new conversation. Send key, token usage,
 * remote-image loading, and the remember-keys preference apply immediately to
 * chat and settings behavior.
 */
export function ChatSettingsScreen() {
  const management = useProviderManagement()
  const { settings, error: settingsError, loading: settingsLoading, update } = useSettings()
  const [providers, setProviders] = useState<Provider[] | null>(null)
  const [models, setModels] = useState<Model[]>([])
  const [defaults, setDefaults] = useState<SettingsDefaults | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [stops, setStops] = useState('')

  const loadModels = useCallback(
    async (providerId: string | null) => {
      if (providerId === null) {
        setModels([])
        return
      }

      setModels(await management.listModels(providerId))
    },
    [management],
  )

  useEffect(() => {
    let cancelled = false

    Promise.all([management.listProviders(), management.getDefaults()])
      .then(async ([providerList, defaultsValue]) => {
        const providerId = defaultsValue.providerId ?? providerList[0]?.id ?? null
        const modelList = providerId === null ? [] : await management.listModels(providerId)

        if (cancelled) {
          return
        }

        setProviders(providerList)
        setDefaults(defaultsValue)
        setModels(modelList)
        setStops(defaultsValue.params.stop.join('\n'))
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
        }
      })

    return () => {
      cancelled = true
    }
  }, [management])

  async function persistDefaults(patch: Partial<SettingsDefaults>) {
    setError(null)

    try {
      const next = await management.setDefaults(patch)
      setDefaults({
        providerId: next.defaultProviderId,
        modelId: next.defaultModelId,
        systemPrompt: next.defaultSystemPrompt,
        params: next.defaultParams,
      })
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }

  async function handleProviderChange(event: ChangeEvent<HTMLSelectElement>) {
    const providerId = event.target.value === '' ? null : event.target.value

    await persistDefaults({ providerId, modelId: null })
    try {
      await loadModels(providerId)
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }

  function updateParams(patch: Partial<ChatParams>) {
    if (!defaults) {
      return
    }

    const next: ChatParams = {
      ...defaults.params,
      ...patch,
      stop: patch.stop ? [...patch.stop] : [...defaults.params.stop],
      extra: patch.extra ? { ...patch.extra } : { ...defaults.params.extra },
    }

    setDefaults({ ...defaults, params: next })
    void persistDefaults({ params: next })
  }

  if (settings === null || providers === null || defaults === null) {
    const message = error ?? settingsError

    return (
      <section
        className="settings-screen"
        aria-labelledby="chat-settings-title"
        aria-busy={settingsLoading}
      >
        <header className="settings-screen__header">
          <h2 id="chat-settings-title">Chat defaults</h2>
        </header>
        {message ? (
          <p className="settings-screen__error" role="alert">
            {message}
          </p>
        ) : (
          <p role="status">Loading chat defaults…</p>
        )}
      </section>
    )
  }

  const params = defaults.params

  return (
    <section className="settings-screen" aria-labelledby="chat-settings-title">
      <header className="settings-screen__header">
        <h2 id="chat-settings-title">Chat defaults</h2>
        <Link to="/settings/models" className="providers-screen__link">
          Manage models
        </Link>
      </header>

      {(error ?? settingsError) && (
        <p className="settings-screen__error" role="alert">
          {error ?? settingsError}
        </p>
      )}

      <section className="settings-screen__panel" aria-labelledby="chat-default-model-title">
        <h3 id="chat-default-model-title">Default provider and model</h3>
        <p className="settings-screen__muted">
          New conversations start from these defaults. Per-conversation settings can override
          them later.
        </p>

        <label className="settings-screen__field" htmlFor="chat-default-provider">
          <span>Provider</span>
          <select
            id="chat-default-provider"
            value={defaults.providerId ?? ''}
            onChange={(event) => void handleProviderChange(event)}
          >
            <option value="">First enabled provider</option>
            {providers.map((provider) => (
              <option key={provider.id} value={provider.id}>
                {provider.label}
              </option>
            ))}
          </select>
        </label>

        <label className="settings-screen__field" htmlFor="chat-default-model">
          <span>Model</span>
          <select
            id="chat-default-model"
            value={defaults.modelId ?? ''}
            disabled={defaults.providerId === null}
            onChange={(event) =>
              void persistDefaults({
                modelId: event.target.value === '' ? null : event.target.value,
              })
            }
          >
            <option value="">Provider default</option>
            {models.map((model) => (
              <option key={model.id} value={model.id}>
                {model.label}
              </option>
            ))}
          </select>
        </label>

        {providers.length === 0 && (
          <p className="settings-screen__muted">
            No provider is configured. <Link to="/settings/providers/new">Add a provider</Link>{' '}
            before choosing defaults.
          </p>
        )}
      </section>

      <section className="settings-screen__panel" aria-labelledby="chat-default-prompt-title">
        <h3 id="chat-default-prompt-title">Prompt and parameters</h3>

        <label className="settings-screen__field" htmlFor="chat-default-system-prompt">
          <span>System prompt</span>
          <textarea
            id="chat-default-system-prompt"
            rows={4}
            value={defaults.systemPrompt}
            placeholder="Optional instructions sent before each new conversation"
            onChange={(event) => {
              const systemPrompt = event.target.value
              setDefaults({ ...defaults, systemPrompt })
              void persistDefaults({ systemPrompt })
            }}
          />
        </label>

        <div className="settings-screen__grid">
          <label className="settings-screen__field" htmlFor="chat-default-temperature">
            <span>Temperature</span>
            <input
              id="chat-default-temperature"
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

          <label className="settings-screen__field" htmlFor="chat-default-top-p">
            <span>Top P</span>
            <input
              id="chat-default-top-p"
              type="number"
              min={0}
              max={1}
              step={0.05}
              value={params.topP ?? ''}
              placeholder="Model default"
              onChange={(event) => updateParams({ topP: nullableNumber(event.target.value, 0, 1) })}
            />
          </label>

          <label className="settings-screen__field" htmlFor="chat-default-max-output">
            <span>Max output tokens</span>
            <input
              id="chat-default-max-output"
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
          </label>

          <label className="settings-screen__field" htmlFor="chat-default-reasoning">
            <span>Reasoning effort</span>
            <select
              id="chat-default-reasoning"
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

        <label className="settings-screen__field" htmlFor="chat-default-stop">
          <span>Stop sequences</span>
          <textarea
            id="chat-default-stop"
            rows={2}
            value={stops}
            placeholder="One sequence per line"
            onChange={(event) => {
              const value = event.target.value
              setStops(value)
              updateParams({ stop: parseStopSequences(value) })
            }}
          />
        </label>

        <label className="settings-screen__checkbox">
          <input
            type="checkbox"
            checked={params.streaming}
            onChange={(event) => updateParams({ streaming: event.target.checked })}
          />
          <span>Stream responses by default</span>
        </label>
      </section>

      <section className="settings-screen__panel" aria-labelledby="chat-behavior-title">
        <h3 id="chat-behavior-title">Composer and privacy</h3>

        <label className="settings-screen__field" htmlFor="chat-send-key">
          <span>Send key</span>
          <select
            id="chat-send-key"
            value={settings.sendKey}
            onChange={(event) => void update({ sendKey: event.target.value as SendKey })}
          >
            <option value="enter">Enter sends, Shift+Enter adds a line</option>
            <option value="mod-enter">Mod+Enter sends, Enter adds a line</option>
          </select>
        </label>

        <label className="settings-screen__checkbox">
          <input
            type="checkbox"
            checked={settings.showTokenUsage}
            onChange={(event) => void update({ showTokenUsage: event.target.checked })}
          />
          <span>Show token usage on assistant messages</span>
        </label>

        <label className="settings-screen__checkbox">
          <input
            type="checkbox"
            checked={settings.loadRemoteImages}
            onChange={(event) => void update({ loadRemoteImages: event.target.checked })}
          />
          <span>Load remote images in Markdown responses</span>
        </label>
        <p className="settings-screen__muted">
          Remote images are blocked by default. When enabled, image URLs from model output are
          requested directly from their host. Messages always keep a per-image Load button.
        </p>

        <label className="settings-screen__checkbox">
          <input
            type="checkbox"
            checked={settings.rememberKeysOnWeb}
            onChange={(event) => void update({ rememberKeysOnWeb: event.target.checked })}
          />
          <span>Remember keys on this device by default</span>
        </label>
        <p className="settings-screen__muted">
          Keys are encrypted with a non-extractable device key and are never placed in URLs or
          logs. Each provider key still records its own remember or session choice.
        </p>
      </section>
    </section>
  )
}

/** `/settings/chat` route component. */
export function ChatSettingsRoute() {
  return (
    <ProviderManagementProvider>
      <ChatSettingsScreen />
    </ProviderManagementProvider>
  )
}
