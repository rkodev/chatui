/**
 * Provider-agnostic Server-Sent Events (SSE) transport.
 *
 * This module only understands the SSE framing rules. It deliberately does not
 * parse provider payloads: every `data:` value is handed to the caller as a raw
 * string. Adapters are responsible for interpreting JSON, `[DONE]` sentinels,
 * and provider-specific event names.
 */

/**
 * Default maximum number of characters retained for an incomplete event.
 *
 * A complete event is drained as soon as its terminating blank line arrives, so
 * this limit only applies to data that has not yet formed a complete event. It
 * protects against a provider that never sends a blank line or that sends an
 * unbounded `data:` line.
 */
export const DEFAULT_MAX_SSE_BUFFER_CHARS = 1024 * 1024

/** Minimal response shape needed by the transport. A real `Response` fits. */
export interface SseResponse {
  readonly body: ReadableStream<Uint8Array> | null
}

export interface ReadSseOptions {
  /** Called once per complete SSE event with the concatenated `data:` value. */
  onEvent: (data: string) => void
  /** Stops reading promptly and rejects with `SseAbortError`. */
  signal?: AbortSignal
  /** Overrides `DEFAULT_MAX_SSE_BUFFER_CHARS`; useful for tests. */
  maxBufferChars?: number
}

/** Base class for normalized transport failures. */
export class SseTransportError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'SseTransportError'
  }
}

/** The response did not expose a readable body. */
export class SseMissingBodyError extends SseTransportError {
  constructor() {
    super('SSE response body is missing')
    this.name = 'SseMissingBodyError'
  }
}

/** An incomplete event grew past the configured buffer limit. */
export class SseBufferOverflowError extends SseTransportError {
  readonly maxBufferChars: number

  constructor(maxBufferChars: number) {
    super(`SSE buffer exceeded the ${maxBufferChars} character limit`)
    this.name = 'SseBufferOverflowError'
    this.maxBufferChars = maxBufferChars
  }
}

/** Reading was stopped by an `AbortSignal`. */
export class SseAbortError extends SseTransportError {
  constructor() {
    super('SSE read was aborted')
    this.name = 'AbortError'
  }
}

/**
 * Read an SSE response until the stream ends or the signal aborts.
 *
 * Line endings are normalized before event splitting, so CRLF, LF, and lone CR
 * all work even when a CRLF pair is split across chunks. Events are separated
 * by a blank line. Only `data:` fields are collected; comments and unknown
 * fields are ignored. Multiple `data:` lines are joined with a single newline.
 *
 * The final event is flushed when the stream ends without a trailing blank
 * line. Empty data values are not emitted, so keep-alive comments never produce
 * blank messages.
 */
export async function readSse(
  response: SseResponse,
  options: ReadSseOptions,
): Promise<void> {
  const { onEvent, signal, maxBufferChars = DEFAULT_MAX_SSE_BUFFER_CHARS } = options

  if (!response.body) {
    throw new SseMissingBodyError()
  }

  if (signal?.aborted) {
    throw new SseAbortError()
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let pendingCarriageReturn = false

  const normalizeChunk = (chunk: string): string => {
    let text = chunk

    // A CR at the end of the previous chunk may be the first half of CRLF.
    if (pendingCarriageReturn) {
      text = `\r${text}`
      pendingCarriageReturn = false
    }

    // Hold a trailing CR until the next chunk so CRLF is not split into two
    // line endings.
    if (text.endsWith('\r')) {
      pendingCarriageReturn = true
      text = text.slice(0, -1)
    }

    return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  }

  const emitEvent = (rawEvent: string): void => {
    const dataLines: string[] = []

    for (const line of rawEvent.split('\n')) {
      if (line === '' || line.startsWith(':')) {
        continue
      }

      const colon = line.indexOf(':')
      const field = colon === -1 ? line : line.slice(0, colon)

      if (field !== 'data') {
        continue
      }

      let value = colon === -1 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) {
        value = value.slice(1)
      }
      dataLines.push(value)
    }

    if (dataLines.length === 0) {
      return
    }

    const data = dataLines.join('\n')
    if (data === '') {
      return
    }

    onEvent(data)
  }

  const drainCompleteEvents = (): void => {
    let boundary = buffer.indexOf('\n\n')

    while (boundary !== -1) {
      const rawEvent = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      emitEvent(rawEvent)
      boundary = buffer.indexOf('\n\n')
    }

    if (buffer.length > maxBufferChars) {
      throw new SseBufferOverflowError(maxBufferChars)
    }
  }

  const onAbort = (): void => {
    // Cancelling the reader resolves a pending read with `done: true`, which
    // lets the loop observe the abort without waiting for more network data.
    void reader.cancel().catch(() => {})
  }

  signal?.addEventListener('abort', onAbort, { once: true })

  try {
    while (true) {
      if (signal?.aborted) {
        throw new SseAbortError()
      }

      let result: ReadableStreamReadResult<Uint8Array>
      try {
        result = await reader.read()
      } catch (error) {
        if (signal?.aborted) {
          throw new SseAbortError()
        }
        throw new SseTransportError('Failed to read the SSE stream', { cause: error })
      }

      if (signal?.aborted) {
        throw new SseAbortError()
      }

      if (result.done) {
        break
      }

      if (result.value) {
        buffer += normalizeChunk(decoder.decode(result.value, { stream: true }))
        drainCompleteEvents()
      }
    }

    const tail = decoder.decode()
    if (tail) {
      buffer += normalizeChunk(tail)
    }

    if (pendingCarriageReturn) {
      buffer += '\n'
      pendingCarriageReturn = false
    }

    // Flush a final event that was not followed by a blank line. Trailing
    // newlines are removed so a buffer containing only separators is ignored.
    const finalEvent = buffer.replace(/\n+$/, '')
    if (finalEvent !== '') {
      emitEvent(finalEvent)
    }
  } finally {
    signal?.removeEventListener('abort', onAbort)
    try {
      reader.releaseLock()
    } catch {
      // The reader may already be released after cancellation.
    }
  }
}
