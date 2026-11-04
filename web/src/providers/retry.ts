/**
 * Retry timing for provider requests.
 *
 * The policy is intentionally small and injectable so tests can use fake
 * timers and deterministic jitter. It only computes delays and sleeps; the
 * caller decides whether a failure is retryable and whether any output has
 * already been emitted.
 */

/** Maximum number of attempts, including the first request. */
export const RETRY_MAX_ATTEMPTS = 3

/** First backoff delay before jitter. */
export const RETRY_BASE_DELAY_MS = 800

/** Upper bound for a computed backoff delay. */
export const RETRY_MAX_DELAY_MS = 8000

/** Fraction of the exponential delay added as positive jitter. */
export const RETRY_JITTER_RATIO = 0.25

export interface RetryPolicy {
  /** Maximum attempts including the first request. Defaults to 3. */
  maxAttempts?: number
  /** First backoff delay in milliseconds. Defaults to 800. */
  baseDelayMs?: number
  /** Maximum computed backoff delay in milliseconds. Defaults to 8000. */
  maxDelayMs?: number
  /** Injectable random source in `[0, 1)`. Defaults to `Math.random`. */
  random?: () => number
}

/** Raised when a retry sleep is interrupted by an abort signal. */
export class RetryAbortError extends Error {
  constructor() {
    super('Retry sleep was aborted')
    this.name = 'AbortError'
  }
}

/**
 * Compute a jittered exponential backoff delay.
 *
 * `attempt` is 1-based and refers to the attempt that just failed. The first
 * retry therefore starts at `baseDelayMs` (800 ms by default) plus up to 25%
 * positive jitter, doubles each attempt, and never exceeds `maxDelayMs`.
 */
export function computeRetryDelay(attempt: number, policy: RetryPolicy = {}): number {
  const base = policy.baseDelayMs ?? RETRY_BASE_DELAY_MS
  const cap = policy.maxDelayMs ?? RETRY_MAX_DELAY_MS
  const random = policy.random ?? Math.random
  const exponent = Math.max(0, attempt - 1)
  const exponential = Math.min(cap, base * 2 ** exponent)
  const jitter = exponential * RETRY_JITTER_RATIO * random()

  return Math.min(cap, Math.round(exponential + jitter))
}

/**
 * Choose the delay for a retry.
 *
 * A provider-supplied `Retry-After` value wins over the local backoff policy.
 * The value is in seconds and is converted to milliseconds.
 */
export function retryDelayFor(
  retryAfterSeconds: number | null,
  attempt: number,
  policy: RetryPolicy = {},
): number {
  if (retryAfterSeconds !== null) {
    return Math.max(0, Math.round(retryAfterSeconds * 1000))
  }

  return computeRetryDelay(attempt, policy)
}

/** Sleep for `ms`, rejecting with `RetryAbortError` when the signal aborts. */
export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  if (signal?.aborted) {
    return Promise.reject(new RetryAbortError())
  }

  return new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup()
      resolve()
    }, ms)

    const onAbort = (): void => {
      cleanup()
      reject(new RetryAbortError())
    }

    const cleanup = (): void => {
      clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
    }

    signal?.addEventListener('abort', onAbort, { once: true })
  })
}
