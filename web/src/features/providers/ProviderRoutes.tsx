import { ProviderDetailScreen } from './ProviderDetailScreen'
import { ProviderListScreen } from './ProviderListScreen'
import { ProviderManagementProvider } from './ProviderManagementProvider'

/** `/settings/providers` route component. */
export function ProvidersSettingsRoute() {
  return (
    <ProviderManagementProvider>
      <ProviderListScreen />
    </ProviderManagementProvider>
  )
}

/** `/settings/providers/$providerId` route component. */
export function ProviderDetailRoute() {
  return (
    <ProviderManagementProvider>
      <ProviderDetailScreen />
    </ProviderManagementProvider>
  )
}
