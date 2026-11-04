import { describe, expect, it } from 'vitest'
import { loadSseFixture, sseResponse, type SseChunk } from '../test/sse'
import {
  DEFAULT_MAX_SSE_BUFFER_CHARS,
  SseAbortError,
  SseBufferOverflowError,
  SseMissingBodyError,
  SseTransportError,
  readSse,
  type ReadSseOptions,
} from './sse'

async function collect(
  chunks: Iterable<SseChunk>,
  options: Partial<Omit<ReadSseOptions, 'onEvent'>> = {},
): Promise<string[]> {
  const events: string[] = []

  await readSse(sseResponse(chunks), {
    ...options,
    onEvent: (data) => events.push(data),
  })

  return events
}

function hangingStream(firstChunk: string): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()

  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoder.encode(firstChunk))
      // Intentionally never close: the test aborts while a read is pending.
    },
  })
}

describe('readSse framing', () => {
  it('parses LF events and ignores keep-alive comments', async () => {
    const events = await collect([
      ': OPENROUTER PROCESSING\n\n',
      'data: {"a":1}\n\n',
      ': OPENROUTER PROCESSING\n\n',
      'data: {"b":2}\n\n',
    ])

    expect(events).toEqual(['{"a":1}', '{"b":2}'])
  })

  it('normalizes CRLF and lone CR line endings', async () => {
    const events = await collect(['data: one\r\n\r\n', 'data: two\r\r', 'data: three\n\n'])

    expect(events).toEqual(['one', 'two', 'three'])
  })

  it('handles a CRLF pair split across chunks', async () => {
    const events = await collect(['data: one\r', '\n\r', '\ndata: two\r\n\r\n'])

    expect(events).toEqual(['one', 'two'])
  })

  it('handles a chunk split inside a data line', async () => {
    const events = await collect(['data: hel', 'lo\n\n'])

    expect(events).toEqual(['hello'])
  })

  it('handles a chunk split inside JSON', async () => {
    const events = await collect(['data: {"text":"hel', 'lo"}\n\n'])

    expect(events).toEqual(['{"text":"hello"}'])
  })

  it('concatenates multi-line data fields with a single newline', async () => {
    const events = await collect(['data: line one\ndata: line two\ndata:line three\n\n'])

    expect(events).toEqual(['line one\nline two\nline three'])
  })

  it('strips exactly one leading space from data values', async () => {
    const events = await collect(['data:  two spaces\n\n', 'data:no space\n\n'])

    expect(events).toEqual([' two spaces', 'no space'])
  })

  it('ignores comments, empty events, and unknown fields', async () => {
    const events = await collect([
      ': OPENROUTER PROCESSING\n\n',
      '\n\n',
      'event: message\nid: 1\nretry: 100\ndata: payload\n\n',
      'data:\n\n',
      'data: \n\n',
    ])

    expect(events).toEqual(['payload'])
  })

  it('does not parse provider JSON', async () => {
    const events = await collect(['data: {not valid json}\n\n'])

    expect(events).toEqual(['{not valid json}'])
  })

  it('decodes UTF-8 split across chunks', async () => {
    const bytes = new TextEncoder().encode('data: héllo\n\n')
    const events = await collect([bytes.slice(0, 8), bytes.slice(8)])

    expect(events).toEqual(['héllo'])
  })
})

describe('readSse final flush', () => {
  it('flushes the final event when the stream ends without a blank line', async () => {
    const events = await collect(['data: final'])

    expect(events).toEqual(['final'])
  })

  it('flushes the final event when the stream ends with a single newline', async () => {
    const events = await collect(['data: final\n'])

    expect(events).toEqual(['final'])
  })

  it('flushes a final event split across chunks', async () => {
    const events = await collect(['data: fi', 'nal'])

    expect(events).toEqual(['final'])
  })

  it('flushes multi-line data in the final buffer', async () => {
    const events = await collect(['data: one\ndata: two'])

    expect(events).toEqual(['one\ntwo'])
  })

  it('does not duplicate an event that already ended with a blank line', async () => {
    const events = await collect(['data: one\n\n'])

    expect(events).toEqual(['one'])
  })

  it('does not emit a final comment as an event', async () => {
    const events = await collect([': OPENROUTER PROCESSING'])

    expect(events).toEqual([])
  })
})

describe('readSse buffering', () => {
  it('exposes a bounded default buffer limit', () => {
    expect(DEFAULT_MAX_SSE_BUFFER_CHARS).toBe(1024 * 1024)
  })

  it('rejects when an incomplete event exceeds the buffer limit', async () => {
    const events: string[] = []

    await expect(
      readSse(sseResponse([`data: ${'x'.repeat(100)}`]), {
        onEvent: (data) => events.push(data),
        maxBufferChars: 50,
      }),
    ).rejects.toBeInstanceOf(SseBufferOverflowError)

    expect(events).toEqual([])
  })

  it('reports the configured buffer limit on overflow', async () => {
    try {
      await readSse(sseResponse([`data: ${'x'.repeat(100)}`]), {
        onEvent: () => {},
        maxBufferChars: 50,
      })
      throw new Error('Expected readSse to reject')
    } catch (error) {
      expect(error).toBeInstanceOf(SseBufferOverflowError)
      expect((error as SseBufferOverflowError).maxBufferChars).toBe(50)
    }
  })

  it('does not overflow when complete events are drained', async () => {
    const chunks = Array.from({ length: 100 }, (_, index) => `data: ${index}\n\n`)
    const events = await collect(chunks, { maxBufferChars: 32 })

    expect(events).toHaveLength(100)
    expect(events[0]).toBe('0')
    expect(events[99]).toBe('99')
  })
})

describe('readSse cancellation and failures', () => {
  it('rejects immediately when the signal is already aborted', async () => {
    const controller = new AbortController()
    controller.abort()

    await expect(
      readSse(sseResponse(['data: one\n\n']), {
        signal: controller.signal,
        onEvent: () => {},
      }),
    ).rejects.toBeInstanceOf(SseAbortError)
  })

  it('stops reading when the signal aborts', async () => {
    const controller = new AbortController()
    const events: string[] = []
    let resolveFirstEvent: () => void = () => {}
    const sawFirstEvent = new Promise<void>((resolve) => {
      resolveFirstEvent = resolve
    })

    const promise = readSse(
      { body: hangingStream('data: one\n\n') },
      {
        signal: controller.signal,
        onEvent: (data) => {
          events.push(data)
          resolveFirstEvent()
        },
      },
    )

    await sawFirstEvent
    controller.abort()

    await expect(promise).rejects.toBeInstanceOf(SseAbortError)
    expect(events).toEqual(['one'])
  })

  it('rejects when the response has no body', async () => {
    await expect(readSse({ body: null }, { onEvent: () => {} })).rejects.toBeInstanceOf(
      SseMissingBodyError,
    )
  })

  it('wraps stream read failures in a transport error', async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.error(new Error('boom'))
      },
    })

    try {
      await readSse({ body: stream }, { onEvent: () => {} })
      throw new Error('Expected readSse to reject')
    } catch (error) {
      expect(error).toBeInstanceOf(SseTransportError)
      expect((error as SseTransportError).name).toBe('SseTransportError')
      expect((error as SseTransportError).cause).toBeInstanceOf(Error)
    }
  })
})

describe('readSse OpenRouter keep-alive fixture', () => {
  it('ignores keep-alive comments and emits only data payloads', async () => {
    const fixture = loadSseFixture('openrouter-keepalive.sse')
    const events = await collect([fixture])

    expect(events).toHaveLength(5)
    expect(events[0]).toContain('"content":"Keep"')
    expect(events[1]).toContain('"content":" alive"')
    expect(events[2]).toContain('"content":" comments"')
    expect(events[3]).toContain('"finish_reason":"stop"')
    expect(events[4]).toBe('[DONE]')
    expect(events.every((event) => event.length > 0)).toBe(true)
    expect(events.some((event) => event.includes('OPENROUTER PROCESSING'))).toBe(false)
  })

  it('handles the fixture split at arbitrary chunk boundaries', async () => {
    const fixture = loadSseFixture('openrouter-keepalive.sse')
    const chunks = fixture.match(/[\s\S]{1,7}/g) ?? []
    const events = await collect(chunks)

    expect(events).toHaveLength(5)
    expect(events[4]).toBe('[DONE]')
  })

  it('handles the fixture with CRLF line endings', async () => {
    const fixture = loadSseFixture('openrouter-keepalive.sse').replace(/\n/g, '\r\n')
    const events = await collect([fixture])

    expect(events).toHaveLength(5)
    expect(events[4]).toBe('[DONE]')
  })
})
