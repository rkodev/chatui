import type { ReactNode } from 'react'
import { ProviderRegistryProvider } from '../providers'
import { ToastProvider } from '../ui/primitives'
import { ThemeProvider } from '../ui/theme'
import { useConfig } from './config'

interface AppProvidersProps {
  children: ReactNode
}

/**
 * Application-wide provider slot. Runtime config is supplied by bootstrap;
 * the theme provider applies the configured default appearance, and the toast
 * provider renders the shell toast queue.
 */
export function AppProviders({ children }: AppProvidersProps) {
  const config = useConfig()

  return (
    <ProviderRegistryProvider>
      <ThemeProvider initialTheme={config.defaultTheme}>
        <ToastProvider>{children}</ToastProvider>
      </ThemeProvider>
    </ProviderRegistryProvider>
  )
}
