import { afterEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import {
  newId,

  type Attachment,
  type ChatParams,
  type Conversation,
  type Message,
} from '../../domain'
import {
  AttachmentRepository,
  BackupConfigRepository,
  ConversationRepository,
  MessageRepository,
  SyncQueueRepository,
  deleteDatabase,
  openDatabase,
  type ChatUiDB,
} from '../../storage'
import { buildConversationPayload, type ConversationPayload } from './conversationPayload'
import { EnvelopeError, decryptObject, encryptObject } from './envelope'
import {
  MANIFEST_FORMAT_VERSION,
  type ManifestAttachmentEntry,
  type ManifestBody,
  type ManifestConversationEntry,
  type ManifestTombstone,
} from './manifest'
import { mergeConversation, mergeMessages } from './merge'
import {
  DELETE_REMOTE_CONFIRMATION,
  MANIFEST_OBJECT_KEY,
  SyncEngine,
  type SyncEngineOptions,
} from './sync'
import {
  StorageError,
  type ListObjectsOptions,
  type ListObjectsResult,
  type PutObjectOptions,
  type StorageAdapter,
  type StorageObject,
} from './storage'

const PASSPHRASE = 'correct horse battery staple'
const ITERATIONS = 1000
const NOW = '2026-01-01T00:00:00.000Z'

const params: ChatParams = {
  temperature: null,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

const databaseNames = new Set<string>()
const openDatabases = new Set<IDBPDatabase<ChatUiDB>>()

function uniqueDatabaseName(): string {
  const name = `chatui-sync-engine-${newId()}`
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

class MemoryStorage implements StorageAdapter {
  readonly objects = new Map<string, { bytes: Uint8Array; etag: string }>()
  readonly putCalls: Array<{ key: string; options: PutObjectOptions }> = []
  readonly getCalls: string[] = []
  failPut: ((key: string, options: PutObjectOptions) => StorageError | undefined) | undefined
  private etagCounter = 0

  async testConnection(): Promise<void> {}

  async getObject(key: string): Promise<StorageObject> {
    this.getCalls.push(key)
    const object = this.objects.get(key)
    if (object === undefined) {
      throw new StorageError('not_found', `Missing ${key}`, { status: 404 })
    }
    return { key, bytes: object.bytes, etag: object.etag }
  }

  async putObject(
    key: string,
    bytes: Uint8Array,
    options: PutObjectOptions = {},
  ): Promise<StorageObject> {
    this.putCalls.push({ key, options })
    const failure = this.failPut?.(key, options)
    if (failure !== undefined) {
      throw failure
    }

    const existing = this.objects.get(key)
    if (options.ifMatch !== undefined && (existing === undefined || existing.etag !== options.ifMatch)) {
      throw new StorageError('conflict', `ETag mismatch for ${key}`, { status: 412 })
    }
    if (options.ifNoneMatch === '*' && existing !== undefined) {
      throw new StorageError('conflict', `${key} already exists`, { status: 412 })
    }

    const etag = `"etag-${(this.etagCounter += 1)}"`
    this.objects.set(key, { bytes, etag })
    return { key, bytes, etag }
  }

  async headObject(key: string): Promise<StorageObject> {
    const object = this.objects.get(key)
    if (object === undefined) {
      throw new StorageError('not_found', `Missing ${key}`, { status: 404 })
    }
    return { key, bytes: new Uint8Array(0), etag: object.etag }
  }

  async deleteObject(key: string): Promise<void> {
    this.objects.delete(key)
  }

  async listObjects(options: ListObjectsOptions = {}): Promise<ListObjectsResult> {
    const prefix = options.prefix ?? ''
    return {
      objects: [...this.objects.entries()]
        .filter(([key]) => key.startsWith(prefix))
        .map(([key, value]) => ({ key, bytes: new Uint8Array(0), etag: value.etag })),
    }
  }
}

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: newId(),
    title: 'Conversation',
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
    messageCount: 0,
    lastError: null,
    sync: null,
    storageKey: newId().replace(/-/gu, '').slice(0, 32),
    revision: 1,
    ...overrides,
  }
}

function message(
  conversationId: string,
  sequence: number,
  overrides: Partial<Message> = {},
): Message {
  return {
    id: newId(),
    conversationId,
    sequence,
    role: 'user',
    parts: [{ type: 'text', text: `Message ${sequence}` }],
    status: 'complete',
    providerId: null,
    modelId: null,
    modelLabel: null,
    usage: null,
    finishReason: null,
    error: null,
    latencyMs: null,
    firstTokenMs: null,
    createdAt: NOW,
    updatedAt: NOW,
    deletedAt: null,
    ...overrides,
  }
}

function payload(
  source: Conversation,
  messages: readonly Message[],
  attachments: readonly Attachment[] = [],
): ConversationPayload {
  return buildConversationPayload(source, messages, attachments)
}

function manifestEntry(
  source: Conversation,
  deviceId: string,
  overrides: Partial<ManifestConversationEntry> = {},
): ManifestConversationEntry {
  return {
    id: source.id,
    storageKey: source.storageKey,
    revision: source.revision,
    updatedAt: source.updatedAt,
    deletedAt: null,
    deviceId,
    objectKey: `conversations/${source.storageKey}.enc`,
    ...overrides,
  }
}

function manifestBody(
  deviceId: string,
  conversations: ManifestConversationEntry[],
  attachments: ManifestAttachmentEntry[] = [],
  tombstones: ManifestTombstone[] = [],
): ManifestBody {
  return {
    formatVersion: MANIFEST_FORMAT_VERSION,
    createdAt: NOW,
    updatedAt: NOW,
    deviceId,
    conversations,
    attachments,
    tombstones,
  }
}

async function putRemoteManifest(
  storage: MemoryStorage,
  body: ManifestBody,
  passphrase = PASSPHRASE,
  salt: Uint8Array,
): Promise<void> {
  const bytes = await encryptObject({
    passphrase,
    salt,
    objectType: 'manifest',
    objectKey: MANIFEST_OBJECT_KEY,
    plaintext: JSON.stringify(body),
    compress: false,
    iterations: ITERATIONS,
  })
  await storage.putObject(MANIFEST_OBJECT_KEY, bytes)
}

async function putRemoteConversation(
  storage: MemoryStorage,
  source: ConversationPayload,
  salt: Uint8Array,
  passphrase = PASSPHRASE,
): Promise<void> {
  const objectKey = `conversations/${source.storageKey}.enc`
  const bytes = await encryptObject({
    passphrase,
    salt,
    objectType: 'conversation',
    objectKey,
    plaintext: JSON.stringify(source),
    iterations: ITERATIONS,
  })
  await storage.putObject(objectKey, bytes)
}

async function readRemoteManifest(storage: MemoryStorage): Promise<ManifestBody> {
  const object = await storage.getObject(MANIFEST_OBJECT_KEY)
  const decrypted = await decryptObject({ passphrase: PASSPHRASE, bytes: object.bytes })
  return JSON.parse(new TextDecoder().decode(decrypted.plaintext)) as ManifestBody
}

interface Harness {
  db: IDBPDatabase<ChatUiDB>
  storage: MemoryStorage
  engine: SyncEngine
  conversations: ConversationRepository
  messages: MessageRepository
  attachments: AttachmentRepository
  syncQueue: SyncQueueRepository
  backupConfig: BackupConfigRepository
}

async function harness(overrides: Partial<SyncEngineOptions> = {}): Promise<Harness> {
  const db = await openDatabase(uniqueDatabaseName())
  openDatabases.add(db)
  const storage = new MemoryStorage()
  const engine = new SyncEngine({
    db,
    storage,
    passphrase: PASSPHRASE,
    iterations: ITERATIONS,
    now: () => NOW,
    sleep: async () => undefined,
    ...overrides,
  })

  return {
    db,
    storage,
    engine,
    conversations: new ConversationRepository(db),
    messages: new MessageRepository(db),
    attachments: new AttachmentRepository(db),
    syncQueue: new SyncQueueRepository(db),
    backupConfig: new BackupConfigRepository(db),
  }
}

describe('SyncEngine identity', () => {
  it('creates and persists a device ID and backup salt on first setup', async () => {
    const { engine, backupConfig } = await harness()

    const first = await engine.ensureIdentity()
    const second = await engine.ensureIdentity()
    const stored = await backupConfig.get()

    expect(first.deviceId).not.toBe('')
    expect(first.salt).toHaveLength(16)
    expect(second.deviceId).toBe(first.deviceId)
    expect(second.salt).toEqual(first.salt)
    expect(stored?.deviceId).toBe(first.deviceId)
    expect(stored?.kdfSalt).not.toBeNull()
  })
})

describe('SyncEngine sync', () => {
  it('creates an empty manifest when the remote is empty', async () => {
    const { engine, storage } = await harness()

    const result = await engine.sync()

    expect(result.uploadedConversations).toEqual([])
    expect(storage.objects.has(MANIFEST_OBJECT_KEY)).toBe(true)
    const manifest = await readRemoteManifest(storage)
    expect(manifest.conversations).toEqual([])
    expect(manifest.attachments).toEqual([])
    expect(manifest.tombstones).toEqual([])
  })

  it('uploads local-only changes and downloads remote-only changes', async () => {
    const { engine, storage, conversations, messages } = await harness()
    const identity = await engine.ensureIdentity()

    const local = await conversations.create(conversation({ title: 'Local only' }))
    await messages.appendMessage(message(local.id, 0))

    const remote = conversation({ title: 'Remote only' })
    const remoteMessage = message(remote.id, 0)
    await putRemoteConversation(storage, payload(remote, [remoteMessage]), identity.salt)
    await putRemoteManifest(
      storage,
      manifestBody('remote-device', [manifestEntry(remote, 'remote-device')]),
      PASSPHRASE,
      identity.salt,
    )

    const result = await engine.sync()

    expect(result.uploadedConversations).toContain(local.id)
    expect(result.downloadedConversations).toContain(remote.id)
    expect(await conversations.get(local.id)).not.toBeNull()
    expect(await conversations.get(remote.id)).not.toBeNull()
    expect(storage.objects.has(`conversations/${local.storageKey}.enc`)).toBe(true)

    const manifest = await readRemoteManifest(storage)
    expect(manifest.conversations.map((entry) => entry.id).sort()).toEqual(
      [local.id, remote.id].sort(),
    )
  })

  it('uploads dirty attachments encrypted and records them in the manifest', async () => {
    const bytes = new Uint8Array([1, 2, 3, 4, 5])
    const sha256 = 'a'.repeat(64)
    const attachment: Attachment = {
      id: newId(),
      messageId: null,
      kind: 'image',
      mimeType: 'image/png',
      name: 'pixel.png',
      size: bytes.length,
      sha256,
      storageKey: `attachments/${sha256}`,
      width: 1,
      height: 1,
      createdAt: NOW,
    }
    const { engine, storage, conversations, messages, attachments } = await harness({
      attachmentStore: {
        get: async (storageKey) => {
          expect(storageKey).toBe(`attachments/${sha256}`)
          return bytes
        },
      },
    })

    const local = await conversations.create(conversation())
    await messages.appendMessage(
      message(local.id, 0, {
        parts: [{ type: 'image', attachmentId: attachment.id, mimeType: 'image/png', alt: '' }],
      }),
    )
    await attachments.create(attachment)

    const result = await engine.sync()

    expect(result.uploadedAttachments).toEqual([sha256])
    const object = storage.objects.get(`attachments/${sha256}.enc`)
    expect(object).toBeDefined()
    const decrypted = await decryptObject({ passphrase: PASSPHRASE, bytes: object!.bytes })
    expect(decrypted.header.objectType).toBe('attachment')
    expect(decrypted.plaintext).toEqual(bytes)

    const manifest = await readRemoteManifest(storage)
    expect(manifest.attachments.map((entry) => entry.sha256)).toEqual([sha256])
  })

  it('retries and re-reads the manifest on a conflict', async () => {
    const { engine, storage } = await harness()
    let conflicts = 0
    storage.failPut = (key) => {
      if (key === MANIFEST_OBJECT_KEY && conflicts === 0) {
        conflicts += 1
        return new StorageError('conflict', 'Simulated manifest conflict', { status: 412 })
      }
      return undefined
    }

    await engine.sync()

    expect(conflicts).toBe(1)
    expect(storage.putCalls.filter((call) => call.key === MANIFEST_OBJECT_KEY)).toHaveLength(2)
    expect(storage.getCalls.filter((key) => key === MANIFEST_OBJECT_KEY)).toHaveLength(2)
    expect(storage.objects.has(MANIFEST_OBJECT_KEY)).toBe(true)
  })

  it('leaves local data unchanged when an upload is interrupted', async () => {
    const { engine, storage, conversations } = await harness()
    const local = await conversations.create(conversation({ title: 'Keep me' }))
    storage.failPut = (key) =>
      key.startsWith('conversations/')
        ? new StorageError('network', 'Simulated network failure')
        : undefined

    await expect(engine.sync()).rejects.toBeInstanceOf(StorageError)

    const stored = await conversations.get(local.id)
    expect(stored?.title).toBe('Keep me')
    expect(stored?.revision).toBe(local.revision)
    expect(storage.objects.has(MANIFEST_OBJECT_KEY)).toBe(false)
  })

  it('drains the sync queue after a successful sync', async () => {
    const { engine, syncQueue } = await harness()
    await syncQueue.enqueue({ conversationId: newId(), operation: 'upsert' })
    await syncQueue.enqueue({ conversationId: newId(), operation: 'delete' })

    const result = await engine.sync()

    expect(result.drainedQueue).toBe(2)
    expect(await syncQueue.list()).toEqual([])
  })

  it('fails before any local mutation with a wrong passphrase', async () => {
    const { engine, storage, conversations } = await harness()
    const identity = await engine.ensureIdentity()
    const local = await conversations.create(conversation({ title: 'Untouched' }))

    await putRemoteManifest(
      storage,
      manifestBody('remote-device', []),
      'wrong passphrase',
      identity.salt,
    )

    await expect(engine.sync()).rejects.toBeInstanceOf(EnvelopeError)
    await expect(engine.restore()).rejects.toBeInstanceOf(EnvelopeError)

    const stored = await conversations.get(local.id)
    expect(stored?.title).toBe('Untouched')
    expect(stored?.revision).toBe(local.revision)
  })
})

describe('SyncEngine restore', () => {
  it('merges by default and never wipes local data', async () => {
    const { engine, storage, conversations, messages } = await harness()
    const identity = await engine.ensureIdentity()

    const local = await conversations.create(conversation({ title: 'Local' }))
    await messages.appendMessage(message(local.id, 0))

    const remote = conversation({ title: 'Remote' })
    await putRemoteConversation(storage, payload(remote, [message(remote.id, 0)]), identity.salt)
    await putRemoteManifest(
      storage,
      manifestBody('remote-device', [manifestEntry(remote, 'remote-device')]),
      PASSPHRASE,
      identity.salt,
    )

    const result = await engine.restore()

    expect(result.replaced).toBe(false)
    expect(result.mergedConversations).toContain(remote.id)
    expect(await conversations.get(local.id)).not.toBeNull()
    expect(await conversations.get(remote.id)).not.toBeNull()
  })

  it('merges remote messages into an existing local conversation', async () => {
    const { engine, storage, conversations, messages } = await harness()
    const identity = await engine.ensureIdentity()

    const local = await conversations.create(conversation({ title: 'Shared' }))
    const localMessage = message(local.id, 0)
    await messages.appendMessage(localMessage)

    const remoteMessage = message(local.id, 1)
    const remotePayload = payload(local, [localMessage, remoteMessage])
    await putRemoteConversation(storage, remotePayload, identity.salt)
    await putRemoteManifest(
      storage,
      manifestBody('remote-device', [manifestEntry(local, 'remote-device')]),
      PASSPHRASE,
      identity.salt,
    )

    await engine.restore()

    const stored = await messages.listByConversation(local.id)
    expect(stored.items.map((item) => item.id).sort()).toEqual(
      [localMessage.id, remoteMessage.id].sort(),
    )
  })

  it('only replaces local conversations when explicitly requested', async () => {
    const { engine, storage, conversations } = await harness()
    const identity = await engine.ensureIdentity()

    const local = await conversations.create(conversation({ title: 'Local' }))
    const remote = conversation({ title: 'Remote' })
    await putRemoteConversation(storage, payload(remote, []), identity.salt)
    await putRemoteManifest(
      storage,
      manifestBody('remote-device', [manifestEntry(remote, 'remote-device')]),
      PASSPHRASE,
      identity.salt,
    )

    const result = await engine.restore({ replace: true })

    expect(result.replaced).toBe(true)
    expect(await conversations.get(local.id)).toBeNull()
    expect(await conversations.get(remote.id)).not.toBeNull()
  })
})

describe('SyncEngine deleteRemote', () => {
  it('requires the typed confirmation token and never deletes local data', async () => {
    const { engine, storage, conversations } = await harness()
    const identity = await engine.ensureIdentity()
    const local = await conversations.create(conversation())
    await putRemoteManifest(storage, manifestBody('remote-device', []), PASSPHRASE, identity.salt)

    await expect(engine.deleteRemote('delete')).rejects.toThrow()
    expect(storage.objects.has(MANIFEST_OBJECT_KEY)).toBe(true)

    await engine.deleteRemote(DELETE_REMOTE_CONFIRMATION)

    expect(storage.objects.has(MANIFEST_OBJECT_KEY)).toBe(false)
    expect(await conversations.get(local.id)).not.toBeNull()
  })
})

describe('mergeConversation', () => {
  it('keeps concurrent additions and renumbers sequences deterministically', () => {
    const source = conversation()
    const shared0 = message(source.id, 0)
    const shared1 = message(source.id, 1)
    const localExtra = message(source.id, 2, { createdAt: '2026-01-01T00:00:02.000Z' })
    const remoteExtra = message(source.id, 2, { createdAt: '2026-01-01T00:00:03.000Z' })

    const local = payload(source, [shared0, shared1, localExtra])
    const remote = payload(source, [shared0, shared1, remoteExtra])

    const merged = mergeConversation(local, remote, {
      localDeviceId: 'device-a',
      remoteDeviceId: 'device-b',
    })

    expect(merged.renumbered).toBe(true)
    expect(merged.payload.messages.map((item) => item.id)).toEqual([
      shared0.id,
      shared1.id,
      localExtra.id,
      remoteExtra.id,
    ])
    expect(merged.payload.messages.map((item) => item.sequence)).toEqual([0, 1, 2, 3])
  })

  it('resolves metadata by last-write-wins and device ID tiebreak', () => {
    const source = conversation({ title: 'Local title', updatedAt: '2026-01-01T00:00:00.000Z' })
    const local = payload(source, [])
    const remote = payload(
      conversation({
        id: source.id,
        storageKey: source.storageKey,
        title: 'Remote title',
        updatedAt: '2026-01-02T00:00:00.000Z',
      }),
      [],
    )

    const laterRemote = mergeConversation(local, remote, {
      localDeviceId: 'device-a',
      remoteDeviceId: 'device-b',
    })
    expect(laterRemote.payload.title).toBe('Remote title')

    const sameTimeRemote = payload(
      conversation({
        id: source.id,
        storageKey: source.storageKey,
        title: 'Remote title',
        updatedAt: source.updatedAt,
      }),
      [],
    )
    const remoteWinsTie = mergeConversation(local, sameTimeRemote, {
      localDeviceId: 'device-a',
      remoteDeviceId: 'device-b',
    })
    expect(remoteWinsTie.payload.title).toBe('Remote title')

    const localWinsTie = mergeConversation(local, sameTimeRemote, {
      localDeviceId: 'device-c',
      remoteDeviceId: 'device-b',
    })
    expect(localWinsTie.payload.title).toBe('Local title')
  })

  it('lets a deletion tombstone win over an older update', () => {
    const source = conversation({ updatedAt: '2026-01-01T00:00:00.000Z' })
    const local = payload(source, [])
    const remote = payload(source, [])
    const tombstones: ManifestTombstone[] = [
      { conversationId: source.id, deletedAt: '2026-01-02T00:00:00.000Z', deviceId: 'device-b' },
    ]

    const deleted = mergeConversation(local, remote, {
      localDeviceId: 'device-a',
      remoteDeviceId: 'device-b',
      conversationTombstones: tombstones,
    })
    expect(deleted.deleted).toBe(true)

    const newer = payload(
      conversation({ id: source.id, storageKey: source.storageKey, updatedAt: '2026-01-03T00:00:00.000Z' }),
      [],
    )
    const kept = mergeConversation(local, newer, {
      localDeviceId: 'device-a',
      remoteDeviceId: 'device-b',
      conversationTombstones: tombstones,
    })
    expect(kept.deleted).toBe(false)
  })

  it('drops a message only when its tombstone is at or after the update', () => {
    const source = conversation()
    const oldMessage = message(source.id, 0, { updatedAt: '2026-01-01T00:00:00.000Z' })
    const newMessage = message(source.id, 1, { updatedAt: '2026-01-03T00:00:00.000Z' })
    const local = payload(source, [oldMessage, newMessage])

    const merged = mergeMessages(local.messages, [], [
      {
        id: newId(),
        conversationId: source.id,
        messageId: oldMessage.id,
        deletedAt: '2026-01-02T00:00:00.000Z',
        deviceId: 'device-b',
      },
      {
        id: newId(),
        conversationId: source.id,
        messageId: newMessage.id,
        deletedAt: '2026-01-02T00:00:00.000Z',
        deviceId: 'device-b',
      },
    ])

    expect(merged.messages.map((item) => item.id)).toEqual([newMessage.id])
  })

  it('never drops local messages that are not tombstoned', () => {
    const source = conversation()
    const localMessages = [message(source.id, 0), message(source.id, 1)]
    const local = payload(source, localMessages)
    const remote = payload(source, [message(source.id, 0)])

    const merged = mergeConversation(local, remote, {
      localDeviceId: 'device-a',
      remoteDeviceId: 'device-b',
    })

    const mergedIds = new Set(merged.payload.messages.map((item) => item.id))
    for (const item of localMessages) {
      expect(mergedIds.has(item.id)).toBe(true)
    }
  })
})
