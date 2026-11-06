import type { ChatParams, Conversation, Model, Provider, Settings } from '../../domain'
import type { ConversationStore } from '../conversations/types'
import type { SettingsDefaults } from '../settings/settingsService'
import type { CreateCustomProviderInput, ManualModelInput, RefreshModelsResult } from './types'
import type { RoutingPreferences } from './routing'

/**
 * UI-facing provider management surface.
 *
 * The concrete `ProviderManagementService` wraps the provider, model, settings,
 * and conversation services. Component tests inject a fake so screens can be
 * exercised without IndexedDB or network access.
 */
export interface ProviderManagementServiceLike {
  listProviders(): Promise<Provider[]>
  getProvider(providerId: string): Promise<Provider | null>
  createCustomProvider(input: CreateCustomProviderInput): Promise<Provider>
  validateProviderKey(providerId: string): Promise<Provider>
  rotateKey(providerId: string, apiKey: string, rememberKeys?: boolean): Promise<Provider>
  removeKey(providerId: string): Promise<Provider>
  setProviderEnabled(providerId: string, enabled: boolean): Promise<Provider>
  deleteProvider(providerId: string): Promise<void>
  refreshModels(providerId: string): Promise<RefreshModelsResult>
  listModels(providerId: string): Promise<Model[]>
  setModelPinned(modelId: string, pinned: boolean): Promise<Model>
  setModelHidden(modelId: string, hidden: boolean): Promise<Model>
  addManualModel(providerId: string, input: ManualModelInput): Promise<Model>
  getDefaults(): Promise<SettingsDefaults>
  setDefaults(patch: Partial<SettingsDefaults>): Promise<Settings>
  getConversation(conversationId: string): Promise<Conversation | null>
  setConversationModel(conversationId: string, modelId: string): Promise<Conversation>
  setConversationSettings(
    conversationId: string,
    patch: { systemPrompt?: string; params?: ChatParams },
  ): Promise<Conversation>
  deleteConversation(conversationId: string): Promise<void>
  setConversationRouting(
    conversationId: string,
    preferences: RoutingPreferences,
  ): Promise<Conversation>
  getConversationRouting(conversationId: string): Promise<RoutingPreferences>
  getDefaultModelWarning(): Promise<string | null>
  resolveConversationModel(conversation: Conversation): Promise<ResolvedConversationModel>
}

export type ResolvedConversationModel =
  | { ok: true; model: Model; provider: Provider }
  | { ok: false; reason: 'unavailable'; message: string }

export type ProviderManagementErrorCode = 'not_found' | 'invalid_input' | 'unavailable'

/** Service-level failure that is safe to show in the UI. */
export class ProviderManagementError extends Error {
  readonly code: ProviderManagementErrorCode

  constructor(
    code: ProviderManagementErrorCode,
    message: string,
    options: { cause?: unknown } = {},
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'ProviderManagementError'
    this.code = code
  }
}

export interface ProviderManagementServiceOptions {
  providers: {
    get(id: string): Promise<Provider | null>
    list(options?: { enabled?: boolean }): Promise<Provider[]>
  }
  models: {
    get(id: string): Promise<Model | null>
    list(options?: { providerId?: string }): Promise<Model[]>
  }
  providerService: {
    listProviders(options?: { enabled?: boolean }): Promise<Provider[]>
    getProvider(providerId: string): Promise<Provider | null>
    createCustomProvider(input: CreateCustomProviderInput): Promise<Provider>
    validateProviderKey(providerId: string): Promise<Provider>
    rotateKey(providerId: string, apiKey: string, rememberKeys?: boolean): Promise<Provider>
    removeKey(providerId: string): Promise<Provider>
    setProviderEnabled(providerId: string, enabled: boolean): Promise<Provider>
    deleteProvider(providerId: string): Promise<void>
  }
  modelService: {
    refreshModels(providerId: string): Promise<RefreshModelsResult>
    listModels(providerId: string): Promise<Model[]>
    setModelPinned(modelId: string, pinned: boolean): Promise<Model>
    setModelHidden(modelId: string, hidden: boolean): Promise<Model>
    addManualModel(providerId: string, input: ManualModelInput): Promise<Model>
  }
  settings: {
    getDefaults(): Promise<SettingsDefaults>
    setDefaults(patch: Partial<SettingsDefaults>): Promise<Settings>
    getLastActiveConversationId(): Promise<string | null>
    setLastActiveConversationId(value: string | null): Promise<void>
  }
  conversations: ConversationStore
}
