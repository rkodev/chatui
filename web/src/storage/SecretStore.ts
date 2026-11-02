import type { IDBPDatabase } from 'idb'
import { nowIso } from '../domain'
import {
  base64ToBytes,
  bytesToBase64,
  createCrypto,
  type CryptoPrimitives,
  type EncryptedPayload,
} from '../platform/crypto'
import type { ChatUiDB, SecretRecord } from './db'
import { runTransaction } from './db'
import { SecretRepository } from './SecretRepository'

export type SecretMode = 'session' | 'remember' | 'passphrase'

export const DEVICE_KEY_RECORD_ID = '__chatui_device_key__'
export const PASSPHRASE_SALT_RECORD_ID = '__chatui_passphrase_salt__'
export const PASSPHRASE_CHECK_RECORD_ID = '__chatui_passphrase_check__'

const REMEMBER_PREFIX = 'remember:'
const PASSPHRASE_PREFIX = 'passphrase:'
const PASSPHRASE_CHECK_VALUE = 'chatui-passphrase-check'

export function rememberRecordId(id: string): string {
  return `${REMEMBER_PREFIX}${id}`
}

export function passphraseRecordId(id: string): string {
  return `${PASSPHRASE_PREFIX}${id}`
}

export class SecretLockedError extends Error {
  constructor() {
    super('Passphrase-locked secrets require unlock() before use')
    this.name = 'SecretLockedError'
  }
}

export class InvalidPassphraseError extends Error {
  constructor() {
    super('Invalid passphrase')
    this.name = 'InvalidPassphraseError'
  }
}

export class SecretStoreError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SecretStoreError'
  }
}

export interface SecretStoreOptions {
  db: IDBPDatabase<ChatUiDB>
  /** Inject crypto primitives. Tests use this to lower PBKDF2 iterations. */
  crypto?: CryptoPrimitives
  now?: () => string
  /** Test-only PBKDF2 override used when `crypto` is not supplied. */
  pbkdf2Iterations?: number
}

/**
 * Secret storage with three modes:
 *
 * - `session`: plaintext lives only in this instance's memory.
 * - `remember`: AES-GCM ciphertext plus IV in IndexedDB, encrypted with one
 *   non-extractable device key per browser profile.
 * - `passphrase`: AES-GCM ciphertext plus IV in IndexedDB, encrypted with a
 *   PBKDF2-derived key that only exists in memory after `unlock()`.
 *
 * Persisted payload records are keyed by `remember:<id>` or `passphrase:<id>`
 * so the mode can be recovered after a reload without storing a mode field.
 * The device key, passphrase salt, and passphrase verification record use
 * reserved ids that cannot collide with a caller-provided secret id.
 */
export class SecretStore {
  private readonly db: IDBPDatabase<ChatUiDB>
  private readonly repository: SecretRepository
  private readonly crypto: CryptoPrimitives
  private readonly now: () => string
  private readonly sessionSecrets = new Map<string, string>()
  private passphraseKey: CryptoKey | null = null
  private deviceKeyPromise: Promise<CryptoKey> | null = null

  constructor(options: SecretStoreOptions) {
    this.db = options.db
    this.repository = new SecretRepository(options.db)
    this.crypto =
      options.crypto ?? createCrypto({ pbkdf2Iterations: options.pbkdf2Iterations })
    this.now = options.now ?? nowIso
  }

  async setSecret(id: string, value: string, mode: SecretMode): Promise<void> {
    if (id.length === 0) {
      throw new SecretStoreError('Secret id must not be empty')
    }

    let key: CryptoKey | null = null
    if (mode === 'remember') {
      key = await this.getDeviceKey()
    } else if (mode === 'passphrase') {
      key = this.requirePassphraseKey()
    } else if (mode !== 'session') {
      throw new SecretStoreError(`Unknown secret mode: ${String(mode)}`)
    }

    await this.deletePersisted(id)
    this.sessionSecrets.delete(id)

    if (mode === 'session') {
      this.sessionSecrets.set(id, value)
      return
    }

    if (key === null) {
      throw new SecretStoreError('Secret key is unavailable')
    }

    const payload = await this.crypto.encrypt(key, value)
    const recordId = mode === 'remember' ? rememberRecordId(id) : passphraseRecordId(id)
    await this.repository.put(this.payloadRecord(recordId, payload))
  }

  async getSecret(id: string): Promise<string | null> {
    const sessionValue = this.sessionSecrets.get(id)
    if (sessionValue !== undefined) {
      return sessionValue
    }

    const rememberRecord = await this.repository.get(rememberRecordId(id))
    if (rememberRecord !== null) {
      const key = await this.getDeviceKey()
      return this.crypto.decrypt(key, this.payloadFrom(rememberRecord))
    }

    const passphraseRecord = await this.repository.get(passphraseRecordId(id))
    if (passphraseRecord !== null) {
      const key = this.requirePassphraseKey()
      return this.crypto.decrypt(key, this.payloadFrom(passphraseRecord))
    }

    return null
  }

  async deleteSecret(id: string): Promise<void> {
    this.sessionSecrets.delete(id)
    await this.deletePersisted(id)
  }

  /**
   * Remove every secret, the device key, and the passphrase salt/verification
   * records. The next remember-mode write generates a fresh device key.
   */
  async forgetAll(): Promise<void> {
    this.sessionSecrets.clear()
    this.passphraseKey = null
    this.deviceKeyPromise = null

    await runTransaction(this.db, ['secrets'], 'readwrite', async (transaction) => {
      const store = transaction.objectStore('secrets')
      const keys = await store.getAllKeys()
      for (const key of keys) {
        await store.delete(key)
      }
    })
  }

  /**
   * Unlock passphrase mode. The first call initializes a random salt and a
   * verification record; later calls verify the passphrase before accepting
   * the derived key.
   */
  async unlock(passphrase: string): Promise<void> {
    if (passphrase.length === 0) {
      throw new InvalidPassphraseError()
    }

    const saltRecord = await this.repository.get(PASSPHRASE_SALT_RECORD_ID)
    if (saltRecord === null) {
      const salt = this.crypto.generateSalt()
      const key = await this.crypto.derivePassphraseKey(passphrase, salt)
      const check = await this.crypto.encrypt(key, PASSPHRASE_CHECK_VALUE)
      await this.repository.put(this.saltRecord(salt))
      await this.repository.put(this.payloadRecord(PASSPHRASE_CHECK_RECORD_ID, check))
      this.passphraseKey = key
      return
    }

    if (saltRecord.iv === null) {
      throw new SecretStoreError('Passphrase salt record is corrupt')
    }

    const key = await this.crypto.derivePassphraseKey(passphrase, base64ToBytes(saltRecord.iv))
    const checkRecord = await this.repository.get(PASSPHRASE_CHECK_RECORD_ID)
    if (checkRecord !== null) {
      try {
        await this.crypto.decrypt(key, this.payloadFrom(checkRecord))
      } catch {
        throw new InvalidPassphraseError()
      }
    }

    this.passphraseKey = key
  }

  lock(): void {
    this.passphraseKey = null
  }

  isUnlocked(): boolean {
    return this.passphraseKey !== null
  }

  private requirePassphraseKey(): CryptoKey {
    if (this.passphraseKey === null) {
      throw new SecretLockedError()
    }
    return this.passphraseKey
  }

  private async getDeviceKey(): Promise<CryptoKey> {
    if (this.deviceKeyPromise === null) {
      this.deviceKeyPromise = this.loadOrCreateDeviceKey()
    }
    return this.deviceKeyPromise
  }

  private async loadOrCreateDeviceKey(): Promise<CryptoKey> {
    const record = await this.repository.get(DEVICE_KEY_RECORD_ID)
    if (record?.key !== undefined) {
      return record.key
    }

    const key = await this.crypto.generateDeviceKey()
    await this.repository.put(this.keyRecord(DEVICE_KEY_RECORD_ID, key))
    return key
  }

  private async deletePersisted(id: string): Promise<void> {
    await this.repository.delete(rememberRecordId(id))
    await this.repository.delete(passphraseRecordId(id))
  }

  private payloadFrom(record: SecretRecord): EncryptedPayload {
    if (record.iv === null || record.ciphertext === null) {
      throw new SecretStoreError(`Secret record ${record.id} is missing ciphertext`)
    }
    return { iv: record.iv, ciphertext: record.ciphertext }
  }

  private payloadRecord(id: string, payload: EncryptedPayload): SecretRecord {
    const now = this.now()
    return {
      id,
      iv: payload.iv,
      ciphertext: payload.ciphertext,
      createdAt: now,
      updatedAt: now,
    }
  }

  private keyRecord(id: string, key: CryptoKey): SecretRecord {
    const now = this.now()
    return {
      id,
      iv: null,
      ciphertext: null,
      key,
      createdAt: now,
      updatedAt: now,
    }
  }

  private saltRecord(salt: Uint8Array): SecretRecord {
    const now = this.now()
    return {
      id: PASSPHRASE_SALT_RECORD_ID,
      iv: bytesToBase64(salt),
      ciphertext: null,
      createdAt: now,
      updatedAt: now,
    }
  }
}
