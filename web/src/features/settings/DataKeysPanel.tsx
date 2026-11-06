import { Link } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import type { DataControlsServiceLike, ProviderKeySummary } from './dataControlsService'
import './settings.css'

export interface DataKeysPanelProps {
  service: Pick<DataControlsServiceLike, 'listProviderKeys' | 'removeProviderKey'>
  /** Bump to reload the key list after a clear. */
  refreshKey?: number
  /** Called after a key is removed so usage can reload. */
  onChanged?: () => void
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * Per-key deletion for `/settings/data`.
 *
 * Deletion goes through provider management, so the stored secret and the
 * cached key metadata are removed together. The provider record and its models
 * stay in place; sending is disabled until a new key is added.
 */
export function DataKeysPanel({ service, refreshKey = 0, onChanged }: DataKeysPanelProps) {
  const [keys, setKeys] = useState<ProviderKeySummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [confirmId, setConfirmId] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    service.listProviderKeys().then(
      (value) => {
        if (!cancelled) {
          setKeys(value)
        }
      },
      (cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
        }
      },
    )

    return () => {
      cancelled = true
    }
  }, [service, refreshKey])

  async function handleRemove(providerId: string) {
    if (busyId !== null) {
      return
    }

    if (confirmId !== providerId) {
      setConfirmId(providerId)
      return
    }

    setBusyId(providerId)
    setError(null)
    setStatus(null)

    try {
      await service.removeProviderKey(providerId)
      setKeys((current) =>
        current === null
          ? current
          : current.map((key) =>
              key.id === providerId ? { ...key, hasKey: false, keyLabel: null } : key,
            ),
      )
      setConfirmId(null)
      setStatus('Key removed. Add a new key before sending with this provider.')
      onChanged?.()
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusyId(null)
    }
  }

  const storedKeys = keys?.filter((key) => key.hasKey) ?? []

  return (
    <div className="settings-screen__panel" aria-labelledby="data-keys-title">
      <h3 id="data-keys-title">Stored API keys</h3>
      <p className="settings-screen__muted">
        Keys are stored separately from conversations and are never included in exports. Removing
        a key clears its cached metadata and disables sending until a new key is added.
      </p>

      {keys === null ? (
        <p role="status">Loading stored keys…</p>
      ) : storedKeys.length === 0 ? (
        <p className="settings-screen__muted">No API keys are stored on this device.</p>
      ) : (
        <ul className="settings-screen__key-list">
          {storedKeys.map((key) => (
            <li key={key.id} className="settings-screen__key-item">
              <div>
                <strong>{key.label}</strong>
                <p className="settings-screen__muted">
                  {key.presetId}
                  {key.keyLabel ? ` · ${key.keyLabel}` : ''}
                </p>
              </div>
              <div className="settings-screen__actions">
                <button
                  type="button"
                  className="ui-button ui-button--danger ui-button--md"
                  disabled={busyId !== null}
                  onClick={() => void handleRemove(key.id)}
                >
                  {busyId === key.id
                    ? 'Removing…'
                    : confirmId === key.id
                      ? 'Confirm removal'
                      : 'Remove key'}
                </button>
                {confirmId === key.id && busyId === null && (
                  <button
                    type="button"
                    className="ui-button ui-button--ghost ui-button--md"
                    onClick={() => setConfirmId(null)}
                  >
                    Cancel
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {error && (
        <p className="settings-screen__error" role="alert">
          {error}
        </p>
      )}

      {status && (
        <p className="settings-screen__muted" role="status">
          {status}
        </p>
      )}

      <p>
        <Link to="/settings/providers">Manage providers and keys</Link>
      </p>
    </div>
  )
}
