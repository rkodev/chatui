import { Link, useParams } from '@tanstack/react-router'
import { useEffect, useState, type FormEvent } from 'react'
import type { Model, Provider } from '../../domain'
import { getPreset } from '../../providers/registry'
import { Button } from '../../ui/primitives'
import { formatCors, formatFreeTier, formatRateLimit, formatUsd } from './format'
import { getProviderKeyMetadata } from './providerService'
import { useProviderManagement } from './providerManagementContext'
import './providers.css'

type BusyAction = 'rotate' | 'remove' | 'toggle' | 'refresh' | 'validate' | null

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * `/settings/providers/$providerId` details.
 *
 * The key itself is never rendered. Only the non-secret `/key` metadata cached
 * on the provider record is shown.
 */
export function ProviderDetailScreen() {
  const service = useProviderManagement()
  const params = useParams({ strict: false }) as { providerId?: string }
  const providerId = params.providerId ?? ''
  const [provider, setProvider] = useState<Provider | null | undefined>(undefined)
  const [models, setModels] = useState<Model[]>([])
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [newKey, setNewKey] = useState('')
  const [rememberKeys, setRememberKeys] = useState(true)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [busy, setBusy] = useState<BusyAction>(null)

  useEffect(() => {
    if (providerId === '') {
      return
    }

    let cancelled = false

    Promise.all([service.getProvider(providerId), service.listModels(providerId)])
      .then(([value, modelList]) => {
        if (!cancelled) {
          setProvider(value)
          setModels(modelList)
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
  }, [providerId, service])

  async function handleRotate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (provider === null || provider === undefined || busy !== null) {
      return
    }

    const apiKey = newKey.trim()

    if (apiKey === '') {
      setError('Enter a new API key before rotating.')
      return
    }

    setBusy('rotate')
    setError(null)
    setNotice(null)

    try {
      const rotated = await service.rotateKey(provider.id, apiKey, rememberKeys)
      setProvider(rotated)
      const validated = await service.validateProviderKey(provider.id)
      setProvider(validated)
      setNewKey('')
      setNotice('Key rotated and validated.')
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(null)
    }
  }

  async function handleRemove() {
    if (provider === null || provider === undefined || busy !== null) {
      return
    }

    if (!confirmRemove) {
      setConfirmRemove(true)
      return
    }

    setBusy('remove')
    setError(null)
    setNotice(null)

    try {
      const updated = await service.removeKey(provider.id)
      setProvider(updated)
      setConfirmRemove(false)
      setNotice('Key removed. Add a new key before sending.')
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(null)
    }
  }

  async function handleToggle() {
    if (provider === null || provider === undefined || busy !== null) {
      return
    }

    setBusy('toggle')
    setError(null)

    try {
      const updated = await service.setProviderEnabled(provider.id, !provider.enabled)
      setProvider(updated)
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(null)
    }
  }

  async function handleValidate() {
    if (provider === null || provider === undefined || busy !== null) {
      return
    }

    setBusy('validate')
    setError(null)
    setNotice(null)

    try {
      const updated = await service.validateProviderKey(provider.id)
      setProvider(updated)
      setNotice('Key details refreshed.')
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(null)
    }
  }

  async function handleRefreshModels() {
    if (provider === null || provider === undefined || busy !== null) {
      return
    }

    setBusy('refresh')
    setError(null)
    setNotice(null)

    try {
      const result = await service.refreshModels(provider.id)
      setModels(result.models)
      setNotice(`Refreshed ${result.models.length} models.`)
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(null)
    }
  }

  if (providerId === '') {
    return (
      <section className="provider-detail" aria-labelledby="provider-detail-title">
        <h2 id="provider-detail-title">Provider not found</h2>
        <p>
          This provider no longer exists. <Link to="/settings/providers">Back to providers</Link>
        </p>
      </section>
    )
  }

  if (provider === undefined) {
    if (error) {
      return (
        <p className="provider-detail__error" role="alert">
          {error}
        </p>
      )
    }

    return <p role="status">Loading provider…</p>
  }

  if (provider === null) {
    return (
      <section className="provider-detail" aria-labelledby="provider-detail-title">
        <h2 id="provider-detail-title">Provider not found</h2>
        <p>
          This provider no longer exists. <Link to="/settings/providers">Back to providers</Link>
        </p>
      </section>
    )
  }

  const metadata = getProviderKeyMetadata(provider)
  const preset = getPreset(provider.presetId)
  const docsUrl = preset?.docsUrl ?? null
  const rateLimit = formatRateLimit(metadata?.rateLimit)

  return (
    <section className="provider-detail" aria-labelledby="provider-detail-title">
      <header className="provider-detail__header">
        <div>
          <h2 id="provider-detail-title">{provider.label}</h2>
          <p className="provider-detail__muted">
            {provider.enabled ? 'Enabled' : 'Disabled'} · {provider.baseUrl}
          </p>
        </div>
        <Link to="/settings/providers" className="provider-detail__link">
          Back to providers
        </Link>
      </header>

      {error && (
        <p className="provider-detail__error" role="alert">
          {error}
        </p>
      )}
      {notice && <p role="status">{notice}</p>}

      <section className="provider-detail__panel" aria-labelledby="provider-key-title">
        <h3 id="provider-key-title">Key and usage</h3>
        <dl className="provider-detail__details">
          <dt>Key label</dt>
          <dd>{metadata?.label ?? provider.label}</dd>
          <dt>Usage</dt>
          <dd>{metadata ? formatUsd(metadata.usageUsd) : 'Not available'}</dd>
          <dt>Limit</dt>
          <dd>
            {metadata
              ? metadata.limitUsd === null
                ? 'No limit'
                : formatUsd(metadata.limitUsd)
              : 'Not available'}
          </dd>
          <dt>Free tier</dt>
          <dd>
            <span
              className={
                metadata?.isFreeTier
                  ? 'provider-detail__badge provider-detail__badge--free'
                  : 'provider-detail__badge'
              }
            >
              {formatFreeTier(metadata?.isFreeTier)}
            </span>
          </dd>
          <dt>Rate limit</dt>
          <dd>{rateLimit ?? 'Not reported'}</dd>
          <dt>CORS</dt>
          <dd>{formatCors(provider.browserCors)}</dd>
        </dl>
        <p className="provider-detail__muted">
          OpenRouter may route the same model to different upstream providers with
          different data policies.
        </p>
        {docsUrl && (
          <a
            className="provider-detail__link"
            href={docsUrl}
            target="_blank"
            rel="noreferrer"
          >
            OpenRouter documentation
          </a>
        )}
      </section>

      <section className="provider-detail__panel" aria-labelledby="provider-controls-title">
        <h3 id="provider-controls-title">Provider controls</h3>
        <label className="provider-detail__checkbox">
          <input
            type="checkbox"
            checked={provider.enabled}
            disabled={busy !== null}
            onChange={handleToggle}
          />
          <span>Enable this provider</span>
        </label>

        <div className="provider-detail__actions">
          <Button
            type="button"
            variant="secondary"
            disabled={busy !== null || provider.secretId === null}
            onClick={handleValidate}
          >
            Refresh key details
          </Button>
          <Button
            type="button"
            variant="secondary"
            disabled={busy !== null}
            onClick={handleRefreshModels}
          >
            Refresh models
          </Button>
        </div>
      </section>

      <section className="provider-detail__panel" aria-labelledby="provider-rotate-title">
        <h3 id="provider-rotate-title">Rotate key</h3>
        <form className="provider-detail__form" onSubmit={handleRotate}>
          <div className="provider-detail__field">
            <label htmlFor="provider-new-key">New API key</label>
            <input
              id="provider-new-key"
              type="password"
              autoComplete="off"
              spellCheck={false}
              value={newKey}
              onChange={(event) => setNewKey(event.target.value)}
            />
          </div>
          <label className="provider-detail__checkbox">
            <input
              type="checkbox"
              checked={rememberKeys}
              onChange={(event) => setRememberKeys(event.target.checked)}
            />
            <span>Remember this key on this device</span>
          </label>
          <div className="provider-detail__actions">
            <Button type="submit" disabled={busy !== null || newKey.trim() === ''}>
              {busy === 'rotate' ? 'Rotating…' : 'Rotate key'}
            </Button>
          </div>
        </form>
      </section>

      <section className="provider-detail__panel" aria-labelledby="provider-remove-title">
        <h3 id="provider-remove-title">Remove key</h3>
        <p className="provider-detail__muted">
          Removing the key keeps the provider and its models but disables sending until
          a new key is added.
        </p>
        <div className="provider-detail__actions">
          <Button
            type="button"
            variant="danger"
            disabled={busy !== null || provider.secretId === null}
            onClick={handleRemove}
          >
            {busy === 'remove'
              ? 'Removing…'
              : confirmRemove
                ? 'Confirm removal'
                : 'Remove key'}
          </Button>
          {confirmRemove && (
            <Button
              type="button"
              variant="ghost"
              onClick={() => setConfirmRemove(false)}
            >
              Cancel
            </Button>
          )}
        </div>
      </section>

      <section className="provider-detail__panel" aria-labelledby="provider-models-title">
        <h3 id="provider-models-title">Models</h3>
        <p className="provider-detail__muted">
          {models.length} models are cached for this provider.
        </p>
        <Link to="/settings/models" className="provider-detail__link">
          Manage models and defaults
        </Link>
      </section>
    </section>
  )
}
