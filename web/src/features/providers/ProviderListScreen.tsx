import { Link } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { useConfig } from '../../app/config'
import type { Provider } from '../../domain'
import { getPresetAvailability, providerPresets } from '../../providers/registry'
import { CustomProviderForm } from './CustomProviderForm'
import { formatCors } from './format'
import { useProviderManagement } from './providerManagementContext'
import './providers.css'

function availabilityLabel(status: 'available' | 'disabled' | 'coming-later'): string {
  switch (status) {
    case 'available':
      return 'Available'
    case 'disabled':
      return 'Disabled in this deployment'
    case 'coming-later':
      return 'Coming later'
  }
}

/**
 * `/settings/providers` list.
 *
 * Configured providers are listed first. The remaining contract presets show
 * whether they are available, disabled by runtime config, or coming later.
 * Custom provider creation is offered only when the deployment allows it.
 */
export function ProviderListScreen() {
  const service = useProviderManagement()
  const config = useConfig()
  const [providers, setProviders] = useState<Provider[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

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
  }, [service, reloadToken])

  const presets = getPresetAvailability(providerPresets, config.enabledPresets)
  const configuredPresetIds = new Set((providers ?? []).map((provider) => provider.presetId))
  const otherPresets = presets.filter(({ preset }) => !configuredPresetIds.has(preset.id))

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
          No providers yet. Add OpenRouter
          {config.allowCustomProviders ? ' or a custom provider' : ''} to start chatting.
          Your key stays on this device.
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
          These presets come from the shared contracts. Only enabled presets can be
          added.
        </p>
        <ul>
          {otherPresets.map(({ preset, status }) => (
            <li key={preset.id}>
              <span>{preset.label}</span>
              <span>{availabilityLabel(status)}</span>
            </li>
          ))}
        </ul>
      </section>

      {config.allowCustomProviders && (
        <CustomProviderForm onCreated={() => setReloadToken((value) => value + 1)} />
      )}
    </section>
  )
}
