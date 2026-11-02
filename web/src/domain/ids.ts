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
