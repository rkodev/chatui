import { useContext, useEffect, useState, type ReactNode } from 'react'
import { useConfig } from '../../app/config'
import { ProviderManagementContext } from './providerManagementContext'
import { createProviderManagementService } from './services'
import type { ProviderManagementServiceLike } from './managementTypes'

export interface ProviderManagementProviderProps {
  children: ReactNode
  /** Test or story override. When omitted, the provider builds the real service. */
  service?: ProviderManagementServiceLike
}

/**
 * Supplies provider, model, defaults, and routing services to settings routes.
 *
 * The real service opens IndexedDB lazily. Tests can pass a fake service and
 * avoid storage and network entirely.
 */
export function ProviderManagementProvider({
  children,
  service,
}: ProviderManagementProviderProps) {
  const config = useConfig()
  const existing = useContext(ProviderManagementContext)
  const [created, setCreated] = useState<ProviderManagementServiceLike | null>(null)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    if (service || existing) {
      return
    }

    let cancelled = false

    createProviderManagementService(config)
      .then((value) => {
        if (!cancelled) {
          setCreated(value)
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause : new Error(String(cause)))
        }
      })

    return () => {
      cancelled = true
    }
  }, [config, existing, service])

  const value = service ?? existing ?? created

  if (value === null) {
    if (error) {
      return (
        <p role="alert">ChatUI could not open provider settings: {error.message}</p>
      )
    }

    return <p role="status">Preparing provider settings…</p>
  }

  return (
    <ProviderManagementContext.Provider value={value}>
      {children}
    </ProviderManagementContext.Provider>
  )
}
