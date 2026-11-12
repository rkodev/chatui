/**
 * Web sync engine.
 *
 * The engine reads the encrypted remote manifest, merges it with the local
 * index by conversation revision, uploads dirty encrypted objects, and rewrites
 * the manifest with an `If-Match` precondition. Restore is non-destructive by
 * default and a wrong passphrase fails before any local mutation.
 *
 * The engine never uploads plaintext: every object goes through the envelope
 * codec and the storage adapter only accepts `Uint8Array` bodies.
 */

import type { IDBPDatabase } from 'idb'
import {
  newId,
  nowIso,
  type Attachment,
  type BackupConfig,
  type Conversation,
  type Message,
  type Tombstone,
} from '../../domain'
import { base64ToBytes, bytesToBase64, generateSalt } from '../../platform/crypto'
import {
  BackupConfigRepository,
  ExportReader,
  SyncQueueRepository,
  TombstoneRepository,
  fromConversationRecord,
  runTransaction,
  toConversationRecord,
  type ChatUiDB,
  type ExportSettingsSource,
  type ExportSnapshot,
} from '../../storage'
import { AttachmentStore } from '../../storage/AttachmentStore'
import { DEFAULT_SETTINGS } from '../settings/settingsService'
import {
  buildConversationPayload,
  parseConversationPayload,
  type ConversationPayload,
  type ConversationPayloadMessage,
} from './conversationPayload'
import { decryptObject, encryptObject } from './envelope'
import {
  MANIFEST_FORMAT_VERSION,
  buildManifest,
  mergeManifestIndexes,
  parseManifest,
  type ManifestBody,
  type ManifestConversationEntry,
  type ManifestTombstone,
} from './manifest'
import { mergeConversation } from './merge'
import { StorageError, type StorageAdapter } from './storage'

export const MANIFEST_OBJECT_KEY = 'manifest.enc'
export const DELETE_REMOTE_CONFIRMATION = 'DELETE REMOTE BACKUP'

const DEFAULT_MAX_CONFLICT_RETRIES = 4
const DEFAULT_BASE_BACKOFF_MS = 50

const DEFAULT_BACKUP_CONFIG: BackupConfig = {
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

export interface BackupIdentity {
  deviceId: string
  salt: Uint8Array
}

export interface SyncEngineOptions {
  db: IDBPDatabase<ChatUiDB>
  storage: StorageAdapter
  passphrase: string
  now?: () => string
  sleep?: (milliseconds: number) => Promise<void>
  maxConflictRetries?: number
  baseBackoffMs?: number
  /** Test-only PBKDF2 iteration override. Production callers must omit this. */
  iterations?: number
  attachmentStore?: Pick<AttachmentStore, 'get'>
  settings?: ExportSettingsSource
}

export interface SyncResult {
  uploadedConversations: string[]
  uploadedAttachments: string[]
  downloadedConversations: string[]
  manifestEtag: string | null
  drainedQueue: number
}

export interface RestoreOptions {
  /** Replace local conversations instead of merging. Requires explicit opt-in. */
  replace?: boolean
}

export interface RestoreResult {
  mergedConversations: string[]
  replaced: boolean
}

interface RemoteManifest {
  manifest: ManifestBody
  etag?: string
}

function defaultSleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, milliseconds)
  })
}

function emptyManifest(deviceId: string, now: string): ManifestBody {
  return {
    formatVersion: MANIFEST_FORMAT_VERSION,
    createdAt: now,
    updatedAt: now,
    deviceId,
    conversations: [],
    attachments: [],
    tombstones: [],
  }
}

function referencedAttachmentIds(messages: readonly Message[]): Set<string> {
  const ids = new Set<string>()

  for (const message of messages) {
    for (const part of message.parts) {
      if (part.type === 'image' || part.type === 'file') {
        ids.add(part.attachmentId)
      }
    }
  }

  return ids
}

function payloadMessageToMessage(
  message: ConversationPayloadMessage,
  conversationId: string,
): Message {
  return {
    id: message.id,
    conversationId,
    sequence: message.sequence,
    role: message.role,
    parts: structuredClone(message.parts),
    status: message.status,
    providerId: null,
    modelId: null,
    modelLabel: null,
    usage: message.usage === null ? null : { ...message.usage },
    finishReason: null,
    error: null,
    latencyMs: null,
    firstTokenMs: null,
    createdAt: message.createdAt,
    updatedAt: message.updatedAt,
    deletedAt: message.deletedAt,
  }
}

function payloadAttachmentToAttachment(
  attachment: ConversationPayload['attachments'][number],
  createdAt: string,
): Attachment {
  return {
    id: attachment.id,
    messageId: null,
    kind: attachment.mimeType.startsWith('image/') ? 'image' : 'file',
    mimeType: attachment.mimeType,
    name: attachment.name,
    size: attachment.size,
    sha256: attachment.sha256,
    storageKey: `attachments/${attachment.sha256}`,
    width: null,
    height: null,
    createdAt,
  }
}

export class SyncEngine {
  private readonly db: IDBPDatabase<ChatUiDB>
  private readonly storage: StorageAdapter
  private readonly passphrase: string
  private readonly now: () => string
  private readonly sleep: (milliseconds: number) => Promise<void>
  private readonly maxConflictRetries: number
  private readonly baseBackoffMs: number
  private readonly iterations: number | undefined
  private readonly attachmentStore: Pick<AttachmentStore, 'get'>
  private readonly reader: ExportReader
  private readonly tombstones: TombstoneRepository
  private readonly syncQueue: SyncQueueRepository

  constructor(options: SyncEngineOptions) {
    this.db = options.db
    this.storage = options.storage
    this.passphrase = options.passphrase
    this.now = options.now ?? nowIso
    this.sleep = options.sleep ?? defaultSleep
    this.maxConflictRetries = options.maxConflictRetries ?? DEFAULT_MAX_CONFLICT_RETRIES
    this.baseBackoffMs = options.baseBackoffMs ?? DEFAULT_BASE_BACKOFF_MS
    this.iterations = options.iterations
    this.attachmentStore = options.attachmentStore ?? new AttachmentStore({ db: options.db })
    this.reader = new ExportReader({
      db: options.db,
      settings: options.settings ?? { get: async () => DEFAULT_SETTINGS },
    })
    this.tombstones = new TombstoneRepository(options.db)
    this.syncQueue = new SyncQueueRepository(options.db)
  }

  /**
   * Create and persist the device ID and backup salt on first setup.
   *
   * Both values live in `backupConfig`; the salt is stored base64-encoded so it
   * survives IndexedDB structured cloning unchanged.
   */
  async ensureIdentity(): Promise<BackupIdentity> {
    const repository = new BackupConfigRepository(this.db)
    const existing = await repository.get()
    const deviceId = existing?.deviceId.trim() ? existing.deviceId : newId()
    const kdfSalt = existing?.kdfSalt ?? bytesToBase64(generateSalt())

    if (existing === null || existing.deviceId !== deviceId || existing.kdfSalt !== kdfSalt) {
      await repository.put({
        ...DEFAULT_BACKUP_CONFIG,
        ...(existing ?? {}),
        deviceId,
        kdfSalt,
      })
    }

    return { deviceId, salt: base64ToBytes(kdfSalt) }
  }

  /** Back up local changes and merge remote changes. */
  async sync(): Promise<SyncResult> {
    const identity = await this.ensureIdentity()
    const remote = await this.readRemoteManifest(identity.deviceId)
    const localTombstones = await this.tombstones.listAll()
    const messageTombstones = localTombstones.filter(
      (tombstone) => tombstone.messageId !== undefined,
    )

    const downloadedConversations: string[] = []
    let snapshot = await this.reader.readAll()
    let localById = new Map(snapshot.conversations.map((conversation) => [conversation.id, conversation]))

    for (const entry of remote.manifest.conversations) {
      const localConversation = localById.get(entry.id) ?? null
      if (localConversation !== null && localConversation.revision > entry.revision) {
        continue
      }

      const payload = await this.downloadConversation(entry)
      const changed = await this.applyRemoteConversation(
        payload,
        entry,
        localConversation,
        snapshot,
        remote.manifest.tombstones,
        messageTombstones,
        identity.deviceId,
      )
      if (changed) {
        downloadedConversations.push(entry.id)
      }
    }

    snapshot = await this.reader.readAll()
    localById = new Map(snapshot.conversations.map((conversation) => [conversation.id, conversation]))

    const localManifest = buildManifest(snapshot, {
      deviceId: identity.deviceId,
      tombstones: localTombstones.filter((tombstone) => tombstone.messageId === undefined),
      createdAt: this.now(),
      updatedAt: this.now(),
    })
    const merged = mergeManifestIndexes(localManifest, remote.manifest)

    const uploadedConversations: string[] = []
    const remoteById = new Map(
      remote.manifest.conversations.map((entry) => [entry.id, entry]),
    )

    for (const entry of merged.conversations) {
      const localConversation = localById.get(entry.id)
      if (localConversation === undefined) {
        continue
      }

      const remoteEntry = remoteById.get(entry.id)
      if (remoteEntry !== undefined && remoteEntry.revision >= localConversation.revision) {
        continue
      }

      const payload = this.buildLocalPayload(localConversation, snapshot)
      const bytes = await encryptObject({
        passphrase: this.passphrase,
        salt: identity.salt,
        objectType: 'conversation',
        objectKey: entry.objectKey,
        plaintext: JSON.stringify(payload),
        iterations: this.iterations,
      })
      await this.storage.putObject(entry.objectKey, bytes)
      uploadedConversations.push(entry.id)
    }

    const uploadedAttachments: string[] = []
    const remoteAttachmentShas = new Set(
      remote.manifest.attachments.map((entry) => entry.sha256),
    )

    for (const entry of merged.attachments) {
      if (remoteAttachmentShas.has(entry.sha256)) {
        continue
      }

      const bytes = await this.attachmentStore.get(`attachments/${entry.sha256}`)
      const encrypted = await encryptObject({
        passphrase: this.passphrase,
        salt: identity.salt,
        objectType: 'attachment',
        objectKey: entry.objectKey,
        plaintext: bytes,
        compress: false,
        iterations: this.iterations,
      })
      await this.storage.putObject(entry.objectKey, encrypted)
      uploadedAttachments.push(entry.sha256)
    }

    const written = await this.writeManifest(localManifest, remote, identity)
    const drainedQueue = await this.drainSyncQueue()
    await this.updateConfig({ lastSyncAt: this.now(), lastManifestEtag: written.etag })

    return {
      uploadedConversations,
      uploadedAttachments,
      downloadedConversations,
      manifestEtag: written.etag,
      drainedQueue,
    }
  }

  /** Explicit "back up now" entry point. */
  async backupNow(): Promise<SyncResult> {
    return this.sync()
  }

  /**
   * Restore remote data into local storage.
   *
   * The default merges and never deletes local conversations. `replace: true`
   * is the only path that clears local conversations, and callers must obtain
   * explicit user confirmation before passing it.
   */
  async restore(options: RestoreOptions = {}): Promise<RestoreResult> {
    const identity = await this.ensureIdentity()
    const remote = await this.readRemoteManifest(identity.deviceId)
    const localTombstones = await this.tombstones.listAll()
    const messageTombstones = localTombstones.filter(
      (tombstone) => tombstone.messageId !== undefined,
    )

    if (options.replace === true) {
      await this.clearLocalConversations()
    }

    const snapshot = await this.reader.readAll()
    const localById = new Map(
      snapshot.conversations.map((conversation) => [conversation.id, conversation]),
    )
    const mergedConversations: string[] = []

    for (const entry of remote.manifest.conversations) {
      const localConversation = localById.get(entry.id) ?? null
      const payload = await this.downloadConversation(entry)
      const changed = await this.applyRemoteConversation(
        payload,
        entry,
        localConversation,
        snapshot,
        remote.manifest.tombstones,
        messageTombstones,
        identity.deviceId,
      )
      if (changed) {
        mergedConversations.push(entry.id)
      }
    }

    return { mergedConversations, replaced: options.replace === true }
  }

  /**
   * Delete every remote backup object. The caller must pass the exact typed
   * confirmation token. Local data is never touched.
   */
  async deleteRemote(confirmation: string): Promise<void> {
    if (confirmation !== DELETE_REMOTE_CONFIRMATION) {
      throw new Error('Remote backup deletion requires the typed confirmation token')
    }

    try {
      await this.storage.deleteObject(MANIFEST_OBJECT_KEY)
    } catch (error) {
      if (!(error instanceof StorageError) || error.code !== 'not_found') {
        throw error
      }
    }

    let continuationToken: string | undefined
    do {
      const page = await this.storage.listObjects({
        prefix: '',
        continuationToken,
      })
      for (const object of page.objects) {
        if (object.key === MANIFEST_OBJECT_KEY) {
          continue
        }
        await this.storage.deleteObject(object.key)
      }
      continuationToken = page.nextContinuationToken
    } while (continuationToken !== undefined)
  }

  private async readRemoteManifest(deviceId: string): Promise<RemoteManifest> {
    try {
      const object = await this.storage.getObject(MANIFEST_OBJECT_KEY)
      const decrypted = await decryptObject({
        passphrase: this.passphrase,
        bytes: object.bytes,
      })

      if (
        decrypted.header.objectType !== 'manifest' ||
        decrypted.header.objectKey !== MANIFEST_OBJECT_KEY
      ) {
        throw new Error('Remote manifest has an unexpected envelope header')
      }

      return { manifest: parseManifest(decrypted.plaintext), etag: object.etag }
    } catch (error) {
      if (error instanceof StorageError && error.code === 'not_found') {
        return { manifest: emptyManifest(deviceId, this.now()), etag: undefined }
      }
      throw error
    }
  }

  private async downloadConversation(
    entry: ManifestConversationEntry,
  ): Promise<ConversationPayload> {
    const object = await this.storage.getObject(entry.objectKey)
    const decrypted = await decryptObject({
      passphrase: this.passphrase,
      bytes: object.bytes,
    })

    if (
      decrypted.header.objectType !== 'conversation' ||
      decrypted.header.objectKey !== entry.objectKey
    ) {
      throw new Error(`Remote conversation ${entry.id} has an unexpected envelope header`)
    }

    return parseConversationPayload(decrypted.plaintext)
  }

  private buildLocalPayload(
    conversation: Conversation,
    snapshot: ExportSnapshot,
  ): ConversationPayload {
    const messages = snapshot.messages.filter(
      (message) => message.conversationId === conversation.id,
    )
    const referenced = referencedAttachmentIds(messages)
    const attachments = snapshot.attachments.filter((attachment) =>
      referenced.has(attachment.id),
    )

    return buildConversationPayload(conversation, messages, attachments)
  }

  private async applyRemoteConversation(
    payload: ConversationPayload,
    entry: ManifestConversationEntry,
    localConversation: Conversation | null,
    snapshot: ExportSnapshot,
    conversationTombstones: readonly ManifestTombstone[],
    messageTombstones: readonly Tombstone[],
    localDeviceId: string,
  ): Promise<boolean> {
    if (localConversation === null) {
      const tombstone = conversationTombstones.find(
        (candidate) => candidate.conversationId === payload.id,
      )
      if (tombstone !== undefined && tombstone.deletedAt >= payload.updatedAt) {
        return false
      }

      await this.writeConversationPayload(payload, payload.revision)
      return true
    }

    const localPayload = this.buildLocalPayload(localConversation, snapshot)
    const merged = mergeConversation(localPayload, payload, {
      localDeviceId,
      remoteDeviceId: entry.deviceId,
      conversationTombstones,
      messageTombstones,
    })

    if (merged.deleted) {
      return false
    }

    if (JSON.stringify(merged.payload) === JSON.stringify(localPayload)) {
      return false
    }

    const revision = Math.max(localConversation.revision, entry.revision) + 1
    await this.writeConversationPayload(merged.payload, revision)
    return true
  }

  private async writeConversationPayload(
    payload: ConversationPayload,
    revision: number,
  ): Promise<void> {
    const messages = payload.messages.map((message) =>
      payloadMessageToMessage(message, payload.id),
    )

    await runTransaction(
      this.db,
      ['conversations', 'messages', 'attachments'],
      'readwrite',
      async (transaction) => {
        const conversations = transaction.objectStore('conversations')
        const messageStore = transaction.objectStore('messages')
        const attachments = transaction.objectStore('attachments')
        const existing = await conversations.get(payload.id)
        const base = existing === undefined ? null : fromConversationRecord(existing)

        const conversation: Conversation = {
          id: payload.id,
          title: payload.title,
          systemPrompt: payload.systemPrompt,
          providerId: payload.providerId,
          modelId: payload.modelId,
          modelLabel: payload.modelLabel,
          params: structuredClone(payload.params),
          pinned: payload.pinned,
          archived: payload.archived,
          temporary: payload.temporary,
          createdAt: payload.createdAt,
          updatedAt: payload.updatedAt,
          lastMessageAt: payload.lastMessageAt,
          messageCount: messages.length,
          lastError: base?.lastError ?? null,
          sync: base?.sync ?? null,
          storageKey: payload.storageKey,
          revision,
        }

        await conversations.put(toConversationRecord(conversation))

        for (const message of messages) {
          await messageStore.put(message)
        }

        for (const attachment of payload.attachments) {
          const existingAttachment = await attachments.get(attachment.id)
          if (existingAttachment === undefined) {
            await attachments.put(
              payloadAttachmentToAttachment(attachment, payload.createdAt),
            )
          }
        }
      },
    )
  }

  private async writeManifest(
    localManifest: ManifestBody,
    initialRemote: RemoteManifest,
    identity: BackupIdentity,
  ): Promise<{ body: ManifestBody; etag: string | null }> {
    let remote = initialRemote

    for (let attempt = 0; ; attempt += 1) {
      const merged = mergeManifestIndexes(localManifest, remote.manifest)
      const body: ManifestBody = {
        formatVersion: MANIFEST_FORMAT_VERSION,
        createdAt: remote.manifest.createdAt,
        updatedAt: this.now(),
        deviceId: identity.deviceId,
        conversations: merged.conversations,
        attachments: merged.attachments,
        tombstones: merged.tombstones,
      }

      const bytes = await encryptObject({
        passphrase: this.passphrase,
        salt: identity.salt,
        objectType: 'manifest',
        objectKey: MANIFEST_OBJECT_KEY,
        plaintext: JSON.stringify(body),
        compress: false,
        iterations: this.iterations,
      })

      try {
        const result = await this.storage.putObject(
          MANIFEST_OBJECT_KEY,
          bytes,
          remote.etag === undefined ? { ifNoneMatch: '*' } : { ifMatch: remote.etag },
        )
        return { body, etag: result.etag ?? null }
      } catch (error) {
        if (
          error instanceof StorageError &&
          error.code === 'conflict' &&
          attempt < this.maxConflictRetries
        ) {
          await this.sleep(this.baseBackoffMs * 2 ** attempt)
          remote = await this.readRemoteManifest(identity.deviceId)
          continue
        }
        throw error
      }
    }
  }

  private async drainSyncQueue(): Promise<number> {
    const items = await this.syncQueue.list()
    for (const item of items) {
      await this.syncQueue.delete(item.id)
    }
    return items.length
  }

  private async updateConfig(patch: Partial<BackupConfig>): Promise<void> {
    const repository = new BackupConfigRepository(this.db)
    const existing = await repository.get()
    if (existing === null) {
      return
    }
    await repository.put({ ...existing, ...patch })
  }

  private async clearLocalConversations(): Promise<void> {
    await runTransaction(
      this.db,
      ['conversations', 'messages'],
      'readwrite',
      async (transaction) => {
        await transaction.objectStore('conversations').clear()
        await transaction.objectStore('messages').clear()
      },
    )
  }
}
