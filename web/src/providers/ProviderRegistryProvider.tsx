import { useMemo } from 'react'
import {
  getDefaultPreset,
  getEnabledPresets,
  getPreset,
  isPresetEnabledInM2,
  providerPresets,
} from './registry'
import {
  ProviderRegistryContext,
  type ProviderRegistryProviderProps,
  type ProviderRegistryValue,
} from './provider-registry-context'

/**
 * Supplies validated provider presets to feature code. The default source is
 * the checked-in contracts document; tests can inject a fixture list.
 */
export function ProviderRegistryProvider({
  children,
  presets = providerPresets,
}: ProviderRegistryProviderProps) {
  const value = useMemo<ProviderRegistryValue>(
    () => ({
      presets,
      enabledPresets: getEnabledPresets(presets),
      getPreset: (id) => getPreset(id, presets),
      getDefaultPreset: () => getDefaultPreset(presets),
      isEnabled: isPresetEnabledInM2,
    }),
    [presets],
  )

  return (
    <ProviderRegistryContext.Provider value={value}>{children}</ProviderRegistryContext.Provider>
  )
}
