import { describe, expect, it } from 'vitest'
import {
  newId,
  newStorageKey,
  nowIso,
  type ChatParams,
  type Conversation,
  type Message,
  type Model,
  type Provider,
  type Settings,
} from '../../domain'
import type {
  AppendMessageResult,
  ConversationPage,
  ConversationSearchResult,
  MessagePage,
  NewConversation,
} from '../../storage'
import { ConversationService } from '../conversations'
import { DEFAULT_SETTINGS } from '../settings'
import type { SettingsDefaults } from '../settings/settingsService'
import { ProviderManagementService } from './managementService'
import type { ProviderManagementServiceOptions } from './managementTypes'
import { DEFAULT_ROUTING_PREFERENCES, type RoutingPreferences } from './routing'
import { createTestConversation, createTestModel, createTestProvider } from './testSupport'

const params: ChatParams = {
  temperature: null,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

class MemoryProviderStore {
  readonly values = new Map<string, Provider>()

  async get(id: string): Promise<Provider | null> {
    return this.values.get(id) ?? null
  }

  async list(options: { enabled?: boolean } = {}): Promise<Provider[]> {
    const all = [...this.values.values()]
    return options.enabled === undefined
      ? all
      : all.filter((provider) => provider.enabled === options.enabled)
  }
}

class MemoryModelStore {
  readonly values = new Map<string, Model>()

  async get(id: string): Promise<Model | null> {
    return this.values.get(id) ?? null
  }

  async list(options: { providerId?: string } = {}): Promise<Model[]> {
    const all = [...this.values.values()]
    return options.providerId === undefined
      ? all
      : all.filter((model) => model.providerId === options.providerId)
  }
}

class MemoryConversationStore {
  readonly values = new Map<string, Conversation>()

  async create(input: NewConversation): Promise<Conversation> {
    const now = nowIso()
    const conversation: Conversation = {
      ...input,
      id: input.id ?? newId(),
      createdAt: input.createdAt ?? now,
      updatedAt: input.updatedAt ?? now,
      lastMessageAt: input.lastMessageAt ?? input.createdAt ?? now,
      messageCount: input.messageCount ?? 0,
      storageKey: input.storageKey ?? newStorageKey(),
      revision: input.revision ?? 1,
    }
    this.values.set(conversation.id, conversation)
    return conversation
  }

  async get(id: string): Promise<Conversation | null> {
    return this.values.get(id) ?? null
  }

  async list(): Promise<ConversationPage> {
    return { items: [...this.values.values()], nextCursor: null }
  }

  async search(query: string): Promise<ConversationSearchResult> {
    return {
      query,
      groups: [],
      truncated: false,
      scannedConversations: 0,
      scannedMessages: 0,
    }
  }

  async update(
    id: string,
    patch: Partial<Omit<Conversation, 'id' | 'createdAt'>>,
  ): Promise<Conversation> {
    const existing = this.values.get(id)

    if (!existing) {
      throw new Error(`Conversation ${id} not found`)
    }

    const updated: Conversation = {
      ...existing,
      ...patch,
      id,
      createdAt: existing.createdAt,
      updatedAt: nowIso(),
    }
    this.values.set(id, updated)
    return updated
  }

  async delete(id: string): Promise<void> {
    this.values.delete(id)
  }
}

class MemoryMessageStore {
  readonly values = new Map<string, Message>()
  private readonly conversations: MemoryConversationStore

  constructor(conversations: MemoryConversationStore) {
    this.conversations = conversations
  }

  async get(id: string): Promise<Message | null> {
    return this.values.get(id) ?? null
  }

  async listByConversation(conversationId: string): Promise<MessagePage> {
    return {
      items: [...this.values.values()].filter(
        (message) => message.conversationId === conversationId,
      ),
      nextCursor: null,
    }
  }

  async appendMessage(message: Message): Promise<AppendMessageResult> {
    this.values.set(message.id, message)
    const conversation = await this.conversations.get(message.conversationId)

    if (!conversation) {
      throw new Error(`Conversation ${message.conversationId} not found`)
    }

    return { message, conversation }
  }
}

class MemorySettings {
  lastActive: string | null = null

  defaults: SettingsDefaults = {
    providerId: null,
    modelId: null,
    systemPrompt: '',
    params,
  }

  async getDefaults(): Promise<SettingsDefaults> {
    return structuredClone(this.defaults)
  }

  async setDefaults(patch: Partial<SettingsDefaults>): Promise<Settings> {
    this.defaults = { ...this.defaults, ...patch }
    return {
      ...DEFAULT_SETTINGS,
      defaultProviderId: this.defaults.providerId,
      defaultModelId: this.defaults.modelId,
      defaultSystemPrompt: this.defaults.systemPrompt,
      defaultParams: this.defaults.params,
    }
  }

  async getLastActiveConversationId(): Promise<string | null> {
    return this.lastActive
  }

  async setLastActiveConversationId(value: string | null): Promise<void> {
    this.lastActive = value
  }
}

function message(conversationId: string, role: Message['role']): Message {
  const now = nowIso()

  return {
    id: newId(),
    conversationId,
    sequence: 1,
    role,
    parts: [{ type: 'text', text: 'Hello' }],
    status: 'complete',
    providerId: null,
    modelId: null,
    modelLabel: null,
    usage: null,
    finishReason: null,
    error: null,
    latencyMs: null,
    firstTokenMs: null,
    createdAt: now,
    updatedAt: now,
    deletedAt: null,
  }
}

function setup() {
  const providers = new MemoryProviderStore()
  const models = new MemoryModelStore()
  const conversations = new MemoryConversationStore()
  const messages = new MemoryMessageStore(conversations)
  const settings = new MemorySettings()

  const provider = createTestProvider()
  const firstModel = createTestModel()
  const secondModel = createTestModel({
    id: 'model-2',
    remoteId: 'anthropic/claude-sonnet',
    label: 'Anthropic: Claude Sonnet',
    pricing: { inputPerMTok: 3, outputPerMTok: 15 },
  })
  providers.values.set(provider.id, provider)
  models.values.set(firstModel.id, firstModel)
  models.values.set(secondModel.id, secondModel)

  const conversation = createTestConversation()
  conversations.values.set(conversation.id, conversation)
  settings.defaults = {
    providerId: provider.id,
    modelId: firstModel.id,
    systemPrompt: '',
    params,
  }

  const providerService: ProviderManagementServiceOptions['providerService'] = {
    listProviders: async (options) => providers.list(options),
    getProvider: async (id) => providers.get(id),
    createCustomProvider: async (input) => {
      const now = nowIso()
      const provider: Provider = {
        id: 'custom-provider',
        presetId: 'custom',
        label: input.label?.trim() || 'Custom OpenAI-compatible',
        kind: 'openai-compatible',
        baseUrl: input.baseUrl,
        authType: input.authType,
        secretId: input.authType === 'none' ? null : 'custom-secret',
        headers: input.headers ?? {},
        enabled: true,
        browserCors: 'unknown',
        metadata: null,
        createdAt: now,
        updatedAt: now,
      }
      providers.values.set(provider.id, provider)
      return provider
    },
    validateProviderKey: async (id) => {
      const value = await providers.get(id)
      if (!value) throw new Error('missing provider')
      return value
    },
    rotateKey: async (id) => {
      const value = await providers.get(id)
      if (!value) throw new Error('missing provider')
      const updated = { ...value, secretId: 'rotated-secret' }
      providers.values.set(id, updated)
      return updated
    },
    removeKey: async (id) => {
      const value = await providers.get(id)
      if (!value) throw new Error('missing provider')
      const updated = { ...value, secretId: null }
      providers.values.set(id, updated)
      return updated
    },
    setProviderEnabled: async (id, enabled) => {
      const value = await providers.get(id)
      if (!value) throw new Error('missing provider')
      const updated = { ...value, enabled }
      providers.values.set(id, updated)
      return updated
    },
    deleteProvider: async (id) => {
      providers.values.delete(id)
    },
  }

  const modelService: ProviderManagementServiceOptions['modelService'] = {
    refreshModels: async (providerId) => ({
      models: await models.list({ providerId }),
      created: 0,
      updated: 0,
    }),
    listModels: async (providerId) => models.list({ providerId }),
    setModelPinned: async (id, pinned) => {
      const value = await models.get(id)
      if (!value) throw new Error('missing model')
      const updated = { ...value, pinned }
      models.values.set(id, updated)
      return updated
    },
    setModelHidden: async (id, hidden) => {
      const value = await models.get(id)
      if (!value) throw new Error('missing model')
      const updated = { ...value, hidden }
      models.values.set(id, updated)
      return updated
    },
    addManualModel: async (providerId, input) => {
      const value = createTestModel({
        id: 'manual-model',
        providerId,
        remoteId: input.remoteId,
        label: input.label ?? input.remoteId,
        source: 'manual',
      })
      models.values.set(value.id, value)
      return value
    },
  }

  const service = new ProviderManagementService({
    providers,
    models,
    providerService,
    modelService,
    settings,
    conversations,
  })

  const conversationService = new ConversationService({
    conversations,
    messages,
    providers,
    models: {
      selectDefaultModel: async (providerId) => {
        const list = await models.list({ providerId })
        const first = list[0]
        return first
          ? { modelId: first.id, remoteId: first.remoteId, notice: null }
          : { modelId: null, remoteId: null, notice: 'No model' }
      },
      listModels: async (providerId) => models.list({ providerId }),
    },
    settings,
  })

  return {
    service,
    conversationService,
    providers,
    models,
    conversations,
    settings,
    provider,
    firstModel,
    secondModel,
    conversation,
  }
}

describe('ProviderManagementService', () => {
  it('persists global defaults and rejects a model from another provider', async () => {
    const { service, provider, secondModel } = setup()

    const settings = await service.setDefaults({
      providerId: provider.id,
      modelId: secondModel.id,
    })

    expect(settings.defaultProviderId).toBe(provider.id)
    expect(settings.defaultModelId).toBe(secondModel.id)
    expect(await service.getDefaults()).toMatchObject({
      providerId: provider.id,
      modelId: secondModel.id,
    })

    await expect(
      service.setDefaults({ providerId: 'other-provider', modelId: secondModel.id }),
    ).rejects.toThrow('does not belong')
  })

  it('switches a conversation model immediately and resolves it for the next send', async () => {
    const { service, conversation, secondModel } = setup()

    const updated = await service.setConversationModel(conversation.id, secondModel.id)

    expect(updated.modelId).toBe(secondModel.id)
    expect(updated.modelLabel).toBe(secondModel.label)
    expect((await service.getConversation(conversation.id))?.modelId).toBe(secondModel.id)

    const resolved = await service.resolveConversationModel(updated)
    expect(resolved.ok).toBe(true)
    if (resolved.ok) {
      expect(resolved.model.id).toBe(secondModel.id)
    }
  })

  it('keeps assistant-message snapshots from before and after a model switch', async () => {
    const { service, conversationService, conversation, firstModel, secondModel } = setup()

    const before = await conversationService.appendMessage(
      message(conversation.id, 'assistant'),
    )
    expect(before.message.modelId).toBe(firstModel.id)
    expect(before.message.modelLabel).toBe(firstModel.label)

    await service.setConversationModel(conversation.id, secondModel.id)

    const after = await conversationService.appendMessage(
      message(conversation.id, 'assistant'),
    )
    expect(after.message.modelId).toBe(secondModel.id)
    expect(after.message.modelLabel).toBe(secondModel.label)
    expect(before.message.modelId).toBe(firstModel.id)
  })

  it('warns about an unavailable default model without substituting', async () => {
    const { service, models, firstModel } = setup()
    models.values.delete(firstModel.id)

    expect(await service.getDefaultModelWarning()).toContain('unavailable')

    const conversation = createTestConversation({ modelId: firstModel.id })
    const resolved = await service.resolveConversationModel(conversation)
    expect(resolved.ok).toBe(false)
    if (!resolved.ok) {
      expect(resolved.reason).toBe('unavailable')
      expect(resolved.message).toContain('unavailable')
    }
  })

  it('persists routing preferences in conversation params', async () => {
    const { service, conversation } = setup()
    const preferences: RoutingPreferences = {
      enabled: true,
      allowFallbacks: true,
      sort: 'price',
      denyDataCollection: true,
    }

    const updated = await service.setConversationRouting(conversation.id, preferences)

    expect(updated.params.extra).toEqual({
      openrouter: {
        provider: {
          allow_fallbacks: true,
          sort: 'price',
          data_collection: 'deny',
        },
      },
    })
    expect(await service.getConversationRouting(conversation.id)).toEqual(preferences)

    const cleared = await service.setConversationRouting(
      conversation.id,
      DEFAULT_ROUTING_PREFERENCES,
    )
    expect(cleared.params.extra).toEqual({})
  })

  it('persists system prompt and parameters immediately', async () => {
    const { service, conversation } = setup()
    const nextParams: ChatParams = {
      ...params,
      temperature: 0.4,
      topP: 0.8,
      stop: ['DONE'],
      reasoningEffort: 'medium',
      streaming: false,
    }

    const updated = await service.setConversationSettings(conversation.id, {
      systemPrompt: 'Answer briefly.',
      params: nextParams,
    })

    expect(updated.systemPrompt).toBe('Answer briefly.')
    expect(updated.params).toEqual(nextParams)
    expect(await service.getConversation(conversation.id)).toMatchObject({
      systemPrompt: 'Answer briefly.',
      params: nextParams,
    })
  })

  it('preserves core parameters and prunes stale extras across a provider switch', async () => {
    const { service, conversations, models, providers, conversation, firstModel } = setup()
    const anthropicProvider = createTestProvider({
      id: 'provider-2',
      presetId: 'anthropic',
      label: 'Anthropic',
      kind: 'anthropic',
    })
    const anthropicModel = createTestModel({
      id: 'model-3',
      providerId: anthropicProvider.id,
      remoteId: 'claude-sonnet',
      label: 'Claude Sonnet',
      supportedParameters: ['temperature', 'top_p', 'max_tokens', 'stop'],
    })
    providers.values.set(anthropicProvider.id, anthropicProvider)
    models.values.set(anthropicModel.id, anthropicModel)

    const withParameters: Conversation = {
      ...conversation,
      params: {
        ...params,
        temperature: 0.4,
        stop: ['END'],
        extra: {
          openrouter: { provider: { sort: 'price' } },
          anthropic: { metadata: { user_id: 'abc' } },
        },
      },
    }
    conversations.values.set(withParameters.id, withParameters)

    await service.setConversationModel(conversation.id, firstModel.id)
    expect((await service.getConversation(conversation.id))?.params.extra).toEqual(
      withParameters.params.extra,
    )

    const updated = await service.setConversationModel(conversation.id, anthropicModel.id)

    expect(updated.providerId).toBe(anthropicProvider.id)
    expect(updated.modelId).toBe(anthropicModel.id)
    expect(updated.params.temperature).toBe(0.4)
    expect(updated.params.stop).toEqual(['END'])
    expect(updated.params.extra).toEqual({ anthropic: { metadata: { user_id: 'abc' } } })
  })

  it('deletes a conversation and clears the matching last-active pointer', async () => {
    const { service, conversations, settings, conversation } = setup()
    settings.lastActive = conversation.id

    await service.deleteConversation(conversation.id)

    expect(conversations.values.has(conversation.id)).toBe(false)
    expect(settings.lastActive).toBeNull()
  })

  it('persists pin, hide, and manual model state', async () => {
    const { service, provider, firstModel } = setup()

    expect((await service.setModelPinned(firstModel.id, true)).pinned).toBe(true)
    expect((await service.setModelHidden(firstModel.id, true)).hidden).toBe(true)

    const manual = await service.addManualModel(provider.id, {
      remoteId: 'author/manual',
      label: 'Manual model',
    })
    expect(manual.source).toBe('manual')
    expect(manual.label).toBe('Manual model')
  })
})
