import type { AppConfig } from '../../app/config'
import { openRouterAdapter, type ModelInfo } from '../../providers/openrouter'
import { getDefaultCatalogCache } from '../../providers/openrouterCatalog'
import { getPreset } from '../../providers/registry'
import { ConversationRepository } from '../../storage/ConversationRepository'
import { MessageRepository } from '../../storage/MessageRepository'
import { ModelRepository } from '../../storage/ModelRepository'
import { ProviderRepository } from '../../storage/ProviderRepository'
import { SecretStore } from '../../storage/SecretStore'
import { SettingsRepository } from '../../storage/SettingsRepository'
import { openDatabase } from '../../storage/db'
import { ConversationService } from '../conversations'
import { ModelService, ProviderService } from '../providers'
import { SettingsService } from '../settings'
import { OnboardingService } from './onboardingService'

function attributionFromConfig(config: AppConfig) {
  return {
    origin: config.openRouter.attributionOrigin,
    title: config.openRouter.appTitle,
  }
}

/**
 * Build the production onboarding service from IndexedDB and the OpenRouter
 * adapter. The catalog loader uses the public `/models` endpoint with no key.
 */
export async function createOnboardingService(config: AppConfig): Promise<OnboardingService> {
  const preset = getPreset('openrouter')

  if (!preset) {
    throw new Error('OpenRouter preset is missing from contracts/providers.json')
  }

  const db = await openDatabase()
  const attribution = attributionFromConfig(config)
  const secrets = new SecretStore({ db })
  const providers = new ProviderRepository(db)
  const models = new ModelRepository(db)
  const settings = new SettingsService(new SettingsRepository(db))
  const modelService = new ModelService({
    providers,
    models,
    adapter: openRouterAdapter,
    fetch: globalThis.fetch,
    attribution,
    catalogCache: getDefaultCatalogCache(),
  })
  const conversations = new ConversationService({
    conversations: new ConversationRepository(db),
    messages: new MessageRepository(db),
    providers,
    models: modelService,
    settings,
  })
  const providerService = new ProviderService({
    providers,
    secrets,
    adapter: openRouterAdapter,
    fetch: globalThis.fetch,
    attribution,
  })

  const loadCatalog = (signal: AbortSignal): Promise<ModelInfo[]> =>
    openRouterAdapter.listModels(
      {
        provider: { id: preset.id, baseUrl: preset.baseUrl },
        apiKey: null,
        fetch: globalThis.fetch,
        attribution,
      },
      signal,
      { forceRefresh: true },
    )

  return new OnboardingService({
    providers: providerService,
    models: modelService,
    settings,
    conversations,
    loadCatalog,
  })
}
