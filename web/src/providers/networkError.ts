/**
 * Browser network failure classification.
 *
 * A browser `fetch` rejection does not say whether the request was aborted, the
 * browser was offline, a CORS policy blocked the response, or the network was
 * otherwise unavailable. These helpers apply the only reliable signals the web
 * platform exposes: the abort signal, `navigator.onLine`, and the error text
 * produced when a CORS preflight or response is blocked.
 *
 * A CORS-shaped failure is only classified as `cors` when the browser reports
 * that it is online. Offline failures stay `network`. No proxy is introduced.
 */

export type NetworkFailureKind = 'aborted' | 'offline' | 'cors' | 'network'

const CORS_SHAPED_PATTERNS: readonly RegExp[] = [
  /failed to fetch/i,
  /networkerror when attempting to fetch resource/i,
  /load failed/i,
  /network request failed/i,
  /access-control-allow-origin/i,
  /\bcors\b/i,
]

/** Return true when a thrown value looks like a browser CORS/network rejection. */
export function isCorsShapedNetworkError(error: unknown): boolean {
  const message =
    error instanceof Error ? error.message : typeof error === 'string' ? error : ''

  return CORS_SHAPED_PATTERNS.some((pattern) => pattern.test(message))
}

/**
 * Read `navigator.onLine` when available.
 *
 * Returns `null` outside a browser so callers do not guess an online state.
 */
export function browserOnlineStatus(): boolean | null {
  if (typeof navigator === 'undefined' || typeof navigator.onLine !== 'boolean') {
    return null
  }

  return navigator.onLine
}

export interface NetworkFailureOptions {
  /** Set from the request's `AbortSignal`. */
  aborted?: boolean
  /** Override `navigator.onLine` for tests or hosts without the API. */
  online?: boolean | null
}

/**
 * Classify a failed `fetch` attempt.
 *
 * Order matters: an explicit abort wins, then a known offline state, then the
 * browser's CORS-shaped rejection text.
 */
export function classifyNetworkFailure(
  error: unknown,
  options: NetworkFailureOptions = {},
): NetworkFailureKind {
  if (options.aborted) {
    return 'aborted'
  }

  const online = options.online === undefined ? browserOnlineStatus() : options.online

  if (online === false) {
    return 'offline'
  }

  if (isCorsShapedNetworkError(error)) {
    return 'cors'
  }

  return 'network'
}

/** Actionable copy for a CORS-shaped failure against a known provider. */
export function corsNetworkMessage(providerLabel: string): string {
  return (
    `Could not reach ${providerLabel}. The browser blocked the request, which may be ` +
    'a CORS restriction. Try the Android app or a self-hosted proxy.'
  )
}
