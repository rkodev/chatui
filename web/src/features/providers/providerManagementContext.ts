import { createContext, useContext } from 'react'
import type { ProviderManagementServiceLike } from './managementTypes'

export const ProviderManagementContext = createContext<ProviderManagementServiceLike | null>(null)

export function useProviderManagement(): ProviderManagementServiceLike {
  const service = useContext(ProviderManagementContext)

  if (service === null) {
    throw new Error('useProviderManagement must be used inside ProviderManagementProvider')
  }

  return service
}
