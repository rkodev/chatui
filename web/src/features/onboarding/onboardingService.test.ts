import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createCrypto } from '../../platform/crypto'
import { openRouterAdapter, OpenRouterRequestError } from '../../providers/openrouter'
import { MemoryCatalogCache } from '../../providers/openrouterCatalog'
import { ConversationRepository } from '../../storage/ConversationRepository'
import { MessageRepository } from '../../storage/MessageRepository'
import { ModelRepository } from '../../storage/ModelRepository'
import { ProviderRepository } from '../../storage/ProviderRepository'
import { SecretStore } from '../../storage/SecretStore'
import { SettingsRepository } from '../../storage/SettingsRepository'
import { deleteDatabase, openDatabase, type ChatUiDB } from '../../storage/db'
import { ConversationService } from '../conversations'
import { ModelService, ProviderService } from '../providers'
import { SettingsService } from '../settings'
import { OnboardingService } from './onboardingService'
import { OnboardingError } from './types'

const modelsFixture: Record<string, unknown> = JSON.parse(
  readFileSync(
    resolve(process.cwd(), '../contracts/fixtures/models/openrouter-models.json'),
    'utf8',
  ),
)

const keyFixture: Record<string, unknown> = JSON.parse(
  readFileSync(resolve(process.cwd(), '../contracts/fixtures/models/openrouter-key.json'), 'utf8'),
)

const attribution = { origin: 'https://chat.example', title: 'ChatUI' }
const server = setupServer()

interface TestContext {
  db: IDBPDatabase<ChatUiDB>
  secrets: SecretStore
  providers: ProviderRepository
  models: ModelRepository
  settings: SettingsService
  conversations: ConversationService
  service: OnboardingService
}

async function createTestContext(): Promise<TestContext> {
  const db = await openDatabase()
  const secrets = new SecretStore({
    db,
    crypto: createCrypto({ pbkdf2Iterations: 1000 }),
  })
  const providers = new ProviderRepository(db)
  const models = new ModelRepository(db)
  const settings = new SettingsService(new SettingsRepository(db))
  const modelService = new ModelService({
    providers,
    models,
    adapter: openRouterAdapter,
    fetch: globalThis.fetch,
    attribution,
    catalogCache: new MemoryCatalogCache(),
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
  const catalogCache = new MemoryCatalogCache()
  const service = new OnboardingService({
    providers: providerService,
    models: modelService,
    settings,
    conversations,
    loadCatalog: (signal) =>
      openRouterAdapter.listModels(
        {
          provider: { id: 'openrouter', baseUrl: 'https://openrouter.ai/api/v1' },
          apiKey: null,
          fetch: globalThis.fetch,
          attribution,
        },
        signal,
        { forceRefresh: true, cache: catalogCache },
      ),
  })

  return { db, secrets, providers, models, settings, conversations, service }
}

describe('OnboardingService', () => {
  let context: TestContext

  beforeAll(() => {
    server.listen({ onUnhandledFrame: 'error' })
  })

  afterAll(() => {
    server.close()
  })

  beforeEach(async () => {
    await deleteDatabase()
    context = await createTestContext()
  })

  afterEach(async () => {
    server.resetHandlers()
    context.db.close()
    await deleteDatabase()
  })

  it('loads the public catalog without a key', async () => {
    server.use(
      http.get('https://openrouter.ai/api/v1/models', () =>
        HttpResponse.json(modelsFixture),
      ),
    )

    const models = await context.service.loadCatalog(new AbortController().signal)

    expect(models).toHaveLength(8)
    expect(models[0]?.remoteId).toEqual(expect.any(String))
  })

  it('rejects an invalid key, stores nothing, and preserves defaults', async () => {
    server.use(
      http.get('https://openrouter.ai/api/v1/key', () =>
        HttpResponse.json(
          { error: { message: 'Invalid API key sk-test-key' } },
          { status: 401 },
        ),
      ),
    )

    await expect(
      context.service.connect({
        apiKey: 'sk-test-key',
        label: 'Work',
        rememberKeys: true,
      }),
    ).rejects.toMatchObject({ code: 'auth' })

    expect(await context.providers.list()).toEqual([])
    expect(await context.settings.getOnboardingCompleted()).toBe(false)
    expect(await context.settings.getDefaults()).toMatchObject({
      providerId: null,
      modelId: null,
    })

    const secretKeys = await context.db.getAllKeys('secrets')
    expect(secretKeys.filter((key) => key.startsWith('remember:'))).toEqual([])
  })

  it('accepts a valid key, loads models, selects a default, and creates a chat', async () => {
    server.use(
      http.get('https://openrouter.ai/api/v1/key', () => HttpResponse.json(keyFixture)),
      http.get('https://openrouter.ai/api/v1/models', () =>
        HttpResponse.json(modelsFixture),
      ),
    )

    const result = await context.service.connect({
      apiKey: 'sk-test-key',
      label: 'Work',
      rememberKeys: true,
    })

    expect(result.conversationId).toEqual(expect.any(String))
    expect(result.notice).toContain('Default model openrouter/auto is unavailable')

    const providers = await context.providers.list()
    expect(providers).toHaveLength(1)
    const provider = providers[0]
    expect(provider).toMatchObject({
      presetId: 'openrouter',
      label: 'Work',
      enabled: true,
    })
    expect(JSON.stringify(provider)).not.toContain('sk-test-key')
    expect(provider.metadata?.key).toMatchObject({
      label: 'Example OpenRouter Key',
      usageUsd: 12.34,
    })
    expect(await context.secrets.getSecret(provider.secretId ?? '')).toBe('sk-test-key')

    const models = await context.models.list({ providerId: provider.id })
    expect(models).toHaveLength(8)

    const settings = await context.settings.get()
    expect(settings.onboardingCompleted).toBe(true)
    expect(settings.defaultProviderId).toBe(provider.id)
    expect(settings.defaultModelId).toEqual(expect.any(String))

    const conversations = await context.conversations.listConversations()
    expect(conversations.items).toHaveLength(1)
    expect(conversations.items[0]).toMatchObject({
      id: result.conversationId,
      providerId: provider.id,
      modelId: settings.defaultModelId,
    })
  })

  it('rolls back the provider when no model is available', async () => {
    server.use(
      http.get('https://openrouter.ai/api/v1/key', () => HttpResponse.json(keyFixture)),
      http.get('https://openrouter.ai/api/v1/models', () =>
        HttpResponse.json({ data: [] }),
      ),
    )

    await expect(
      context.service.connect({
        apiKey: 'sk-test-key',
        label: 'Work',
        rememberKeys: true,
      }),
    ).rejects.toBeInstanceOf(OnboardingError)

    expect(await context.providers.list()).toEqual([])
    expect(await context.settings.getOnboardingCompleted()).toBe(false)
  })

  it('rejects an empty key before creating a provider', async () => {
    await expect(
      context.service.connect({ apiKey: '   ', label: '', rememberKeys: true }),
    ).rejects.toMatchObject({ code: 'invalid_input' })

    expect(await context.providers.list()).toEqual([])
  })

  it('propagates adapter errors without exposing the key', async () => {
    server.use(
      http.get('https://openrouter.ai/api/v1/key', () =>
        HttpResponse.json(
          { error: { message: 'Invalid API key sk-test-key' } },
          { status: 401 },
        ),
      ),
    )

    const error = await context.service
      .connect({ apiKey: 'sk-test-key', label: '', rememberKeys: true })
      .catch((cause: unknown) => cause)

    expect(error).toBeInstanceOf(OpenRouterRequestError)
    expect((error as OpenRouterRequestError).message).not.toContain('sk-test-key')
  })
})
