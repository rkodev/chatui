import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatError } from '../domain'
import { loadSseFixture } from '../test/sse'
import { mapOpenAIModels } from './openaiCatalog'
import { MemoryCatalogCache } from './openrouterCatalog'
import {
  ProviderRequestError,
  buildChatRequestBody,
  buildStreamHeaders,
  createOpenAICompatibleAdapter,
  listModels,
  streamChat,
  validateKey,
  type NormalizedChatRequest,
  type OpenAICompatiblePreset,
  type ProviderContext,
  type StreamChatOptions,
  type StreamEvent,
} from './openaiCompatible'
import { getOpenAICompatiblePreset } from './presets'
import { OPENROUTER_PRESET } from './openrouterPreset'

const FIXED_NOW = '2026-01-01T00:00:00.000Z'
const API_KEY = 'sk-openai-test-key'

const openAIPreset = getOpenAICompatiblePreset('openai')

if (!openAIPreset) {
  throw new Error('OpenAI preset is missing from contracts')
}

const OPENAI_PRESET: OpenAICompatiblePreset = openAIPreset

interface ExpectedVector {
  source: string
  events: unknown[]
  text: string
  reasoning: string
  retryAllowed: boolean
}

interface ExpectedErrorCase {
  name: string
  providerId: string
  kind: 'http' | 'stream' | 'synthetic'
  fixture?: string
  status: number | null
  error: Omit<ChatError, 'at'>
}

interface ExpectedErrorsVector {
  source: string
  cases: ExpectedErrorCase[]
}

interface RequestVector {
  name: string
  kind: string
  providerId: string
  request: NormalizedChatRequest
  attachments?: Record<string, { mimeType: string; name?: string; dataBase64: string }>
  supportedParameters: string[]
  expected: {
    body?: Record<string, unknown>
    error?: Omit<ChatError, 'at'>
  }
}

interface CatalogVector {
  source: string
  cases: Array<{
    name: string
    providerId: string
    fixture: string
    expected: Array<Record<string, unknown>>
  }>
}

function loadJson<T>(path: string): T {
  return JSON.parse(readFileSync(resolve(process.cwd(), path), 'utf8')) as T
}

function loadText(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8')
}

function loadExpected(name: string): ExpectedVector {
  return loadJson<ExpectedVector>(`../contracts/fixtures/expected/${name}.json`)
}

function loadExpectedErrors(): ExpectedErrorsVector {
  return loadJson<ExpectedErrorsVector>('../contracts/fixtures/expected/provider-errors.json')
}

function loadRequest(name: string): RequestVector {
  return loadJson<RequestVector>(`../contracts/fixtures/requests/${name}.json`)
}

function makeContext(
  fetchImpl: typeof fetch,
  apiKey: string | null = API_KEY,
  preset: OpenAICompatiblePreset = OPENAI_PRESET,
): ProviderContext {
  return {
    provider: { id: preset.id, baseUrl: preset.baseUrl },
    apiKey,
    fetch: fetchImpl,
    preset,
  }
}

function sseResponse(sse: string, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(sse, {
    status,
    headers: { 'Content-Type': 'text/event-stream', ...headers },
  })
}

function jsonResponse(
  body: unknown,
  status: number,
  headers: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

function baseRequest(overrides: Partial<NormalizedChatRequest> = {}): NormalizedChatRequest {
  return {
    model: 'gpt-4o-mini',
    system: null,
    messages: [{ role: 'user', parts: [{ type: 'text', text: 'Hello' }] }],
    params: {
      temperature: null,
      topP: null,
      maxOutputTokens: null,
      stop: [],
      reasoningEffort: null,
      streaming: true,
      extra: {},
    },
    ...overrides,
  }
}

async function collect(
  context: ProviderContext,
  request: NormalizedChatRequest,
  options: StreamChatOptions = {},
  signal: AbortSignal = new AbortController().signal,
): Promise<StreamEvent[]> {
  const events: StreamEvent[] = []
  await streamChat(context, request, signal, (event) => events.push(event), options)
  return events
}

function withoutAt(events: StreamEvent[]): unknown[] {
  return events.map((event) => {
    if (event.type !== 'error') {
      return event
    }

    const { at, ...error } = event.error
    void at
    return { ...event, error }
  })
}

afterEach(() => {
  vi.useRealTimers()
})

describe('OpenAI-compatible request serialization', () => {
  it('serializes the OpenAI basic request vector exactly', () => {
    const vector = loadRequest('openai-basic')

    expect(buildChatRequestBody(vector.request, OPENAI_PRESET, vector.supportedParameters)).toEqual(
      vector.expected.body,
    )
  })

  it('serializes the OpenAI extras vector exactly', () => {
    const vector = loadRequest('openai-extras')

    expect(buildChatRequestBody(vector.request, OPENAI_PRESET, vector.supportedParameters)).toEqual(
      vector.expected.body,
    )
  })

  it('serializes images as data URLs and text files as text parts', () => {
    const vector = loadRequest('openai-attachments')

    expect(
      buildChatRequestBody(
        { ...vector.request, attachments: vector.attachments },
        OPENAI_PRESET,
        vector.supportedParameters,
      ),
    ).toEqual(vector.expected.body)
  })

  it('rejects protected OpenAI advanced fields before any fetch', async () => {
    const vector = loadRequest('openai-protected')
    const fetchMock = vi.fn()

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      vector.request,
      { now: () => FIXED_NOW },
    )

    expect(fetchMock).not.toHaveBeenCalled()
    expect(events).toHaveLength(1)
    expect(events[0]).toMatchObject({
      type: 'error',
      error: {
        code: 'invalid_request',
        message: vector.expected.error?.message,
        details: vector.expected.error?.details,
      },
    })
  })

  it('throws a normalized invalid_request error from the builder', () => {
    const vector = loadRequest('openai-protected')
    let thrown: unknown

    try {
      buildChatRequestBody(vector.request, OPENAI_PRESET, vector.supportedParameters)
    } catch (error) {
      thrown = error
    }

    expect(thrown).toBeInstanceOf(ProviderRequestError)
    expect(thrown).toMatchObject({
      code: 'invalid_request',
      message: vector.expected.error?.message,
      details: vector.expected.error?.details,
    })
  })

  it('keeps the OpenRouter preset serializing its shared vector', () => {
    const vector = loadRequest('openrouter-basic')

    expect(
      buildChatRequestBody(vector.request, OPENROUTER_PRESET, vector.supportedParameters),
    ).toEqual(vector.expected.body)
  })
})

describe('OpenAI-compatible streaming fixtures', () => {
  const fixtures = [
    ['openai-text', 'openai-text.sse'],
    ['openai-reasoning', 'openai-reasoning.sse'],
  ] as const

  it.each(fixtures)('emits the expected event order for %s', async (expectedName, fixtureName) => {
    const expected = loadExpected(expectedName)
    const fetchMock = vi.fn(async () => sseResponse(loadSseFixture(fixtureName)))

    const events = await collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      now: () => FIXED_NOW,
    })

    expect(withoutAt(events)).toEqual(expected.events)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('keeps reasoning separate from answer text and never deduplicates chunks', async () => {
    const chunk = (delta: Record<string, unknown>) =>
      `data: ${JSON.stringify({
        id: 'chatcmpl-reasoning-mix',
        choices: [{ index: 0, delta, finish_reason: null }],
      })}\n\n`
    const sse = [
      chunk({ reasoning_content: 'I' }),
      chunk({ reasoning_content: 'I' }),
      chunk({ reasoning: ' think' }),
      chunk({ reasoning_details: [{ type: 'reasoning.text', text: ' more' }] }),
      chunk({ content: 'Answer' }),
      `data: ${JSON.stringify({
        id: 'chatcmpl-reasoning-mix',
        choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      })}\n\n`,
      'data: [DONE]\n\n',
    ].join('')
    const fetchMock = vi.fn(async () => sseResponse(sse))

    const events = await collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      now: () => FIXED_NOW,
    })

    expect(events.filter((event) => event.type === 'reasoning-delta')).toEqual([
      { type: 'reasoning-delta', text: 'I' },
      { type: 'reasoning-delta', text: 'I' },
      { type: 'reasoning-delta', text: ' think' },
      { type: 'reasoning-delta', text: ' more' },
    ])
    expect(events.filter((event) => event.type === 'text-delta')).toEqual([
      { type: 'text-delta', text: 'Answer' },
    ])
  })

  it('maps an in-stream OpenAI error without emitting a start event', async () => {
    const expected = loadExpectedErrors().cases.find(
      (entry) => entry.name === 'openai-stream-server-error',
    )
    const fetchMock = vi.fn(async () =>
      sseResponse(loadText('../contracts/fixtures/errors/openai-stream-error.sse')),
    )

    const events = await collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      now: () => FIXED_NOW,
    })

    expect(withoutAt(events)).toEqual([{ type: 'error', error: expected?.error }])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('OpenAI-compatible HTTP error mapping', () => {
  const cases = loadExpectedErrors().cases.filter(
    (entry) => entry.providerId === 'openai' && entry.kind === 'http',
  )

  it.each(cases)('maps $name from its fixture', async (entry) => {
    const fixture = loadJson<{
      status: number
      headers: Record<string, string>
      body: unknown
    }>(`../contracts/fixtures/${entry.fixture}`)
    const fetchMock = vi.fn(async () => jsonResponse(fixture.body, fixture.status, fixture.headers))

    const events = await collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      retry: { maxAttempts: 1 },
      now: () => FIXED_NOW,
    })

    expect(withoutAt(events)).toEqual([{ type: 'error', error: entry.error }])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('maps a synthetic 402 quota error', async () => {
    const expected = loadExpectedErrors().cases.find((entry) => entry.name === 'provider-quota')
    const fetchMock = vi.fn(async () => jsonResponse({}, 402))

    const events = await collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      retry: { maxAttempts: 1 },
      now: () => FIXED_NOW,
    })

    expect(withoutAt(events)).toEqual([{ type: 'error', error: expected?.error }])
  })

  it('redacts the API key from provider error messages', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ error: { message: `Invalid key ${API_KEY}` } }, 401),
    )

    const events = await collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      retry: { maxAttempts: 1 },
      now: () => FIXED_NOW,
    })

    expect(JSON.stringify(events)).not.toContain(API_KEY)
    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'auth', message: 'Invalid key [redacted]' },
    })
  })
})

describe('OpenAI-compatible retry policy', () => {
  it('honors Retry-After from the response header', async () => {
    vi.useFakeTimers()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ error: { message: 'Rate limit reached.' } }, 429, {
          'Retry-After': '2',
        }),
      )
      .mockResolvedValueOnce(sseResponse(loadSseFixture('openai-text.sse')))

    const promise = collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      retry: { random: () => 0 },
      now: () => FIXED_NOW,
    })

    await vi.advanceTimersByTimeAsync(0)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1999)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1)
    const events = await promise

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(events.some((event) => event.type === 'text-delta')).toBe(true)
  })

  it('retries network failures with exponential backoff', async () => {
    vi.useFakeTimers()
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce(sseResponse(loadSseFixture('openai-text.sse')))

    const promise = collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      retry: { random: () => 0 },
      now: () => FIXED_NOW,
    })

    await vi.advanceTimersByTimeAsync(0)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(800)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(1600)
    const events = await promise

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(events.some((event) => event.type === 'text-delta')).toBe(true)
  })

  it('classifies a browser CORS-shaped rejection as cors without retrying', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))

    const events = await collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      now: () => FIXED_NOW,
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const error = events.at(-1)
    expect(error).toMatchObject({
      type: 'error',
      error: { code: 'cors', retryable: false },
    })
    if (error?.type === 'error') {
      expect(error.error.message).toContain('OpenAI')
      expect(error.error.message).toContain('Android app')
      expect(error.error.message).toContain('self-hosted proxy')
    }
  })

  it('does not retry a network failure after the first token', async () => {
    const encoder = new TextEncoder()
    let step = 0
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (step === 0) {
          step += 1
          controller.enqueue(
            encoder.encode(
              'data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}\n\n',
            ),
          )
          return
        }

        controller.error(new Error('connection lost'))
      },
    })
    const fetchMock = vi.fn(async () => new Response(stream, { status: 200 }))

    const events = await collect(makeContext(fetchMock as unknown as typeof fetch), baseRequest(), {
      now: () => FIXED_NOW,
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(events.filter((event) => event.type === 'text-delta')).toEqual([
      { type: 'text-delta', text: 'Hi' },
    ])
    expect(events.at(-1)).toMatchObject({
      type: 'error',
      error: { code: 'network', retryable: false },
    })
  })
})

describe('OpenAI-compatible cancellation', () => {
  it('maps an already-aborted signal to cancelled without fetching', async () => {
    const fetchMock = vi.fn()
    const controller = new AbortController()
    controller.abort()

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
      controller.signal,
    )

    expect(fetchMock).not.toHaveBeenCalled()
    expect(events).toEqual([
      {
        type: 'error',
        error: expect.objectContaining({ code: 'cancelled', retryable: false }),
      },
    ])
  })

  it('maps an abort during streaming to cancelled and preserves partial text', async () => {
    const encoder = new TextEncoder()
    const controller = new AbortController()
    const stream = new ReadableStream<Uint8Array>({
      start(streamController) {
        streamController.enqueue(
          encoder.encode(
            'data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}\n\n',
          ),
        )
      },
    })
    const fetchMock = vi.fn(async () => new Response(stream, { status: 200 }))

    const promise = collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
      controller.signal,
    )

    await new Promise((resolve) => setTimeout(resolve, 0))
    controller.abort()
    const events = await promise

    expect(events.filter((event) => event.type === 'text-delta')).toEqual([
      { type: 'text-delta', text: 'Hi' },
    ])
    expect(events.at(-1)).toMatchObject({
      type: 'error',
      error: { code: 'cancelled', retryable: false },
    })
  })
})

describe('OpenAI-compatible headers and auth', () => {
  it('sends bearer auth and no attribution headers for OpenAI', () => {
    const headers = buildStreamHeaders(makeContext(vi.fn() as unknown as typeof fetch))

    expect(headers.get('Authorization')).toBe(`Bearer ${API_KEY}`)
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('Accept')).toBe('text/event-stream')
    expect(headers.get('HTTP-Referer')).toBeNull()
    expect(headers.get('X-Title')).toBeNull()
  })

  it('uses preset default headers and x-api-key auth', () => {
    const preset: OpenAICompatiblePreset = {
      ...OPENAI_PRESET,
      id: 'custom-header',
      auth: { type: 'x-api-key', headerName: 'x-api-key' },
      defaultHeaders: { 'X-Custom': 'value' },
    }
    const headers = buildStreamHeaders(
      makeContext(vi.fn() as unknown as typeof fetch, API_KEY, preset),
    )

    expect(headers.get('x-api-key')).toBe(API_KEY)
    expect(headers.get('X-Custom')).toBe('value')
    expect(headers.get('Authorization')).toBeNull()
  })

  it('never places a query-key in the URL', async () => {
    const preset: OpenAICompatiblePreset = {
      ...OPENAI_PRESET,
      id: 'query-key',
      auth: { type: 'query-key', queryParam: 'key' },
    }
    const fetchMock = vi.fn()

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch, API_KEY, preset),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(fetchMock).not.toHaveBeenCalled()
    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'invalid_request' },
    })
    expect(JSON.stringify(events)).not.toContain(API_KEY)
  })
})

describe('OpenAI-compatible key validation and discovery', () => {
  it('validates an OpenAI key with GET /models and bearer auth', async () => {
    const modelsFixture = loadJson<unknown>('../contracts/fixtures/models/openai-models.json')
    const fetchMock = vi.fn(async () => jsonResponse(modelsFixture, 200))

    const info = await validateKey(
      makeContext(fetchMock as unknown as typeof fetch),
      new AbortController().signal,
    )

    expect(info).toEqual({
      label: null,
      usageUsd: null,
      limitUsd: null,
      limitRemainingUsd: null,
      isFreeTier: null,
      rateLimit: null,
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const [url, init] = fetchMock.mock.calls[0] as unknown as [RequestInfo | URL, RequestInit?]
    expect(url).toBe('https://api.openai.com/v1/models')
    expect(String(url)).not.toContain(API_KEY)
    expect(init?.method).toBe('GET')

    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe(`Bearer ${API_KEY}`)
  })

  it('rejects a missing OpenAI key before making a request', async () => {
    const fetchMock = vi.fn()

    await expect(
      validateKey(
        makeContext(fetchMock as unknown as typeof fetch, null),
        new AbortController().signal,
      ),
    ).rejects.toMatchObject({ code: 'auth' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('loads OpenAI models with auth and maps the shared catalog fixture', async () => {
    const catalog = loadJson<CatalogVector>('../contracts/fixtures/expected/catalogs.json')
    const expected = catalog.cases.find((entry) => entry.name === 'openai')
    const modelsFixture = loadJson<unknown>('../contracts/fixtures/models/openai-models.json')
    const fetchMock = vi.fn(async () => jsonResponse(modelsFixture, 200))

    const models = await listModels(
      makeContext(fetchMock as unknown as typeof fetch),
      new AbortController().signal,
      { cache: new MemoryCatalogCache(), now: () => 1000 },
    )

    expect(models).toHaveLength(expected?.expected.length ?? 0)
    expect(
      models.map((model) => ({
        remoteId: model.remoteId,
        label: model.label,
        contextWindow: model.contextWindow,
        maxOutputTokens: model.maxOutputTokens,
        inputModalities: model.inputModalities,
        outputModalities: model.outputModalities,
        capabilities: model.capabilities,
        supportedParameters: model.supportedParameters,
        pricing: model.pricing,
        hidden: model.hidden ?? false,
      })),
    ).toEqual(expected?.expected)

    const [, init] = fetchMock.mock.calls[0] as unknown as [RequestInfo | URL, RequestInit?]
    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe(`Bearer ${API_KEY}`)
  })

  it('maps the OpenAI catalog fixture directly', () => {
    const modelsFixture = loadJson<unknown>('../contracts/fixtures/models/openai-models.json')
    const models = mapOpenAIModels(modelsFixture)

    expect(models.map((model) => model.remoteId)).toEqual([
      'gpt-4o-mini',
      'gpt-4o',
      'text-embedding-3-small',
    ])
    expect(models[2].hidden).toBe(true)
  })
})

describe('OpenAI-compatible adapter surface', () => {
  it('binds the preset for streaming and key validation', async () => {
    const adapter = createOpenAICompatibleAdapter(OPENAI_PRESET)
    const fetchMock = vi.fn(async () => sseResponse(loadSseFixture('openai-text.sse')))
    const context: ProviderContext = {
      provider: { id: 'openai', baseUrl: OPENAI_PRESET.baseUrl },
      apiKey: API_KEY,
      fetch: fetchMock as unknown as typeof fetch,
    }
    const events: StreamEvent[] = []

    await adapter.streamChat(context, baseRequest(), new AbortController().signal, (event) =>
      events.push(event),
    )

    expect(events.some((event) => event.type === 'text-delta')).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
