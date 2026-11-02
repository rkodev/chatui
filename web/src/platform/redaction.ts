/**
 * Central redaction helpers for diagnostics.
 *
 * Header matching is case-insensitive. Secret values are replaced before any
 * text leaves the app, so callers must route diagnostic headers and messages
 * through these helpers instead of logging raw values.
 */

export const REDACTED = '[REDACTED]'

export const DEFAULT_SECRET_HEADERS = [
  'authorization',
  'x-api-key',
  'x-goog-api-key',
] as const

export type HeaderValue = string | string[] | undefined
export type RedactedHeaderValue = string | string[]
export type RedactedHeaders = Record<string, RedactedHeaderValue>

export interface RedactionOptions {
  /** Additional or replacement secret header names. */
  headers?: Iterable<string>
  /** Replacement text. Defaults to `[REDACTED]`. */
  replacement?: string
}

function secretHeaderSet(options: RedactionOptions): Set<string> {
  const headers = options.headers ?? DEFAULT_SECRET_HEADERS
  return new Set(
    [...headers]
      .map((header) => header.trim().toLowerCase())
      .filter((header) => header.length > 0),
  )
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Return a copy of the headers with every configured secret header value
 * replaced. Non-secret headers are preserved.
 */
export function redactHeaders(
  headers: Headers | Record<string, HeaderValue>,
  options: RedactionOptions = {},
): RedactedHeaders {
  const secretHeaders = secretHeaderSet(options)
  const replacement = options.replacement ?? REDACTED
  const entries: Array<[string, HeaderValue]> =
    typeof Headers !== 'undefined' && headers instanceof Headers
      ? [...headers.entries()]
      : Object.entries(headers)

  const redacted: RedactedHeaders = {}
  for (const [name, value] of entries) {
    if (value === undefined) {
      continue
    }
    redacted[name] = secretHeaders.has(name.toLowerCase()) ? replacement : value
  }
  return redacted
}

/**
 * Redact secret header values from a diagnostic string. Handles header-style
 * (`Authorization: ...`), assignment-style (`x-api-key=...`), and JSON-style
 * (`"Authorization": "..."`) text. Bare bearer tokens are also redacted as a
 * safety net.
 */
export function redactText(text: string, options: RedactionOptions = {}): string {
  const replacement = options.replacement ?? REDACTED
  let redacted = text

  for (const header of secretHeaderSet(options)) {
    const pattern = new RegExp(
      `(${escapeRegExp(header)})(\\s*"?\\s*[:=]\\s*)("[^"]*"|'[^']*'|[^\\r\\n]*)`,
      'gi',
    )
    redacted = redacted.replace(
      pattern,
      (_match: string, name: string, separator: string, value: string) => {
        const quote = value.startsWith('"') ? '"' : value.startsWith("'") ? "'" : ''
        return `${name}${separator}${quote}${replacement}${quote}`
      },
    )
  }

  return redacted.replace(/\bBearer\s+[A-Za-z0-9._~+/=-]+/gi, `Bearer ${replacement}`)
}
