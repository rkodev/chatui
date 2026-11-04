import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { newId, nowIso, type Model, type Provider } from '../../domain'
import { openRouterAdapter } from '../../providers/openrouter'
import { deleteDatabase, openDatabase, type ChatUiDB } from '../../storage/db'
import { ModelRepository } from '../../storage/ModelRepository'
import { ProviderRepository } from '../../storage/ProviderRepository'
import type { SecretMode } from '../../storage/SecretStore'
import { getProviderKeyMetadata, ProviderService } from './providerService'
import type { ProviderStore, SecretStoreLike } from './types'

class FakeSecretStore implements SecretStoreLike {
  readonly values = new Map<string, { value: string; mode: SecretMode }>()

  async setSecret(id: string, value: string, mode: SecretMode): Promise<void> {
    this.values.set(id, { value, mode })
  }

  async getSecret(id: string): Promise<string | null> {
    return this.values.get(id)?.value ?? null
  }

  async deleteSecret(id: string): Promise<void> {
    this.values.delete(id)
  }
}

const server = setupServer()

describe('ProviderService', () => {
  let db: IDBPDatabase<ChatUiDB>
  let secrets: FakeSecretStore
  let providers: ProviderRepository
  let models: ModelRepository
  let service: ProviderService

  beforeAll(() => {
    server.listen({ onUnhandledFrame: 'error' })
  })

  afterAll(() => {
    server.close()
  })

  beforeEach(async () => {
    await deleteDatabase()
    db = await openDatabase()
    secrets = new FakeSecretStore()
    providers = new ProviderRepository(db)
    models = new ModelRepository(db)
    service = new ProviderService({
      providers,
      secrets,
      adapter: openRouterAdapter,
      fetch: globalThis.fetch,
      attribution: { origin: 'https://chat.example', title: 'ChatUI' },
      allowCustomProviders: true,
    })
  })

  afterEach(async () => {
    server.resetHandlers()
    db.close()
    await deleteDatabase()
  })

  it('creates an OpenRouter provider without putting the key in the record', async () => {
    const provider = await service.createOpenRouterProvider({
      apiKey: 'sk-test-key',
      label: 'Work',
      rememberKeys: true,
    })

    expect(provider).toMatchObject({
      presetId: 'openrouter',
      label: 'Work',
      kind: 'openai-compatible',
      baseUrl: 'https://openrouter.ai/api/v1',
      authType: 'bearer',
      enabled: true,
      browserCors: 'supported',
      metadata: null,
    })
    expect(provider.secretId).toEqual(expect.any(String))
    expect(JSON.stringify(provider)).not.toContain('sk-test-key')
    expect(secrets.values.get(provider.secretId ?? '')).toEqual({
      value: 'sk-test-key',
      mode: 'remember',
    })
  })

  it('uses session mode when rememberKeys is false', async () => {
    const provider = await service.createOpenRouterProvider({
      apiKey: 'sk-session',
      rememberKeys: false,
    })

    expect(secrets.values.get(provider.secretId ?? '')).toEqual({
      value: 'sk-session',
      mode: 'session',
    })
  })

  it('validates the stored key and caches normalized metadata', async () => {
    server.use(
      http.get('https://openrouter.ai/api/v1/key', () =>
        HttpResponse.json({
          data: {
            label: 'Work key',
            usage: 1.25,
            limit: 10,
            limit_remaining: 8.75,
            is_free_tier: false,
            rate_limit: { requests: 60 },
          },
        }),
      ),
    )

    const provider = await service.createOpenRouterProvider({ apiKey: 'sk-test-key' })
    const updated = await service.validateProviderKey(provider.id)

    expect(getProviderKeyMetadata(updated)).toMatchObject({
      label: 'Work key',
      usageUsd: 1.25,
      limitUsd: 10,
      limitRemainingUsd: 8.75,
      isFreeTier: false,
      rateLimit: { requests: 60 },
    })
    expect(getProviderKeyMetadata(updated)?.validatedAt).toEqual(expect.any(String))
    expect(JSON.stringify(updated)).not.toContain('sk-test-key')
  })

  it('propagates adapter errors without exposing the key', async () => {
    server.use(
      http.get('https://openrouter.ai/api/v1/key', () =>
        HttpResponse.json({ error: { message: 'Invalid API key sk-test-key' } }, { status: 401 }),
      ),
    )

    const provider = await service.createOpenRouterProvider({ apiKey: 'sk-test-key' })

    await expect(service.validateProviderKey(provider.id)).rejects.toMatchObject({
      code: 'auth',
      status: 401,
    })
  })

  it('reports a missing key before calling the adapter', async () => {
    const provider = await service.createOpenRouterProvider({ apiKey: 'sk-test-key' })
    await service.removeKey(provider.id)

    await expect(service.validateProviderKey(provider.id)).rejects.toMatchObject({
      code: 'missing_key',
    })
  })

  it('rotates the key, removes the old secret, and clears stale metadata', async () => {
    server.use(
      http.get('https://openrouter.ai/api/v1/key', () =>
        HttpResponse.json({ data: { label: 'Old key' } }),
      ),
    )

    const provider = await service.createOpenRouterProvider({ apiKey: 'sk-old' })
    const oldSecretId = provider.secretId ?? ''
    await service.validateProviderKey(provider.id)

    const rotated = await service.rotateKey(provider.id, 'sk-new', false)

    expect(rotated.secretId).not.toBe(oldSecretId)
    expect(secrets.values.has(oldSecretId)).toBe(false)
    expect(secrets.values.get(rotated.secretId ?? '')).toEqual({
      value: 'sk-new',
      mode: 'session',
    })
    expect(getProviderKeyMetadata(rotated)).toBeNull()
  })

  it('removes the key and clears cached metadata', async () => {
    server.use(
      http.get('https://openrouter.ai/api/v1/key', () =>
        HttpResponse.json({ data: { label: 'Work key' } }),
      ),
    )

    const provider = await service.createOpenRouterProvider({ apiKey: 'sk-test-key' })
    const secretId = provider.secretId ?? ''
    await service.validateProviderKey(provider.id)

    const removed = await service.removeKey(provider.id)

    expect(removed.secretId).toBeNull()
    expect(secrets.values.has(secretId)).toBe(false)
    expect(getProviderKeyMetadata(removed)).toBeNull()
  })

  it('toggles enabled state', async () => {
    const provider = await service.createOpenRouterProvider({ apiKey: 'sk-test-key' })

    const disabled = await service.setProviderEnabled(provider.id, false)

    expect(disabled.enabled).toBe(false)
    expect((await service.getProvider(provider.id))?.enabled).toBe(false)
  })

  it('deletes the provider, its models, and its secret', async () => {
    const provider = await service.createOpenRouterProvider({ apiKey: 'sk-test-key' })
    const secretId = provider.secretId ?? ''
    const now = nowIso()
    const model: Model = {
      id: newId(),
      providerId: provider.id,
      remoteId: 'openai/gpt-4o-mini-2024-07-18',
      label: 'GPT-4o mini',
      contextWindow: 128000,
      maxOutputTokens: 16384,
      capabilities: ['text'],
      pricing: null,
      canonicalSlug: null,
      inputModalities: ['text'],
      outputModalities: ['text'],
      supportedParameters: [],
      metadata: null,
      hidden: false,
      pinned: false,
      source: 'discovered',
      createdAt: now,
      updatedAt: now,
    }
    await models.create(model)

    await service.deleteProvider(provider.id)

    expect(await service.getProvider(provider.id)).toBeNull()
    expect(await models.get(model.id)).toBeNull()
    expect(secrets.values.has(secretId)).toBe(false)
  })

  it('never returns the key from list or get methods', async () => {
    const provider = await service.createOpenRouterProvider({ apiKey: 'sk-test-key' })

    const listed = await service.listProviders()
    const fetched = await service.getProvider(provider.id)

    expect(JSON.stringify(listed)).not.toContain('sk-test-key')
    expect(JSON.stringify(fetched)).not.toContain('sk-test-key')
  })

  it('creates a custom provider with non-secret headers and a separate secret', async () => {
    const provider = await service.createCustomProvider({
      label: 'Local',
      baseUrl: 'https://api.example.com/v1',
      authType: 'bearer',
      apiKey: 'sk-custom',
      headers: { 'X-Organization': 'team' },
    })

    expect(provider).toMatchObject({
      presetId: 'custom',
      label: 'Local',
      kind: 'openai-compatible',
      baseUrl: 'https://api.example.com/v1',
      authType: 'bearer',
      headers: { 'X-Organization': 'team' },
      enabled: true,
      browserCors: 'unknown',
      metadata: null,
    })
    expect(provider.secretId).toEqual(expect.any(String))
    expect(JSON.stringify(provider)).not.toContain('sk-custom')
    expect(secrets.values.get(provider.secretId ?? '')).toEqual({
      value: 'sk-custom',
      mode: 'remember',
    })
  })

  it('creates a custom provider without a secret when auth is none', async () => {
    const provider = await service.createCustomProvider({
      baseUrl: 'http://localhost:8080/v1',
      authType: 'none',
    })

    expect(provider.secretId).toBeNull()
    expect(provider.authType).toBe('none')
    expect(secrets.values.size).toBe(0)
  })

  it('rejects an invalid custom base URL', async () => {
    await expect(
      service.createCustomProvider({
        baseUrl: 'not-a-url',
        authType: 'bearer',
        apiKey: 'sk-custom',
      }),
    ).rejects.toMatchObject({
      code: 'invalid_input',
      message: expect.stringContaining('http(s) base URL'),
    })
  })

  it('rejects a custom provider without an auth style', async () => {
    await expect(
      service.createCustomProvider({
        baseUrl: 'https://api.example.com/v1',
        authType: undefined as never,
        apiKey: 'sk-custom',
      }),
    ).rejects.toMatchObject({
      code: 'invalid_input',
      message: expect.stringContaining('Choose an authentication style'),
    })
  })

  it('rejects a custom provider without a key for key-based auth', async () => {
    await expect(
      service.createCustomProvider({
        baseUrl: 'https://api.example.com/v1',
        authType: 'bearer',
      }),
    ).rejects.toMatchObject({
      code: 'invalid_input',
      message: expect.stringContaining('Enter an API key'),
    })
  })

  it('rejects authentication headers in custom headers', async () => {
    await expect(
      service.createCustomProvider({
        baseUrl: 'https://api.example.com/v1',
        authType: 'bearer',
        apiKey: 'sk-custom',
        headers: { Authorization: 'Bearer sk-custom' },
      }),
    ).rejects.toMatchObject({
      code: 'invalid_input',
      message: expect.stringContaining('reserved for authentication'),
    })
  })

  it('rejects duplicate provider labels', async () => {
    await service.createCustomProvider({
      label: 'Local',
      baseUrl: 'https://api.example.com/v1',
      authType: 'bearer',
      apiKey: 'sk-custom',
    })

    await expect(
      service.createCustomProvider({
        label: 'local',
        baseUrl: 'https://api.example.com/v2',
        authType: 'bearer',
        apiKey: 'sk-custom-2',
      }),
    ).rejects.toMatchObject({
      code: 'invalid_input',
      message: expect.stringContaining('already exists'),
    })
  })

  it('creates a provider from another enabled preset', async () => {
    const provider = await service.createPresetProvider({
      presetId: 'openai',
      apiKey: 'sk-openai',
      label: 'OpenAI work',
    })

    expect(provider).toMatchObject({
      presetId: 'openai',
      label: 'OpenAI work',
      baseUrl: 'https://api.openai.com/v1',
      authType: 'bearer',
    })
    expect(secrets.values.get(provider.secretId ?? '')).toEqual({
      value: 'sk-openai',
      mode: 'remember',
    })
  })

  it('creates a keyless provider for a no-auth preset', async () => {
    const provider = await service.createPresetProvider({ presetId: 'lmstudio' })

    expect(provider).toMatchObject({
      presetId: 'lmstudio',
      authType: 'none',
      secretId: null,
    })
    expect(secrets.values.size).toBe(0)
  })

  it('rejects presets that are not available in M2', async () => {
    await expect(
      service.createPresetProvider({ presetId: 'anthropic', apiKey: 'sk-ant' }),
    ).rejects.toMatchObject({
      code: 'invalid_input',
      message: expect.stringContaining('not available in M2'),
    })
  })

  it('rejects presets disabled by the runtime allow-list', async () => {
    const restricted = new ProviderService({
      providers,
      secrets,
      adapter: openRouterAdapter,
      enabledPresetIds: ['openrouter'],
      allowCustomProviders: false,
    })

    await expect(
      restricted.createPresetProvider({ presetId: 'openai', apiKey: 'sk-openai' }),
    ).rejects.toMatchObject({
      code: 'invalid_input',
      message: expect.stringContaining('not enabled in this deployment'),
    })
  })

  it('rejects custom creation when the deployment disables it', async () => {
    const restricted = new ProviderService({
      providers,
      secrets,
      adapter: openRouterAdapter,
      allowCustomProviders: false,
    })

    await expect(
      restricted.createCustomProvider({
        baseUrl: 'https://api.example.com/v1',
        authType: 'bearer',
        apiKey: 'sk-custom',
      }),
    ).rejects.toMatchObject({
      code: 'invalid_input',
      message: expect.stringContaining('not enabled in this deployment'),
    })
  })

  it('rejects the custom preset through the generic preset path', async () => {
    await expect(
      service.createPresetProvider({ presetId: 'custom', apiKey: 'sk-custom' }),
    ).rejects.toMatchObject({
      code: 'invalid_input',
      message: expect.stringContaining('createCustomProvider'),
    })
  })
})

class FakeProviderStore implements ProviderStore {
  readonly providers = new Map<string, Provider>()

  async create(provider: Provider): Promise<Provider> {
    this.providers.set(provider.id, provider)
    return provider
  }

  async get(id: string): Promise<Provider | null> {
    return this.providers.get(id) ?? null
  }

  async list(): Promise<Provider[]> {
    return [...this.providers.values()]
  }

  async update(
    id: string,
    patch: Partial<Omit<Provider, 'id' | 'createdAt'>>,
  ): Promise<Provider> {
    const existing = this.providers.get(id)

    if (!existing) {
      throw new Error(`Provider ${id} not found`)
    }

    const updated: Provider = {
      ...existing,
      ...patch,
      id,
      createdAt: existing.createdAt,
      updatedAt: patch.updatedAt ?? nowIso(),
    }
    this.providers.set(id, updated)
    return updated
  }

  async delete(id: string): Promise<void> {
    this.providers.delete(id)
  }
}

class FailingProviderStore extends FakeProviderStore {
  async create(): Promise<Provider> {
    throw new Error('provider write failed')
  }
}

class FailingSecretStore extends FakeSecretStore {
  async setSecret(): Promise<void> {
    throw new Error('secret write failed')
  }
}

describe('ProviderService rollback', () => {
  it('removes the secret when the provider write fails', async () => {
    const providers = new FailingProviderStore()
    const secrets = new FakeSecretStore()
    const service = new ProviderService({
      providers,
      secrets,
      adapter: openRouterAdapter,
      allowCustomProviders: true,
    })

    await expect(
      service.createCustomProvider({
        baseUrl: 'https://api.example.com/v1',
        authType: 'bearer',
        apiKey: 'sk-custom',
      }),
    ).rejects.toMatchObject({ code: 'storage' })

    expect(providers.providers.size).toBe(0)
    expect(secrets.values.size).toBe(0)
  })

  it('does not write a provider when the secret write fails', async () => {
    const providers = new FakeProviderStore()
    const secrets = new FailingSecretStore()
    const service = new ProviderService({
      providers,
      secrets,
      adapter: openRouterAdapter,
      allowCustomProviders: true,
    })

    await expect(
      service.createCustomProvider({
        baseUrl: 'https://api.example.com/v1',
        authType: 'bearer',
        apiKey: 'sk-custom',
      }),
    ).rejects.toMatchObject({ code: 'storage' })

    expect(providers.providers.size).toBe(0)
  })
})
