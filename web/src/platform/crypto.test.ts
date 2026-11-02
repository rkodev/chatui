import { describe, expect, it } from 'vitest'
import {
  AES_GCM,
  AES_KEY_LENGTH,
  PBKDF2_ITERATIONS,
  base64ToBytes,
  bytesToBase64,
  createCrypto,
  decryptText,
  derivePassphraseKey,
  encryptText,
  generateDeviceKey,
  generateIv,
  generateSalt,
} from './crypto'

const fastCrypto = createCrypto({ pbkdf2Iterations: 1000 })

describe('crypto', () => {
  it('generates a non-extractable AES-GCM device key', async () => {
    const key = await generateDeviceKey()

    expect(key.type).toBe('secret')
    expect(key.algorithm).toMatchObject({ name: AES_GCM, length: AES_KEY_LENGTH })
    expect(key.extractable).toBe(false)
    expect(key.usages).toEqual(expect.arrayContaining(['encrypt', 'decrypt']))
  })

  it('round-trips text through AES-GCM', async () => {
    const key = await generateDeviceKey()
    const payload = await encryptText(key, 'sk-secret-value')

    expect(payload.iv).not.toBe('')
    expect(payload.ciphertext).not.toBe('')
    expect(await decryptText(key, payload)).toBe('sk-secret-value')
  })

  it('uses a fresh random IV for each encryption', async () => {
    const key = await generateDeviceKey()
    const first = await encryptText(key, 'same plaintext')
    const second = await encryptText(key, 'same plaintext')

    expect(first.iv).not.toBe(second.iv)
    expect(first.ciphertext).not.toBe(second.ciphertext)
  })

  it('does not include plaintext in the ciphertext', async () => {
    const key = await generateDeviceKey()
    const plaintext = 'sk-plaintext-must-not-appear'
    const payload = await encryptText(key, plaintext)

    expect(payload.ciphertext).not.toContain(plaintext)
    expect(new TextDecoder().decode(base64ToBytes(payload.ciphertext))).not.toContain(plaintext)
  })

  it('derives a passphrase key that decrypts its own ciphertext', async () => {
    const salt = generateSalt()
    const key = await derivePassphraseKey('correct horse', salt, 1000)
    const payload = await encryptText(key, 'secret')

    expect(await decryptText(key, payload)).toBe('secret')
  })

  it('fails to decrypt with a different passphrase', async () => {
    const salt = generateSalt()
    const key = await derivePassphraseKey('correct horse', salt, 1000)
    const wrongKey = await derivePassphraseKey('wrong horse', salt, 1000)
    const payload = await encryptText(key, 'secret')

    await expect(decryptText(wrongKey, payload)).rejects.toThrow()
  })

  it('uses 600,000 PBKDF2 iterations by default', () => {
    expect(PBKDF2_ITERATIONS).toBe(600_000)
    expect(createCrypto().pbkdf2Iterations).toBe(600_000)
    expect(fastCrypto.pbkdf2Iterations).toBe(1000)
  })

  it('round-trips base64 bytes', () => {
    const bytes = new Uint8Array([0, 1, 2, 253, 254, 255])

    expect(Array.from(base64ToBytes(bytesToBase64(bytes)))).toEqual(Array.from(bytes))
  })

  it('generates IVs and salts of the expected length', () => {
    expect(generateIv()).toHaveLength(12)
    expect(generateSalt()).toHaveLength(16)
  })
})
