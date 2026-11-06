import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatError } from '../domain'
import {
  OllamaStreamMapper,
  buildOllamaChatUrl,
  buildOllamaHeaders,
  buildOllamaRequestBody,
  mapOllamaModels,
  readNdjson,
  selectOllamaTransport,
  streamOllamaChat,
  validateKey,
  type OllamaChatRequest,
} from './ollama'
import type {
  NormalizedChatRequest,
  ProviderContext,
  StreamChatOptions,
  StreamEvent,
} from './openaiCompatible'
import { ProviderRequestError } from './openaiCompatible'
import { getOpenAICompatiblePreset } from './presets'

const FIXED_NOW = '2026-01-01T00:00:00.000Z'

const ollamaPreset = getOpenAICompatiblePreset('ollama')

if (!ollamaPreset) {
  throw new Error('Ollama preset is missing from contracts')
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

function makeContext(fetchImpl: typeof fetch): ProviderContext {
  return {
    provider: { id: 'ollama', baseUrl: ollamaPreset!.baseUrl },
    apiKey: null,
    fetch: fetchImpl,
    preset: ollamaPreset,
  }
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

function ndjsonResponse(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: { 'Content-Type': 'application/x-ndjson' },
  })
}

function baseRequest(overrides: Partial<OllamaChatRequest> = {}): OllamaChatRequest {
  return {
    model: 'llama3.2:latest',
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
  request: OllamaChatRequest,
  options: StreamChatOptions = {},
  signal: AbortSignal = new AbortController().signal,
): Promise<StreamEvent[]> {
  const events: StreamEvent[] = []
  await streamOllamaChat(context, request, signal, (event) => events.push(event), options)
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

describe('Ollama request serialization', () => {
  it('serializes the basic request vector exactly', () => {
    const vector = loadRequest('ollama-basic')

    expect(buildOllamaRequestBody(vector.request, vector.supportedParameters)).toEqual(
      vector.expected.body,
    )
  })

  it('serializes images into the message images array and files into content', () => {
    const vector = loadRequest('ollama-attachments')

    expect(
      buildOllamaRequestBody(
        { ...vector.request, attachments: vector.attachments },
        vector.supportedParameters,
      ),
    ).toEqual(vector.expected.body)
  })

  it('round-trips safe advanced JSON under the ollama namespace', () => {
    const vector = loadRequest('ollama-extras')

    expect(buildOllamaRequestBody(vector.request, vector.supportedParameters)).toEqual(
      vector.expected.body,
    )
  })

  it('throws a normalized invalid_request error for protected fields', () => {
    const vector = loadRequest('ollama-protected')
    let thrown: unknown

    try {
      buildOllamaRequestBody(vector.request, vector.supportedParameters)
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
    const vector = loadRequest('ollama-protected')
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

  it('does not leak the transport control field into either body', () => {
    const request = baseRequest({
      params: {
        ...baseRequest().params,
        extra: { ollama: { transport: 'openai-compatible', keep_alive: '5m' } },
      },
    })

    const body = buildOllamaRequestBody(request, ['stream'])

    expect(body).not.toHaveProperty('transport')
    expect(body).toMatchObject({ keep_alive: '5m' })
  })
})

describe('Ollama transport selection', () => {
  it('uses native NDJSON for the Ollama preset by default', () => {
    expect(selectOllamaTransport(ollamaPreset, baseRequest())).toBe('native')
  })

  it('falls back to OpenAI-compatible for a Custom local server preset', () => {
    const customPreset = getOpenAICompatiblePreset('custom')

    expect(selectOllamaTransport(customPreset, baseRequest())).toBe('openai-compatible')
  })

  it('honors an explicit per-request transport override', () => {
    const fallback = baseRequest({
      params: {
        ...baseRequest().params,
        extra: { ollama: { transport: 'openai-compatible' } },
      },
    })
    const native = baseRequest({
      params: { ...baseRequest().params, extra: { ollama: { transport: 'native' } } },
    })

    expect(selectOllamaTransport(ollamaPreset, fallback)).toBe('openai-compatible')
    expect(selectOllamaTransport(getOpenAICompatiblePreset('custom'), native)).toBe('native')
  })

  it('builds the native URL at the server root, not the /v1 prefix', () => {
    expect(buildOllamaChatUrl('http://localhost:11434/v1')).toBe(
      'http://localhost:11434/api/chat',
    )
    expect(buildOllamaChatUrl('http://localhost:11434/v1/')).toBe(
      'http://localhost:11434/api/chat',
    )
    expect(buildOllamaChatUrl('http://localhost:11434')).toBe(
      'http://localhost:11434/api/chat',
    )
  })

  it('sends NDJSON headers without an auth header for the no-auth preset', () => {
    const headers = buildOllamaHeaders(makeContext(vi.fn() as unknown as typeof fetch))

    expect(headers.get('accept')).toBe('application/x-ndjson')
    expect(headers.get('content-type')).toBe('application/json')
    expect(headers.get('authorization')).toBeNull()
    expect(headers.get('x-api-key')).toBeNull()
  })
})

describe('Ollama discovery', () => {
  it('maps the shared /api/tags fixture', () => {
    const fixture = loadJson<unknown>('../contracts/fixtures/models/ollama-tags.json')
    const expected = loadJson<CatalogVector>('../contracts/fixtures/expected/catalogs.json')
      .cases.find((entry) => entry.name === 'ollama')

    const models = mapOllamaModels(fixture)

    expect(models).toHaveLength(2)
    expect(models).toMatchObject(expected?.expected ?? [])
    expect(models.map((model) => model.remoteId)).toEqual([
      'llama3.2:latest',
      'nomic-embed-text:latest',
    ])
    expect(models.map((model) => model.hidden)).toEqual([false, true])
  })

  it('validates a keyless endpoint without a network request', async () => {
    const fetchMock = vi.fn()

    const info = await validateKey(
      makeContext(fetchMock as unknown as typeof fetch),
      new AbortController().signal,
    )

    expect(info).toMatchObject({ label: null, usageUsd: null })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('NDJSON reader', () => {
  it('joins partial lines across chunks and flushes the final line', async () => {
    const encoder = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('{"a":1}\n{"b"'))
        controller.enqueue(encoder.encode(':2}\r\n{"c":3}'))
        controller.close()
      },
    })
    const lines: string[] = []

    await readNdjson(
      { body: stream },
      { onLine: (line) => lines.push(line) },
    )

    expect(lines).toEqual(['{"a":1}', '{"b":2}', '{"c":3}'])
  })

  it('rejects an over-limit incomplete line', async () => {
    await expect(
      readNdjson(
        { body: new Response('1234567890').body },
        { onLine: () => {}, maxBufferChars: 4 },
      ),
    ).rejects.toBeInstanceOf(Error)
  })
})

describe('Ollama streaming fixtures', () => {
  it('emits text, finish, and usage from the native NDJSON fixture', async () => {
    const expected = loadExpected('ollama-ndjson')
    const fetchMock = vi.fn(async () =>
      ndjsonResponse(loadText('../contracts/fixtures/streams/ollama-ndjson.txt')),
    )

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(withoutAt(events)).toEqual(expected.events)

    const text = events
      .filter((event) => event.type === 'text-delta')
      .map((event) => (event.type === 'text-delta' ? event.text : ''))
      .join('')
    expect(text).toBe(expected.text)

    const [url] = fetchMock.mock.calls[0] as unknown as [RequestInfo | URL, RequestInit?]
    expect(String(url)).toBe('http://localhost:11434/api/chat')
  })

  it('maps an in-stream error line', async () => {
    const expected = loadExpectedErrors().cases.find(
      (entry) => entry.name === 'ollama-stream-error',
    )
    const fetchMock = vi.fn(async () =>
      ndjsonResponse(loadText('../contracts/fixtures/errors/ollama-stream-error.txt')),
    )

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      baseRequest(),
      { now: () => FIXED_NOW },
    )

    expect(withoutAt(events)).toEqual([{ type: 'error', error: expected?.error }])
  })

  it('keeps thinking content separate from answer text', () => {
    const events: StreamEvent[] = []
    const mapper = new OllamaStreamMapper({
      context: makeContext(vi.fn() as unknown as typeof fetch),
      emit: (event) => events.push(event),
    })

    mapper.handleLine(
      JSON.stringify({
        message: { role: 'assistant', content: 'Answer.', thinking: 'Thinking.' },
        done: false,
      }),
    )
    mapper.handleLine(
      JSON.stringify({
        message: { role: 'assistant', content: '' },
        done: true,
        done_reason: 'stop',
        prompt_eval_count: 2,
        eval_count: 1,
      }),
    )

    expect(events).toEqual([
      { type: 'start', responseId: null },
      { type: 'text-delta', text: 'Answer.' },
      { type: 'reasoning-delta', text: 'Thinking.' },
      { type: 'finish', reason: 'stop' },
      {
        type: 'usage',
        usage: {
          inputTokens: 2,
          outputTokens: 1,
          totalTokens: 3,
          costUsd: null,
          estimated: false,
        },
      },
    ])
  })
})

describe('Ollama HTTP error mapping', () => {
  const cases = loadExpectedErrors().cases.filter(
    (entry) => entry.providerId === 'ollama' && entry.kind === 'http',
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
})

describe('Ollama retry and cancellation', () => {
  it('retries a 500 before any output', async () => {
    vi.useFakeTimers()
    const serverError = loadJson<unknown>('../contracts/fixtures/errors/ollama-500.json')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(serverError, 500))
      .mockResolvedValueOnce(
        ndjsonResponse(loadText('../contracts/fixtures/streams/ollama-ndjson.txt')),
      )

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
      expect(error.error.message).toContain('Ollama')
      expect(error.error.message).toContain('OLLAMA_ORIGINS')
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
            `${JSON.stringify({
              message: { role: 'assistant', content: 'Hi' },
              done: false,
            })}\n`,
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

describe('Ollama OpenAI-compatible fallback', () => {
  it('uses /v1/chat/completions when the request opts into the fallback', async () => {
    const sse = [
      'data: {"id":"chat-1","choices":[{"delta":{"content":"Hi"},"finish_reason":"stop"}]}',
      '',
      'data: [DONE]',
      '',
      '',
    ].join('\n')
    const fetchMock = vi.fn(async () =>
      new Response(sse, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      }),
    )
    const request = baseRequest({
      params: {
        ...baseRequest().params,
        extra: { ollama: { transport: 'openai-compatible' } },
      },
    })

    const events = await collect(
      makeContext(fetchMock as unknown as typeof fetch),
      request,
      { now: () => FIXED_NOW },
    )

    expect(events.filter((event) => event.type === 'text-delta')).toEqual([
      { type: 'text-delta', text: 'Hi' },
    ])

    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      RequestInfo | URL,
      RequestInit?,
    ]
    expect(String(url)).toBe('http://localhost:11434/v1/chat/completions')

    const body = JSON.parse(String(init?.body)) as Record<string, unknown>
    expect(body).not.toHaveProperty('transport')
  })
})
