import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { base64ToBytes, createCrypto } from '../platform/crypto'
import { deleteDatabase, openDatabase, type ChatUiDB } from './db'
import {
  DEVICE_KEY_RECORD_ID,
  InvalidPassphraseError,
  PASSPHRASE_SALT_RECORD_ID,
  SecretLockedError,
  SecretStore,
  passphraseRecordId,
  rememberRecordId,
} from './SecretStore'

const fastCrypto = createCrypto({ pbkdf2Iterations: 1000 })

describe('SecretStore', () => {
  let db: IDBPDatabase<ChatUiDB>

  beforeEach(async () => {
    await deleteDatabase()
    db = await openDatabase()
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase()
  })

  function store(): SecretStore {
    return new SecretStore({ db, crypto: fastCrypto })
  }

  it('returns null for unknown secrets', async () => {
    expect(await store().getSecret('missing')).toBeNull()
  })

  it('keeps session secrets in memory only', async () => {
    const first = store()
    await first.setSecret('session-key', 'sk-session', 'session')

    expect(await first.getSecret('session-key')).toBe('sk-session')
    expect(await db.getAllKeys('secrets')).toEqual([])

    const second = store()
    expect(await second.getSecret('session-key')).toBeNull()
  })

  it('encrypts remember-mode secrets and decrypts them after a reload', async () => {
    const first = store()
    await first.setSecret('remember-key', 'sk-remember', 'remember')

    expect(await first.getSecret('remember-key')).toBe('sk-remember')

    const second = store()
    expect(await second.getSecret('remember-key')).toBe('sk-remember')
  })

  it('stores only IV and ciphertext for remember mode', async () => {
    const first = store()
    await first.setSecret('remember-key', 'sk-remember', 'remember')

    const record = await db.get('secrets', rememberRecordId('remember-key'))

    expect(record).toBeDefined()
    expect(record?.iv).toEqual(expect.any(String))
    expect(record?.ciphertext).toEqual(expect.any(String))
    expect(record?.ciphertext).not.toContain('sk-remember')
    expect(JSON.stringify(record)).not.toContain('sk-remember')
    expect(new TextDecoder().decode(base64ToBytes(record?.ciphertext ?? ''))).not.toContain(
      'sk-remember',
    )
  })

  it('uses a non-extractable device key stored in IndexedDB', async () => {
    const first = store()
    await first.setSecret('remember-key', 'sk-remember', 'remember')

    const keyRecord = await db.get('secrets', DEVICE_KEY_RECORD_ID)

    expect(keyRecord?.key).toBeDefined()
    expect(keyRecord?.key?.extractable).toBe(false)
    expect(keyRecord?.key?.algorithm).toMatchObject({ name: 'AES-GCM', length: 256 })
    expect(keyRecord?.iv).toBeNull()
    expect(keyRecord?.ciphertext).toBeNull()
  })

  it('requires unlock before passphrase secrets can be written', async () => {
    const first = store()

    await expect(
      first.setSecret('pass-key', 'sk-pass', 'passphrase'),
    ).rejects.toBeInstanceOf(SecretLockedError)
    expect(first.isUnlocked()).toBe(false)
  })

  it('unlocks passphrase mode and decrypts after a reload', async () => {
    const first = store()
    await first.unlock('correct horse')
    expect(first.isUnlocked()).toBe(true)
    await first.setSecret('pass-key', 'sk-pass', 'passphrase')

    expect(await first.getSecret('pass-key')).toBe('sk-pass')

    const second = store()
    expect(second.isUnlocked()).toBe(false)
    await expect(second.getSecret('pass-key')).rejects.toBeInstanceOf(SecretLockedError)

    await second.unlock('correct horse')
    expect(await second.getSecret('pass-key')).toBe('sk-pass')
  })

  it('rejects a wrong passphrase cleanly', async () => {
    const first = store()
    await first.unlock('correct horse')
    await first.setSecret('pass-key', 'sk-pass', 'passphrase')

    const second = store()
    await expect(second.unlock('wrong horse')).rejects.toBeInstanceOf(InvalidPassphraseError)
    expect(second.isUnlocked()).toBe(false)
    await expect(second.getSecret('pass-key')).rejects.toBeInstanceOf(SecretLockedError)
  })

  it('never stores the passphrase or plaintext', async () => {
    const first = store()
    await first.unlock('correct horse')
    await first.setSecret('pass-key', 'sk-pass', 'passphrase')

    const records = await db.getAll('secrets')
    expect(JSON.stringify(records)).not.toContain('correct horse')
    expect(JSON.stringify(records)).not.toContain('sk-pass')

    const saltRecord = await db.get('secrets', PASSPHRASE_SALT_RECORD_ID)
    expect(saltRecord?.iv).toEqual(expect.any(String))
    expect(saltRecord?.ciphertext).toBeNull()
  })

  it('locks passphrase mode', async () => {
    const first = store()
    await first.unlock('correct horse')
    await first.setSecret('pass-key', 'sk-pass', 'passphrase')

    first.lock()

    expect(first.isUnlocked()).toBe(false)
    await expect(first.getSecret('pass-key')).rejects.toBeInstanceOf(SecretLockedError)
  })

  it('deletes secrets in every mode', async () => {
    const first = store()
    await first.setSecret('session-key', 'sk-session', 'session')
    await first.setSecret('remember-key', 'sk-remember', 'remember')
    await first.unlock('correct horse')
    await first.setSecret('pass-key', 'sk-pass', 'passphrase')

    await first.deleteSecret('session-key')
    await first.deleteSecret('remember-key')
    await first.deleteSecret('pass-key')

    expect(await first.getSecret('session-key')).toBeNull()
    expect(await first.getSecret('remember-key')).toBeNull()
    expect(await first.getSecret('pass-key')).toBeNull()

    const keys = await db.getAllKeys('secrets')
    expect(
      keys.filter(
        (key) =>
          key === rememberRecordId('remember-key') ||
          key === passphraseRecordId('pass-key'),
      ),
    ).toEqual([])
  })

  it('forgets all secrets and keys', async () => {
    const first = store()
    await first.setSecret('session-key', 'sk-session', 'session')
    await first.setSecret('remember-key', 'sk-remember', 'remember')
    await first.unlock('correct horse')
    await first.setSecret('pass-key', 'sk-pass', 'passphrase')

    await first.forgetAll()

    expect(await first.getSecret('session-key')).toBeNull()
    expect(await first.getSecret('remember-key')).toBeNull()
    expect(first.isUnlocked()).toBe(false)
    expect(await db.getAllKeys('secrets')).toEqual([])

    const second = store()
    expect(await second.getSecret('remember-key')).toBeNull()
    expect(await second.getSecret('pass-key')).toBeNull()
  })

  it('removes persisted ciphertext when switching to session mode', async () => {
    const first = store()
    await first.setSecret('key', 'sk-remember', 'remember')
    await first.setSecret('key', 'sk-session', 'session')

    expect(await db.get('secrets', rememberRecordId('key'))).toBeUndefined()
    expect(await first.getSecret('key')).toBe('sk-session')

    const second = store()
    expect(await second.getSecret('key')).toBeNull()
  })

  it('persists a secret when switching from session to remember mode', async () => {
    const first = store()
    await first.setSecret('key', 'sk-session', 'session')
    await first.setSecret('key', 'sk-remember', 'remember')

    const second = store()
    expect(await second.getSecret('key')).toBe('sk-remember')
  })
})
