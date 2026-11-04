import {
  nowIso,
  type ChatParams,
  type Conversation,
  type Model,
  type Provider,
  type Settings,
} from '../../domain'
import type { AppConfig } from '../../app/config'
import { DEFAULT_SETTINGS } from '../settings'
import type { ProviderManagementServiceLike } from './managementTypes'
import { readRoutingPreferences, withRoutingPreferences } from './routing'

export const testConfig: AppConfig = {
  appName: 'ChatUI',
  defaultTheme: 'system',
  defaultProviderPreset: 'openrouter',
  enabledPresets: ['openrouter'],
  allowCustomProviders: false,
  openRouter: {
    attributionOrigin: 'https://chat.example.com',
    appTitle: 'ChatUI',
  },
  docsUrl: 'https://github.com/rkodev/chatui',
  telemetry: false,
}

const params: ChatParams = {
  temperature: null,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

export function createTestProvider(overrides: Partial<Provider> = {}): Provider {
  const now = nowIso()

  return {
    id: 'provider-1',
    presetId: 'openrouter',
    label: 'OpenRouter',
    kind: 'openai-compatible',
    baseUrl: 'https://openrouter.ai/api/v1',
    authType: 'bearer',
    secretId: 'secret-1',
    headers: {},
    enabled: true,
    browserCors: 'unknown',
    metadata: {
      key: {
        label: 'Work key',
        usageUsd: 1.25,
        limitUsd: 10,
        limitRemainingUsd: 8.75,
        isFreeTier: false,
        rateLimit: { requests: 60, interval: 'minute' },
        validatedAt: now,
      },
    },
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

export function createTestModel(overrides: Partial<Model> = {}): Model {
  const now = nowIso()

  return {
    id: 'model-1',
    providerId: 'provider-1',
    remoteId: 'openai/gpt-4o-mini',
    label: 'OpenAI: GPT-4o mini',
    contextWindow: 128000,
    maxOutputTokens: 16384,
    capabilities: ['text', 'vision', 'tools'],
    pricing: { inputPerMTok: 0.15, outputPerMTok: 0.6 },
    canonicalSlug: 'openai/gpt-4o-mini',
    inputModalities: ['text', 'image'],
    outputModalities: ['text'],
    supportedParameters: ['temperature', 'tools'],
    metadata: null,
    hidden: false,
    pinned: false,
    source: 'discovered',
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

export function createTestConversation(overrides: Partial<Conversation> = {}): Conversation {
  const now = nowIso()

  return {
    id: 'conversation-1',
    title: 'New chat',
    systemPrompt: '',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'OpenAI: GPT-4o mini',
    params,
    pinned: false,
    archived: false,
    temporary: false,
    createdAt: now,
    updatedAt: now,
    lastMessageAt: now,
    messageCount: 0,
    lastError: null,
    sync: null,
    ...overrides,
  }
}

/**
 * In-memory provider-management fake for component and route tests.
 *
 * It mirrors the real service's observable behavior without IndexedDB or
 * network access. Individual tests override only the methods they exercise.
 */
export function createFakeProviderManagementService(
  overrides: Partial<ProviderManagementServiceLike> = {},
): ProviderManagementServiceLike {
  const provider = createTestProvider()
  const model = createTestModel()
  const conversation = createTestConversation()

  return {
    listProviders: async () => [provider],
    getProvider: async () => provider,
    validateProviderKey: async () => provider,
    rotateKey: async () => provider,
    removeKey: async () => ({ ...provider, secretId: null }),
    setProviderEnabled: async (_providerId, enabled) => ({ ...provider, enabled }),
    deleteProvider: async () => undefined,
    refreshModels: async () => ({ models: [model], created: 0, updated: 1 }),
    listModels: async () => [model],
    setModelPinned: async (_modelId, pinned) => ({ ...model, pinned }),
    setModelHidden: async (_modelId, hidden) => ({ ...model, hidden }),
    addManualModel: async (_providerId, input) => ({
      ...model,
      id: 'manual-model',
      remoteId: input.remoteId,
      label: input.label ?? input.remoteId,
      source: 'manual',
    }),
    getDefaults: async () => ({
      providerId: provider.id,
      modelId: model.id,
      systemPrompt: '',
      params,
    }),
    setDefaults: async (patch): Promise<Settings> => ({
      ...DEFAULT_SETTINGS,
      defaultProviderId: patch.providerId ?? provider.id,
      defaultModelId: patch.modelId ?? model.id,
    }),
    getConversation: async () => conversation,
    setConversationModel: async (_conversationId, modelId) => ({
      ...conversation,
      modelId,
      modelLabel: model.label,
    }),
    setConversationRouting: async (_conversationId, preferences) => ({
      ...conversation,
      params: withRoutingPreferences(conversation.params, preferences),
    }),
    getConversationRouting: async () => readRoutingPreferences(conversation.params),
    getDefaultModelWarning: async () => null,
    resolveConversationModel: async () => ({ ok: true, model, provider }),
    ...overrides,
  }
}
