import type { ChatError, Conversation, Message, Model, Provider, SendKey } from '../../domain'
import type { ProviderRuntime } from '../providers/types'
import type { SecretStoreLike } from '../providers/types'
import type { ConversationStore } from '../conversations/types'
import type { MessageListOptions, MessagePage } from '../../storage'
import { buildProviderContext } from '../providers/runtime'
import type {
  ChatRunFailureReason,
  ChatRunInput,
  ChatRunResult,
  RegenerateInput,
} from './ChatRunner'
import { buildChatRequest } from './chatRequest'

export type ChatAvailability =
  | { ok: true; model: Model; provider: Provider }
  | { ok: false; reason: 'no_provider' | 'model_unavailable'; message: string }

export type ChatSendFailureReason =
  'no_provider' | 'model_unavailable' | 'no_key' | 'busy' | 'storage' | 'invalid_target'

export type ChatSendResult =
  | { ok: true; message: Message }
  | { ok: false; reason: ChatSendFailureReason; message: string; error?: ChatError }

/**
 * UI-facing chat surface.
 *
 * The concrete service owns request construction, key lookup, and the
 * `ChatRunner` lifecycle. Component tests inject a fake so the screen can be
 * exercised without IndexedDB or network access.
 */
export interface ChatServiceLike {
  getConversation(conversationId: string): Promise<Conversation | null>
  listMessages(conversationId: string, options?: MessageListOptions): Promise<MessagePage>
  getAvailability(conversation: Conversation): Promise<ChatAvailability>
  getShowTokenUsage(): Promise<boolean>
  getSendKey(): Promise<SendKey>
  send(conversation: Conversation, userMessage?: Message): Promise<ChatSendResult>
  retry(conversation: Conversation, target: Message): Promise<ChatSendResult>
  cancel(conversationId: string): Promise<Message | null>
  isStreaming(conversationId: string): boolean
}

/** Minimal runner surface used by the chat service. */
export interface ChatRunnerLike {
  send(input: ChatRunInput): Promise<ChatRunResult>
  regenerate(input: RegenerateInput): Promise<ChatRunResult>
  cancel(conversationId: string): Promise<Message | null>
  isStreaming(conversationId: string): boolean
}

export interface ChatServiceOptions {
  conversations: Pick<ConversationStore, 'get'> & {
    appendMessage(message: Message): Promise<{ message: Message; conversation: Conversation }>
  }
  messages: {
    listByConversation(conversationId: string, options?: MessageListOptions): Promise<MessagePage>
  }
  providers: {
    get(id: string): Promise<Provider | null>
    list(options?: { enabled?: boolean }): Promise<Provider[]>
  }
  models: {
    get(id: string): Promise<Model | null>
  }
  secrets: Pick<SecretStoreLike, 'getSecret'>
  settings: {
    getShowTokenUsage(): Promise<boolean>
    getSendKey(): Promise<SendKey>
  }
  runner: ChatRunnerLike
  runtime: ProviderRuntime
}

const PAGE_SIZE = 200

function failureMessage(reason: ChatRunFailureReason, error?: ChatError): string {
  switch (reason) {
    case 'busy':
      return 'A response is already streaming for this conversation.'
    case 'storage':
      return error?.message ?? 'ChatUI could not save the message on this device.'
    case 'invalid_target':
      return 'This message cannot be regenerated.'
  }
}

/**
 * Chat orchestration service.
 *
 * It resolves the conversation model, reads the key from the secret store,
 * builds the normalized request from persisted history, and delegates the
 * stream lifecycle to `ChatRunner`. The key never leaves the provider context.
 */
export class ChatService implements ChatServiceLike {
  private readonly conversations: ChatServiceOptions['conversations']
  private readonly messages: ChatServiceOptions['messages']
  private readonly providers: ChatServiceOptions['providers']
  private readonly models: ChatServiceOptions['models']
  private readonly secrets: ChatServiceOptions['secrets']
  private readonly settings: ChatServiceOptions['settings']
  private readonly runner: ChatRunnerLike
  private readonly runtime: ProviderRuntime

  constructor(options: ChatServiceOptions) {
    this.conversations = options.conversations
    this.messages = options.messages
    this.providers = options.providers
    this.models = options.models
    this.secrets = options.secrets
    this.settings = options.settings
    this.runner = options.runner
    this.runtime = options.runtime
  }

  async getConversation(conversationId: string): Promise<Conversation | null> {
    return this.conversations.get(conversationId)
  }

  async listMessages(
    conversationId: string,
    options: MessageListOptions = {},
  ): Promise<MessagePage> {
    return this.messages.listByConversation(conversationId, options)
  }

  async getShowTokenUsage(): Promise<boolean> {
    return this.settings.getShowTokenUsage()
  }

  async getSendKey(): Promise<SendKey> {
    return this.settings.getSendKey()
  }

  isStreaming(conversationId: string): boolean {
    return this.runner.isStreaming(conversationId)
  }

  /**
   * Resolve whether the conversation can send.
   *
   * A missing or disabled provider is reported separately from a missing model
   * so the screen can show the onboarding CTA or the model-unavailable warning.
   */
  async getAvailability(conversation: Conversation): Promise<ChatAvailability> {
    const enabled = await this.providers.list({ enabled: true })

    if (enabled.length === 0) {
      return {
        ok: false,
        reason: 'no_provider',
        message: 'No provider is configured. Add a provider before starting a chat.',
      }
    }

    if (conversation.providerId === null) {
      return {
        ok: false,
        reason: 'no_provider',
        message: 'No provider is configured for this conversation. Choose a provider and model.',
      }
    }

    const provider = await this.providers.get(conversation.providerId)

    if (!provider) {
      return {
        ok: false,
        reason: 'model_unavailable',
        message: 'The provider for this conversation is unavailable. Choose another model.',
      }
    }

    if (!provider.enabled) {
      return {
        ok: false,
        reason: 'model_unavailable',
        message: `Provider ${provider.label} is disabled. Enable it or choose another model.`,
      }
    }

    if (conversation.modelId === null) {
      return {
        ok: false,
        reason: 'model_unavailable',
        message: 'No model is selected for this conversation. Choose a model before sending.',
      }
    }

    const model = await this.models.get(conversation.modelId)

    if (!model) {
      return {
        ok: false,
        reason: 'model_unavailable',
        message: `Model ${conversation.modelId} is unavailable. Choose another model before sending.`,
      }
    }

    if (!model.outputModalities.includes('text')) {
      return {
        ok: false,
        reason: 'model_unavailable',
        message: `Model ${model.remoteId} does not produce text output. Choose another model.`,
      }
    }

    return { ok: true, model, provider }
  }

  /**
   * Append an optional user message and start an assistant response.
   *
   * The composer task owns the input UI; this method is the send extension
   * point. Passing the user message here keeps the append and the request
   * snapshot in one place.
   */
  async send(conversation: Conversation, userMessage?: Message): Promise<ChatSendResult> {
    const availability = await this.getAvailability(conversation)

    if (!availability.ok) {
      return { ok: false, reason: availability.reason, message: availability.message }
    }

    const apiKey = await this.getApiKey(availability.provider)

    if (apiKey === null) {
      return {
        ok: false,
        reason: 'no_key',
        message: 'The API key for this provider is unavailable. Re-enter the key and try again.',
      }
    }

    let current = conversation

    if (userMessage) {
      try {
        const appended = await this.conversations.appendMessage(userMessage)
        current = appended.conversation
      } catch (error) {
        return {
          ok: false,
          reason: 'storage',
          message: error instanceof Error ? error.message : 'Could not save the message.',
        }
      }
    }

    try {
      const history = await this.loadAllMessages(current.id)
      const request = buildChatRequest(current, availability.model.remoteId, history)
      const context = buildProviderContext(availability.provider, this.runtime, apiKey)
      const result = await this.runner.send({
        conversation: current,
        context,
        request,
        sequence: current.messageCount + 1,
        streamOptions: { supportedParameters: availability.model.supportedParameters },
      })

      if (!result.ok) {
        return {
          ok: false,
          reason: result.reason,
          message: failureMessage(result.reason, result.error),
          ...(result.error === undefined ? {} : { error: result.error }),
        }
      }

      return { ok: true, message: result.message }
    } catch (error) {
      return {
        ok: false,
        reason: 'storage',
        message: error instanceof Error ? error.message : 'Could not send the message.',
      }
    }
  }

  /**
   * Regenerate an assistant message in place.
   *
   * The request contains history before the target only, so the replacement
   * does not send the failed response back to the provider.
   */
  async retry(conversation: Conversation, target: Message): Promise<ChatSendResult> {
    const availability = await this.getAvailability(conversation)

    if (!availability.ok) {
      return { ok: false, reason: availability.reason, message: availability.message }
    }

    const apiKey = await this.getApiKey(availability.provider)

    if (apiKey === null) {
      return {
        ok: false,
        reason: 'no_key',
        message: 'The API key for this provider is unavailable. Re-enter the key and try again.',
      }
    }

    try {
      const history = await this.loadAllMessages(conversation.id)
      const beforeTarget = history.filter((message) => message.sequence < target.sequence)
      const request = buildChatRequest(conversation, availability.model.remoteId, beforeTarget)
      const context = buildProviderContext(availability.provider, this.runtime, apiKey)
      const result = await this.runner.regenerate({
        conversation,
        context,
        request,
        target,
        sequence: target.sequence,
        streamOptions: { supportedParameters: availability.model.supportedParameters },
      })

      if (!result.ok) {
        return {
          ok: false,
          reason: result.reason,
          message: failureMessage(result.reason, result.error),
          ...(result.error === undefined ? {} : { error: result.error }),
        }
      }

      return { ok: true, message: result.message }
    } catch (error) {
      return {
        ok: false,
        reason: 'storage',
        message: error instanceof Error ? error.message : 'Could not regenerate the message.',
      }
    }
  }

  async cancel(conversationId: string): Promise<Message | null> {
    return this.runner.cancel(conversationId)
  }

  private async getApiKey(provider: Provider): Promise<string | null> {
    if (provider.secretId === null) {
      return null
    }

    return this.secrets.getSecret(provider.secretId)
  }

  private async loadAllMessages(conversationId: string): Promise<Message[]> {
    const all: Message[] = []
    let cursor: number | null = null

    for (let page = 0; page < 1000; page += 1) {
      const result = await this.messages.listByConversation(conversationId, {
        limit: PAGE_SIZE,
        beforeSequence: cursor,
      })

      all.unshift(...result.items)

      if (result.nextCursor === null) {
        break
      }

      cursor = result.nextCursor
    }

    return all.sort((left, right) => left.sequence - right.sequence)
  }
}
