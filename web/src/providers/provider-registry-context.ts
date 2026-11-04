import { createContext, type ReactNode } from 'react'
import type { ProviderPreset } from './registry'

export interface ProviderRegistryValue {
  presets: readonly ProviderPreset[]
  enabledPresets: readonly ProviderPreset[]
  getPreset: (id: string) => ProviderPreset | undefined
  getDefaultPreset: () => ProviderPreset
  isEnabled: (id: string) => boolean
}

export interface ProviderRegistryProviderProps {
  children: ReactNode
  presets?: readonly ProviderPreset[]
}

export const ProviderRegistryContext = createContext<ProviderRegistryValue | null>(null)
