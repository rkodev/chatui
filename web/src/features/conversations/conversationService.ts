import {
  newId,
  nowIso,
  type ChatParams,
  type Conversation,
  type Message,
  type Model,
  type Provider,
} from '../../domain'
import type {
  AppendMessageResult,
  ConversationListOptions,
  ConversationPage,
  ConversationSearchOptions,
  ConversationSearchResult,
} from '../../storage'
import {
  ConversationServiceError,
  type ConversationServiceOptions,
  type CreateConversationInput,
  type CreateConversationResult,
  type EnsureConversationResult,
} from './types'

/** Maximum title length in Unicode code points, per spec 3.2 and X-13. */
export const MAX_TITLE_CODE_POINTS = 60

/** Title used before the first user message supplies one. */
export const DEFAULT_CONVERSATION_TITLE = 'New chat'

/**
 * Derive a conversation title from the first user message.
 *
 * The text is trimmed, internal whitespace (including newlines) is collapsed
 * to single spaces, and the result is capped at 60 Unicode code points. Blank
 * input returns `DEFAULT_CONVERSATION_TITLE`.
 */
export function deriveTitle(text: string): string {
  const normalized = text.replace(/\s+/gu, ' ').trim()

  if (normalized === '') {
    return DEFAULT_CONVERSATION_TITLE
  }

  return Array.from(normalized).slice(0, MAX_TITLE_CODE_POINTS).join('')
}

function cloneParams(params: ChatParams): ChatParams {
  return {
    ...params,
    stop: [...params.stop],
    extra: { ...params.extra },
  }
}

function messageText(message: Message): string {
  return message.parts
    .map((part) => (part.type === 'text' ? part.text : ''))
    .filter((text) => text !== '')
    .join(' ')
}

function isDefaultTitle(title: string): boolean {
  return title.trim() === '' || title === DEFAULT_CONVERSATION_TITLE
}

/**
 * Plain TypeScript conversation service used by chat and history UI.
 *
 * The service owns new-chat default resolution, title defaulting, message
 * append metadata, and last-active tracking. All persistence goes through the
 * injected repositories; no UI or React code lives here.
 */
export class ConversationService {
  private readonly conversations: ConversationServiceOptions['conversations']
  private readonly messages: ConversationServiceOptions['messages']
  private readonly providers: ConversationServiceOptions['providers']
  private readonly models: ConversationServiceOptions['models']
  private readonly settings: ConversationServiceOptions['settings']
  private readonly timestamp: () => string
  private readonly generateId: () => string

  constructor(options: ConversationServiceOptions) {
    this.conversations = options.conversations
    this.messages = options.messages
    this.providers = options.providers
    this.models = options.models
    this.settings = options.settings
    this.timestamp = options.nowIso ?? nowIso
    this.generateId = options.newId ?? newId
  }

  /**
   * Create a conversation from the current typed defaults.
   *
   * The configured default provider/model are preferred. If they are missing
   * or disabled, the first enabled provider and its default model are used.
   * When no provider or model can be resolved, a typed `not_configured`
   * result is returned instead of throwing.
   */
  async createConversation(input: CreateConversationInput = {}): Promise<CreateConversationResult> {
    const defaults = await this.settings.getDefaults()
    const provider = await this.resolveProvider(defaults.providerId)

    if (!provider) {
      return {
        ok: false,
        reason: 'not_configured',
        message: 'No provider is configured. Add a provider before starting a chat.',
      }
    }

    const model = await this.resolveModel(provider, defaults.modelId)

    if (!model) {
      return {
        ok: false,
        reason: 'not_configured',
        message: `No model is available for ${provider.label}. Refresh the model catalog before starting a chat.`,
      }
    }

    const now = this.timestamp()
    const conversation: Conversation = {
      id: this.generateId(),
      title: input.title?.trim() || DEFAULT_CONVERSATION_TITLE,
      systemPrompt: defaults.systemPrompt,
      providerId: provider.id,
      modelId: model.id,
      modelLabel: model.label,
      params: cloneParams(defaults.params),
      pinned: false,
      archived: false,
      temporary: false,
      createdAt: now,
      updatedAt: now,
      lastMessageAt: now,
      messageCount: 0,
      lastError: null,
      sync: null,
    }

    return { ok: true, conversation: await this.conversations.create(conversation) }
  }

  /**
   * Return an existing conversation or create a safe new chat.
   *
   * A missing or unknown id falls back to a new conversation. The returned
   * conversation becomes the last-active conversation.
   */
  async ensureConversationForSend(
    conversationId?: string | null,
  ): Promise<EnsureConversationResult> {
    if (conversationId) {
      const existing = await this.conversations.get(conversationId)

      if (existing) {
        await this.settings.setLastActiveConversationId(existing.id)
        return { ok: true, conversation: existing, created: false }
      }
    }

    const created = await this.createConversation()

    if (!created.ok) {
      return created
    }

    await this.settings.setLastActiveConversationId(created.conversation.id)
    return { ok: true, conversation: created.conversation, created: true }
  }

  async getConversation(id: string): Promise<Conversation | null> {
    return this.conversations.get(id)
  }

  async listConversations(options: ConversationListOptions = {}): Promise<ConversationPage> {
    return this.conversations.list(options)
  }

  /** Search titles and message text through the repository's bounded scan. */
  async searchConversations(
    query: string,
    options: ConversationSearchOptions = {},
  ): Promise<ConversationSearchResult> {
    return this.conversations.search(query, options)
  }

  /** Rename a conversation. Blank titles are rejected. */
  async renameConversation(id: string, title: string): Promise<Conversation> {
    const trimmed = title.trim()

    if (trimmed === '') {
      throw new ConversationServiceError(
        'invalid_input',
        'Conversation title must not be empty.',
        id,
      )
    }

    const existing = await this.conversations.get(id)

    if (!existing) {
      throw new ConversationServiceError('not_found', `Conversation ${id} was not found.`, id)
    }

    return this.conversations.update(id, { title: trimmed })
  }

  /** Pin or unpin a conversation without changing its timestamp. */
  async setConversationPinned(id: string, pinned: boolean): Promise<Conversation> {
    return this.updateConversationFlags(id, { pinned })
  }

  /**
   * Archive or unarchive a conversation.
   *
   * Archived conversations stay in storage and are excluded from the active
   * history list by the repository filter.
   */
  async setConversationArchived(id: string, archived: boolean): Promise<Conversation> {
    return this.updateConversationFlags(id, { archived })
  }

  /**
   * Delete a conversation and its messages through the repository transaction.
   *
   * Attachments are intentionally left for the orphan-cleanup path. If the
   * deleted conversation was last active, the pointer is cleared.
   */
  async deleteConversation(id: string): Promise<void> {
    await this.conversations.delete(id)

    if ((await this.settings.getLastActiveConversationId()) === id) {
      await this.settings.setLastActiveConversationId(null)
    }
  }

  /**
   * Append a message and update conversation metadata in one transaction.
   *
   * Assistant messages inherit provider/model/provider-label snapshots from
   * the conversation when the caller did not supply them. The first user
   * message supplies the title while the conversation still has the default
   * title.
   */
  async appendMessage(message: Message): Promise<AppendMessageResult> {
    const conversation = await this.conversations.get(message.conversationId)

    if (!conversation) {
      throw new ConversationServiceError(
        'not_found',
        `Conversation ${message.conversationId} was not found.`,
        message.conversationId,
      )
    }

    const prepared = this.withSnapshots(message, conversation)
    const result = await this.messages.appendMessage(prepared)
    const title = this.titleFor(conversation, prepared)

    if (title === null) {
      return result
    }

    const updated = await this.conversations.update(conversation.id, { title })
    return { message: result.message, conversation: updated }
  }

  /** Persist the last-active conversation id through typed settings. */
  async setLastActiveConversation(id: string | null): Promise<void> {
    if (id !== null && !(await this.conversations.get(id))) {
      throw new ConversationServiceError('not_found', `Conversation ${id} was not found.`, id)
    }

    await this.settings.setLastActiveConversationId(id)
  }

  /**
   * Read the last-active conversation.
   *
   * A stale id (for example after an external delete) is cleared and returns
   * null so callers can fall back to a safe new chat.
   */
  async getLastActiveConversation(): Promise<Conversation | null> {
    const id = await this.settings.getLastActiveConversationId()

    if (id === null) {
      return null
    }

    const conversation = await this.conversations.get(id)

    if (!conversation) {
      await this.settings.setLastActiveConversationId(null)
      return null
    }

    return conversation
  }

  private async updateConversationFlags(
    id: string,
    flags: Pick<Partial<Conversation>, 'pinned' | 'archived'>,
  ): Promise<Conversation> {
    const existing = await this.conversations.get(id)

    if (!existing) {
      throw new ConversationServiceError('not_found', `Conversation ${id} was not found.`, id)
    }

    return this.conversations.update(id, {
      ...flags,
      updatedAt: existing.updatedAt,
    })
  }

  private async resolveProvider(preferredId: string | null): Promise<Provider | null> {
    if (preferredId !== null) {
      const preferred = await this.providers.get(preferredId)

      if (preferred?.enabled) {
        return preferred
      }
    }

    const enabled = await this.providers.list({ enabled: true })
    return enabled[0] ?? null
  }

  private async resolveModel(
    provider: Provider,
    preferredId: string | null,
  ): Promise<Model | null> {
    const models = await this.models.listModels(provider.id)

    if (preferredId !== null) {
      const preferred = models.find((model) => model.id === preferredId)

      if (preferred) {
        return preferred
      }
    }

    const selection = await this.models.selectDefaultModel(provider.id)

    if (selection.modelId === null) {
      return null
    }

    return models.find((model) => model.id === selection.modelId) ?? null
  }

  private withSnapshots(message: Message, conversation: Conversation): Message {
    if (message.role !== 'assistant') {
      return message
    }

    return {
      ...message,
      providerId: message.providerId ?? conversation.providerId,
      modelId: message.modelId ?? conversation.modelId,
      modelLabel: message.modelLabel ?? conversation.modelLabel,
    }
  }

  private titleFor(conversation: Conversation, message: Message): string | null {
    if (message.role !== 'user' || !isDefaultTitle(conversation.title)) {
      return null
    }

    const text = messageText(message)

    if (text.trim() === '') {
      return null
    }

    return deriveTitle(text)
  }
}
