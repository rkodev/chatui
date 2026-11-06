import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatError } from '../domain'
import { loadSseFixture } from '../test/sse'
import {
  GeminiStreamMapper,
  buildGeminiChatUrl,
  buildGeminiHeaders,
  buildGeminiRequestBody,
  mapGeminiFinishReason,
  mapGeminiModels,
  streamGeminiChat,
  validateKey,
  type GeminiChatRequest,
} from './gemini'
import type {
  NormalizedChatRequest,
  ProviderContext,
  StreamChatOptions,
  StreamEvent,
} from './openaiCompatible'
import { ProviderRequestError } from './openaiCompatible'
import { getOpenAICompatiblePreset } from './presets'

const FIXED_NOW = '2026-01-01T00:00:00.000Z'
const API_KEY = 'AIza-gemini-test-key'

const geminiPreset = getOpenAICompatiblePreset('google')

if (!geminiPreset) {
  throw new Error('Google Gemini preset is missing from contracts')
}

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

function makeContext(fetchImpl: typeof fetch, apiKey: string | null = API_KEY): ProviderContext {
  return {
    provider: { id: 'google', baseUrl: geminiPreset!.baseUrl },
    apiKey,
    fetch: fetchImpl,
    preset: geminiPreset,
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

function baseRequest(overrides: Partial<GeminiChatRequest> = {}): GeminiChatRequest {
  return {
    model: 'gemini-2.5-flash',
    system: null,
    messages: [{ role: 'user', parts: [{ type: 'text', text: 'Hello' }] }],
    params: {
      temperature: null,
      topP: null,
      maxOutputTokens: 256,
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
  request: GeminiChatRequest,
  options: StreamChatOptions = {},
  signal: AbortSignal = new AbortController().signal,
): Promise<StreamEvent[]> {
  const events: StreamEvent[] = []
  await streamGeminiChat(context, request, signal, (event) => events.push(event), options)
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

describe('Gemini request serialization', () => {
  it('serializes the basic request vector exactly', () => {
    const vector = loadRequest('gemini-basic')

    expect(buildGeminiRequestBody(vector.request, vector.supportedParameters)).toEqual(
      vector.expected.body,
    )
  })

  it('serializes images as inlineData and text files as text parts', () => {
    const vector = loadRequest('gemini-attachments')

    expect(
      buildGeminiRequestBody(
        { ...vector.request, attachments: vector.attachments },
        vector.supportedParameters,
      ),
    ).toEqual(vector.expected.body)
  })

  it('round-trips safe advanced JSON under the google namespace', () => {
    const vector = loadRequest('gemini-extras')

    expect(buildGeminiRequestBody(vector.request, vector.supportedParameters)).toEqual(
      vector.expected.body,
    )
  })

  it('throws a normalized invalid_request error for protected fields', () => {
    const vector = loadRequest('gemini-protected')
    let thrown: unknown

    try {
      buildGeminiRequestBody(vector.request, vector.supportedParameters)
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

  it('emits protected-field errors without fetching', async () => {
    const vector = loadRequest('gemini-protected')
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
})

describe('Gemini headers, URL, and discovery', () => {
  it('sends the key only in x-goog-api-key', () => {
    const headers = buildGeminiHeaders(makeContext(vi.fn() as unknown as typeof fetch))

    expect(headers.get('x-goog-api-key')).toBe(API_KEY)
    expect(headers.get('content-type')).toBe('application/json')
    expect(headers.get('accept')).toBe('text/event-stream')
    expect(headers.get('authorization')).toBeNull()
  })

  it('builds the alt=sse URL without the key in the query string', () => {
    const url = buildGeminiChatUrl(geminiPreset!.baseUrl, 'gemini-2.5-flash')

    expect(url).toBe(
      `${geminiPreset!.baseUrl}/models/gemini-2.5-flash:streamGenerateContent?alt=sse`,
    )
    expect(url).not.toContain(API_KEY)
    expect(url).not.toContain('key=')
    expect(buildGeminiChatUrl(geminiPreset!.baseUrl, 'models/gemini-2.5-pro')).toBe(
      `${geminiPreset!.baseUrl}/models/gemini-2.5-pro:streamGenerateContent?alt=sse`,
    )
  })

  it('maps the shared Gemini catalog fixture', () => {
    const fixture = loadJson<unknown>('../contracts/fixtures/models/gemini-models.json')
    const expected = loadJson<CatalogVector>('../contracts/fixtures/expected/catalogs.json')
      .cases.find((entry) => entry.name === 'google')

    const models = mapGeminiModels(fixture)

    expect(models).toHaveLength(2)
    expect(models).toMatchObject(expected?.expected ?? [])
    expect(models.map((model) => model.remoteId)).toEqual([
      'gemini-2.5-flash',
      'gemini-2.5-pro',
    ])
  })

  it('validates the key with GET /models and keeps it out of the URL', async () => {
    const fixture = loadJson<unknown>('../contracts/fixtures/models/gemini-models.json')
    const fetchMock = vi.fn(async () => jsonResponse(fixture, 200))

    const info = await validateKey(
      makeContext(fetchMock as unknown as typeof fetch),
      new AbortController().signal,
    )

    expect(info).toMatchObject({ label: null, usageUsd: null })
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const [url, init] = fetchMock.mock.calls[0] as unknown as [RequestInfo | URL, RequestInit?]
    expect(String(url)).toBe(`${geminiPreset!.baseUrl}/models`)
    expect(String(url)).not.toContain(API_KEY)

    const headers = new Headers(init?.headers)
    expect(headers.get('x-goog-api-key')).toBe(API_KEY)
    expect(headers.get('authorization')).toBeNull()
  })
})

describe('Gemini finish reasons', () => {
  const cases = loadJson<{
    cases: Array<{ name: string; finishReason: string; expected: string }>
  }>('../contracts/fixtures/expected/gemini-finish-reasons.json').cases

  it.each(cases)('maps $finishReason to $expected', (entry) => {
    expect(mapGeminiFinishReason(entry.finishReason)).toBe(entry.expected)
  })

  it('maps unknown reasons to unknown', () => {
    expect(mapGeminiFinishReason('OTHER')).toBe('unknown')
    expect(mapGeminiFinishReason(null)).toBe('unknown')
  })
})

describe('Gemini streaming fixtures', () => {
  it('emits the expected text, finish, and usage events', async () => {
    const expected = loadExpected('gemini-text')
    const fetchMock = vi.fn(async () => sseResponse(loadSseFixture('gemini-text.sse')))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(withoutAt(events)).toEqual(expected.events)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      RequestInfo | URL,
      RequestInit?,
    ]
    expect(String(url)).toBe(
      `${geminiPreset!.baseUrl}/models/gemini-2.5-flash:streamGenerateContent?alt=sse`,
    )
    expect(String(url)).not.toContain(API_KEY)

    const headers = new Headers(init?.headers)
    expect(headers.get('x-goog-api-key')).toBe(API_KEY)
    expect(headers.get('authorization')).toBeNull()
  })

  it('keeps thought parts separate from answer text', () => {
    const events: StreamEvent[] = []
    const mapper = new GeminiStreamMapper({
      context: makeContext(vi.fn() as unknown as typeof fetch),
      emit: (event) => events.push(event),
    })

    mapper.handleData(
      JSON.stringify({
        candidates: [
          {
            content: {
              role: 'model',
              parts: [
                { text: 'Thinking.', thought: true },
                { text: 'Answer.' },
              ],
            },
          },
        ],
        usageMetadata: { promptTokenCount: 2, candidatesTokenCount: 2, totalTokenCount: 4 },
      }),
    )
    mapper.finish()

    expect(events).toEqual([
      { type: 'start', responseId: null },
      { type: 'reasoning-delta', text: 'Thinking.' },
      { type: 'text-delta', text: 'Answer.' },
      { type: 'finish', reason: 'unknown' },
      {
        type: 'usage',
        usage: {
          inputTokens: 2,
          outputTokens: 2,
          totalTokens: 4,
          costUsd: null,
          estimated: false,
        },
      },
    ])
  })

  it('maps an in-stream error', async () => {
    const expected = loadExpectedErrors().cases.find(
      (entry) => entry.name === 'gemini-stream-internal',
    )
    const fetchMock = vi.fn(async () =>
      sseResponse(loadText('../contracts/fixtures/errors/gemini-stream-error.sse')),
    )

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(withoutAt(events)).toEqual([{ type: 'error', error: expected?.error }])
  })
})

describe('Gemini HTTP error mapping', () => {
  const cases = loadExpectedErrors().cases.filter(
    (entry) => entry.providerId === 'google' && entry.kind === 'http',
  )

  it.each(cases)('maps $name from its fixture', async (entry) => {
    const fixture = loadJson<{
      status: number
      headers: Record<string, string>
      body: unknown
    }>(`../contracts/fixtures/${entry.fixture}`)
    const fetchMock = vi.fn(async () => jsonResponse(fixture.body, fixture.status, fixture.headers))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(withoutAt(events)).toEqual([{ type: 'error', error: entry.error }])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('redacts the API key from provider error messages', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(
        { error: { code: 400, message: `bad API key ${API_KEY}`, status: 'INVALID_ARGUMENT' } },
        400,
      ),
    )

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(JSON.stringify(events)).not.toContain(API_KEY)
    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'auth', message: 'bad API key [redacted]' },
    })
  })
})

describe('Gemini retry and cancellation', () => {
  it('honors Retry-After and retries before any output', async () => {
    vi.useFakeTimers()
    const rateLimit = loadJson<unknown>('../contracts/fixtures/errors/gemini-429.json')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(rateLimit, 429, { 'Retry-After': '2' }))
      .mockResolvedValueOnce(sseResponse(loadSseFixture('gemini-text.sse')))

    const promise = collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { random: () => 0 }, now: () => FIXED_NOW },
    )

    await vi.advanceTimersByTimeAsync(0)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1999)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1)
    const events = await promise

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(events.some((event) => event.type === 'text-delta')).toBe(true)
  })

  it('classifies a browser CORS-shaped rejection as cors without retrying', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const error = events.at(-1)
    expect(error).toMatchObject({
      type: 'error',
      error: { code: 'cors', retryable: false },
    })
    if (error?.type === 'error') {
      expect(error.error.message).toContain('Google Gemini')
      expect(error.error.message).toContain('Android app')
    }
  })

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
            [
              'data: {"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"index":0}]}\n\n',
            ].join(''),
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

    await new Promise((resolveNow) => setTimeout(resolveNow, 0))
    controller.abort()
    const events = await promise

    expect(events.filter((event) => event.type === 'text-delta')).toEqual([
      { type: 'text-delta', text: 'Hi' },
    ])
    expect(events.at(-1)).toMatchObject({
      type: 'error',
      error: { code: 'cancelled' },
    })
  })
})
