import type { ReactNode } from 'react'

interface AppProvidersProps {
  children: ReactNode
}

/**
 * Application-wide provider slot. Runtime config is supplied by bootstrap;
 * the theme provider is added by M0-03.2 and the toast provider by M0-03.3.
 */
export function AppProviders({ children }: AppProvidersProps) {
  return <>{children}</>
}
