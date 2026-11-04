import { Link } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import type { Provider } from '../../domain'
import { getPresetAvailability } from '../../providers/registry'
import { formatCors } from './format'
import { useProviderManagement } from './providerManagementContext'
import './providers.css'

/**
 * `/settings/providers` list.
 *
 * Only OpenRouter is usable in M1. Other contract presets are shown as coming
 * later, and there is no custom-provider creation entry point.
 */
export function ProviderListScreen() {
  const service = useProviderManagement()
  const [providers, setProviders] = useState<Provider[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    service
      .listProviders()
      .then((value) => {
        if (!cancelled) {
          setProviders(value)
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : String(cause))
        }
      })

    return () => {
      cancelled = true
    }
  }, [service])

  const presets = getPresetAvailability()

  return (
    <section className="providers-screen" aria-labelledby="providers-title">
      <header className="providers-screen__header">
        <h2 id="providers-title">Providers</h2>
        <Link to="/settings/providers/new" className="providers-screen__link">
          Add provider
        </Link>
      </header>

      {providers === null && !error && <p role="status">Loading providers…</p>}
      {error && <p role="alert">{error}</p>}

      {providers !== null && providers.length === 0 && (
        <p>
          No providers yet. Add OpenRouter to start chatting. Your key stays on this
          device.
        </p>
      )}

      {providers !== null && providers.length > 0 && (
        <ul className="providers-list">
          {providers.map((provider) => (
            <li key={provider.id} className="providers-list__item">
              <Link
                to="/settings/providers/$providerId"
                params={{ providerId: provider.id }}
                className="providers-list__link"
              >
                <span className="providers-list__name">{provider.label}</span>
                <span className="providers-list__meta">
                  {provider.enabled ? 'Enabled' : 'Disabled'} · CORS{' '}
                  {formatCors(provider.browserCors)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}

      <section className="providers-screen__other" aria-labelledby="other-providers-title">
        <h3 id="other-providers-title">Other providers</h3>
        <p className="providers-screen__muted">
          Only OpenRouter is available in this preview. Custom providers are not
          available.
        </p>
        <ul>
          {presets
            .filter(({ enabled }) => !enabled)
            .map(({ preset, status }) => (
              <li key={preset.id}>
                <span>{preset.label}</span>
                <span>{status === 'coming-later' ? 'Coming later' : 'Unavailable'}</span>
              </li>
            ))}
        </ul>
      </section>
    </section>
  )
}
