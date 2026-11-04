import type { Conversation, Message, Model, Provider } from '../../domain'
import type {
  AppendMessageResult,
  ConversationListOptions,
  ConversationPage,
  MessageListOptions,
  MessagePage,
  NewConversation,
} from '../../storage'
import type { DefaultModelSelection } from '../providers/types'
import type { SettingsDefaults } from '../settings/settingsService'

/**
 * Minimal conversation persistence surface used by the service.
 *
 * `ConversationRepository` satisfies this interface. Tests can inject a fake
 * without opening IndexedDB.
 */
export interface ConversationStore {
  create(input: NewConversation): Promise<Conversation>
  get(id: string): Promise<Conversation | null>
  list(options?: ConversationListOptions): Promise<ConversationPage>
  update(id: string, patch: Partial<Omit<Conversation, 'id' | 'createdAt'>>): Promise<Conversation>
  delete(id: string): Promise<void>
}

/** Minimal message persistence surface used by the service. */
export interface ConversationMessageStore {
  get(id: string): Promise<Message | null>
  listByConversation(conversationId: string, options?: MessageListOptions): Promise<MessagePage>
  appendMessage(message: Message): Promise<AppendMessageResult>
}

/** Minimal provider lookup surface used to resolve new-chat defaults. */
export interface ConversationProviderStore {
  get(id: string): Promise<Provider | null>
  list(options?: { enabled?: boolean }): Promise<Provider[]>
}

/**
 * Model selection surface used to resolve new-chat defaults.
 *
 * `ModelService` satisfies this interface.
 */
export interface ConversationModelResolver {
  selectDefaultModel(providerId: string): Promise<DefaultModelSelection>
  listModels(providerId: string): Promise<Model[]>
}

/**
 * Typed settings surface used for defaults and last-active tracking.
 *
 * `SettingsService` satisfies this interface.
 */
export interface ConversationSettings {
  getDefaults(): Promise<SettingsDefaults>
  getLastActiveConversationId(): Promise<string | null>
  setLastActiveConversationId(value: string | null): Promise<void>
}

export interface ConversationServiceOptions {
  conversations: ConversationStore
  messages: ConversationMessageStore
  providers: ConversationProviderStore
  models: ConversationModelResolver
  settings: ConversationSettings
  nowIso?: () => string
  newId?: () => string
}

export interface CreateConversationInput {
  /** Optional explicit title. Blank values fall back to the default title. */
  title?: string
}

export type CreateConversationResult =
  | { ok: true; conversation: Conversation }
  | { ok: false; reason: 'not_configured'; message: string }

export type EnsureConversationResult =
  | { ok: true; conversation: Conversation; created: boolean }
  | { ok: false; reason: 'not_configured'; message: string }

export type ConversationServiceErrorCode = 'not_found' | 'invalid_input' | 'storage'

/**
 * Service-level failure that is safe to show to the UI.
 *
 * Repository failures propagate unchanged. This error covers missing records
 * and invalid service input.
 */
export class ConversationServiceError extends Error {
  readonly code: ConversationServiceErrorCode
  readonly conversationId: string | null

  constructor(
    code: ConversationServiceErrorCode,
    message: string,
    conversationId: string | null = null,
    options: { cause?: unknown } = {},
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'ConversationServiceError'
    this.code = code
    this.conversationId = conversationId
  }
}
