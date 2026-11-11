import { describe, expect, it } from 'vitest'
import { isUuidV7, newId, newStorageKey } from './ids'

describe('newId', () => {
  it('produces UUIDv7 values', () => {
    const id = newId()

    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
    expect(id[14]).toBe('7')
    expect('89ab').toContain(id[19])
    expect(isUuidV7(id)).toBe(true)
  })

  it('produces unique values usable as entity IDs', () => {
    const ids = Array.from({ length: 100 }, () => newId())

    expect(new Set(ids).size).toBe(ids.length)
    expect(ids.every(isUuidV7)).toBe(true)
  })

  it('rejects non-v7 UUIDs and malformed values', () => {
    expect(isUuidV7('00000000-0000-4000-8000-000000000000')).toBe(false)
    expect(isUuidV7('not-a-uuid')).toBe(false)
  })
})

describe('newStorageKey', () => {
  it('produces 32 lowercase hex characters', () => {
    expect(newStorageKey()).toMatch(/^[0-9a-f]{32}$/u)
  })

  it('produces unique values', () => {
    const keys = Array.from({ length: 100 }, () => newStorageKey())

    expect(new Set(keys).size).toBe(keys.length)
  })
})
