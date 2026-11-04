import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { newId, nowIso, type Model, type Provider } from '../../domain'
import { openRouterAdapter } from '../../providers/openrouter'
import { MemoryCatalogCache } from '../../providers/openrouterCatalog'
import { deleteDatabase, openDatabase, type ChatUiDB } from '../../storage/db'
import { ModelRepository } from '../../storage/ModelRepository'
import { ProviderRepository } from '../../storage/ProviderRepository'
import { ModelService } from './modelService'

const modelsFixture: Record<string, unknown> = JSON.parse(
  readFileSync(
    resolve(process.cwd(), '../contracts/fixtures/models/openrouter-models.json'),
    'utf8',
  ),
)

const server = setupServer()

function makeProvider(overrides: Partial<Provider> = {}): Provider {
  const now = nowIso()
  return {
    id: newId(),
    presetId: 'openrouter',
    label: 'OpenRouter',
    kind: 'openai-compatible',
    baseUrl: 'https://openrouter.ai/api/v1',
    authType: 'bearer',
    secretId: null,
    headers: {},
    enabled: true,
    browserCors: 'unknown',
    metadata: null,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function makeModel(providerId: string, remoteId: string, overrides: Partial<Model> = {}): Model {
  const now = nowIso()
  return {
    id: newId(),
    providerId,
    remoteId,
    label: remoteId,
    contextWindow: null,
    maxOutputTokens: null,
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
    ...overrides,
  }
}

describe('ModelService', () => {
  let db: IDBPDatabase<ChatUiDB>
  let providers: ProviderRepository
  let models: ModelRepository
  let service: ModelService

  beforeAll(() => {
    server.listen({ onUnhandledFrame: 'error' })
  })

  afterAll(() => {
    server.close()
  })

  beforeEach(async () => {
    await deleteDatabase()
    db = await openDatabase()
    providers = new ProviderRepository(db)
    models = new ModelRepository(db)
    service = new ModelService({
      providers,
      models,
      adapter: openRouterAdapter,
      fetch: globalThis.fetch,
      attribution: { origin: 'https://chat.example', title: 'ChatUI' },
      catalogCache: new MemoryCatalogCache(),
    })
  })

  afterEach(async () => {
    server.resetHandlers()
    db.close()
    await deleteDatabase()
  })

  it('refreshes the catalog and preserves pinned, hidden, and manual state', async () => {
    const provider = await providers.create(makeProvider())
    const existing = await models.create(
      makeModel(provider.id, 'openai/gpt-4o-mini-2024-07-18', {
        label: 'Old label',
        pinned: true,
        hidden: true,
      }),
    )
    const manual = await models.create(
      makeModel(provider.id, 'manual/model', {
        label: 'My manual model',
        source: 'manual',
        pinned: true,
      }),
    )
    server.use(
      http.get('https://openrouter.ai/api/v1/models', () => HttpResponse.json(modelsFixture)),
    )

    const result = await service.refreshModels(provider.id)

    expect(result.created).toBe(7)
    expect(result.updated).toBe(1)
    expect(result.models).toHaveLength(8)

    const refreshed = await models.get(existing.id)
    expect(refreshed).toMatchObject({
      id: existing.id,
      label: 'OpenAI: GPT-4o mini (2024-07-18)',
      pinned: true,
      hidden: true,
      source: 'discovered',
    })

    const manualAfter = await models.get(manual.id)
    expect(manualAfter).toMatchObject({
      label: 'My manual model',
      pinned: true,
      source: 'manual',
    })

    expect(await service.listModels(provider.id)).toHaveLength(9)
  })

  it('keeps a manual label when the model later appears in the catalog', async () => {
    const provider = await providers.create(makeProvider())
    const manual = await models.create(
      makeModel(provider.id, 'google/gemini-2.5-flash', {
        label: 'My Gemini',
        source: 'manual',
        pinned: true,
        hidden: true,
      }),
    )
    server.use(
      http.get('https://openrouter.ai/api/v1/models', () => HttpResponse.json(modelsFixture)),
    )

    await service.refreshModels(provider.id)

    const refreshed = await models.get(manual.id)
    expect(refreshed).toMatchObject({
      label: 'My Gemini',
      source: 'manual',
      pinned: true,
      hidden: true,
    })
    expect(refreshed?.capabilities).toContain('vision')
  })

  it('selects the preset default even when it is hidden', async () => {
    const provider = await providers.create(makeProvider())
    const presetDefault = await models.create(
      makeModel(provider.id, 'openrouter/auto', { hidden: true }),
    )
    await models.create(makeModel(provider.id, 'aaa/other-model'))

    expect(await service.selectDefaultModel(provider.id)).toEqual({
      modelId: presetDefault.id,
      remoteId: 'openrouter/auto',
      notice: null,
    })
  })

  it('falls back to the first non-hidden text model by remoteId', async () => {
    const provider = await providers.create(makeProvider())
    await models.create(makeModel(provider.id, 'aaa/hidden-text', { hidden: true }))
    const gemini = await models.create(makeModel(provider.id, 'google/gemini-2.5-flash'))
    await models.create(makeModel(provider.id, 'meta-llama/llama-3.2-3b-instruct:free'))
    await models.create(makeModel(provider.id, 'openai/gpt-4o-mini-2024-07-18'))

    expect(await service.selectDefaultModel(provider.id)).toEqual({
      modelId: gemini.id,
      remoteId: 'google/gemini-2.5-flash',
      notice: 'Default model openrouter/auto is unavailable; using google/gemini-2.5-flash.',
    })
  })

  it('returns a notice when no text model is available', async () => {
    const provider = await providers.create(makeProvider())
    await models.create(makeModel(provider.id, 'aaa/hidden-text', { hidden: true }))
    await models.create(
      makeModel(provider.id, 'example/image-only', { outputModalities: ['image'] }),
    )

    expect(await service.selectDefaultModel(provider.id)).toEqual({
      modelId: null,
      remoteId: null,
      notice: 'Default model openrouter/auto is unavailable; no text model is available.',
    })
  })

  it('adds manual models and persists pin/hide state', async () => {
    const provider = await providers.create(makeProvider())

    const manual = await service.addManualModel(provider.id, {
      remoteId: 'custom/model',
      label: 'Custom model',
      contextWindow: 8192,
      capabilities: ['text', 'vision'],
    })

    expect(manual).toMatchObject({
      providerId: provider.id,
      remoteId: 'custom/model',
      label: 'Custom model',
      contextWindow: 8192,
      capabilities: ['text', 'vision'],
      source: 'manual',
      hidden: false,
      pinned: false,
    })

    await expect(
      service.addManualModel(provider.id, { remoteId: 'custom/model' }),
    ).rejects.toMatchObject({ code: 'invalid_input' })

    expect((await service.setModelPinned(manual.id, true)).pinned).toBe(true)
    expect((await service.setModelHidden(manual.id, true)).hidden).toBe(true)
  })

  it('leaves existing models intact when discovery fails', async () => {
    const provider = await providers.create(makeProvider())
    const existing = await models.create(makeModel(provider.id, 'openai/gpt-4o-mini-2024-07-18'))
    server.use(
      http.get('https://openrouter.ai/api/v1/models', () =>
        HttpResponse.json({ error: { message: 'Catalog unavailable' } }, { status: 500 }),
      ),
    )

    await expect(service.refreshModels(provider.id)).rejects.toMatchObject({
      code: 'provider',
      status: 500,
    })
    expect(await models.get(existing.id)).not.toBeNull()
  })
})
