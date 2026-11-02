import type { ReactNode } from 'react'
import { ThemeProvider } from '../ui/theme'
import { useConfig } from './config'

interface AppProvidersProps {
  children: ReactNode
}

/**
 * Application-wide provider slot. Runtime config is supplied by bootstrap;
 * the theme provider applies the configured default appearance, and the toast
 * provider is added by M0-03.3.
 */
export function AppProviders({ children }: AppProvidersProps) {
  const config = useConfig()

  return <ThemeProvider initialTheme={config.defaultTheme}>{children}</ThemeProvider>
}
