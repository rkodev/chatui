import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type { SseResponse } from '../providers/sse'

export type SseChunk = string | Uint8Array

const encoder = new TextEncoder()

/**
 * Build a `ReadableStream` that emits the supplied chunks in order.
 *
 * Strings are UTF-8 encoded. Passing a `Uint8Array` lets tests split a
 * multi-byte character across chunk boundaries.
 */
export function createSseStream(chunks: Iterable<SseChunk>): ReadableStream<Uint8Array> {
  const iterator = chunks[Symbol.iterator]()

  return new ReadableStream<Uint8Array>({
    pull(controller) {
      const next = iterator.next()

      if (next.done) {
        controller.close()
        return
      }

      const chunk = next.value
      controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk)
    },
  })
}

/** Build a minimal SSE response around the supplied chunks. */
export function sseResponse(chunks: Iterable<SseChunk>): SseResponse {
  return { body: createSseStream(chunks) }
}

/** Read a checked-in SSE fixture from `contracts/fixtures/streams`. */
export function loadSseFixture(name: string): string {
  return readFileSync(resolve(process.cwd(), '../contracts/fixtures/streams', name), 'utf8')
}
