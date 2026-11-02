/**
 * WebCrypto primitives for secret storage.
 *
 * Production callers must use the default PBKDF2 iteration count (600,000).
 * Tests may pass a lower `pbkdf2Iterations` value through `createCrypto` to
 * keep the suite fast; the override never changes the production default.
 */

export const AES_GCM = 'AES-GCM'
export const AES_KEY_LENGTH = 256
export const PBKDF2_HASH = 'SHA-256'
export const PBKDF2_ITERATIONS = 600_000
export const IV_BYTES = 12
export const SALT_BYTES = 16

export interface EncryptedPayload {
  /** Base64-encoded AES-GCM initialization vector. */
  iv: string
  /** Base64-encoded AES-GCM ciphertext. */
  ciphertext: string
}

export interface CryptoOptions {
  /**
   * Test-only override for the PBKDF2 iteration count. Production callers
   * should omit this so the 600,000-iteration default is used.
   */
  pbkdf2Iterations?: number
}

export interface CryptoPrimitives {
  readonly pbkdf2Iterations: number
  randomBytes(length: number): Uint8Array
  generateIv(): Uint8Array
  generateSalt(): Uint8Array
  generateDeviceKey(): Promise<CryptoKey>
  derivePassphraseKey(passphrase: string, salt: Uint8Array): Promise<CryptoKey>
  encrypt(key: CryptoKey, plaintext: string, iv?: Uint8Array): Promise<EncryptedPayload>
  decrypt(key: CryptoKey, payload: EncryptedPayload): Promise<string>
}

function subtle(): SubtleCrypto {
  const value = globalThis.crypto?.subtle
  if (value === undefined) {
    throw new Error('WebCrypto is not available in this runtime')
  }
  return value
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  return copy.buffer
}

export function randomBytes(length: number): Uint8Array {
  if (!Number.isInteger(length) || length <= 0) {
    throw new Error('Random byte length must be a positive integer')
  }
  const bytes = new Uint8Array(length)
  globalThis.crypto.getRandomValues(bytes)
  return bytes
}

export function generateIv(): Uint8Array {
  return randomBytes(IV_BYTES)
}

export function generateSalt(): Uint8Array {
  return randomBytes(SALT_BYTES)
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  const chunkSize = 0x8000
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize))
  }
  return btoa(binary)
}

export function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes
}

/**
 * Generate the per-browser-profile device key used by remember mode. The key
 * is deliberately non-extractable so it cannot be exported from IndexedDB.
 */
export async function generateDeviceKey(): Promise<CryptoKey> {
  return (await subtle().generateKey(
    { name: AES_GCM, length: AES_KEY_LENGTH },
    false,
    ['encrypt', 'decrypt'],
  )) as CryptoKey
}

/**
 * Derive an AES-GCM key from a passphrase with PBKDF2-HMAC-SHA256. The derived
 * key is non-extractable and only ever lives in memory.
 */
export async function derivePassphraseKey(
  passphrase: string,
  salt: Uint8Array,
  iterations: number = PBKDF2_ITERATIONS,
): Promise<CryptoKey> {
  if (!Number.isInteger(iterations) || iterations <= 0) {
    throw new Error('PBKDF2 iterations must be a positive integer')
  }

  const baseKey = await subtle().importKey(
    'raw',
    toArrayBuffer(new TextEncoder().encode(passphrase)),
    'PBKDF2',
    false,
    ['deriveKey'],
  )

  return subtle().deriveKey(
    {
      name: 'PBKDF2',
      salt: toArrayBuffer(salt),
      iterations,
      hash: PBKDF2_HASH,
    },
    baseKey,
    { name: AES_GCM, length: AES_KEY_LENGTH },
    false,
    ['encrypt', 'decrypt'],
  )
}

export async function encryptText(
  key: CryptoKey,
  plaintext: string,
  iv: Uint8Array = generateIv(),
): Promise<EncryptedPayload> {
  const ciphertext = await subtle().encrypt(
    { name: AES_GCM, iv: toArrayBuffer(iv) },
    key,
    toArrayBuffer(new TextEncoder().encode(plaintext)),
  )

  return {
    iv: bytesToBase64(iv),
    ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
  }
}

export async function decryptText(key: CryptoKey, payload: EncryptedPayload): Promise<string> {
  const plaintext = await subtle().decrypt(
    { name: AES_GCM, iv: toArrayBuffer(base64ToBytes(payload.iv)) },
    key,
    toArrayBuffer(base64ToBytes(payload.ciphertext)),
  )

  return new TextDecoder().decode(plaintext)
}

/**
 * Build a crypto facade with a fixed PBKDF2 iteration count. The default is
 * the production value; tests pass a smaller count explicitly.
 */
export function createCrypto(options: CryptoOptions = {}): CryptoPrimitives {
  const iterations = options.pbkdf2Iterations ?? PBKDF2_ITERATIONS
  if (!Number.isInteger(iterations) || iterations <= 0) {
    throw new Error('PBKDF2 iterations must be a positive integer')
  }

  return {
    pbkdf2Iterations: iterations,
    randomBytes,
    generateIv,
    generateSalt,
    generateDeviceKey,
    derivePassphraseKey: (passphrase, salt) => derivePassphraseKey(passphrase, salt, iterations),
    encrypt: encryptText,
    decrypt: decryptText,
  }
}

export const defaultCrypto: CryptoPrimitives = createCrypto()
