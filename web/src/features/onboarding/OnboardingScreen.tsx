import { useEffect, useState, type FormEvent } from 'react'
import type { ModelInfo } from '../../providers/openrouter'
import { getPresetAvailability, providerPresets } from '../../providers/registry'
import { Button } from '../../ui/primitives'
import { Icon } from '../../ui/Icon'
import {
  describeCatalogError,
  describeOnboardingError,
  type OnboardingErrorCopy,
} from './errorCopy'
import type { OnboardingServiceLike } from './types'
import './onboarding.css'

type CatalogState =
  | { status: 'loading'; models: ModelInfo[]; message: null }
  | { status: 'ready'; models: ModelInfo[]; message: null }
  | { status: 'error'; models: ModelInfo[]; message: string }

export interface OnboardingScreenProps {
  service: OnboardingServiceLike
  onConnected: (conversationId: string) => void
}

/**
 * First-run onboarding and key entry.
 *
 * The public catalog loads immediately so models are visible before a key is
 * validated. Chat stays disabled until `GET /key` succeeds. On failure the
 * typed key and label remain in the form and the key is never echoed.
 */
export function OnboardingScreen({ service, onConnected }: OnboardingScreenProps) {
  const [apiKey, setApiKey] = useState('')
  const [label, setLabel] = useState('')
  const [rememberKeys, setRememberKeys] = useState(true)
  const [testing, setTesting] = useState(false)
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState<OnboardingErrorCopy | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [catalog, setCatalog] = useState<CatalogState>({
    status: 'loading',
    models: [],
    message: null,
  })

  useEffect(() => {
    const controller = new AbortController()

    service
      .loadCatalog(controller.signal)
      .then((models) => {
        if (!controller.signal.aborted) {
          setCatalog({ status: 'ready', models, message: null })
        }
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setCatalog({
            status: 'error',
            models: [],
            message: describeCatalogError(cause),
          })
        }
      })

    return () => {
      controller.abort()
    }
  }, [service])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (testing || connected) {
      return
    }

    const trimmedKey = apiKey.trim()

    if (trimmedKey === '') {
      setError({
        title: 'Enter an API key',
        message: 'Paste your OpenRouter API key to continue.',
        details: null,
      })
      return
    }

    setTesting(true)
    setError(null)
    setNotice(null)

    try {
      const result = await service.connect({
        apiKey: trimmedKey,
        label,
        rememberKeys,
      })

      setNotice(result.notice)
      setConnected(true)
      setTesting(false)
      onConnected(result.conversationId)
    } catch (cause) {
      setError(describeOnboardingError(cause, trimmedKey))
      setTesting(false)
    }
  }

  const providers = getPresetAvailability(providerPresets, ['openrouter'])
  const connectDisabled = testing || connected || apiKey.trim() === ''
  const renderProvider = ({ preset, enabled, status }: (typeof providers)[number]) => (
    <label key={preset.id} className="onboarding__provider" data-disabled={!enabled}>
      <input
        type="radio"
        name="onboarding-provider"
        value={preset.id}
        checked={preset.id === 'openrouter'}
        disabled={!enabled}
        onChange={() => undefined}
      />
      <span className="onboarding__provider-name">{preset.label}</span>
      {preset.id === 'openrouter' && <span className="onboarding__badge">Recommended</span>}
      {status === 'disabled' && <span className="onboarding__muted">Add from Settings</span>}
      {status === 'coming-later' && <span className="onboarding__muted">Coming later</span>}
    </label>
  )

  return (
    <section className="onboarding" aria-labelledby="onboarding-title">
      <div className="onboarding__intro">
        <span className="onboarding__eyebrow">Your AI, on your terms</span>
        <h2 id="onboarding-title">Welcome to ChatUI</h2>
        <p>
          One calm place for your favorite models. ChatUI is bring-your-own-key (BYOK): your
          conversations, settings, and key stay on this device. Nothing is sent to a ChatUI server.
        </p>
      </div>

      <div className="onboarding__setup">
        <fieldset className="onboarding__providers">
          <legend>Choose a provider</legend>
          {providers.filter(({ enabled }) => enabled).map(renderProvider)}
          <details className="onboarding__other-providers">
            <summary>Looking for another provider?</summary>
            <p className="onboarding__help">
              OpenRouter is required for this preview. Other enabled providers can be added from
              Settings after you connect.
            </p>
            <div className="onboarding__provider-options">
              {providers.filter(({ enabled }) => !enabled).map(renderProvider)}
            </div>
          </details>
        </fieldset>

        <form className="onboarding__form" onSubmit={handleSubmit}>
          <div className="onboarding__form-intro">
            <h3>Connect your first provider</h3>
            <p>Your favorite models are just one key away.</p>
          </div>
          <div className="onboarding__field">
            <label htmlFor="onboarding-api-key">OpenRouter API key</label>
            <input
              id="onboarding-api-key"
              type="password"
              autoComplete="off"
              spellCheck={false}
              value={apiKey}
              placeholder="Paste your OpenRouter key"
              onChange={(event) => setApiKey(event.target.value)}
              aria-describedby="onboarding-key-help"
            />
            <p id="onboarding-key-help" className="onboarding__help">
              Your key is stored on this device and is never sent to a ChatUI server.
            </p>
            <a
              href="https://openrouter.ai/keys"
              target="_blank"
              rel="noopener noreferrer"
              className="onboarding__key-link"
            >
              Get an OpenRouter key <Icon name="arrow-right" size={14} />
            </a>
          </div>

          <div className="onboarding__field">
            <label htmlFor="onboarding-key-label">Label (optional)</label>
            <input
              id="onboarding-key-label"
              type="text"
              autoComplete="off"
              value={label}
              onChange={(event) => setLabel(event.target.value)}
              placeholder="Personal, Work, …"
            />
          </div>

          <label className="onboarding__checkbox">
            <input
              type="checkbox"
              checked={rememberKeys}
              onChange={(event) => setRememberKeys(event.target.checked)}
            />
            <span>Remember this key on this device</span>
          </label>

          <div className="onboarding__actions">
            <Button type="submit" disabled={connectDisabled}>
              {testing ? 'Testing…' : connected ? 'Connected' : 'Connect'}
              <Icon name="arrow-right" size={17} />
            </Button>
            {testing && (
              <p className="onboarding__status" role="status">
                Testing key…
              </p>
            )}
            {connected && (
              <p className="onboarding__status" role="status">
                Connected. Opening a new chat…
              </p>
            )}
          </div>

          {notice && (
            <p className="onboarding__notice" role="status">
              {notice}
            </p>
          )}

          {error && (
            <div className="onboarding__error" role="alert">
              <strong className="onboarding__error-title">{error.title}</strong>
              <p>{error.message}</p>
              {error.details && (
                <details className="onboarding__details">
                  <summary>Details</summary>
                  <pre>{error.details}</pre>
                </details>
              )}
            </div>
          )}
        </form>
      </div>

      <section className="onboarding__catalog" aria-labelledby="onboarding-models-title">
        <h3 id="onboarding-models-title">
          <Icon name="layers" size={20} />
          Available models
        </h3>
        {catalog.status === 'loading' && (
          <p className="onboarding__status" role="status">
            Loading the public model catalog…
          </p>
        )}
        {catalog.status === 'error' && (
          <p className="onboarding__status" role="status">
            {catalog.message}
          </p>
        )}
        {catalog.status === 'ready' && catalog.models.length === 0 && (
          <p className="onboarding__status" role="status">
            No models were returned. You can still connect; models will load after validation.
          </p>
        )}
        {catalog.status === 'ready' && catalog.models.length > 0 && (
          <>
            <p className="onboarding__help">
              {catalog.models.length} models are available. Chat stays disabled until your key is
              validated.
            </p>
            <ul className="onboarding__models">
              {catalog.models.map((model) => (
                <li key={model.remoteId}>
                  <span className="onboarding__model-initial" aria-hidden="true">
                    {model.label.slice(0, 1).toUpperCase()}
                  </span>
                  <div>
                    <span className="onboarding__model-label">{model.label}</span>
                    <span className="onboarding__muted">{model.remoteId}</span>
                  </div>
                </li>
              ))}
            </ul>
          </>
        )}
        <div className="onboarding__privacy">
          <Icon name="shield" size={20} />
          <div>
            <strong>Yours, from the first message</strong>
            <p>No ChatUI account. No middleman. Requests go directly to the provider you choose.</p>
          </div>
        </div>
        <Button
          type="button"
          variant="secondary"
          disabled
          aria-describedby="onboarding-chat-disabled"
        >
          Start chatting
        </Button>
        <p id="onboarding-chat-disabled" className="onboarding__help">
          Chat stays disabled until your key is validated.
        </p>
      </section>
    </section>
  )
}
