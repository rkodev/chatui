/**
 * `CHATUI1\n` backup envelope codec.
 *
 * The byte layout is frozen in `docs/operations/M5/M5-decisions.md`
 * (`DEC-M5-05` through `DEC-M5-08`) and exercised by
 * `contracts/fixtures/backup/vectors.json`:
 *
 *   magic (8) | uint32be headerLength | UTF-8 header JSON | IV (12) | ciphertext || tag (16)
 *
 * This module only produces and consumes bytes. It never writes to storage, so
 * callers cannot accidentally persist plaintext through the codec.
 */

import {
  IV_BYTES,
  PBKDF2_ITERATIONS,
  SALT_BYTES,
  base64ToBytes,
  bytesToBase64,
  derivePassphraseKey,
  generateIv,
} from '../../platform/crypto'

export const MAGIC = 'CHATUI1\n'
export const ENVELOPE_VERSION = 1
export const FORMAT_VERSION = 1
export const ALG = 'AES-256-GCM'
export const KDF = 'pbkdf2-sha256'
export const DEFAULT_ITERATIONS = PBKDF2_ITERATIONS
export const HEADER_LENGTH_BYTES = 4
export const MAX_HEADER_BYTES = 65_536
export const TAG_BYTES = 16
export const KEY_BYTES = 32

const MAGIC_BYTES = new TextEncoder().encode(MAGIC)
const MAX_ITERATIONS = 10_000_000
const MAX_OBJECT_KEY_LENGTH = 4096
const HEADER_FIELDS = [
  'v',
  'formatVersion',
  'alg',
  'kdf',
  'iterations',
  'salt',
  'objectType',
  'objectKey',
] as const
const OBJECT_TYPES: readonly BackupObjectType[] = ['manifest', 'conversation', 'attachment']

export type BackupObjectType = 'manifest' | 'conversation' | 'attachment'

export type EnvelopeErrorCode =
  | 'authentication-failed'
  | 'unsupported-version'
  | 'unsupported-alg'
  | 'unsupported-kdf'
  | 'malformed-envelope'
  | 'invalid-header'
  | 'decompression-failed'

export class EnvelopeError extends Error {
  readonly code: EnvelopeErrorCode

  constructor(code: EnvelopeErrorCode, message: string) {
    super(message)
    this.name = 'EnvelopeError'
    this.code = code
  }
}

export interface EnvelopeHeader {
  v: number
  formatVersion: number
  alg: string
  kdf: string
  iterations: number
  salt: string
  objectType: BackupObjectType
  objectKey: string
}

export interface EnvelopeParts {
  header: EnvelopeHeader
  iv: Uint8Array
  ciphertext: Uint8Array
}

export interface EncryptObjectOptions {
  passphrase: string
  salt: Uint8Array
  objectType: BackupObjectType
  objectKey: string
  plaintext: string | Uint8Array
  /** Defaults to `true` for conversations and `false` for other objects. */
  compress?: boolean
  /** Test-only deterministic IV. Production callers must omit this. */
  iv?: Uint8Array
  /** Test-only PBKDF2 iteration override. Production callers must omit this. */
  iterations?: number
}

export interface DecryptObjectOptions {
  passphrase: string
  bytes: Uint8Array
}

export interface DecryptedObject {
  header: EnvelopeHeader
  plaintext: Uint8Array
}

export interface VerificationTokenOptions {
  passphrase: string
  salt: Uint8Array
  /** Test-only PBKDF2 iteration override. Production callers must omit this. */
  iterations?: number
  /** Test-only deterministic IV. Production callers must omit this. */
  iv?: Uint8Array
}

export interface VerifyPassphraseOptions {
  passphrase: string
  token: Uint8Array
}

/**
 * A small encrypted marker stored alongside the backup set. It lets a client
 * reject a wrong passphrase before touching local data. The marker is encrypted
 * with the same key and AAD scheme as a backup object, using the reserved
 * `verification.enc` object key.
 */
export const VERIFICATION_MARKER = 'chatui-backup-verification-v1'
export const VERIFICATION_OBJECT_TYPE: BackupObjectType = 'manifest'
export const VERIFICATION_OBJECT_KEY = 'verification.enc'

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

function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.length, 0)
  const output = new Uint8Array(total)
  let offset = 0
  for (const part of parts) {
    output.set(part, offset)
    offset += part.length
  }
  return output
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) {
    return false
  }
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) {
      return false
    }
  }
  return true
}

function writeUint32BE(value: number): Uint8Array {
  if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) {
    throw new EnvelopeError('malformed-envelope', 'uint32 value is out of range')
  }
  return new Uint8Array([
    (value >>> 24) & 0xff,
    (value >>> 16) & 0xff,
    (value >>> 8) & 0xff,
    value & 0xff,
  ])
}

function readUint32BE(bytes: Uint8Array, offset: number): number {
  return (
    ((bytes[offset] << 24) |
      (bytes[offset + 1] << 16) |
      (bytes[offset + 2] << 8) |
      bytes[offset + 3]) >>>
    0
  )
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function validateHeader(value: unknown): EnvelopeHeader {
  if (!isRecord(value)) {
    throw new EnvelopeError('invalid-header', 'Envelope header must be a JSON object')
  }

  for (const key of Object.keys(value)) {
    if (!(HEADER_FIELDS as readonly string[]).includes(key)) {
      throw new EnvelopeError('invalid-header', `Unknown envelope header field: ${key}`)
    }
  }

  const v = value.v
  if (typeof v !== 'number' || !Number.isInteger(v)) {
    throw new EnvelopeError('invalid-header', 'Envelope header v must be an integer')
  }
  if (v !== ENVELOPE_VERSION) {
    throw new EnvelopeError('unsupported-version', `Unsupported envelope version: ${v}`)
  }

  const formatVersion = value.formatVersion
  if (typeof formatVersion !== 'number' || !Number.isInteger(formatVersion)) {
    throw new EnvelopeError('invalid-header', 'Envelope header formatVersion must be an integer')
  }
  if (formatVersion !== FORMAT_VERSION) {
    throw new EnvelopeError(
      'unsupported-version',
      `Unsupported backup format version: ${formatVersion}`,
    )
  }

  const alg = value.alg
  if (typeof alg !== 'string') {
    throw new EnvelopeError('invalid-header', 'Envelope header alg must be a string')
  }
  if (alg !== ALG) {
    throw new EnvelopeError('unsupported-alg', `Unsupported envelope algorithm: ${alg}`)
  }

  const kdf = value.kdf
  if (typeof kdf !== 'string') {
    throw new EnvelopeError('invalid-header', 'Envelope header kdf must be a string')
  }
  if (kdf !== KDF) {
    throw new EnvelopeError('unsupported-kdf', `Unsupported envelope KDF: ${kdf}`)
  }

  const iterations = value.iterations
  if (
    typeof iterations !== 'number' ||
    !Number.isInteger(iterations) ||
    iterations <= 0 ||
    iterations > MAX_ITERATIONS
  ) {
    throw new EnvelopeError('invalid-header', 'Envelope header iterations is out of range')
  }

  const salt = value.salt
  if (typeof salt !== 'string') {
    throw new EnvelopeError('invalid-header', 'Envelope header salt must be a string')
  }
  let saltBytes: Uint8Array
  try {
    saltBytes = base64ToBytes(salt)
  } catch {
    throw new EnvelopeError('invalid-header', 'Envelope header salt is not valid base64')
  }
  if (saltBytes.length !== SALT_BYTES) {
    throw new EnvelopeError('invalid-header', 'Envelope header salt must be 16 bytes')
  }

  const objectType = value.objectType
  if (typeof objectType !== 'string' || !OBJECT_TYPES.includes(objectType as BackupObjectType)) {
    throw new EnvelopeError(
      'invalid-header',
      'Envelope header objectType is not a v1 backup object type',
    )
  }

  const objectKey = value.objectKey
  if (
    typeof objectKey !== 'string' ||
    objectKey.length === 0 ||
    objectKey.length > MAX_OBJECT_KEY_LENGTH
  ) {
    throw new EnvelopeError('invalid-header', 'Envelope header objectKey is invalid')
  }
  for (let index = 0; index < objectKey.length; index += 1) {
    const code = objectKey.charCodeAt(index)
    if (code > 0x7f || code < 0x20 || code === 0x7f) {
      throw new EnvelopeError('invalid-header', 'Envelope header objectKey must be printable ASCII')
    }
  }

  return {
    v,
    formatVersion,
    alg,
    kdf,
    iterations,
    salt: bytesToBase64(saltBytes),
    objectType: objectType as BackupObjectType,
    objectKey,
  }
}

function canonicalHeaderJson(header: EnvelopeHeader): string {
  return JSON.stringify({
    v: header.v,
    formatVersion: header.formatVersion,
    alg: header.alg,
    kdf: header.kdf,
    iterations: header.iterations,
    salt: header.salt,
    objectType: header.objectType,
    objectKey: header.objectKey,
  })
}

/**
 * Build the AAD exactly as frozen in `DEC-M5-07`:
 * `uint32be(len(objectType)) || objectType || uint32be(len(objectKey)) || objectKey`.
 */
export function buildAad(objectType: string, objectKey: string): Uint8Array {
  const typeBytes = new TextEncoder().encode(objectType)
  const keyBytes = new TextEncoder().encode(objectKey)
  return concatBytes(
    writeUint32BE(typeBytes.length),
    typeBytes,
    writeUint32BE(keyBytes.length),
    keyBytes,
  )
}

export function encodeEnvelope({ header, iv, ciphertext }: EnvelopeParts): Uint8Array {
  const normalized = validateHeader(header)
  if (iv.length !== IV_BYTES) {
    throw new EnvelopeError('malformed-envelope', 'Envelope IV must be 12 bytes')
  }
  if (ciphertext.length < TAG_BYTES) {
    throw new EnvelopeError('malformed-envelope', 'Envelope ciphertext must include a 16-byte tag')
  }

  const headerBytes = new TextEncoder().encode(canonicalHeaderJson(normalized))
  if (headerBytes.length === 0 || headerBytes.length > MAX_HEADER_BYTES) {
    throw new EnvelopeError('malformed-envelope', 'Envelope header length is out of range')
  }

  return concatBytes(MAGIC_BYTES, writeUint32BE(headerBytes.length), headerBytes, iv, ciphertext)
}

export function decodeEnvelope(bytes: Uint8Array): EnvelopeParts {
  if (bytes.length < MAGIC_BYTES.length + HEADER_LENGTH_BYTES) {
    throw new EnvelopeError('malformed-envelope', 'Envelope is too short')
  }
  if (!bytesEqual(bytes.subarray(0, MAGIC_BYTES.length), MAGIC_BYTES)) {
    throw new EnvelopeError('malformed-envelope', 'Envelope magic does not match')
  }

  const headerLength = readUint32BE(bytes, MAGIC_BYTES.length)
  if (headerLength === 0 || headerLength > MAX_HEADER_BYTES) {
    throw new EnvelopeError('malformed-envelope', 'Envelope header length is out of range')
  }

  const headerStart = MAGIC_BYTES.length + HEADER_LENGTH_BYTES
  const headerEnd = headerStart + headerLength
  const ivEnd = headerEnd + IV_BYTES
  const minimumEnd = ivEnd + TAG_BYTES
  if (bytes.length < minimumEnd) {
    throw new EnvelopeError(
      'malformed-envelope',
      'Envelope is shorter than its declared header, IV, and tag',
    )
  }

  let header: EnvelopeHeader
  try {
    const json = new TextDecoder('utf-8', { fatal: true }).decode(
      bytes.subarray(headerStart, headerEnd),
    )
    header = validateHeader(JSON.parse(json))
  } catch (error) {
    if (error instanceof EnvelopeError) {
      throw error
    }
    throw new EnvelopeError('invalid-header', 'Envelope header is not valid JSON')
  }

  return {
    header,
    iv: bytes.slice(headerEnd, ivEnd),
    ciphertext: bytes.slice(ivEnd),
  }
}

async function collectStream(stream: ReadableStream<Uint8Array>): Promise<Uint8Array> {
  const reader = stream.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      chunks.push(value)
      total += value.length
    }
  } finally {
    reader.releaseLock()
  }

  const output = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    output.set(chunk, offset)
    offset += chunk.length
  }
  return output
}

async function gzipBytes(data: Uint8Array): Promise<Uint8Array> {
  const stream = new CompressionStream('gzip')
  const writer = stream.writable.getWriter()
  const writePromise = (async () => {
    await writer.write(toArrayBuffer(data))
    await writer.close()
  })().catch(() => undefined)
  const result = await collectStream(stream.readable)
  await writePromise
  return result
}

async function gunzipBytes(data: Uint8Array): Promise<Uint8Array> {
  const stream = new DecompressionStream('gzip')
  const writer = stream.writable.getWriter()
  const writePromise = (async () => {
    await writer.write(toArrayBuffer(data))
    await writer.close()
  })().catch(() => undefined)
  const result = await collectStream(stream.readable)
  await writePromise
  return result
}

export async function encryptObject(options: EncryptObjectOptions): Promise<Uint8Array> {
  const { passphrase, salt, objectType, objectKey, plaintext } = options
  if (salt.length !== SALT_BYTES) {
    throw new EnvelopeError('invalid-header', 'Salt must be 16 bytes')
  }

  const iterations = options.iterations ?? DEFAULT_ITERATIONS
  if (!Number.isInteger(iterations) || iterations <= 0 || iterations > MAX_ITERATIONS) {
    throw new EnvelopeError('invalid-header', 'PBKDF2 iterations is out of range')
  }

  const compress = options.compress ?? objectType === 'conversation'
  const header: EnvelopeHeader = {
    v: ENVELOPE_VERSION,
    formatVersion: FORMAT_VERSION,
    alg: ALG,
    kdf: KDF,
    iterations,
    salt: bytesToBase64(salt),
    objectType,
    objectKey,
  }
  const normalized = validateHeader(header)

  const key = await derivePassphraseKey(passphrase, salt, iterations)
  const data = typeof plaintext === 'string' ? new TextEncoder().encode(plaintext) : plaintext
  const payload = compress ? await gzipBytes(data) : data

  const iv = options.iv ?? generateIv()
  if (iv.length !== IV_BYTES) {
    throw new EnvelopeError('malformed-envelope', 'Envelope IV must be 12 bytes')
  }

  const aad = buildAad(normalized.objectType, normalized.objectKey)
  const ciphertext = new Uint8Array(
    await subtle().encrypt(
      { name: 'AES-GCM', iv: toArrayBuffer(iv), additionalData: toArrayBuffer(aad) },
      key,
      toArrayBuffer(payload),
    ),
  )

  return encodeEnvelope({ header: normalized, iv, ciphertext })
}

export async function decryptObject({
  passphrase,
  bytes,
}: DecryptObjectOptions): Promise<DecryptedObject> {
  const { header, iv, ciphertext } = decodeEnvelope(bytes)
  const salt = base64ToBytes(header.salt)
  const key = await derivePassphraseKey(passphrase, salt, header.iterations)
  const aad = buildAad(header.objectType, header.objectKey)

  let decrypted: ArrayBuffer
  try {
    decrypted = await subtle().decrypt(
      { name: 'AES-GCM', iv: toArrayBuffer(iv), additionalData: toArrayBuffer(aad) },
      key,
      toArrayBuffer(ciphertext),
    )
  } catch {
    throw new EnvelopeError('authentication-failed', 'Envelope authentication failed')
  }

  let plaintext: Uint8Array = new Uint8Array(decrypted)
  if (header.objectType === 'conversation') {
    try {
      plaintext = await gunzipBytes(plaintext)
    } catch {
      throw new EnvelopeError('decompression-failed', 'Conversation payload is not valid gzip')
    }
  }

  return { header, plaintext }
}

export async function createVerificationToken(
  options: VerificationTokenOptions,
): Promise<Uint8Array> {
  return encryptObject({
    passphrase: options.passphrase,
    salt: options.salt,
    objectType: VERIFICATION_OBJECT_TYPE,
    objectKey: VERIFICATION_OBJECT_KEY,
    plaintext: VERIFICATION_MARKER,
    compress: false,
    iterations: options.iterations,
    iv: options.iv,
  })
}

export async function verifyPassphrase({
  passphrase,
  token,
}: VerifyPassphraseOptions): Promise<boolean> {
  try {
    const { header, plaintext } = await decryptObject({ passphrase, bytes: token })
    return (
      header.objectType === VERIFICATION_OBJECT_TYPE &&
      header.objectKey === VERIFICATION_OBJECT_KEY &&
      new TextDecoder().decode(plaintext) === VERIFICATION_MARKER
    )
  } catch {
    return false
  }
}
