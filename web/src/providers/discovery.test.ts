import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { MemoryCatalogCache } from './openrouterCatalog'
import {
  catalogCacheKey,
  loadCatalog,
  refreshCatalog,
  type ProviderContext,
} from './openaiCompatible'
import { getOpenAICompatiblePreset } from './presets'
import { M2_ENABLED_PRESET_IDS } from './registry'

const openAIModels = JSON.parse(
  readFileSync(resolve(process.cwd(), '../contracts/fixtures/models/openai-models.json'), 'utf8'),
) as unknown
const openRouterModels = JSON.parse(
  readFileSync(
    resolve(process.cwd(), '../contracts/fixtures/models/openrouter-models.json'),
    'utf8',
  ),
) as unknown

const API_KEY = 'sk-discovery-test'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function makeContext(
  presetId: string,
  fetchImpl: typeof fetch,
): ProviderContext {
  const preset = getOpenAICompatiblePreset(presetId)

  if (!preset) {
    throw new Error(`Missing preset ${presetId}`)
  }

  return {
    provider: { id: presetId, baseUrl: preset.baseUrl },
    apiKey: preset.auth.type === 'none' ? null : API_KEY,
    fetch: fetchImpl,
    preset,
  }
}

describe('per-preset model discovery', () => {
  it.each(M2_ENABLED_PRESET_IDS)('discovers models for the %s preset', async (presetId) => {
    const preset = getOpenAICompatiblePreset(presetId)

    if (!preset) {
      throw new Error(`Missing preset ${presetId}`)
    }

    const payload = preset.modelDiscovery.format === 'openrouter' ? openRouterModels : openAIModels
    const fetchMock = vi.fn(async () => jsonResponse(payload))
    const result = await loadCatalog(
      makeContext(presetId, fetchMock as unknown as typeof fetch),
      new AbortController().signal,
      { cache: new MemoryCatalogCache(), now: () => 1000 },
    )

    expect(result.models.length).toBeGreaterThan(0)
    expect(result.fromCache).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      RequestInfo | URL,
      RequestInit?,
    ]
    expect(url).toBe(
      `${preset.baseUrl.replace(/\/+$/, '')}/${preset.modelDiscovery.path.replace(/^\/+/, '')}`,
    )

    const headers = new Headers(init?.headers)

    if (preset.modelDiscovery.public || preset.auth.type === 'none') {
      expect(headers.get('Authorization')).toBeNull()
      expect(headers.get('x-api-key')).toBeNull()
    } else if (preset.auth.type === 'x-api-key') {
      expect(headers.get('x-api-key')).toBe(API_KEY)
    } else {
      expect(headers.get('Authorization')).toBe(`Bearer ${API_KEY}`)
    }
  })

  it('uses independent cache keys per provider', () => {
    expect(catalogCacheKey('provider-a')).not.toBe(catalogCacheKey('provider-b'))
    expect(catalogCacheKey('provider-a')).toContain('provider-a')
  })

  it('serves a fresh cache entry without a request and refreshes after the TTL', async () => {
    const preset = getOpenAICompatiblePreset('openai')
    const cache = new MemoryCatalogCache()
    let now = 1000
    const fetchMock = vi.fn(async () => jsonResponse(openAIModels))
    const context = makeContext('openai', fetchMock as unknown as typeof fetch)

    const first = await loadCatalog(context, new AbortController().signal, {
      cache,
      now: () => now,
    })
    const cached = await loadCatalog(context, new AbortController().signal, {
      cache,
      now: () => now,
    })

    expect(first.fromCache).toBe(false)
    expect(cached.fromCache).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    now += (preset?.modelDiscovery.cacheTtlSeconds ?? 0) * 1000
    const expired = await loadCatalog(context, new AbortController().signal, {
      cache,
      now: () => now,
    })

    expect(expired.fromCache).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('keeps the last good snapshot when a forced refresh fails', async () => {
    const cache = new MemoryCatalogCache()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(openAIModels))
      .mockRejectedValueOnce(new TypeError('network down'))
    const context = makeContext('openai', fetchMock as unknown as typeof fetch)

    const first = await loadCatalog(context, new AbortController().signal, { cache })
    const stale = await refreshCatalog(context, new AbortController().signal, { cache })

    expect(first.models.length).toBeGreaterThan(0)
    expect(stale.stale).toBe(true)
    expect(stale.fromCache).toBe(true)
    expect(stale.refreshError).toBeInstanceOf(Error)
    expect(stale.models).toEqual(first.models)
  })

  it('throws when discovery fails and there is no last good snapshot', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ error: { message: 'blocked' } }, 500))
    const context = makeContext('openai', fetchMock as unknown as typeof fetch)

    await expect(
      loadCatalog(context, new AbortController().signal, {
        cache: new MemoryCatalogCache(),
        now: () => 1000,
      }),
    ).rejects.toMatchObject({ code: 'provider' })
  })
})
