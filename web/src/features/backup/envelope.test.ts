import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { PBKDF2_ITERATIONS, base64ToBytes, bytesToBase64 } from '../../platform/crypto'
import {
  ALG,
  DEFAULT_ITERATIONS,
  ENVELOPE_VERSION,
  EnvelopeError,
  FORMAT_VERSION,
  KDF,
  KEY_BYTES,
  MAGIC,
  MAX_HEADER_BYTES,
  TAG_BYTES,
  VERIFICATION_MARKER,
  buildAad,
  createVerificationToken,
  decodeEnvelope,
  decryptObject,
  encodeEnvelope,
  encryptObject,
  verifyPassphrase,
  type BackupObjectType,
  type EnvelopeErrorCode,
  type EnvelopeHeader,
} from './envelope'

interface AadVector {
  name: string
  objectType: BackupObjectType
  objectKey: string
  aadBase64: string
  aadLength: number
}

interface EnvelopeVector {
  name: string
  objectType: BackupObjectType
  objectKey: string
  passphrase: string
  saltBase64: string
  ivBase64: string
  iterations: number
  header: EnvelopeHeader
  headerJson: string
  headerLength: number
  aadBase64: string
  plaintextBase64: string
  uncompressedBase64: string
  ciphertextBase64: string
  tagBase64: string
  envelopeBase64: string
}

interface FailureVector {
  name: string
  baseVector: string
  wrongPassphrase?: string
  tamperedEnvelopeBase64?: string
  aadObjectKey?: string
  expected: EnvelopeErrorCode
}

interface Vectors {
  envelope: {
    magic: string
    magicBase64: string
    headerLengthBytes: number
    ivBytes: number
    tagBytes: number
    saltBytes: number
    keyBytes: number
    maxHeaderBytes: number
    kdf: { iterations: number; saltBytes: number; keyBytes: number }
  }
  aadVectors: AadVector[]
  gzipVector: { uncompressedBase64: string; gzipBase64: string }
  envelopeVectors: EnvelopeVector[]
  failureVectors: FailureVector[]
}

const vectors = JSON.parse(
  readFileSync(resolve(process.cwd(), '../contracts/fixtures/backup/vectors.json'), 'utf8'),
) as Vectors

const vector = vectors.envelopeVectors[0]
const vectorBytes = base64ToBytes(vector.envelopeBase64)
const fastIterations = 1000

function tamper(bytes: Uint8Array, index: number): Uint8Array {
  const copy = bytes.slice()
  copy[index] ^= 0xff
  return copy
}

function expectEnvelopeError(action: () => unknown, code: EnvelopeErrorCode): void {
  try {
    action()
  } catch (error) {
    expect(error).toBeInstanceOf(EnvelopeError)
    expect((error as EnvelopeError).code).toBe(code)
    return
  }
  throw new Error(`Expected EnvelopeError with code ${code}`)
}

function requireFailure(name: string): FailureVector {
  const failure = vectors.failureVectors.find((item) => item.name === name)
  if (failure === undefined) {
    throw new Error(`Missing failure vector: ${name}`)
  }
  return failure
}

describe('backup envelope', () => {
  it('matches the frozen envelope constants', () => {
    expect(MAGIC).toBe(vectors.envelope.magic)
    expect(bytesToBase64(new TextEncoder().encode(MAGIC))).toBe(vectors.envelope.magicBase64)
    expect(ENVELOPE_VERSION).toBe(1)
    expect(FORMAT_VERSION).toBe(1)
    expect(ALG).toBe('AES-256-GCM')
    expect(KDF).toBe('pbkdf2-sha256')
    expect(DEFAULT_ITERATIONS).toBe(PBKDF2_ITERATIONS)
    expect(DEFAULT_ITERATIONS).toBe(vectors.envelope.kdf.iterations)
    expect(KEY_BYTES).toBe(vectors.envelope.keyBytes)
    expect(TAG_BYTES).toBe(vectors.envelope.tagBytes)
    expect(MAX_HEADER_BYTES).toBe(vectors.envelope.maxHeaderBytes)
  })

  it('builds the frozen AAD for every object type', () => {
    for (const aad of vectors.aadVectors) {
      const bytes = buildAad(aad.objectType, aad.objectKey)
      expect(bytesToBase64(bytes)).toBe(aad.aadBase64)
      expect(bytes).toHaveLength(aad.aadLength)
    }
  })

  it('decodes the shared envelope vector byte-for-byte', () => {
    const parts = decodeEnvelope(vectorBytes)

    expect(parts.header).toEqual(vector.header)
    expect(bytesToBase64(parts.iv)).toBe(vector.ivBase64)
    expect(bytesToBase64(parts.ciphertext.slice(0, -TAG_BYTES))).toBe(vector.ciphertextBase64)
    expect(bytesToBase64(parts.ciphertext.slice(-TAG_BYTES))).toBe(vector.tagBase64)
    expect(bytesToBase64(encodeEnvelope(parts))).toBe(vector.envelopeBase64)

    const headerJson = new TextDecoder().decode(vectorBytes.subarray(12, 12 + vector.headerLength))
    expect(headerJson).toBe(vector.headerJson)
  })

  it('decrypts the shared envelope vector and gunzips the conversation', async () => {
    const result = await decryptObject({ passphrase: vector.passphrase, bytes: vectorBytes })

    expect(result.header).toEqual(vector.header)
    expect(bytesToBase64(result.plaintext)).toBe(vector.uncompressedBase64)
    expect(bytesToBase64(result.plaintext)).toBe(vectors.gzipVector.uncompressedBase64)
  })

  it('reproduces the shared envelope vector with fixed inputs', async () => {
    const envelope = await encryptObject({
      passphrase: vector.passphrase,
      salt: base64ToBytes(vector.saltBase64),
      objectType: vector.objectType,
      objectKey: vector.objectKey,
      plaintext: base64ToBytes(vector.plaintextBase64),
      compress: false,
      iv: base64ToBytes(vector.ivBase64),
      iterations: vector.iterations,
    })

    expect(bytesToBase64(envelope)).toBe(vector.envelopeBase64)
  })

  it('round-trips manifest, conversation, and attachment objects', async () => {
    const salt = new Uint8Array(16).fill(7)
    const cases: Array<{
      objectType: BackupObjectType
      objectKey: string
      plaintext: string | Uint8Array
      compress: boolean
    }> = [
      {
        objectType: 'manifest',
        objectKey: 'manifest.enc',
        plaintext: '{"formatVersion":1}',
        compress: false,
      },
      {
        objectType: 'conversation',
        objectKey: 'conversations/00112233445566778899aabbccddeeff.enc',
        plaintext: '{"id":"conversation"}',
        compress: true,
      },
      {
        objectType: 'attachment',
        objectKey:
          'attachments/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.enc',
        plaintext: new Uint8Array([0, 1, 2, 3, 255]),
        compress: false,
      },
    ]

    for (const testCase of cases) {
      const envelope = await encryptObject({
        passphrase: 'test passphrase',
        salt,
        ...testCase,
        iterations: fastIterations,
      })
      const result = await decryptObject({ passphrase: 'test passphrase', bytes: envelope })

      expect(result.header).toEqual({
        v: ENVELOPE_VERSION,
        formatVersion: FORMAT_VERSION,
        alg: ALG,
        kdf: KDF,
        iterations: fastIterations,
        salt: bytesToBase64(salt),
        objectType: testCase.objectType,
        objectKey: testCase.objectKey,
      })
      const expected =
        typeof testCase.plaintext === 'string'
          ? new TextEncoder().encode(testCase.plaintext)
          : testCase.plaintext
      expect(Array.from(result.plaintext)).toEqual(Array.from(expected))
    }
  })

  it('writes every required header field in canonical order', async () => {
    const salt = new Uint8Array(16).fill(10)
    const envelope = await encryptObject({
      passphrase: 'test',
      salt,
      objectType: 'manifest',
      objectKey: 'manifest.enc',
      plaintext: '{}',
      compress: false,
      iterations: fastIterations,
    })
    const { header } = decodeEnvelope(envelope)

    expect(Object.keys(header)).toEqual([
      'v',
      'formatVersion',
      'alg',
      'kdf',
      'iterations',
      'salt',
      'objectType',
      'objectKey',
    ])
    expect(header).toEqual({
      v: ENVELOPE_VERSION,
      formatVersion: FORMAT_VERSION,
      alg: ALG,
      kdf: KDF,
      iterations: fastIterations,
      salt: bytesToBase64(salt),
      objectType: 'manifest',
      objectKey: 'manifest.enc',
    })
  })

  it('uses a fresh 12-byte IV for every write and never reuses one', async () => {
    const salt = new Uint8Array(16).fill(1)
    const seen = new Set<string>()

    for (let index = 0; index < 8; index += 1) {
      const envelope = await encryptObject({
        passphrase: 'test',
        salt,
        objectType: 'manifest',
        objectKey: 'manifest.enc',
        plaintext: 'same plaintext',
        compress: false,
        iterations: fastIterations,
      })
      const { iv } = decodeEnvelope(envelope)

      expect(iv).toHaveLength(12)
      const encoded = bytesToBase64(iv)
      expect(seen.has(encoded)).toBe(false)
      seen.add(encoded)
    }
  })

  it('fails a wrong passphrase without returning plaintext', async () => {
    const envelope = await encryptObject({
      passphrase: 'correct horse',
      salt: new Uint8Array(16).fill(2),
      objectType: 'manifest',
      objectKey: 'manifest.enc',
      plaintext: 'secret',
      compress: false,
      iterations: fastIterations,
    })

    await expect(
      decryptObject({ passphrase: 'wrong horse', bytes: envelope }),
    ).rejects.toMatchObject({ code: 'authentication-failed' })
  })

  it('fails the shared wrong-passphrase vector', async () => {
    const failure = requireFailure('wrong-passphrase')
    if (failure.wrongPassphrase === undefined) {
      throw new Error('wrong-passphrase vector is missing its passphrase')
    }

    await expect(
      decryptObject({ passphrase: failure.wrongPassphrase, bytes: vectorBytes }),
    ).rejects.toMatchObject({ code: failure.expected })
  })

  it(
    'fails every shared tampered-envelope vector with the documented code',
    async () => {
      for (const failure of vectors.failureVectors) {
        if (failure.tamperedEnvelopeBase64 === undefined) {
          continue
        }

        await expect(
          decryptObject({
            passphrase: vector.passphrase,
            bytes: base64ToBytes(failure.tamperedEnvelopeBase64),
          }),
        ).rejects.toMatchObject({ code: failure.expected })
      }
    },
    30_000,
  )

  it('fails the shared tampered-AAD vector', async () => {
    const failure = requireFailure('tampered-aad-object-key')
    if (failure.aadObjectKey === undefined) {
      throw new Error('tampered-aad-object-key vector is missing its object key')
    }

    const parts = decodeEnvelope(vectorBytes)
    const tampered = encodeEnvelope({
      header: { ...parts.header, objectKey: failure.aadObjectKey },
      iv: parts.iv,
      ciphertext: parts.ciphertext,
    })

    await expect(
      decryptObject({ passphrase: vector.passphrase, bytes: tampered }),
    ).rejects.toMatchObject({ code: failure.expected })
  })

  it('rejects malformed magic, header length, and header JSON', () => {
    expectEnvelopeError(() => decodeEnvelope(tamper(vectorBytes, 0)), 'malformed-envelope')

    const zeroLength = vectorBytes.slice()
    new DataView(zeroLength.buffer).setUint32(8, 0, false)
    expectEnvelopeError(() => decodeEnvelope(zeroLength), 'malformed-envelope')

    const hugeLength = vectorBytes.slice()
    new DataView(hugeLength.buffer).setUint32(8, MAX_HEADER_BYTES + 1, false)
    expectEnvelopeError(() => decodeEnvelope(hugeLength), 'malformed-envelope')

    expectEnvelopeError(() => decodeEnvelope(vectorBytes.slice(0, 20)), 'malformed-envelope')

    const badJson = vectorBytes.slice()
    badJson[12] = 0xff
    expectEnvelopeError(() => decodeEnvelope(badJson), 'invalid-header')
  })

  it('rejects tampered IV, ciphertext, and tag', async () => {
    const ivOffset = 12 + vector.headerLength
    const ciphertextOffset = ivOffset + 12

    await expect(
      decryptObject({ passphrase: vector.passphrase, bytes: tamper(vectorBytes, ivOffset) }),
    ).rejects.toMatchObject({ code: 'authentication-failed' })
    await expect(
      decryptObject({
        passphrase: vector.passphrase,
        bytes: tamper(vectorBytes, ciphertextOffset),
      }),
    ).rejects.toMatchObject({ code: 'authentication-failed' })
    await expect(
      decryptObject({
        passphrase: vector.passphrase,
        bytes: tamper(vectorBytes, vectorBytes.length - 1),
      }),
    ).rejects.toMatchObject({ code: 'authentication-failed' })
  })

  it('binds objectType and objectKey through the AAD', async () => {
    const parts = decodeEnvelope(vectorBytes)
    const tamperedKey = 'conversations/ffffffffffffffffffffffffffffffff.enc'
    const tamperedKeyEnvelope = encodeEnvelope({
      header: { ...parts.header, objectKey: tamperedKey },
      iv: parts.iv,
      ciphertext: parts.ciphertext,
    })
    const tamperedTypeEnvelope = encodeEnvelope({
      header: { ...parts.header, objectType: 'attachment' },
      iv: parts.iv,
      ciphertext: parts.ciphertext,
    })

    await expect(
      decryptObject({ passphrase: vector.passphrase, bytes: tamperedKeyEnvelope }),
    ).rejects.toMatchObject({ code: 'authentication-failed' })
    await expect(
      decryptObject({ passphrase: vector.passphrase, bytes: tamperedTypeEnvelope }),
    ).rejects.toMatchObject({ code: 'authentication-failed' })
    expect(bytesToBase64(buildAad(parts.header.objectType, tamperedKey))).not.toBe(vector.aadBase64)
  })

  it('gzips conversation payloads and leaves attachment bytes uncompressed', async () => {
    const salt = new Uint8Array(16).fill(3)
    const conversation = '{"id":"conversation","messages":[]}'
    const conversationKey = 'conversations/00112233445566778899aabbccddeeff.enc'

    const compressed = await encryptObject({
      passphrase: 'test',
      salt,
      objectType: 'conversation',
      objectKey: conversationKey,
      plaintext: conversation,
      iterations: fastIterations,
    })
    const compressedResult = await decryptObject({ passphrase: 'test', bytes: compressed })
    expect(new TextDecoder().decode(compressedResult.plaintext)).toBe(conversation)

    const uncompressed = await encryptObject({
      passphrase: 'test',
      salt,
      objectType: 'conversation',
      objectKey: conversationKey,
      plaintext: conversation,
      compress: false,
      iterations: fastIterations,
    })
    expect(bytesToBase64(compressed)).not.toBe(bytesToBase64(uncompressed))

    const attachmentBytes = new Uint8Array([0, 1, 2, 3, 4, 5])
    const attachment = await encryptObject({
      passphrase: 'test',
      salt,
      objectType: 'attachment',
      objectKey: 'attachments/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.enc',
      plaintext: attachmentBytes,
      iterations: fastIterations,
    })
    const attachmentResult = await decryptObject({ passphrase: 'test', bytes: attachment })
    expect(Array.from(attachmentResult.plaintext)).toEqual(Array.from(attachmentBytes))
  })

  it('rejects unknown envelope and format versions', () => {
    const salt = new Uint8Array(16).fill(4)
    const baseHeader: EnvelopeHeader = {
      v: ENVELOPE_VERSION,
      formatVersion: FORMAT_VERSION,
      alg: ALG,
      kdf: KDF,
      iterations: fastIterations,
      salt: bytesToBase64(salt),
      objectType: 'manifest',
      objectKey: 'manifest.enc',
    }
    const iv = new Uint8Array(12).fill(5)
    const ciphertext = new Uint8Array(TAG_BYTES).fill(6)

    expectEnvelopeError(
      () => encodeEnvelope({ header: { ...baseHeader, v: 2 }, iv, ciphertext }),
      'unsupported-version',
    )
    expectEnvelopeError(
      () => encodeEnvelope({ header: { ...baseHeader, formatVersion: 2 }, iv, ciphertext }),
      'unsupported-version',
    )
    expectEnvelopeError(
      () => encodeEnvelope({ header: { ...baseHeader, alg: 'AES-128-GCM' }, iv, ciphertext }),
      'unsupported-alg',
    )
    expectEnvelopeError(
      () => encodeEnvelope({ header: { ...baseHeader, kdf: 'argon2id' }, iv, ciphertext }),
      'unsupported-kdf',
    )
  })

  it('rejects invalid header fields and salt lengths', async () => {
    const salt = new Uint8Array(16).fill(11)
    const baseHeader: EnvelopeHeader = {
      v: ENVELOPE_VERSION,
      formatVersion: FORMAT_VERSION,
      alg: ALG,
      kdf: KDF,
      iterations: fastIterations,
      salt: bytesToBase64(salt),
      objectType: 'manifest',
      objectKey: 'manifest.enc',
    }
    const iv = new Uint8Array(12)
    const ciphertext = new Uint8Array(TAG_BYTES)

    expectEnvelopeError(
      () =>
        encodeEnvelope({
          header: { ...baseHeader, objectType: 'unknown' as BackupObjectType },
          iv,
          ciphertext,
        }),
      'invalid-header',
    )
    expectEnvelopeError(
      () => encodeEnvelope({ header: { ...baseHeader, objectKey: '' }, iv, ciphertext }),
      'invalid-header',
    )
    expectEnvelopeError(
      () => encodeEnvelope({ header: { ...baseHeader, salt: 'not-base64' }, iv, ciphertext }),
      'invalid-header',
    )
    expectEnvelopeError(
      () => encodeEnvelope({ header: { ...baseHeader, iterations: 0 }, iv, ciphertext }),
      'invalid-header',
    )

    await expect(
      encryptObject({
        passphrase: 'test',
        salt: new Uint8Array(8),
        objectType: 'manifest',
        objectKey: 'manifest.enc',
        plaintext: '{}',
        compress: false,
        iterations: fastIterations,
      }),
    ).rejects.toMatchObject({ code: 'invalid-header' })
  })

  it('creates and verifies a passphrase token', async () => {
    const salt = new Uint8Array(16).fill(8)
    const token = await createVerificationToken({
      passphrase: 'correct horse',
      salt,
      iterations: fastIterations,
    })

    const decrypted = await decryptObject({ passphrase: 'correct horse', bytes: token })
    expect(new TextDecoder().decode(decrypted.plaintext)).toBe(VERIFICATION_MARKER)
    expect(await verifyPassphrase({ passphrase: 'correct horse', token })).toBe(true)
    expect(await verifyPassphrase({ passphrase: 'wrong horse', token })).toBe(false)

    const tampered = token.slice()
    tampered[tampered.length - 1] ^= 0xff
    expect(await verifyPassphrase({ passphrase: 'correct horse', token: tampered })).toBe(false)
  })

  it('never includes plaintext in the envelope bytes', async () => {
    const plaintext = 'synthetic-plaintext-must-not-appear'
    const envelope = await encryptObject({
      passphrase: 'test',
      salt: new Uint8Array(16).fill(9),
      objectType: 'manifest',
      objectKey: 'manifest.enc',
      plaintext,
      compress: false,
      iterations: fastIterations,
    })

    expect(new TextDecoder().decode(envelope)).not.toContain(plaintext)
  })
})
