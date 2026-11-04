import { createContext, useContext } from 'react'
import type { Settings } from '../../domain'
import type { SettingsClient } from './settingsService'

export interface SettingsContextValue {
  settings: Settings | null
  error: string | null
  loading: boolean
  service: SettingsClient | null
  update: (patch: Partial<Settings>) => Promise<Settings>
  reload: () => Promise<void>
}

export const SettingsContext = createContext<SettingsContextValue | null>(null)

/** Read the nearest settings provider. Throws when used outside the app shell. */
export function useSettings(): SettingsContextValue {
  const value = useContext(SettingsContext)

  if (value === null) {
    throw new Error('useSettings must be used inside SettingsProvider')
  }

  return value
}
