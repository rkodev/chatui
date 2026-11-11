import { v7 as uuidv7 } from 'uuid'

const UUID_V7_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

/**
 * Generate a time-ordered UUIDv7 entity ID.
 *
 * IDs are generated client-side; provider IDs are never used for local
 * identity.
 */
export function newId(): string {
  return uuidv7()
}

/**
 * Return true when `value` is a canonical UUIDv7 string.
 */
export function isUuidV7(value: string): boolean {
  return UUID_V7_PATTERN.test(value)
}

const STORAGE_KEY_BYTES = 16

/**
 * Generate a random 128-bit backup storage key as 32 lowercase hex characters.
 *
 * The key is independent of the conversation UUID so remote object names do
 * not leak conversation identity or creation order.
 */
export function newStorageKey(): string {
  const bytes = new Uint8Array(STORAGE_KEY_BYTES)
  globalThis.crypto.getRandomValues(bytes)
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}
