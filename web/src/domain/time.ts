const ISO_UTC_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/

/**
 * Return the current time as an ISO 8601 UTC string.
 *
 * `Date.prototype.toISOString` always emits UTC with a `Z` suffix and
 * millisecond precision.
 */
export function nowIso(): string {
  return new Date().toISOString()
}

/**
 * Return true when `value` is an ISO 8601 UTC timestamp with a `Z` suffix.
 */
export function isIsoUtc(value: string): boolean {
  if (!ISO_UTC_PATTERN.test(value)) {
    return false
  }

  return Number.isFinite(Date.parse(value))
}
