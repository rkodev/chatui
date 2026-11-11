import type { IDBPDatabase } from 'idb'
import type { BackupConfig } from '../../domain'
import {
  BackupConfigRepository,
  SyncQueueRepository,
  openDatabase,
  type ChatUiDB,
} from '../../storage'
import { SecretStore, type SecretMode } from '../../storage/SecretStore'
import {
  AzureBlobStorageAdapter,
  GcsStorageAdapter,
  GoogleDriveStorageAdapter,
  S3StorageAdapter,
  WebDavStorageAdapter,
  type StorageAdapter,
} from '../backup/storage'
import { BackupScheduler, type BackupSchedulerLike } from '../backup/scheduler'
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
  /** Azure Blob account name. */
  accountName?: string
  /** Azure Blob account key. */
  accountKey?: string
  /** Azure Blob SAS token. */
  sasToken?: string
  /** GCS service account JSON. */
  serviceAccountJson?: string
  /** Google Drive OAuth access token. */
  accessToken?: string
  /** Google Drive folder id, or `appDataFolder`. */
  folderId?: string
  /** WebDAV username. */
  username?: string
  /** WebDAV password. */
  password?: string
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
  /** Azure Blob account name. */
  accountName?: string
  /** Azure Blob account key. */
  accountKey?: string
  /** Azure Blob SAS token. */
  sasToken?: string
  /** GCS service account JSON. */
  serviceAccountJson?: string
  /** Google Drive OAuth access token. */
  accessToken?: string
  /** Google Drive folder id, or `appDataFolder`. */
  folderId?: string
  /** WebDAV username. */
  username?: string
  /** WebDAV password. */
  password?: string
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
  /** Optional scheduler. Production wiring is added by the factory below. */
  scheduler?: BackupSchedulerLike
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
      const credentials: BackupCredentials = {
        accessKeyId: parsed.accessKeyId,
        secretAccessKey: parsed.secretAccessKey,
      }
      for (const key of [
        'accountName',
        'accountKey',
        'sasToken',
        'serviceAccountJson',
        'accessToken',
        'folderId',
        'username',
        'password',
      ] as const) {
        const value = parsed[key]
        if (typeof value === 'string') {
          credentials[key] = value
        }
      }
      return credentials
    }
  } catch {
    return null
  }

  return null
}

function credentialsFromValues(values: BackupFormValues): BackupCredentials {
  return {
    accessKeyId: values.accessKeyId.trim(),
    secretAccessKey: values.secretAccessKey,
    accountName: values.accountName?.trim(),
    accountKey: values.accountKey,
    sasToken: values.sasToken,
    serviceAccountJson: values.serviceAccountJson,
    accessToken: values.accessToken,
    folderId: values.folderId?.trim(),
    username: values.username,
    password: values.password,
  }
}

/** Validate the storage fields. The passphrase is validated separately. */
export function validateBackupForm(values: BackupFormValues): string | null {
  switch (values.backend) {
    case 's3':
      return validateS3Form(values)
    case 'azure-blob':
      return validateAzureForm(values)
    case 'gcs':
      return validateGcsForm(values)
    case 'google-drive':
      return validateGoogleDriveForm(values)
    case 'webdav':
      return validateWebDavForm(values)
    default:
      return 'Unsupported backup backend.'
  }
}

function validateEndpoint(value: string, label: string): string | null {
  if (value.trim() === '') {
    return `${label} is required.`
  }

  try {
    const endpoint = new URL(value)
    if (endpoint.protocol !== 'https:' && endpoint.protocol !== 'http:') {
      return `${label} must use http or https.`
    }
  } catch {
    return `${label} must be a valid URL.`
  }

  return null
}

function validateS3Form(values: BackupFormValues): string | null {
  const endpointError = validateEndpoint(values.endpoint, 'Endpoint')
  if (endpointError !== null) {
    return endpointError
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

function validateAzureForm(values: BackupFormValues): string | null {
  if ((values.accountName ?? '').trim() === '') {
    return 'Azure account name is required.'
  }
  if (values.bucket.trim() === '') {
    return 'Azure container is required.'
  }
  if ((values.accountKey ?? '') === '' && (values.sasToken ?? '') === '') {
    return 'Azure account key or SAS token is required.'
  }
  if (values.endpoint.trim() !== '') {
    return validateEndpoint(values.endpoint, 'Azure endpoint')
  }
  return null
}

function validateGcsForm(values: BackupFormValues): string | null {
  if (values.bucket.trim() === '') {
    return 'GCS bucket is required.'
  }
  if ((values.serviceAccountJson ?? '').trim() === '') {
    return 'GCS service account JSON is required.'
  }

  try {
    const parsed = JSON.parse(values.serviceAccountJson ?? '') as {
      client_email?: unknown
      private_key?: unknown
    }
    if (typeof parsed.client_email !== 'string' || parsed.client_email === '') {
      return 'GCS service account JSON is missing client_email.'
    }
    if (typeof parsed.private_key !== 'string' || parsed.private_key === '') {
      return 'GCS service account JSON is missing private_key.'
    }
  } catch {
    return 'GCS service account JSON must be valid JSON.'
  }

  return null
}

function validateGoogleDriveForm(values: BackupFormValues): string | null {
  if ((values.accessToken ?? '').trim() === '') {
    return 'Google Drive access token is required.'
  }
  return null
}

function validateWebDavForm(values: BackupFormValues): string | null {
  const endpointError = validateEndpoint(values.endpoint, 'WebDAV URL')
  if (endpointError !== null) {
    return endpointError
  }
  if ((values.username ?? '') === '') {
    return 'WebDAV username is required.'
  }
  if ((values.password ?? '') === '') {
    return 'WebDAV password is required.'
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
  switch (config.backend) {
    case 's3':
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
    case 'azure-blob':
      return new AzureBlobStorageAdapter({
        accountName: credentials.accountName ?? '',
        accountKey: credentials.accountKey,
        sasToken: credentials.sasToken,
        container: config.bucket,
        endpoint: config.endpoint ?? undefined,
        prefix: config.prefix,
      })
    case 'gcs':
      return new GcsStorageAdapter({
        bucket: config.bucket,
        serviceAccountJson: credentials.serviceAccountJson ?? '',
        prefix: config.prefix,
      })
    case 'google-drive':
      return new GoogleDriveStorageAdapter({
        accessToken: credentials.accessToken ?? '',
        folderId: config.bucket === '' ? credentials.folderId : config.bucket,
        prefix: config.prefix,
      })
    case 'webdav':
      if (config.endpoint === null) {
        throw new Error('WebDAV URL is required.')
      }
      return new WebDavStorageAdapter({
        url: config.endpoint,
        username: credentials.username ?? '',
        password: credentials.password ?? '',
        prefix: config.prefix,
      })
    default:
      throw new Error('Unsupported backup backend.')
  }
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
  private scheduler: BackupSchedulerLike | null

  constructor(options: BackupSettingsServiceOptions) {
    this.db = options.db
    this.secrets = options.secrets
    this.createStorage = options.createStorage ?? defaultCreateStorage
    this.createEngine = options.createEngine ?? defaultCreateEngine
    this.now = options.now ?? (() => new Date().toISOString())
    this.repository = new BackupConfigRepository(options.db)
    this.syncQueue = new SyncQueueRepository(options.db)
    this.scheduler = options.scheduler ?? null
  }

  /** Attach the scheduler used by enable/disable and app-open scheduling. */
  setScheduler(scheduler: BackupSchedulerLike | null): void {
    this.scheduler = scheduler
  }

  async load(): Promise<BackupSettingsSnapshot> {
    const config = await this.repository.get()
    if (config === null) {
      return { config: null, credentials: null }
    }

    if (config.enabled) {
      await this.scheduler?.schedule()
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
    const credentials = credentialsFromValues(values)

    await this.secrets.setSecret(secretId, JSON.stringify(credentials), 'remember')

    const config: BackupConfig = {
      ...this.configFromValues(values, enabled),
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
      const config = await this.saveConfig(values, true)
      await this.scheduler?.schedule()
      return config
    }

    const existing = await this.repository.get()
    if (existing === null) {
      return null
    }

    const config: BackupConfig = { ...existing, enabled: false }
    await this.repository.put(config)
    await this.scheduler?.cancel()
    return config
  }

  async testConnection(values: BackupFormValues): Promise<void> {
    const validation = validateBackupForm(values)
    if (validation !== null) {
      throw new Error(validation)
    }

    const config = this.configFromValues(values, false)
    const storage = this.createStorage(config, credentialsFromValues(values))
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
    const base: BackupConfig = {
      ...DEFAULT_CONFIG,
      enabled,
      backend: values.backend,
      prefix: values.prefix.trim(),
    }

    switch (values.backend) {
      case 's3':
        return {
          ...base,
          endpoint: values.endpoint.trim(),
          region: values.region.trim(),
          bucket: values.bucket.trim(),
        }
      case 'azure-blob':
        return {
          ...base,
          endpoint:
            values.endpoint.trim() === ''
              ? `https://${(values.accountName ?? '').trim()}.blob.core.windows.net`
              : values.endpoint.trim(),
          region: null,
          bucket: values.bucket.trim(),
        }
      case 'gcs':
        return {
          ...base,
          endpoint: null,
          region: null,
          bucket: values.bucket.trim(),
        }
      case 'google-drive':
        return {
          ...base,
          endpoint: null,
          region: null,
          bucket: (values.folderId ?? '').trim() || 'appDataFolder',
        }
      case 'webdav':
        return {
          ...base,
          endpoint: values.endpoint.trim(),
          region: null,
          bucket: '',
        }
      default:
        return base
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

  const service = new BackupSettingsService({
    db,
    secrets: options.secrets ?? new SecretStore({ db }),
    createStorage: options.createStorage,
    createEngine: options.createEngine,
    now: options.now,
  })

  const repository = new BackupConfigRepository(db)
  service.setScheduler(
    new BackupScheduler({
      engine: { backupNow: () => service.backupNow() },
      isEnabled: async () => (await repository.get())?.enabled ?? false,
    }),
  )

  return service
}
