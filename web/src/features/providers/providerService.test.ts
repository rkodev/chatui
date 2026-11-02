import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { newId, nowIso, type Model } from '../../domain'
import { openRouterAdapter } from '../../providers/openrouter'
import { deleteDatabase, openDatabase, type ChatUiDB } from '../../storage/db'
import { ModelRepository } from '../../storage/ModelRepository'
import { ProviderRepository } from '../../storage/ProviderRepository'
import type { SecretMode } from '../../storage/SecretStore'
import { getProviderKeyMetadata, ProviderService } from './providerService'
import type { SecretStoreLike } from './types'

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
      browserCors: 'unknown',
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
})
