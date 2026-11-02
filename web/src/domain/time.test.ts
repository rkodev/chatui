import { describe, expect, it } from 'vitest'
import { isIsoUtc, nowIso } from './time'

describe('nowIso', () => {
  it('returns an ISO 8601 UTC string', () => {
    const value = nowIso()

    expect(value).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/)
    expect(value.endsWith('Z')).toBe(true)
    expect(Number.isNaN(Date.parse(value))).toBe(false)
    expect(isIsoUtc(value)).toBe(true)
  })

  it('rejects non-UTC or malformed timestamps', () => {
    expect(isIsoUtc('2026-11-02T12:00:00+01:00')).toBe(false)
    expect(isIsoUtc('2026-11-02T12:00:00')).toBe(false)
    expect(isIsoUtc('not-a-date')).toBe(false)
  })
})
