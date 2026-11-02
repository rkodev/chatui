import { describe, expect, it } from 'vitest'
import {
  DEFAULT_SECRET_HEADERS,
  REDACTED,
  redactHeaders,
  redactText,
} from './redaction'

describe('redaction', () => {
  it('redacts all default secret headers case-insensitively', () => {
    const redacted = redactHeaders({
      Authorization: 'Bearer sk-123',
      'x-api-key': 'key-456',
      'X-Goog-Api-Key': 'key-789',
      'Content-Type': 'application/json',
    })

    expect(redacted.Authorization).toBe(REDACTED)
    expect(redacted['x-api-key']).toBe(REDACTED)
    expect(redacted['X-Goog-Api-Key']).toBe(REDACTED)
    expect(redacted['Content-Type']).toBe('application/json')
    expect(JSON.stringify(redacted)).not.toContain('sk-123')
    expect(JSON.stringify(redacted)).not.toContain('key-456')
    expect(JSON.stringify(redacted)).not.toContain('key-789')
  })

  it('redacts configurable secret headers', () => {
    const redacted = redactHeaders(
      { 'X-Custom-Secret': 'custom-value', Accept: 'text/plain' },
      { headers: ['x-custom-secret'] },
    )

    expect(redacted['X-Custom-Secret']).toBe(REDACTED)
    expect(redacted.Accept).toBe('text/plain')
  })

  it('redacts Headers instances', () => {
    const headers = new Headers()
    headers.set('Authorization', 'Bearer sk-123')
    headers.set('Accept', 'application/json')

    const redacted = redactHeaders(headers)

    expect(redacted.authorization).toBe(REDACTED)
    expect(redacted.accept).toBe('application/json')
  })

  it('redacts header values in diagnostic text', () => {
    const text = [
      'Authorization: Bearer sk-123',
      'x-api-key: key-456',
      'X-Goog-Api-Key: key-789',
      'Content-Type: application/json',
    ].join('\n')

    const redacted = redactText(text)

    expect(redacted).toContain('Authorization: [REDACTED]')
    expect(redacted).toContain('x-api-key: [REDACTED]')
    expect(redacted).toContain('X-Goog-Api-Key: [REDACTED]')
    expect(redacted).toContain('Content-Type: application/json')
    expect(redacted).not.toContain('sk-123')
    expect(redacted).not.toContain('key-456')
    expect(redacted).not.toContain('key-789')
  })

  it('redacts JSON-style header values', () => {
    const redacted = redactText('{"Authorization":"Bearer sk-123","x-api-key":"key-456"}')

    expect(redacted).not.toContain('sk-123')
    expect(redacted).not.toContain('key-456')
    expect(redacted).toContain('"Authorization":"[REDACTED]"')
  })

  it('redacts bare bearer tokens', () => {
    expect(redactText('request failed with Bearer sk-123')).toBe(
      'request failed with Bearer [REDACTED]',
    )
  })

  it('redacts custom headers in text', () => {
    const redacted = redactText('X-Custom-Secret: custom-value', {
      headers: ['x-custom-secret'],
    })

    expect(redacted).toBe('X-Custom-Secret: [REDACTED]')
  })

  it('exposes the default header names', () => {
    expect(DEFAULT_SECRET_HEADERS).toEqual(['authorization', 'x-api-key', 'x-goog-api-key'])
  })
})
