import type { AppConfig } from '../../app/config'
import { openRouterAdapter } from '../../providers/openrouter'
import { getDefaultCatalogCache } from '../../providers/openrouterCatalog'
import { ConversationRepository } from '../../storage/ConversationRepository'
import { ModelRepository } from '../../storage/ModelRepository'
import { ProviderRepository } from '../../storage/ProviderRepository'
import { SecretStore } from '../../storage/SecretStore'
import { SettingsRepository } from '../../storage/SettingsRepository'
import { openDatabase } from '../../storage/db'
import { SettingsService } from '../settings'
import { ProviderManagementService } from './managementService'
import { ModelService } from './modelService'
import { ProviderService } from './providerService'

function attributionFromConfig(config: AppConfig) {
  return {
    origin: config.openRouter.attributionOrigin,
    title: config.openRouter.appTitle,
  }
}

/**
 * Build the production provider-management service from IndexedDB and the
 * OpenRouter adapter. The catalog cache is shared with onboarding so opening
 * settings does not force an unnecessary `/models` request.
 */
export async function createProviderManagementService(
  config: AppConfig,
): Promise<ProviderManagementService> {
  const db = await openDatabase()
  const attribution = attributionFromConfig(config)
  const providers = new ProviderRepository(db)
  const models = new ModelRepository(db)
  const secrets = new SecretStore({ db })
  const settings = new SettingsService(new SettingsRepository(db))
  const modelService = new ModelService({
    providers,
    models,
    adapter: openRouterAdapter,
    fetch: globalThis.fetch,
    attribution,
    catalogCache: getDefaultCatalogCache(),
  })
  const providerService = new ProviderService({
    providers,
    secrets,
    adapter: openRouterAdapter,
    fetch: globalThis.fetch,
    attribution,
  })

  return new ProviderManagementService({
    providers,
    models,
    providerService,
    modelService,
    settings,
    conversations: new ConversationRepository(db),
  })
}
