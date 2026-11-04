import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import {
  MemoryCatalogCache,
  OPENROUTER_CATALOG_TTL_MS,
  OpenRouterCatalogError,
  createLocalStorageCatalogCache,
  isCatalogFresh,
  mapOpenRouterKey,
  mapOpenRouterModel,
  mapOpenRouterModels,
  multiplyDecimalByMillion,
  openRouterCatalogCacheKey,
  usdAmount,
  usdPerMillion,
  type CatalogCache,
  type CatalogSnapshot,
  type ModelInfo,
} from './openrouterCatalog'
import {
  OpenRouterRequestError,
  listModels,
  loadCatalog,
  refreshCatalog,
  validateKey,
  type ProviderContext,
} from './openrouter'

const modelsFixture: unknown = JSON.parse(
  readFileSync(
    resolve(process.cwd(), '../contracts/fixtures/models/openrouter-models.json'),
    'utf8',
  ),
)

const keyFixture: unknown = JSON.parse(
  readFileSync(resolve(process.cwd(), '../contracts/fixtures/models/openrouter-key.json'), 'utf8'),
)

const provider: ProviderContext['provider'] = {
  id: 'openrouter',
  baseUrl: 'https://openrouter.ai/api/v1',
}

const attribution = {
  origin: 'https://chat.example.com',
  title: 'ChatUI',
}

function makeContext(
  fetchImpl: typeof fetch,
  apiKey: string | null = 'sk-test-key',
): ProviderContext {
  return { provider, apiKey, fetch: fetchImpl, attribution }
}

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

function modelByRemoteId(models: readonly ModelInfo[], remoteId: string): ModelInfo {
  const model = models.find((entry) => entry.remoteId === remoteId)
  if (!model) {
    throw new Error(`Missing fixture model ${remoteId}`)
  }
  return model
}

describe('OpenRouter catalog mapping', () => {
  const models = mapOpenRouterModels(modelsFixture)

  it('maps every fixture entry', () => {
    expect(models).toHaveLength(8)
  })

  it('maps a paid text and tool model', () => {
    const model = modelByRemoteId(models, 'openai/gpt-4o-mini-2024-07-18')

    expect(model).toMatchObject({
      remoteId: 'openai/gpt-4o-mini-2024-07-18',
      canonicalSlug: 'openai/gpt-4o-mini',
      label: 'OpenAI: GPT-4o mini (2024-07-18)',
      contextWindow: 128000,
      maxOutputTokens: 16384,
      inputModalities: ['text'],
      outputModalities: ['text'],
      isFree: false,
    })
    expect(model.capabilities).toEqual(['text', 'tools', 'streaming'])
    expect(model.pricing).toEqual({
      inputPerMTok: 0.15,
      outputPerMTok: 0.6,
      perRequest: 0,
      currency: 'USD',
    })
    expect(model.metadata).toMatchObject({
      architecture: { modality: 'text->text', tokenizer: 'GPT' },
      pricing: {
        prompt: '0.00000015',
        completion: '0.0000006',
        request: '0',
      },
      topProvider: { isModerated: false },
    })
  })

  it('marks a zero-priced :free model and preserves per-request limits', () => {
    const model = modelByRemoteId(models, 'meta-llama/llama-3.2-3b-instruct:free')

    expect(model.isFree).toBe(true)
    expect(model.pricing).toEqual({
      inputPerMTok: 0,
      outputPerMTok: 0,
      perRequest: 0,
      currency: 'USD',
    })
    expect(model.capabilities).toEqual(['text', 'streaming'])
    expect(model.metadata).toMatchObject({
      perRequestLimits: { requests_per_minute: 20 },
    })
  })

  it('infers vision and file capabilities from modalities', () => {
    const model = modelByRemoteId(models, 'google/gemini-2.5-flash')

    expect(model.inputModalities).toEqual(['text', 'image', 'file'])
    expect(model.outputModalities).toEqual(['text'])
    expect(model.capabilities).toEqual([
      'text',
      'vision',
      'files',
      'tools',
      'reasoning',
      'streaming',
    ])
    expect(model.pricing).toEqual({
      inputPerMTok: 0.3,
      outputPerMTok: 2.5,
      perRequest: 0,
      currency: 'USD',
    })
    expect(model.metadata).toMatchObject({
      defaultParameters: { temperature: 0.7, top_p: 0.95 },
      topProvider: { isModerated: true },
    })
  })

  it('infers reasoning from supported parameters and keeps raw reasoning pricing', () => {
    const model = modelByRemoteId(models, 'deepseek/deepseek-r1')

    expect(model.capabilities).toEqual(['text', 'reasoning', 'streaming'])
    expect(model.metadata).toMatchObject({
      pricing: { internal_reasoning: '0.00000219' },
    })
  })

  it('maps a tool-capable model', () => {
    const model = modelByRemoteId(models, 'anthropic/claude-sonnet-4.5')

    expect(model.capabilities).toEqual([
      'text',
      'vision',
      'files',
      'tools',
      'reasoning',
      'streaming',
    ])
  })

  it('maps a long-context model', () => {
    const model = modelByRemoteId(models, 'google/gemini-2.5-pro')

    expect(model.contextWindow).toBe(1048576)
    expect(model.maxOutputTokens).toBe(65536)
  })

  it('converts unusual decimal pricing without binary-float artifacts', () => {
    const model = modelByRemoteId(models, 'example/unusual-pricing')

    expect(model.pricing).toEqual({
      inputPerMTok: 0.000001,
      outputPerMTok: 0.000002,
      perRequest: 0.0005,
      currency: 'USD',
    })
    expect(model.metadata).toMatchObject({
      pricing: {
        prompt: '0.000000000001',
        completion: '0.000000000002',
        request: '0.0005',
        image: '0.0000001',
      },
    })
    expect(model.isFree).toBe(false)
  })

  it('ignores unknown fields but keeps small non-secret metadata', () => {
    const model = modelByRemoteId(models, 'example/unknown-fields')

    expect(model.metadata).toMatchObject({
      x_provider_note: 'ignore this unknown field',
      future_capability_flags: { synthetic: true },
    })
  })

  it('skips malformed entries instead of failing the catalog', () => {
    const mapped = mapOpenRouterModels({
      data: [
        null,
        {},
        { id: '' },
        { id: 'example/minimal', name: '' },
        {
          id: 'example/fallback',
          context_length: 0,
          top_provider: { context_length: 100, max_completion_tokens: 0 },
        },
      ],
    })

    expect(mapped).toHaveLength(2)
    expect(mapped[0]).toMatchObject({
      remoteId: 'example/minimal',
      label: 'example/minimal',
      contextWindow: null,
      maxOutputTokens: null,
      capabilities: [],
      pricing: null,
      canonicalSlug: null,
      inputModalities: [],
      outputModalities: [],
      supportedParameters: [],
      isFree: false,
    })
    expect(mapped[1]).toMatchObject({
      remoteId: 'example/fallback',
      contextWindow: 100,
      maxOutputTokens: 100,
    })
  })

  it('accepts bare arrays and models envelopes', () => {
    expect(mapOpenRouterModels([{ id: 'example/array' }])).toHaveLength(1)
    expect(mapOpenRouterModels({ models: [{ id: 'example/models' }] })).toHaveLength(1)
  })

  it('rejects a malformed envelope', () => {
    expect(() => mapOpenRouterModels({})).toThrow(OpenRouterCatalogError)
    expect(() => mapOpenRouterModels({ data: 'nope' })).toThrow(OpenRouterCatalogError)
  })

  it('returns null for a non-object model entry', () => {
    expect(mapOpenRouterModel(null)).toBeNull()
    expect(mapOpenRouterModel('model')).toBeNull()
  })

  it('infers modalities from the architecture modality string', () => {
    const model = mapOpenRouterModel({
      id: 'example/modality',
      architecture: { modality: 'text+image->text' },
    })

    expect(model?.inputModalities).toEqual(['text', 'image'])
    expect(model?.outputModalities).toEqual(['text'])
    expect(model?.capabilities).toEqual(['text', 'vision'])
  })

  it('deduplicates supported parameters', () => {
    const model = mapOpenRouterModel({
      id: 'example/params',
      supported_parameters: ['stream', 'stream', 'tools', 'tools'],
    })

    expect(model?.supportedParameters).toEqual(['stream', 'tools'])
    expect(model?.capabilities).toEqual(['tools', 'streaming'])
  })

  it('does not mark a model free when a non-token charge is non-zero', () => {
    const model = mapOpenRouterModel({
      id: 'example/paid-image',
      pricing: {
        prompt: '0',
        completion: '0',
        request: '0',
        image: '0.0001',
      },
    })

    expect(model?.isFree).toBe(false)
  })

  it('marks a :free suffix model free even when pricing is non-zero', () => {
    const model = mapOpenRouterModel({
      id: 'example/suffix:free',
      pricing: { prompt: '0.1', completion: '0.2' },
    })

    expect(model?.isFree).toBe(true)
  })

  it('does not copy secret-like unknown fields into metadata', () => {
    const model = mapOpenRouterModel({
      id: 'example/secret-metadata',
      api_key: 'must-not-be-copied',
      x_provider_note: 'safe',
    })

    expect(model?.metadata).toMatchObject({ x_provider_note: 'safe' })
    expect(model?.metadata).not.toHaveProperty('api_key')
  })
})

describe('OpenRouter key mapping', () => {
  it('maps the shared key fixture', () => {
    expect(mapOpenRouterKey(keyFixture)).toEqual({
      label: 'Example OpenRouter Key',
      usageUsd: 12.34,
      limitUsd: 100,
      limitRemainingUsd: 87.66,
      isFreeTier: false,
      rateLimit: { requests: 60, interval: '1m' },
    })
  })

  it('maps null and numeric-string fields', () => {
    expect(
      mapOpenRouterKey({
        data: {
          label: null,
          usage: null,
          limit: null,
          limit_remaining: null,
          is_free_tier: null,
          rate_limit: null,
        },
      }),
    ).toEqual({
      label: null,
      usageUsd: null,
      limitUsd: null,
      limitRemainingUsd: null,
      isFreeTier: null,
      rateLimit: null,
    })

    expect(mapOpenRouterKey({ data: { usage: '12.5', limit: '100' } })).toMatchObject({
      usageUsd: 12.5,
      limitUsd: 100,
    })
  })

  it('rejects a malformed key envelope', () => {
    expect(() => mapOpenRouterKey({})).toThrow(OpenRouterCatalogError)
    expect(() => mapOpenRouterKey({ data: null })).toThrow(OpenRouterCatalogError)
  })
})

describe('decimal price conversion', () => {
  it('multiplies decimal strings by one million exactly', () => {
    expect(multiplyDecimalByMillion('0.00000015')).toBe('0.15')
    expect(multiplyDecimalByMillion('0.0000006')).toBe('0.6')
    expect(multiplyDecimalByMillion('0.000000000001')).toBe('0.000001')
    expect(multiplyDecimalByMillion('0.1')).toBe('100000')
    expect(multiplyDecimalByMillion('1.5')).toBe('1500000')
    expect(multiplyDecimalByMillion('0')).toBe('0')
    expect(multiplyDecimalByMillion('-0.000001')).toBe('-1')
  })

  it('rejects invalid decimal strings', () => {
    expect(multiplyDecimalByMillion('abc')).toBeNull()
    expect(multiplyDecimalByMillion('1e-6')).toBeNull()
    expect(multiplyDecimalByMillion('')).toBeNull()
  })

  it('converts USD per token to USD per million', () => {
    expect(usdPerMillion('0.00000015')).toBe(0.15)
    expect(usdPerMillion('0.1')).toBe(100000)
    expect(usdPerMillion(0.1)).toBeNull()
    expect(usdPerMillion('abc')).toBeNull()
  })

  it('parses USD amounts without multiplying', () => {
    expect(usdAmount('0.0005')).toBe(0.0005)
    expect(usdAmount('0')).toBe(0)
    expect(usdAmount('abc')).toBeNull()
    expect(usdAmount(1)).toBeNull()
  })
})

describe('catalog cache', () => {
  it('uses a 24-hour TTL and a stable key', () => {
    expect(OPENROUTER_CATALOG_TTL_MS).toBe(24 * 60 * 60 * 1000)
    expect(openRouterCatalogCacheKey()).toBe('chatui.catalog.openrouter.v1')
  })

  it('reports freshness at the TTL boundary', () => {
    const snapshot: CatalogSnapshot = { fetchedAt: 1000, models: [] }

    expect(isCatalogFresh(snapshot, 1000 + OPENROUTER_CATALOG_TTL_MS - 1)).toBe(true)
    expect(isCatalogFresh(snapshot, 1000 + OPENROUTER_CATALOG_TTL_MS)).toBe(false)
  })

  it('clones snapshots in the memory cache', async () => {
    const cache = new MemoryCatalogCache()
    const snapshot: CatalogSnapshot = {
      fetchedAt: 1000,
      models: [mapOpenRouterModels(modelsFixture)[0]],
    }

    await cache.write('key', snapshot)
    const first = await cache.read('key')
    first?.models.pop()

    const second = await cache.read('key')
    expect(second?.models).toHaveLength(1)
  })

  it('round-trips through localStorage and ignores malformed data', async () => {
    const storage = new FakeStorage()
    const cache = createLocalStorageCatalogCache(storage)
    const snapshot: CatalogSnapshot = {
      fetchedAt: 1000,
      models: [mapOpenRouterModels(modelsFixture)[0]],
    }

    await cache.write('key', snapshot)
    expect(await cache.read('key')).toEqual(snapshot)

    storage.setItem('key', '{not json')
    expect(await cache.read('key')).toBeNull()
  })
})

class FakeStorage implements Storage {
  private readonly data = new Map<string, string>()

  get length(): number {
    return this.data.size
  }

  clear(): void {
    this.data.clear()
  }

  getItem(key: string): string | null {
    return this.data.get(key) ?? null
  }

  key(index: number): string | null {
    return [...this.data.keys()][index] ?? null
  }

  removeItem(key: string): void {
    this.data.delete(key)
  }

  setItem(key: string, value: string): void {
    this.data.set(key, value)
  }
}

describe('OpenRouter key validation', () => {
  it('sends GET /key with bearer auth and attribution headers', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(keyFixture))
    const context = makeContext(fetchMock as unknown as typeof fetch)

    const info = await validateKey(context, new AbortController().signal)

    expect(info).toEqual({
      label: 'Example OpenRouter Key',
      usageUsd: 12.34,
      limitUsd: 100,
      limitRemainingUsd: 87.66,
      isFreeTier: false,
      rateLimit: { requests: 60, interval: '1m' },
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const [url, init] = fetchMock.mock.calls[0] as unknown as [RequestInfo | URL, RequestInit?]
    expect(url).toBe('https://openrouter.ai/api/v1/key')
    expect(String(url)).not.toContain('sk-test-key')
    expect(init?.method).toBe('GET')

    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe('Bearer sk-test-key')
    expect(headers.get('HTTP-Referer')).toBe('https://chat.example.com')
    expect(headers.get('X-Title')).toBe('ChatUI')
  })

  it('calls fetch without a receiver so browsers do not throw Illegal invocation', async () => {
    const fetchMock = vi.fn(function (this: unknown) {
      if (this !== undefined) {
        throw new TypeError("Failed to execute 'fetch' on 'Window': Illegal invocation")
      }
      return Promise.resolve(jsonResponse(keyFixture))
    })

    const info = await validateKey(
      makeContext(fetchMock as unknown as typeof fetch),
      new AbortController().signal,
    )

    expect(info.label).toBe('Example OpenRouter Key')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('rejects a missing key before making a request', async () => {
    const fetchMock = vi.fn()
    const context = makeContext(fetchMock as unknown as typeof fetch, null)

    await expect(validateKey(context, new AbortController().signal)).rejects.toMatchObject({
      code: 'auth',
    })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('maps an HTTP 401 and redacts the key from the message', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ error: { message: 'Invalid key sk-test-key' } }, 401),
    )
    const context = makeContext(fetchMock as unknown as typeof fetch)

    await expect(validateKey(context, new AbortController().signal)).rejects.toMatchObject({
      code: 'auth',
      status: 401,
      retryable: false,
      message: 'Invalid key [redacted]',
    })
  })

  it('maps a network failure', async () => {
    const fetchMock = vi.fn(async () => {
      throw new Error('offline')
    })
    const context = makeContext(fetchMock as unknown as typeof fetch)

    await expect(validateKey(context, new AbortController().signal)).rejects.toMatchObject({
      code: 'network',
      retryable: true,
    })
  })

  it('maps an abort to cancelled', async () => {
    const controller = new AbortController()
    const fetchMock = vi.fn(async () => {
      controller.abort()
      throw new Error('aborted')
    })
    const context = makeContext(fetchMock as unknown as typeof fetch)

    await expect(validateKey(context, controller.signal)).rejects.toMatchObject({
      code: 'cancelled',
    })
  })

  it('maps invalid JSON and malformed key payloads to parse errors', async () => {
    const invalidJson = vi.fn(async () => new Response('not json', { status: 200 }))
    await expect(
      validateKey(
        makeContext(invalidJson as unknown as typeof fetch),
        new AbortController().signal,
      ),
    ).rejects.toMatchObject({ code: 'parse' })

    const malformed = vi.fn(async () => jsonResponse({}))
    await expect(
      validateKey(makeContext(malformed as unknown as typeof fetch), new AbortController().signal),
    ).rejects.toMatchObject({ code: 'parse' })
  })
})

describe('OpenRouter catalog loading', () => {
  it('loads the public catalog without sending the key', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(modelsFixture))
    const context = makeContext(fetchMock as unknown as typeof fetch)
    const cache = new MemoryCatalogCache()

    const models = await listModels(context, new AbortController().signal, {
      cache,
      now: () => 1000,
    })

    expect(models).toHaveLength(8)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const [url, init] = fetchMock.mock.calls[0] as unknown as [RequestInfo | URL, RequestInit?]
    expect(url).toBe('https://openrouter.ai/api/v1/models')
    expect(String(url)).not.toContain('sk-test-key')

    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBeNull()
    expect(headers.get('HTTP-Referer')).toBe('https://chat.example.com')
    expect(headers.get('X-Title')).toBe('ChatUI')
  })

  it('returns a fresh cache entry without fetching', async () => {
    const cache = new MemoryCatalogCache()
    const cachedModel = mapOpenRouterModels(modelsFixture)[0]
    await cache.write(openRouterCatalogCacheKey(), {
      fetchedAt: 1000,
      models: [cachedModel],
    })

    const fetchMock = vi.fn(async () => {
      throw new Error('should not fetch')
    })
    const context = makeContext(fetchMock as unknown as typeof fetch)

    const result = await loadCatalog(context, new AbortController().signal, {
      cache,
      now: () => 1000 + OPENROUTER_CATALOG_TTL_MS - 1,
    })

    expect(result).toMatchObject({
      fromCache: true,
      stale: false,
      refreshError: null,
    })
    expect(result.models).toEqual([cachedModel])
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('refreshes an expired cache entry and writes the new snapshot', async () => {
    const cache = new MemoryCatalogCache()
    const cachedModel = mapOpenRouterModels(modelsFixture)[0]
    await cache.write(openRouterCatalogCacheKey(), {
      fetchedAt: 1000,
      models: [cachedModel],
    })

    const fetchMock = vi.fn(async () => jsonResponse(modelsFixture))
    const context = makeContext(fetchMock as unknown as typeof fetch)
    const now = 1000 + OPENROUTER_CATALOG_TTL_MS

    const result = await loadCatalog(context, new AbortController().signal, {
      cache,
      now: () => now,
    })

    expect(result.fromCache).toBe(false)
    expect(result.stale).toBe(false)
    expect(result.models).toHaveLength(8)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const snapshot = await cache.read(openRouterCatalogCacheKey())
    expect(snapshot?.fetchedAt).toBe(now)
    expect(snapshot?.models).toHaveLength(8)
  })

  it('forces a manual refresh even when the cache is fresh', async () => {
    const cache = new MemoryCatalogCache()
    const cachedModel = mapOpenRouterModels(modelsFixture)[0]
    await cache.write(openRouterCatalogCacheKey(), {
      fetchedAt: 1000,
      models: [cachedModel],
    })

    const fetchMock = vi.fn(async () => jsonResponse(modelsFixture))
    const context = makeContext(fetchMock as unknown as typeof fetch)

    const result = await refreshCatalog(context, new AbortController().signal, {
      cache,
      now: () => 1000,
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(result.fromCache).toBe(false)
    expect(result.models).toHaveLength(8)
  })

  it('returns the last-known-good catalog when refresh fails', async () => {
    const cache = new MemoryCatalogCache()
    const cachedModel = mapOpenRouterModels(modelsFixture)[0]
    await cache.write(openRouterCatalogCacheKey(), {
      fetchedAt: 1000,
      models: [cachedModel],
    })

    const fetchMock = vi.fn(async () => {
      throw new Error('offline')
    })
    const context = makeContext(fetchMock as unknown as typeof fetch)

    const result = await loadCatalog(context, new AbortController().signal, {
      cache,
      now: () => 1000 + OPENROUTER_CATALOG_TTL_MS,
    })

    expect(result.models).toEqual([cachedModel])
    expect(result.fromCache).toBe(true)
    expect(result.stale).toBe(true)
    expect(result.refreshError).toBeInstanceOf(OpenRouterRequestError)
    expect((result.refreshError as OpenRouterRequestError).code).toBe('network')
  })

  it('throws when refresh fails and no snapshot exists', async () => {
    const fetchMock = vi.fn(async () => {
      throw new Error('offline')
    })
    const context = makeContext(fetchMock as unknown as typeof fetch)

    await expect(
      loadCatalog(context, new AbortController().signal, {
        cache: new MemoryCatalogCache(),
        now: () => 1000,
      }),
    ).rejects.toMatchObject({ code: 'network' })
  })

  it('keeps a successful refresh when the cache write fails', async () => {
    const cache: CatalogCache = {
      read: async () => null,
      write: async () => {
        throw new Error('quota exceeded')
      },
    }
    const fetchMock = vi.fn(async () => jsonResponse(modelsFixture))
    const context = makeContext(fetchMock as unknown as typeof fetch)

    const result = await loadCatalog(context, new AbortController().signal, {
      cache,
      now: () => 1000,
    })

    expect(result.models).toHaveLength(8)
    expect(result.fromCache).toBe(false)
    expect(result.refreshError).toBeNull()
  })

  it('maps a malformed catalog response to a parse error', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ nope: true }))
    const context = makeContext(fetchMock as unknown as typeof fetch)

    await expect(
      listModels(context, new AbortController().signal, {
        cache: new MemoryCatalogCache(),
        now: () => 1000,
      }),
    ).rejects.toMatchObject({ code: 'parse' })
  })

  it('does not fall back to stale data when the refresh is aborted', async () => {
    const cache = new MemoryCatalogCache()
    await cache.write(openRouterCatalogCacheKey(), {
      fetchedAt: 1000,
      models: [mapOpenRouterModels(modelsFixture)[0]],
    })

    const controller = new AbortController()
    const fetchMock = vi.fn(async () => {
      controller.abort()
      throw new Error('aborted')
    })
    const context = makeContext(fetchMock as unknown as typeof fetch)

    await expect(
      loadCatalog(context, controller.signal, {
        cache,
        now: () => 1000 + OPENROUTER_CATALOG_TTL_MS,
      }),
    ).rejects.toMatchObject({ code: 'cancelled' })
  })
})
