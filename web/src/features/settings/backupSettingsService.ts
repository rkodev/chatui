import type { IDBPDatabase } from 'idb'
import type { BackupConfig } from '../../domain'
import {
  BackupConfigRepository,
  SyncQueueRepository,
  openDatabase,
  type ChatUiDB,
} from '../../storage'
import { SecretStore, type SecretMode } from '../../storage/SecretStore'
import { S3StorageAdapter, type StorageAdapter } from '../backup/storage'
import {
  DELETE_REMOTE_CONFIRMATION,
  SyncEngine,
  type RestoreResult,
  type SyncResult,
} from '../backup/sync'
import type { BackupStatus } from '../backup/useBackupStatus'

/** Stable secret id for the S3 access key pair. */
export const BACKUP_CREDENTIALS_SECRET_ID = 'backup-credentials'

/** Typed confirmation required before a replace restore. */
export const RESTORE_REPLACE_CONFIRMATION = 'REPLACE LOCAL DATA'

export interface BackupCredentials {
  accessKeyId: string
  secretAccessKey: string
}

export interface BackupFormValues {
  backend: BackupConfig['backend']
  endpoint: string
  region: string
  bucket: string
  prefix: string
  accessKeyId: string
  secretAccessKey: string
  passphrase: string
}

export interface BackupSettingsSnapshot {
  config: BackupConfig | null
  credentials: BackupCredentials | null
}

export interface RestoreRequest {
  replace?: boolean
  confirmation?: string
}

export interface SecretStoreLike {
  getSecret(id: string): Promise<string | null>
  setSecret(id: string, value: string, mode: SecretMode): Promise<void>
  deleteSecret(id: string): Promise<void>
}

export interface SyncEngineLike {
  backupNow(): Promise<SyncResult>
  restore(options?: { replace?: boolean }): Promise<RestoreResult>
  deleteRemote(confirmation: string): Promise<void>
}

export type BackupStorageFactory = (
  config: BackupConfig,
  credentials: BackupCredentials,
) => StorageAdapter

export type BackupEngineFactory = (options: {
  db: IDBPDatabase<ChatUiDB>
  storage: StorageAdapter
  passphrase: string
}) => SyncEngineLike

export interface BackupSettingsServiceOptions {
  db: IDBPDatabase<ChatUiDB>
  secrets: SecretStoreLike
  createStorage?: BackupStorageFactory
  createEngine?: BackupEngineFactory
  now?: () => string
}

export interface CreateBackupSettingsServiceOptions {
  db?: IDBPDatabase<ChatUiDB>
  secrets?: SecretStoreLike
  createStorage?: BackupStorageFactory
  createEngine?: BackupEngineFactory
  now?: () => string
}

export interface BackupSettingsServiceLike {
  load(): Promise<BackupSettingsSnapshot>
  saveConfig(values: BackupFormValues, enabled?: boolean): Promise<BackupConfig>
  setEnabled(enabled: boolean, values: BackupFormValues): Promise<BackupConfig | null>
  testConnection(values: BackupFormValues): Promise<void>
  backupNow(): Promise<SyncResult>
  restore(options?: RestoreRequest): Promise<RestoreResult>
  deleteRemote(confirmation: string): Promise<void>
  getStatus(): Promise<BackupStatus>
}

const DEFAULT_CONFIG: BackupConfig = {
  enabled: false,
  backend: 's3',
  endpoint: null,
  region: null,
  bucket: '',
  prefix: '',
  credentialSecretId: null,
  encryption: 'aes-256-gcm',
  kdf: 'pbkdf2-sha256',
  kdfSalt: null,
  lastSyncAt: null,
  lastManifestEtag: null,
  deviceId: '',
}

const encoder = new TextEncoder()

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

function byteLength(value: unknown): number {
  return encoder.encode(JSON.stringify(value)).byteLength
}

function parseCredentials(raw: string | null): BackupCredentials | null {
  if (raw === null) {
    return null
  }

  try {
    const parsed = JSON.parse(raw) as Partial<BackupCredentials>
    if (
      typeof parsed.accessKeyId === 'string' &&
      typeof parsed.secretAccessKey === 'string'
    ) {
      return {
        accessKeyId: parsed.accessKeyId,
        secretAccessKey: parsed.secretAccessKey,
      }
    }
  } catch {
    return null
  }

  return null
}

/** Validate the storage fields. The passphrase is validated separately. */
export function validateBackupForm(values: BackupFormValues): string | null {
  if (values.backend !== 's3') {
    return 'Only S3-compatible storage is available in this release.'
  }

  if (values.endpoint.trim() === '') {
    return 'Endpoint is required.'
  }

  try {
    const endpoint = new URL(values.endpoint)
    if (endpoint.protocol !== 'https:' && endpoint.protocol !== 'http:') {
      return 'Endpoint must use http or https.'
    }
  } catch {
    return 'Endpoint must be a valid URL.'
  }

  if (values.region.trim() === '') {
    return 'Region is required.'
  }
  if (values.bucket.trim() === '') {
    return 'Bucket is required.'
  }
  if (values.accessKeyId.trim() === '') {
    return 'Access key ID is required.'
  }
  if (values.secretAccessKey === '') {
    return 'Secret access key is required.'
  }

  return null
}

export function validatePassphrase(passphrase: string): string | null {
  return passphrase.length === 0 ? 'Passphrase is required.' : null
}

function defaultCreateStorage(
  config: BackupConfig,
  credentials: BackupCredentials,
): StorageAdapter {
  if (config.backend !== 's3') {
    throw new Error('Only S3-compatible storage is available in this release.')
  }
  if (config.endpoint === null || config.region === null) {
    throw new Error('Endpoint and region are required.')
  }

  return new S3StorageAdapter({
    endpoint: config.endpoint,
    bucket: config.bucket,
    region: config.region,
    accessKeyId: credentials.accessKeyId,
    secretAccessKey: credentials.secretAccessKey,
    prefix: config.prefix,
  })
}

function defaultCreateEngine(options: {
  db: IDBPDatabase<ChatUiDB>
  storage: StorageAdapter
  passphrase: string
}): SyncEngineLike {
  return new SyncEngine(options)
}

/**
 * Backup configuration and manual sync actions for `/settings/backup`.
 *
 * Credentials are written to `SecretStore` in remember mode. The passphrase is
 * only kept in this instance's memory and is never written to IndexedDB or to
 * `BackupConfig`. A reload therefore requires the user to enter it again.
 */
export class BackupSettingsService implements BackupSettingsServiceLike {
  private readonly db: IDBPDatabase<ChatUiDB>
  private readonly secrets: SecretStoreLike
  private readonly createStorage: BackupStorageFactory
  private readonly createEngine: BackupEngineFactory
  private readonly now: () => string
  private readonly repository: BackupConfigRepository
  private readonly syncQueue: SyncQueueRepository
  private passphrase = ''
  private lastError: string | null = null

  constructor(options: BackupSettingsServiceOptions) {
    this.db = options.db
    this.secrets = options.secrets
    this.createStorage = options.createStorage ?? defaultCreateStorage
    this.createEngine = options.createEngine ?? defaultCreateEngine
    this.now = options.now ?? (() => new Date().toISOString())
    this.repository = new BackupConfigRepository(options.db)
    this.syncQueue = new SyncQueueRepository(options.db)
  }

  async load(): Promise<BackupSettingsSnapshot> {
    const config = await this.repository.get()
    if (config === null) {
      return { config: null, credentials: null }
    }

    return { config, credentials: await this.loadCredentials(config) }
  }

  async saveConfig(values: BackupFormValues, enabled = false): Promise<BackupConfig> {
    const validation = validateBackupForm(values)
    if (validation !== null) {
      throw new Error(validation)
    }

    const passphraseError = validatePassphrase(values.passphrase)
    if (passphraseError !== null) {
      throw new Error(passphraseError)
    }

    const existing = await this.repository.get()
    const secretId = existing?.credentialSecretId ?? BACKUP_CREDENTIALS_SECRET_ID
    const credentials: BackupCredentials = {
      accessKeyId: values.accessKeyId.trim(),
      secretAccessKey: values.secretAccessKey,
    }

    await this.secrets.setSecret(secretId, JSON.stringify(credentials), 'remember')

    const config: BackupConfig = {
      enabled,
      backend: values.backend,
      endpoint: values.endpoint.trim(),
      region: values.region.trim(),
      bucket: values.bucket.trim(),
      prefix: values.prefix.trim(),
      credentialSecretId: secretId,
      encryption: existing?.encryption ?? DEFAULT_CONFIG.encryption,
      kdf: existing?.kdf ?? DEFAULT_CONFIG.kdf,
      kdfSalt: existing?.kdfSalt ?? DEFAULT_CONFIG.kdfSalt,
      lastSyncAt: existing?.lastSyncAt ?? DEFAULT_CONFIG.lastSyncAt,
      lastManifestEtag: existing?.lastManifestEtag ?? DEFAULT_CONFIG.lastManifestEtag,
      deviceId: existing?.deviceId ?? DEFAULT_CONFIG.deviceId,
    }

    await this.repository.put(config)
    this.passphrase = values.passphrase
    this.lastError = null
    return config
  }

  async setEnabled(enabled: boolean, values: BackupFormValues): Promise<BackupConfig | null> {
    if (enabled) {
      return this.saveConfig(values, true)
    }

    const existing = await this.repository.get()
    if (existing === null) {
      return null
    }

    const config: BackupConfig = { ...existing, enabled: false }
    await this.repository.put(config)
    return config
  }

  async testConnection(values: BackupFormValues): Promise<void> {
    const validation = validateBackupForm(values)
    if (validation !== null) {
      throw new Error(validation)
    }

    const config = this.configFromValues(values, false)
    const storage = this.createStorage(config, {
      accessKeyId: values.accessKeyId.trim(),
      secretAccessKey: values.secretAccessKey,
    })
    await storage.testConnection()
  }

  async backupNow(): Promise<SyncResult> {
    const config = await this.requireConfig()
    const credentials = await this.requireCredentials(config)
    const passphrase = this.requirePassphrase()
    const engine = this.createEngine({
      db: this.db,
      storage: this.createStorage(config, credentials),
      passphrase,
    })

    try {
      const result = await engine.backupNow()
      await this.repository.put({ ...config, lastSyncAt: this.now() })
      this.lastError = null
      return result
    } catch (cause) {
      this.lastError = errorMessage(cause)
      throw cause
    }
  }

  async restore(options: RestoreRequest = {}): Promise<RestoreResult> {
    const replace = options.replace === true
    if (replace && options.confirmation !== RESTORE_REPLACE_CONFIRMATION) {
      throw new Error(
        `Replace restore requires the typed confirmation "${RESTORE_REPLACE_CONFIRMATION}".`,
      )
    }

    const config = await this.requireConfig()
    const credentials = await this.requireCredentials(config)
    const passphrase = this.requirePassphrase()
    const engine = this.createEngine({
      db: this.db,
      storage: this.createStorage(config, credentials),
      passphrase,
    })

    try {
      const result = await engine.restore({ replace })
      this.lastError = null
      return result
    } catch (cause) {
      this.lastError = errorMessage(cause)
      throw cause
    }
  }

  async deleteRemote(confirmation: string): Promise<void> {
    if (confirmation !== DELETE_REMOTE_CONFIRMATION) {
      throw new Error(
        `Remote backup deletion requires the typed confirmation "${DELETE_REMOTE_CONFIRMATION}".`,
      )
    }

    const config = await this.requireConfig()
    const credentials = await this.requireCredentials(config)
    const passphrase = this.requirePassphrase()
    const engine = this.createEngine({
      db: this.db,
      storage: this.createStorage(config, credentials),
      passphrase,
    })

    try {
      await engine.deleteRemote(confirmation)
      await this.repository.put({
        ...config,
        lastSyncAt: null,
        lastManifestEtag: null,
      })
      this.lastError = null
    } catch (cause) {
      this.lastError = errorMessage(cause)
      throw cause
    }
  }

  async getStatus(): Promise<BackupStatus> {
    const [config, pending, storageUsageBytes] = await Promise.all([
      this.repository.get(),
      this.syncQueue.list(),
      this.localUsageBytes(),
    ])

    return {
      enabled: config?.enabled ?? false,
      lastBackupAt: config?.lastSyncAt ?? null,
      pendingChanges: pending.length,
      lastError: this.lastError,
      storageUsageBytes,
    }
  }

  private configFromValues(values: BackupFormValues, enabled: boolean): BackupConfig {
    return {
      ...DEFAULT_CONFIG,
      enabled,
      backend: values.backend,
      endpoint: values.endpoint.trim(),
      region: values.region.trim(),
      bucket: values.bucket.trim(),
      prefix: values.prefix.trim(),
    }
  }

  private async loadCredentials(config: BackupConfig): Promise<BackupCredentials | null> {
    if (config.credentialSecretId === null) {
      return null
    }

    return parseCredentials(await this.secrets.getSecret(config.credentialSecretId))
  }

  private async requireConfig(): Promise<BackupConfig> {
    const config = await this.repository.get()
    if (config === null) {
      throw new Error('Backup is not configured yet.')
    }
    return config
  }

  private async requireCredentials(config: BackupConfig): Promise<BackupCredentials> {
    const credentials = await this.loadCredentials(config)
    if (credentials === null) {
      throw new Error('Backup credentials are missing. Save the configuration again.')
    }
    return credentials
  }

  private requirePassphrase(): string {
    if (this.passphrase === '') {
      throw new Error('Enter your backup passphrase to continue.')
    }
    return this.passphrase
  }

  private async localUsageBytes(): Promise<number> {
    const [conversations, messages, attachments] = await Promise.all([
      this.db.getAll('conversations'),
      this.db.getAll('messages'),
      this.db.getAll('attachments'),
    ])

    const attachmentSizes = new Map<string, number>()
    for (const attachment of attachments) {
      if (!attachmentSizes.has(attachment.sha256)) {
        attachmentSizes.set(attachment.sha256, attachment.size)
      }
    }

    return (
      conversations.reduce((total, record) => total + byteLength(record), 0) +
      messages.reduce((total, record) => total + byteLength(record), 0) +
      [...attachmentSizes.values()].reduce((total, size) => total + size, 0)
    )
  }
}

/** Build the production backup settings service. */
export async function createBackupSettingsService(
  options: CreateBackupSettingsServiceOptions = {},
): Promise<BackupSettingsService> {
  const db = options.db ?? (await openDatabase())

  return new BackupSettingsService({
    db,
    secrets: options.secrets ?? new SecretStore({ db }),
    createStorage: options.createStorage,
    createEngine: options.createEngine,
    now: options.now,
  })
}
