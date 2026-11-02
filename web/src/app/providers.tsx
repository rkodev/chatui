import type { ReactNode } from 'react'

interface AppProvidersProps {
  children: ReactNode
}

/**
 * Placeholder for application-wide providers. Router, storage, and provider
 * adapters are added by later M0 tasks.
 */
export function AppProviders({ children }: AppProvidersProps) {
  return <>{children}</>
}
