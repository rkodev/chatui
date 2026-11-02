import { useContext, useEffect, useState, type ReactNode } from 'react'
import { useConfig } from '../../app/config'
import { OnboardingServiceContext } from './onboardingServiceContext'
import { createOnboardingService } from './services'
import type { OnboardingServiceLike } from './types'

export interface OnboardingServiceProviderProps {
  children: ReactNode
  /** Test or story override. When omitted, the provider builds the real service. */
  service?: OnboardingServiceLike
}

/**
 * Supplies the onboarding service to route-level components.
 *
 * The real service opens IndexedDB lazily. Tests can pass a fake service and
 * avoid storage and network entirely.
 */
export function OnboardingServiceProvider({
  children,
  service,
}: OnboardingServiceProviderProps) {
  const config = useConfig()
  const existing = useContext(OnboardingServiceContext)
  const [created, setCreated] = useState<OnboardingServiceLike | null>(null)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    if (service || existing) {
      return
    }

    let cancelled = false

    createOnboardingService(config)
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
        <p role="alert">
          ChatUI could not open local storage: {error.message}
        </p>
      )
    }

    return <p role="status">Preparing onboarding…</p>
  }

  return (
    <OnboardingServiceContext.Provider value={value}>
      {children}
    </OnboardingServiceContext.Provider>
  )
}
