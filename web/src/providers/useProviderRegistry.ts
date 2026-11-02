import { useContext } from 'react'
import {
  ProviderRegistryContext,
  type ProviderRegistryValue,
} from './provider-registry-context'

/**
 * Read the validated provider registry. Throws outside a provider so feature
 * code never silently falls back to a hard-coded preset.
 */
export function useProviderRegistry(): ProviderRegistryValue {
  const value = useContext(ProviderRegistryContext)

  if (value === null) {
    throw new Error('useProviderRegistry must be used inside a ProviderRegistryProvider')
  }

  return value
}
