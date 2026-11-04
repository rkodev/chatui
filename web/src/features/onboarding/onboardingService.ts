import type { ModelInfo } from '../../providers/openrouter'
import type { ConversationService } from '../conversations'
import type { ModelService, ProviderService } from '../providers'
import type { SettingsService } from '../settings'
import {
  OnboardingError,
  type ConnectInput,
  type ConnectResult,
  type OnboardingServiceLike,
} from './types'

export interface OnboardingServiceOptions {
  providers: Pick<
    ProviderService,
    'createOpenRouterProvider' | 'validateProviderKey' | 'deleteProvider'
  >
  models: Pick<ModelService, 'refreshModels' | 'selectDefaultModel'>
  settings: Pick<
    SettingsService,
    'getDefaults' | 'setDefaults' | 'getOnboardingCompleted' | 'setOnboardingCompleted'
  >
  conversations: Pick<ConversationService, 'createConversation'>
  loadCatalog: (signal: AbortSignal) => Promise<ModelInfo[]>
}

/**
 * First-run orchestration.
 *
 * The service keeps the key out of the provider record, validates it with
 * `GET /key` before accepting it, refreshes the catalog, selects a default
 * model, persists typed defaults, marks onboarding complete, and creates the
 * first conversation. Any failure before completion rolls the provider and
 * defaults back so an invalid key stores nothing.
 */
export class OnboardingService implements OnboardingServiceLike {
  private readonly providers: OnboardingServiceOptions['providers']
  private readonly models: OnboardingServiceOptions['models']
  private readonly settings: OnboardingServiceOptions['settings']
  private readonly conversations: OnboardingServiceOptions['conversations']
  private readonly catalog: OnboardingServiceOptions['loadCatalog']

  constructor(options: OnboardingServiceOptions) {
    this.providers = options.providers
    this.models = options.models
    this.settings = options.settings
    this.conversations = options.conversations
    this.catalog = options.loadCatalog
  }

  /** Load the public OpenRouter catalog. No key is required or sent. */
  async loadCatalog(signal: AbortSignal): Promise<ModelInfo[]> {
    return this.catalog(signal)
  }

  async isOnboardingComplete(): Promise<boolean> {
    return this.settings.getOnboardingCompleted()
  }

  async connect(input: ConnectInput): Promise<ConnectResult> {
    const apiKey = input.apiKey.trim()

    if (apiKey === '') {
      throw new OnboardingError('invalid_input', 'Enter an OpenRouter API key to continue.')
    }

    const previousDefaults = await this.settings.getDefaults()
    let providerId: string | null = null

    try {
      const provider = await this.providers.createOpenRouterProvider({
        apiKey,
        label: input.label,
        rememberKeys: input.rememberKeys,
      })
      providerId = provider.id

      // `/key` is the only accepted proof that the key works. Never treat a
      // stored key or a successful catalog load as validation.
      await this.providers.validateProviderKey(provider.id)

      await this.models.refreshModels(provider.id, { forceRefresh: false })
      const selection = await this.models.selectDefaultModel(provider.id)

      if (selection.modelId === null) {
        throw new OnboardingError(
          'no_model',
          selection.notice ?? 'No text model is available for OpenRouter.',
        )
      }

      await this.settings.setDefaults({
        providerId: provider.id,
        modelId: selection.modelId,
      })

      const created = await this.conversations.createConversation()

      if (!created.ok) {
        throw new OnboardingError('not_configured', created.message)
      }

      await this.settings.setOnboardingCompleted(true)

      return {
        conversationId: created.conversation.id,
        notice: selection.notice,
      }
    } catch (error) {
      if (providerId !== null) {
        await this.providers.deleteProvider(providerId).catch(() => undefined)
      }

      await this.settings
        .setDefaults({
          providerId: previousDefaults.providerId,
          modelId: previousDefaults.modelId,
        })
        .catch(() => undefined)

      throw error
    }
  }
}
