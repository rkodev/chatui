import { useState, type FormEvent } from 'react'
import type { Provider, ProviderAuthType } from '../../domain'
import { Button } from '../../ui/primitives'
import { useProviderManagement } from './providerManagementContext'
import './providers.css'

export interface CustomProviderFormProps {
  onCreated?: (provider: Provider) => void
}

function parseHeaders(text: string): Record<string, string> {
  const headers: Record<string, string> = {}

  for (const line of text.split('\n')) {
    const trimmed = line.trim()

    if (trimmed === '') {
      continue
    }

    const separator = trimmed.indexOf(':')

    if (separator <= 0) {
      throw new Error(`Header lines must use "Name: value". Invalid line: ${trimmed}`)
    }

    const name = trimmed.slice(0, separator).trim()
    const value = trimmed.slice(separator + 1).trim()
    headers[name] = value
  }

  return headers
}

/**
 * Custom OpenAI-compatible provider creation form.
 *
 * The form only collects input. URL, auth, header, and duplicate-label
 * validation live in `ProviderService` so the same rules apply to every caller.
 */
export function CustomProviderForm({ onCreated }: CustomProviderFormProps) {
  const service = useProviderManagement()
  const [label, setLabel] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [authType, setAuthType] = useState<ProviderAuthType>('bearer')
  const [apiKey, setApiKey] = useState('')
  const [rememberKeys, setRememberKeys] = useState(true)
  const [headersText, setHeadersText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (busy) {
      return
    }

    setBusy(true)
    setError(null)
    setNotice(null)

    try {
      const headers = parseHeaders(headersText)
      const provider = await service.createCustomProvider({
        label,
        baseUrl,
        authType,
        apiKey: authType === 'none' ? undefined : apiKey,
        rememberKeys,
        headers,
      })

      setNotice(`Added ${provider.label}.`)
      setLabel('')
      setBaseUrl('')
      setApiKey('')
      setHeadersText('')
      onCreated?.(provider)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="provider-detail__panel" aria-labelledby="custom-provider-title">
      <h3 id="custom-provider-title">Add a custom provider</h3>
      <p className="provider-detail__muted">
        Custom providers use an OpenAI-compatible API. Enter an http(s) base URL and an
        authentication style. Keys are stored separately from the provider record.
      </p>

      <form className="provider-detail__form" onSubmit={handleSubmit} noValidate>
        <div className="provider-detail__field">
          <label htmlFor="custom-provider-label">Label (optional)</label>
          <input
            id="custom-provider-label"
            type="text"
            autoComplete="off"
            value={label}
            onChange={(event) => setLabel(event.target.value)}
            placeholder="Custom OpenAI-compatible"
          />
        </div>

        <div className="provider-detail__field">
          <label htmlFor="custom-provider-base-url">Base URL</label>
          <input
            id="custom-provider-base-url"
            type="url"
            autoComplete="off"
            spellCheck={false}
            value={baseUrl}
            onChange={(event) => setBaseUrl(event.target.value)}
            placeholder="https://api.example.com/v1"
          />
        </div>

        <div className="provider-detail__field">
          <label htmlFor="custom-provider-auth">Authentication</label>
          <select
            id="custom-provider-auth"
            value={authType}
            onChange={(event) => setAuthType(event.target.value as ProviderAuthType)}
          >
            <option value="bearer">Bearer token</option>
            <option value="x-api-key">x-api-key header</option>
            <option value="none">No authentication</option>
          </select>
        </div>

        {authType !== 'none' && (
          <div className="provider-detail__field">
            <label htmlFor="custom-provider-key">API key</label>
            <input
              id="custom-provider-key"
              type="password"
              autoComplete="off"
              spellCheck={false}
              value={apiKey}
              onChange={(event) => setApiKey(event.target.value)}
            />
          </div>
        )}

        <label className="provider-detail__checkbox">
          <input
            type="checkbox"
            checked={rememberKeys}
            onChange={(event) => setRememberKeys(event.target.checked)}
          />
          <span>Remember this key on this device</span>
        </label>

        <div className="provider-detail__field">
          <label htmlFor="custom-provider-headers">Non-secret headers (optional)</label>
          <textarea
            id="custom-provider-headers"
            rows={3}
            value={headersText}
            onChange={(event) => setHeadersText(event.target.value)}
            placeholder={'X-Organization: team\nX-Project: chat'}
          />
          <p className="provider-detail__muted">
            One header per line as Name: value. Authentication headers are rejected.
          </p>
        </div>

        <div className="provider-detail__actions">
          <Button type="submit" disabled={busy}>
            {busy ? 'Adding…' : 'Add custom provider'}
          </Button>
        </div>

        {notice && <p role="status">{notice}</p>}
        {error && (
          <p className="provider-detail__error" role="alert">
            {error}
          </p>
        )}
      </form>
    </section>
  )
}
