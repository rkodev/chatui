import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatError } from '../domain'
import { loadSseFixture } from '../test/sse'
import type { ProviderContext } from './openrouter'
import {
  OpenRouterRequestError,
  buildChatRequestBody,
  buildStreamHeaders,
  estimateUsage,
  streamChat,
  type NormalizedChatRequest,
  type StreamChatOptions,
  type StreamEvent,
} from './openrouterStream'
import { computeRetryDelay, retryDelayFor, sleep } from './retry'

const FIXED_NOW = '2026-01-01T00:00:00.000Z'
const API_KEY = 'sk-test-key'

const provider: ProviderContext['provider'] = {
  id: 'openrouter',
  baseUrl: 'https://openrouter.ai/api/v1',
}

const attribution = {
  origin: 'https://chat.example.com',
  title: 'ChatUI',
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
  fixture?: string
  synthetic?: boolean
  status: number
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
  supportedParameters: string[]
  expected: {
    body?: Record<string, unknown>
    error?: Omit<ChatError, 'at'>
  }
}

interface AttributionVector {
  name: string
  kind: string
  providerId: string
  appConfig: { name: string; origin: string }
  expected: { headers: Record<string, string> }
}

function loadJson<T>(path: string): T {
  return JSON.parse(readFileSync(resolve(process.cwd(), path), 'utf8')) as T
}

function loadExpected(name: string): ExpectedVector {
  return loadJson<ExpectedVector>(`../contracts/fixtures/expected/${name}.json`)
}

function loadExpectedErrors(): ExpectedErrorsVector {
  return loadJson<ExpectedErrorsVector>('../contracts/fixtures/expected/openrouter-errors.json')
}

function loadRequest(name: string): RequestVector {
  return loadJson<RequestVector>(`../contracts/fixtures/requests/${name}.json`)
}

function makeContext(
  fetchImpl: typeof fetch,
  apiKey: string | null = API_KEY,
): ProviderContext {
  return { provider, apiKey, fetch: fetchImpl, attribution }
}

function sseResponse(
  sse: string,
  status = 200,
  headers: Record<string, string> = {},
): Response {
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
    model: 'openai/gpt-4o-mini-2024-07-18',
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

describe('OpenRouter request serialization', () => {
  it('serializes the basic request vector exactly', () => {
    const vector = loadRequest('openrouter-basic')

    expect(buildChatRequestBody(vector.request, vector.supportedParameters)).toEqual(
      vector.expected.body,
    )
  })

  it('serializes curated routing exactly', () => {
    const vector = loadRequest('openrouter-routing')

    expect(buildChatRequestBody(vector.request, vector.supportedParameters)).toEqual(
      vector.expected.body,
    )
  })

  it('omits an unsupported reasoning control', () => {
    const vector = loadRequest('openrouter-unsupported-parameter')

    expect(buildChatRequestBody(vector.request, vector.supportedParameters)).toEqual(
      vector.expected.body,
    )
  })

  it('serializes sampling parameters only when supported', () => {
    const request = baseRequest({
      params: {
        temperature: 0.2,
        topP: 0.9,
        maxOutputTokens: 128,
        stop: ['END'],
        reasoningEffort: 'high',
        streaming: true,
        extra: {},
      },
    })

    expect(buildChatRequestBody(request, ['temperature', 'max_tokens', 'reasoning'])).toEqual({
      model: request.model,
      messages: [{ role: 'user', content: 'Hello' }],
      stream: true,
      stream_options: { include_usage: true },
      temperature: 0.2,
      max_tokens: 128,
      reasoning: { effort: 'high' },
    })
  })

  it('concatenates multiple text parts into one content string', () => {
    const request = baseRequest({
      messages: [
        {
          role: 'user',
          parts: [
            { type: 'text', text: 'Hello' },
            { type: 'text', text: ' world' },
          ],
        },
      ],
    })

    expect(buildChatRequestBody(request)).toMatchObject({
      messages: [{ role: 'user', content: 'Hello world' }],
    })
  })

  it('does not send reasoning parts back to the provider', () => {
    const request = baseRequest({
      messages: [
        {
          role: 'assistant',
          parts: [
            { type: 'reasoning', text: 'private reasoning' },
            { type: 'text', text: 'Answer' },
          ],
        },
      ],
    })

    expect(buildChatRequestBody(request)).toMatchObject({
      messages: [{ role: 'assistant', content: 'Answer' }],
    })
  })

  it('merges advanced reasoning over the curated effort', () => {
    const request = baseRequest({
      params: {
        temperature: null,
        topP: null,
        maxOutputTokens: null,
        stop: [],
        reasoningEffort: 'medium',
        streaming: true,
        extra: {
          openrouter: {
            reasoning: { max_tokens: 2048, exclude: true },
          },
        },
      },
    })

    expect(buildChatRequestBody(request, ['reasoning'])).toMatchObject({
      reasoning: { effort: 'medium', max_tokens: 2048, exclude: true },
    })
  })

  it('rejects protected advanced fields before any fetch', async () => {
    const vector = loadRequest('openrouter-advanced-protected')
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
    const vector = loadRequest('openrouter-advanced-protected')
    let thrown: unknown

    try {
      buildChatRequestBody(vector.request, vector.supportedParameters)
    } catch (error) {
      thrown = error
    }

    expect(thrown).toBeInstanceOf(OpenRouterRequestError)
    expect(thrown).toMatchObject({
      code: 'invalid_request',
      message: vector.expected.error?.message,
      details: vector.expected.error?.details,
    })
  })
})

describe('OpenRouter attribution headers', () => {
  it('builds the expected headers from app config', () => {
    const vector = loadJson<AttributionVector>(
      '../contracts/fixtures/requests/openrouter-attribution.json',
    )
    const context = makeContext(vi.fn() as unknown as typeof fetch)
    context.attribution = {
      origin: vector.appConfig.origin,
      title: vector.appConfig.name,
    }

    const headers = buildStreamHeaders(context)

    expect(headers.get('HTTP-Referer')).toBe(vector.expected.headers['HTTP-Referer'])
    expect(headers.get('X-Title')).toBe(vector.expected.headers['X-Title'])
    expect(headers.get('Authorization')).toBe(`Bearer ${API_KEY}`)
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('Accept')).toBe('text/event-stream')
  })
})

describe('OpenRouter streaming fixtures', () => {
  const fixtures = [
    ['openrouter-text', 'openrouter-text.sse'],
    ['openrouter-keepalive', 'openrouter-keepalive.sse'],
    ['openrouter-reasoning', 'openrouter-reasoning.sse'],
    ['openrouter-usage', 'openrouter-usage.sse'],
  ] as const

  it.each(fixtures)('emits the expected event order for %s', async (expectedName, fixtureName) => {
    const expected = loadExpected(expectedName)
    const fetchMock = vi.fn(async () => sseResponse(loadSseFixture(fixtureName)))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(withoutAt(events)).toEqual(expected.events)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('calls fetch without a receiver so browsers do not throw Illegal invocation', async () => {
    const fetchMock = vi.fn(function (this: unknown) {
      if (this !== undefined) {
        throw new TypeError("Failed to execute 'fetch' on 'Window': Illegal invocation")
      }
      return Promise.resolve(sseResponse(loadSseFixture('openrouter-text.sse')))
    })

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(events.some((event) => event.type === 'text-delta')).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('emits reasoning once when reasoning and reasoning_details duplicate', async () => {
    const fetchMock = vi.fn(async () => sseResponse(loadSseFixture('openrouter-reasoning.sse')))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(events.filter((event) => event.type === 'reasoning-delta')).toEqual([
      { type: 'reasoning-delta', text: 'Let me compare the available models.' },
    ])
  })

  it('preserves partial text and does not retry an in-stream error', async () => {
    const expected = loadExpected('openrouter-error-200')
    const fetchMock = vi.fn(async () => sseResponse(loadSseFixture('openrouter-error-200.sse')))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(withoutAt(events)).toEqual(expected.events)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(events.filter((event) => event.type === 'text-delta')).toEqual([
      { type: 'text-delta', text: 'Partial answer' },
    ])
  })

  it('concatenates content arrays defensively', async () => {
    const sse = [
      'data: {"id":"gen-array","choices":[{"index":0,"delta":{"content":[{"type":"text","text":"Hello"},{"type":"text","text":" array"}]},"finish_reason":null}]}',
      '',
      'data: {"id":"gen-array","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}',
      '',
      'data: [DONE]',
      '',
    ].join('\n')
    const fetchMock = vi.fn(async () => sseResponse(sse))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(events.filter((event) => event.type === 'text-delta')).toEqual([
      { type: 'text-delta', text: 'Hello array' },
    ])
  })
})

describe('OpenRouter HTTP error mapping', () => {
  const fixtureCases = [
    ['402-insufficient-credits', 'errors/openrouter-402.json', 402],
    ['429-rate-limit', 'errors/openrouter-429.json', 429],
    ['503-no-upstream-provider', 'errors/openrouter-503.json', 503],
  ] as const

  it.each(fixtureCases)('maps %s from its fixture', async (name, fixture, status) => {
    const expected = loadExpectedErrors().cases.find((entry) => entry.name === name)
    const body = loadJson<unknown>(`../contracts/fixtures/${fixture}`)
    const fetchMock = vi.fn(async () => jsonResponse(body, status))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(withoutAt(events)).toEqual([{ type: 'error', error: expected?.error }])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  const syntheticCases = [
    [
      '401-invalid-key',
      401,
      { error: { code: 401, message: 'Authentication failed. Check your OpenRouter API key.' } },
    ],
    [
      '404-model-not-found',
      404,
      { error: { code: 404, message: 'The requested model was not found.' } },
    ],
    [
      '400-context-length',
      400,
      { error: { code: 400, message: "The request exceeds the model's context length." } },
    ],
  ] as const

  it.each(syntheticCases)('maps %s from a synthetic envelope', async (name, status, body) => {
    const expected = loadExpectedErrors().cases.find((entry) => entry.name === name)
    const fetchMock = vi.fn(async () => jsonResponse(body, status))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(withoutAt(events)).toEqual([{ type: 'error', error: expected?.error }])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('maps a 403 moderation block to content_filter', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ error: { code: 403, message: 'Blocked by moderation policy.' } }, 403),
    )

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'content_filter', status: 403, retryable: false },
    })
  })

  it('maps a non-moderation 403 to permission', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ error: { code: 403, message: 'This key is not allowed.' } }, 403),
    )

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'permission', status: 403, retryable: false },
    })
  })

  it('maps 413 to context_length', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ error: { code: 413, message: 'Payload too large.' } }, 413),
    )

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'context_length', status: 413, retryable: false },
    })
  })

  it('maps a generic 500 to a retryable provider error', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ error: { code: 500, message: 'Internal error.' } }, 500),
    )

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'provider', status: 500, retryable: true },
    })
  })

  it('maps invalid stream JSON to parse', async () => {
    const fetchMock = vi.fn(async () => sseResponse('data: not-json\n\n'))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(events.at(-1)).toMatchObject({
      type: 'error',
      error: { code: 'parse', retryable: false },
    })
  })

  it('maps a missing response body to provider', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 200 }))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'provider', retryable: false },
    })
  })

  it('redacts the API key from provider error messages', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ error: { message: `Invalid key ${API_KEY}` } }, 401),
    )

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { maxAttempts: 1 }, now: () => FIXED_NOW },
    )

    expect(JSON.stringify(events)).not.toContain(API_KEY)
    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'auth', message: 'Invalid key [redacted]' },
    })
  })
})

describe('OpenRouter retry policy', () => {
  it('honors Retry-After from the response header', async () => {
    vi.useFakeTimers()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ error: { code: 429, message: 'Rate limit exceeded.' } }, 429, {
          'Retry-After': '2',
        }),
      )
      .mockResolvedValueOnce(sseResponse(loadSseFixture('openrouter-text.sse')))

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

  it('honors Retry-After from fixture metadata', async () => {
    vi.useFakeTimers()
    const body = loadJson<unknown>('../contracts/fixtures/errors/openrouter-429.json')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(body, 429))
      .mockResolvedValueOnce(sseResponse(loadSseFixture('openrouter-text.sse')))

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
    await promise

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('retries network failures with exponential backoff', async () => {
    vi.useFakeTimers()
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce(sseResponse(loadSseFixture('openrouter-text.sse')))

    const promise = collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { random: () => 0 }, now: () => FIXED_NOW },
    )

    await vi.advanceTimersByTimeAsync(0)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(799)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(1599)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(1)
    const events = await promise

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(events.some((event) => event.type === 'text-delta')).toBe(true)
  })

  it('stops after three attempts', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('network down'))

    const promise = collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { retry: { random: () => 0 }, now: () => FIXED_NOW },
    )

    await vi.advanceTimersByTimeAsync(0)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(800)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(1600)
    const events = await promise

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(events).toHaveLength(1)
    expect(events[0]).toMatchObject({
      type: 'error',
      error: { code: 'network', retryable: true },
    })
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
              'data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}\n\n',
            ),
          )
          return
        }

        controller.error(new Error('connection lost'))
      },
    })
    const fetchMock = vi.fn(async () => new Response(stream, { status: 200 }))

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

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

describe('OpenRouter cancellation', () => {
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
            'data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}\n\n',
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

describe('retry timing helpers', () => {
  it('starts at 800 ms, doubles, and caps at 8 s', () => {
    expect(computeRetryDelay(1, { random: () => 0 })).toBe(800)
    expect(computeRetryDelay(2, { random: () => 0 })).toBe(1600)
    expect(computeRetryDelay(3, { random: () => 0 })).toBe(3200)
    expect(computeRetryDelay(10, { random: () => 0 })).toBe(8000)
  })

  it('adds bounded positive jitter', () => {
    expect(computeRetryDelay(1, { random: () => 1 })).toBe(1000)
  })

  it('honors Retry-After over the local backoff', () => {
    expect(retryDelayFor(2, 1, { random: () => 0 })).toBe(2000)
  })

  it('sleeps and rejects when aborted', async () => {
    vi.useFakeTimers()
    const promise = sleep(800)
    await vi.advanceTimersByTimeAsync(800)
    await expect(promise).resolves.toBeUndefined()

    const controller = new AbortController()
    const aborted = sleep(800, controller.signal)
    controller.abort()
    await expect(aborted).rejects.toThrow('Retry sleep was aborted')
  })
})

describe('usage estimation', () => {
  it('marks estimated usage', () => {
    expect(estimateUsage('12345678')).toEqual({
      inputTokens: 2,
      outputTokens: 2,
      totalTokens: 4,
      costUsd: null,
      estimated: true,
    })
  })
})
