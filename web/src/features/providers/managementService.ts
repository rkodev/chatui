import type { ChatParams, Conversation, Model, Provider, Settings } from '../../domain'
import type { SettingsDefaults } from '../settings/settingsService'
import {
  ProviderManagementError,
  type ProviderManagementServiceLike,
  type ProviderManagementServiceOptions,
  type ResolvedConversationModel,
} from './managementTypes'
import {
  DEFAULT_ROUTING_PREFERENCES,
  readRoutingPreferences,
  withRoutingPreferences,
  type RoutingPreferences,
} from './routing'
import { pruneExtrasForPreset } from './parameterSupport'
import type { ManualModelInput, CreateCustomProviderInput, RefreshModelsResult } from './types'

/**
 * Provider, model, defaults, and routing facade used by the settings UI.
 *
 * The service keeps model switching transactional from the UI's perspective:
 * the conversation record is updated immediately, and past messages are never
 * rewritten. Assistant-message snapshots are still owned by
 * `ConversationService.appendMessage`.
 */
export class ProviderManagementService implements ProviderManagementServiceLike {
  private readonly providers: ProviderManagementServiceOptions['providers']
  private readonly models: ProviderManagementServiceOptions['models']
  private readonly providerService: ProviderManagementServiceOptions['providerService']
  private readonly modelService: ProviderManagementServiceOptions['modelService']
  private readonly settings: ProviderManagementServiceOptions['settings']
  private readonly conversations: ProviderManagementServiceOptions['conversations']

  constructor(options: ProviderManagementServiceOptions) {
    this.providers = options.providers
    this.models = options.models
    this.providerService = options.providerService
    this.modelService = options.modelService
    this.settings = options.settings
    this.conversations = options.conversations
  }

  async listProviders(): Promise<Provider[]> {
    return this.providerService.listProviders()
  }

  async getProvider(providerId: string): Promise<Provider | null> {
    return this.providerService.getProvider(providerId)
  }

  async createCustomProvider(input: CreateCustomProviderInput): Promise<Provider> {
    return this.providerService.createCustomProvider(input)
  }

  async validateProviderKey(providerId: string): Promise<Provider> {
    return this.providerService.validateProviderKey(providerId)
  }

  async rotateKey(
    providerId: string,
    apiKey: string,
    rememberKeys = true,
  ): Promise<Provider> {
    return this.providerService.rotateKey(providerId, apiKey, rememberKeys)
  }

  async removeKey(providerId: string): Promise<Provider> {
    return this.providerService.removeKey(providerId)
  }

  async setProviderEnabled(providerId: string, enabled: boolean): Promise<Provider> {
    return this.providerService.setProviderEnabled(providerId, enabled)
  }

  async deleteProvider(providerId: string): Promise<void> {
    return this.providerService.deleteProvider(providerId)
  }

  async refreshModels(providerId: string): Promise<RefreshModelsResult> {
    return this.modelService.refreshModels(providerId)
  }

  async listModels(providerId: string): Promise<Model[]> {
    return this.modelService.listModels(providerId)
  }

  async setModelPinned(modelId: string, pinned: boolean): Promise<Model> {
    return this.modelService.setModelPinned(modelId, pinned)
  }

  async setModelHidden(modelId: string, hidden: boolean): Promise<Model> {
    return this.modelService.setModelHidden(modelId, hidden)
  }

  async addManualModel(providerId: string, input: ManualModelInput): Promise<Model> {
    return this.modelService.addManualModel(providerId, input)
  }

  async getDefaults(): Promise<SettingsDefaults> {
    return this.settings.getDefaults()
  }

  /**
   * Persist global defaults.
   *
   * A model must exist and belong to the selected provider. Changing the
   * provider without naming a model clears a model that no longer belongs to
   * it instead of silently keeping a mismatched pair.
   */
  async setDefaults(patch: Partial<SettingsDefaults>): Promise<Settings> {
    const current = await this.settings.getDefaults()
    let providerId = patch.providerId !== undefined ? patch.providerId : current.providerId
    let modelId = patch.modelId !== undefined ? patch.modelId : current.modelId

    if (patch.modelId !== undefined && patch.modelId !== null) {
      const model = await this.models.get(patch.modelId)

      if (!model) {
        throw new ProviderManagementError(
          'not_found',
          `Model ${patch.modelId} was not found.`,
        )
      }

      if (patch.providerId === null) {
        throw new ProviderManagementError(
          'invalid_input',
          'A model cannot be selected without a provider.',
        )
      }

      if (
        patch.providerId !== undefined &&
        patch.providerId !== null &&
        model.providerId !== patch.providerId
      ) {
        throw new ProviderManagementError(
          'invalid_input',
          `Model ${model.remoteId} does not belong to provider ${patch.providerId}.`,
        )
      }

      providerId = model.providerId
    } else if (patch.providerId === null && patch.modelId === undefined) {
      modelId = null
    } else if (patch.providerId !== undefined && patch.modelId === undefined && modelId !== null) {
      const model = await this.models.get(modelId)

      if (!model || model.providerId !== providerId) {
        modelId = null
      }
    }

    if (providerId !== null) {
      const provider = await this.providers.get(providerId)

      if (!provider) {
        throw new ProviderManagementError(
          'not_found',
          `Provider ${providerId} was not found.`,
        )
      }
    }

    return this.settings.setDefaults({ ...patch, providerId, modelId })
  }

  async getConversation(conversationId: string): Promise<Conversation | null> {
    return this.conversations.get(conversationId)
  }

  /**
   * Switch the model for one conversation and persist it immediately.
   *
   * The conversation record is the source of truth for the next send. Past
   * messages keep their own provider/model snapshots.
   */
  async setConversationModel(conversationId: string, modelId: string): Promise<Conversation> {
    const conversation = await this.requireConversation(conversationId)
    const model = await this.models.get(modelId)

    if (!model) {
      throw new ProviderManagementError('not_found', `Model ${modelId} was not found.`)
    }

    const provider = await this.providers.get(model.providerId)

    if (!provider) {
      throw new ProviderManagementError(
        'not_found',
        `Provider ${model.providerId} was not found.`,
      )
    }

    if (!provider.enabled) {
      throw new ProviderManagementError(
        'unavailable',
        `Provider ${provider.label} is disabled. Enable it before selecting its models.`,
      )
    }

    const patch: Partial<Omit<Conversation, 'id' | 'createdAt'>> = {
      providerId: provider.id,
      modelId: model.id,
      modelLabel: model.label,
    }

    if (provider.id !== conversation.providerId) {
      patch.params = {
        ...conversation.params,
        stop: [...conversation.params.stop],
        extra: pruneExtrasForPreset(conversation.params.extra, provider.presetId),
      }
    }

    return this.conversations.update(conversation.id, patch)
  }

  /** Persist the system prompt and generation parameters immediately. */
  async setConversationSettings(
    conversationId: string,
    patch: { systemPrompt?: string; params?: ChatParams },
  ): Promise<Conversation> {
    await this.requireConversation(conversationId)
    return this.conversations.update(conversationId, patch)
  }

  /**
   * Delete one conversation and clear the last-active pointer when it matches.
   *
   * Message deletion is handled by the repository transaction. Attachments are
   * left for the orphan-cleanup path, matching `ConversationService`.
   */
  async deleteConversation(conversationId: string): Promise<void> {
    await this.requireConversation(conversationId)
    await this.conversations.delete(conversationId)

    if ((await this.settings.getLastActiveConversationId()) === conversationId) {
      await this.settings.setLastActiveConversationId(null)
    }
  }

  async setConversationRouting(
    conversationId: string,
    preferences: RoutingPreferences,
  ): Promise<Conversation> {
    const conversation = await this.requireConversation(conversationId)
    const params = withRoutingPreferences(conversation.params, preferences)
    return this.conversations.update(conversation.id, { params })
  }

  async getConversationRouting(conversationId: string): Promise<RoutingPreferences> {
    const conversation = await this.requireConversation(conversationId)
    return readRoutingPreferences(conversation.params)
  }

  /**
   * Warn when the global default model cannot be used.
   *
   * The warning is non-blocking and never substitutes another model.
   */
  async getDefaultModelWarning(): Promise<string | null> {
    const defaults = await this.settings.getDefaults()

    if (defaults.providerId === null) {
      return 'No default provider is configured.'
    }

    const provider = await this.providers.get(defaults.providerId)

    if (!provider) {
      return 'The default provider is unavailable.'
    }

    if (!provider.enabled) {
      return `The default provider ${provider.label} is disabled.`
    }

    if (defaults.modelId === null) {
      return 'No default model is selected.'
    }

    const model = await this.models.get(defaults.modelId)

    if (!model) {
      return `Default model ${defaults.modelId} is unavailable. Choose another model before sending.`
    }

    if (model.providerId !== provider.id) {
      return 'The default model does not belong to the default provider.'
    }

    return null
  }

  /**
   * Resolve the model a conversation would use for the next send.
   *
   * Missing or disabled models return an unavailable result. The caller must
   * show the warning and must not silently substitute another model.
   */
  async resolveConversationModel(
    conversation: Conversation,
  ): Promise<ResolvedConversationModel> {
    if (conversation.modelId === null) {
      return {
        ok: false,
        reason: 'unavailable',
        message: 'No model is selected for this conversation. Choose a model before sending.',
      }
    }

    const model = await this.models.get(conversation.modelId)

    if (!model) {
      return {
        ok: false,
        reason: 'unavailable',
        message: `Model ${conversation.modelId} is unavailable. Choose another model before sending.`,
      }
    }

    if (!model.outputModalities.includes('text')) {
      return {
        ok: false,
        reason: 'unavailable',
        message: `Model ${model.remoteId} does not produce text output. Choose another model before sending.`,
      }
    }

    const provider = await this.providers.get(model.providerId)

    if (!provider) {
      return {
        ok: false,
        reason: 'unavailable',
        message: `Provider ${model.providerId} is unavailable. Choose another model before sending.`,
      }
    }

    if (!provider.enabled) {
      return {
        ok: false,
        reason: 'unavailable',
        message: `Provider ${provider.label} is disabled. Enable it or choose another model before sending.`,
      }
    }

    return { ok: true, model, provider }
  }

  private async requireConversation(conversationId: string): Promise<Conversation> {
    const conversation = await this.conversations.get(conversationId)

    if (!conversation) {
      throw new ProviderManagementError(
        'not_found',
        `Conversation ${conversationId} was not found.`,
      )
    }

    return conversation
  }
}

export { DEFAULT_ROUTING_PREFERENCES }
