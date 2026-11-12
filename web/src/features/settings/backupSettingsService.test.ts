import { afterEach, describe, expect, it, vi } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { newId, type ChatParams, type Conversation, type Message } from '../../domain'
import { createCrypto } from '../../platform/crypto'
import {
  deleteDatabase,
  openDatabase,
  toConversationRecord,
  type ChatUiDB,
} from '../../storage'
import { SecretStore } from '../../storage/SecretStore'
import { StorageError, type StorageAdapter } from '../backup/storage'
import type { BackupSchedulerLike } from '../backup/scheduler'
import { DELETE_REMOTE_CONFIRMATION } from '../backup/sync'
import {
  BACKUP_CREDENTIALS_SECRET_ID,
  BackupSettingsService,
  RESTORE_REPLACE_CONFIRMATION,
  validateBackupForm,
  type BackupFormValues,
  type SyncEngineLike,
} from './backupSettingsService'

const NOW = '2026-11-11T12:00:00.000Z'
const fastCrypto = createCrypto({ pbkdf2Iterations: 1000 })

const params: ChatParams = {
  temperature: null,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

const values: BackupFormValues = {
  backend: 's3',
  endpoint: 'https://s3.us-east-1.amazonaws.com',
  region: 'us-east-1',
  bucket: 'chatui-backups',
  prefix: 'backups',
  accessKeyId: 'AKIAEXAMPLE',
  secretAccessKey: 'secret-example',
  passphrase: 'correct horse battery staple',
}

const databaseNames = new Set<string>()
const openDatabases = new Set<IDBPDatabase<ChatUiDB>>()

function uniqueDatabaseName(): string {
  const name = `chatui-backup-settings-${newId()}`
  databaseNames.add(name)
  return name
}

afterEach(async () => {
  for (const db of openDatabases) {
    db.close()
  }
  openDatabases.clear()
  for (const name of databaseNames) {
    await deleteDatabase(name)
  }
  databaseNames.clear()
})

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1',
    title: 'Backup test',
    systemPrompt: '',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'Model',
    params,
    pinned: false,
    archived: false,
    temporary: false,
    createdAt: NOW,
    updatedAt: NOW,
    lastMessageAt: NOW,
    messageCount: 1,
    lastError: null,
    sync: null,
    storageKey: '0123456789abcdef0123456789abcdef',
    revision: 1,
    ...overrides,
  }
}

function message(overrides: Partial<Message> = {}): Message {
  return {
    id: 'msg-1',
    conversationId: 'conv-1',
    sequence: 0,
    role: 'user',
    parts: [{ type: 'text', text: 'Hello backup' }],
    status: 'complete',
    providerId: null,
    modelId: null,
    modelLabel: null,
    usage: null,
    finishReason: 'stop',
    error: null,
    latencyMs: null,
    firstTokenMs: null,
    createdAt: NOW,
    updatedAt: NOW,
    deletedAt: null,
    ...overrides,
  }
}

function createStorage() {
  const testConnection = vi.fn(async () => undefined)
  const storage: StorageAdapter = {
    testConnection,
    getObject: vi.fn(async () => {
      throw new Error('not used')
    }),
    putObject: vi.fn(async () => {
      throw new Error('not used')
    }),
    headObject: vi.fn(async () => {
      throw new Error('not used')
    }),
    deleteObject: vi.fn(async () => undefined),
    listObjects: vi.fn(async () => ({ objects: [] })),
  }

  return { storage, testConnection }
}

function createEngine() {
  const backupNow = vi.fn(async () => ({
    uploadedConversations: [] as string[],
    uploadedAttachments: [] as string[],
    downloadedConversations: [] as string[],
    manifestEtag: null,
    drainedQueue: 0,
  }))
  const restore = vi.fn(async () => ({
    mergedConversations: [] as string[],
    replaced: false,
  }))
  const deleteRemote = vi.fn(async () => undefined)
  const engine: SyncEngineLike = { backupNow, restore, deleteRemote }

  return { engine, backupNow, restore, deleteRemote }
}

function createScheduler() {
  const schedule = vi.fn(async () => undefined)
  const cancel = vi.fn(async () => undefined)
  const runIfDue = vi.fn(async () => false)
  const scheduler: BackupSchedulerLike = { schedule, cancel, runIfDue }

  return { scheduler, schedule, cancel, runIfDue }
}

async function setup() {
  const name = uniqueDatabaseName()
  const db = await openDatabase(name)
  openDatabases.add(db)
  const secrets = new SecretStore({ db, crypto: fastCrypto })
  const storage = createStorage()
  const engine = createEngine()
  const service = new BackupSettingsService({
    db,
    secrets,
    createStorage: () => storage.storage,
    createEngine: () => engine.engine,
    now: () => NOW,
  })

  return { db, secrets, storage, engine, service }
}

describe('BackupSettingsService', () => {
  it('validates the form and saves config with credentials as secrets', async () => {
    const { service, secrets, db } = await setup()

    await expect(service.saveConfig({ ...values, bucket: '' })).rejects.toThrow(
      'Bucket is required.',
    )
    await expect(service.saveConfig({ ...values, passphrase: '' })).rejects.toThrow(
      'Passphrase is required.',
    )

    const config = await service.saveConfig(values)

    expect(config.enabled).toBe(false)
    expect(config.backend).toBe('s3')
    expect(config.endpoint).toBe(values.endpoint)
    expect(config.region).toBe(values.region)
    expect(config.bucket).toBe(values.bucket)
    expect(config.prefix).toBe(values.prefix)
    expect(config.credentialSecretId).toBe(BACKUP_CREDENTIALS_SECRET_ID)

    const stored = await secrets.getSecret(BACKUP_CREDENTIALS_SECRET_ID)
    expect(stored).not.toBeNull()
    expect(JSON.parse(stored ?? '{}')).toEqual({
      accessKeyId: values.accessKeyId,
      secretAccessKey: values.secretAccessKey,
    })

    const persisted = JSON.stringify([
      ...(await db.getAll('secrets')),
      ...(await db.getAll('backupConfig')),
      ...(await db.getAll('settings')),
    ])
    expect(persisted).not.toContain(values.passphrase)
  })

  it('loads the saved config and credentials', async () => {
    const { service } = await setup()
    await service.saveConfig(values)

    const snapshot = await service.load()

    expect(snapshot.config?.bucket).toBe(values.bucket)
    expect(snapshot.credentials).toEqual({
      accessKeyId: values.accessKeyId,
      secretAccessKey: values.secretAccessKey,
    })
  })

  it('tests connectivity and surfaces typed errors', async () => {
    const { service, storage } = await setup()

    await expect(service.testConnection(values)).resolves.toBeUndefined()
    expect(storage.testConnection).toHaveBeenCalledTimes(1)

    storage.testConnection.mockRejectedValueOnce(
      new StorageError('auth', 'HEAD bucket failed with HTTP 401', { status: 401 }),
    )

    await expect(service.testConnection(values)).rejects.toThrow(
      'HEAD bucket failed with HTTP 401',
    )
  })

  it('enables and disables backup', async () => {
    const { service } = await setup()

    const enabled = await service.setEnabled(true, values)
    expect(enabled?.enabled).toBe(true)

    const disabled = await service.setEnabled(false, values)
    expect(disabled?.enabled).toBe(false)
  })

  it('schedules when enabled and cancels when disabled', async () => {
    const { service } = await setup()
    const scheduler = createScheduler()
    service.setScheduler(scheduler.scheduler)

    await service.setEnabled(true, values)
    expect(scheduler.schedule).toHaveBeenCalledTimes(1)

    await service.setEnabled(false, values)
    expect(scheduler.cancel).toHaveBeenCalledTimes(1)
  })

  it('starts scheduling on load only when backup is already enabled', async () => {
    const { service } = await setup()
    const scheduler = createScheduler()
    service.setScheduler(scheduler.scheduler)

    await service.saveConfig(values, false)
    await service.load()
    expect(scheduler.schedule).not.toHaveBeenCalled()

    await service.saveConfig(values, true)
    await service.load()
    expect(scheduler.schedule).toHaveBeenCalledTimes(1)
  })

  it('backs up now through the sync engine and records the last backup time', async () => {
    const { service, engine } = await setup()
    await service.saveConfig(values, true)

    const result = await service.backupNow()

    expect(engine.backupNow).toHaveBeenCalledTimes(1)
    expect(result.uploadedConversations).toEqual([])
    expect((await service.getStatus()).lastBackupAt).toBe(NOW)
  })

  it('restores with merge by default and requires typed confirmation for replace', async () => {
    const { service, engine } = await setup()
    await service.saveConfig(values, true)

    await service.restore()
    expect(engine.restore).toHaveBeenCalledWith({ replace: false })

    await expect(service.restore({ replace: true })).rejects.toThrow(
      RESTORE_REPLACE_CONFIRMATION,
    )
    expect(engine.restore).toHaveBeenCalledTimes(1)

    await service.restore({
      replace: true,
      confirmation: RESTORE_REPLACE_CONFIRMATION,
    })
    expect(engine.restore).toHaveBeenLastCalledWith({ replace: true })
  })

  it('requires typed confirmation to delete the remote backup and keeps local data', async () => {
    const { service, engine, db } = await setup()
    await service.saveConfig(values, true)
    await db.put('conversations', toConversationRecord(conversation()))

    await expect(service.deleteRemote('DELETE')).rejects.toThrow(
      DELETE_REMOTE_CONFIRMATION,
    )
    expect(engine.deleteRemote).not.toHaveBeenCalled()

    await service.deleteRemote(DELETE_REMOTE_CONFIRMATION)

    expect(engine.deleteRemote).toHaveBeenCalledWith(DELETE_REMOTE_CONFIRMATION)
    expect(await db.getAll('conversations')).toHaveLength(1)
  })

  it('reports last backup, pending changes, last error, and storage usage', async () => {
    const { service, engine, db } = await setup()
    await service.saveConfig(values, true)
    await db.put('conversations', toConversationRecord(conversation()))
    await db.put('messages', message())
    await db.put('syncQueue', {
      id: 'queue-1',
      conversationId: 'conv-1',
      operation: 'upsert',
      payload: null,
      attempts: 0,
      lastAttemptAt: null,
      nextAttemptAt: null,
      lastError: null,
      createdAt: NOW,
      updatedAt: NOW,
    })

    engine.backupNow.mockRejectedValueOnce(new Error('network down'))
    await expect(service.backupNow()).rejects.toThrow('network down')

    const status = await service.getStatus()

    expect(status.enabled).toBe(true)
    expect(status.lastBackupAt).toBeNull()
    expect(status.pendingChanges).toBe(1)
    expect(status.lastError).toBe('network down')
    expect(status.storageUsageBytes).toBeGreaterThan(0)
  })

  it('validates and saves Azure Blob configuration and credentials', async () => {
    const { service, secrets } = await setup()
    const azureValues: BackupFormValues = {
      ...values,
      backend: 'azure-blob',
      endpoint: '',
      region: '',
      bucket: 'backup-container',
      accountName: 'chatuiaccount',
      accountKey: 'a2V5',
      sasToken: '',
    }

    expect(validateBackupForm({ ...azureValues, accountName: '' })).toBe(
      'Azure account name is required.',
    )
    expect(
      validateBackupForm({ ...azureValues, accountKey: '', sasToken: '' }),
    ).toBe('Azure account key or SAS token is required.')

    const config = await service.saveConfig(azureValues)

    expect(config.backend).toBe('azure-blob')
    expect(config.endpoint).toBe('https://chatuiaccount.blob.core.windows.net')
    expect(config.region).toBeNull()
    expect(config.bucket).toBe('backup-container')

    const stored = JSON.parse(
      (await secrets.getSecret(BACKUP_CREDENTIALS_SECRET_ID)) ?? '{}',
    ) as Record<string, unknown>
    expect(stored.accountName).toBe('chatuiaccount')
    expect(stored.accountKey).toBe('a2V5')
  })

  it('validates and saves GCS configuration and credentials', async () => {
    const { service, secrets } = await setup()
    const gcsValues: BackupFormValues = {
      ...values,
      backend: 'gcs',
      endpoint: '',
      region: '',
      bucket: 'chatui-gcs',
      serviceAccountJson: JSON.stringify({
        client_email: 'backup@example.test',
        private_key: 'test-key',
      }),
    }

    expect(validateBackupForm({ ...gcsValues, serviceAccountJson: 'not-json' })).toBe(
      'GCS service account JSON must be valid JSON.',
    )
    expect(
      validateBackupForm({
        ...gcsValues,
        serviceAccountJson: JSON.stringify({ client_email: 'backup@example.test' }),
      }),
    ).toBe('GCS service account JSON is missing private_key.')

    const config = await service.saveConfig(gcsValues)

    expect(config.backend).toBe('gcs')
    expect(config.endpoint).toBeNull()
    expect(config.region).toBeNull()
    expect(config.bucket).toBe('chatui-gcs')

    const stored = JSON.parse(
      (await secrets.getSecret(BACKUP_CREDENTIALS_SECRET_ID)) ?? '{}',
    ) as Record<string, unknown>
    expect(stored.serviceAccountJson).toBe(gcsValues.serviceAccountJson)
  })

  it('validates and saves Google Drive configuration and credentials', async () => {
    const { service, secrets } = await setup()
    const driveValues: BackupFormValues = {
      ...values,
      backend: 'google-drive',
      endpoint: '',
      region: '',
      bucket: '',
      accessToken: 'drive-token',
      folderId: 'folder-1',
    }

    expect(validateBackupForm({ ...driveValues, accessToken: '' })).toBe(
      'Google Drive access token is required.',
    )

    const config = await service.saveConfig(driveValues)

    expect(config.backend).toBe('google-drive')
    expect(config.endpoint).toBeNull()
    expect(config.region).toBeNull()
    expect(config.bucket).toBe('folder-1')

    const stored = JSON.parse(
      (await secrets.getSecret(BACKUP_CREDENTIALS_SECRET_ID)) ?? '{}',
    ) as Record<string, unknown>
    expect(stored.accessToken).toBe('drive-token')
    expect(stored.folderId).toBe('folder-1')
  })

  it('validates and saves WebDAV configuration and credentials', async () => {
    const { service, secrets } = await setup()
    const webdavValues: BackupFormValues = {
      ...values,
      backend: 'webdav',
      endpoint: 'https://dav.example.test/dav/',
      region: '',
      bucket: '',
      username: 'backup-user',
      password: 'backup-pass',
    }

    expect(validateBackupForm({ ...webdavValues, password: '' })).toBe(
      'WebDAV password is required.',
    )

    const config = await service.saveConfig(webdavValues)

    expect(config.backend).toBe('webdav')
    expect(config.endpoint).toBe('https://dav.example.test/dav/')
    expect(config.region).toBeNull()
    expect(config.bucket).toBe('')

    const stored = JSON.parse(
      (await secrets.getSecret(BACKUP_CREDENTIALS_SECRET_ID)) ?? '{}',
    ) as Record<string, unknown>
    expect(stored.username).toBe('backup-user')
    expect(stored.password).toBe('backup-pass')
  })

  it('builds the backend-specific adapter for connectivity tests', async () => {
    const { db, secrets, storage } = await setup()
    const createStorage = vi.fn(() => storage.storage)
    const service = new BackupSettingsService({
      db,
      secrets,
      createStorage,
      createEngine: () => createEngine().engine,
      now: () => NOW,
    })

    await service.testConnection({
      ...values,
      backend: 'azure-blob',
      endpoint: '',
      region: '',
      bucket: 'backup-container',
      accountName: 'chatuiaccount',
      accountKey: 'a2V5',
      sasToken: '',
    })

    expect(createStorage).toHaveBeenCalledWith(
      expect.objectContaining({
        backend: 'azure-blob',
        endpoint: 'https://chatuiaccount.blob.core.windows.net',
        bucket: 'backup-container',
      }),
      expect.objectContaining({ accountName: 'chatuiaccount', accountKey: 'a2V5' }),
    )
    expect(storage.testConnection).toHaveBeenCalledTimes(1)
  })
})
