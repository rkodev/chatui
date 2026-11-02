import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import {
  newId,
  nowIso,
  type Attachment,
  type BackupConfig,
  type ChatParams,
  type Conversation,
  type Message,
  type Model,
  type Provider,
} from '../domain'
import { deleteDatabase, openDatabase, type ChatUiDB } from './db'
import { ConversationRepository } from './ConversationRepository'
import { MessageRepository } from './MessageRepository'
import { AttachmentRepository } from './AttachmentRepository'
import { ProviderRepository } from './ProviderRepository'
import { ModelConflictError, ModelRepository } from './ModelRepository'
import { SettingsRepository } from './SettingsRepository'
import { SecretRepository } from './SecretRepository'
import { BackupConfigRepository, DEFAULT_BACKUP_CONFIG_ID } from './BackupConfigRepository'
import { SyncQueueRepository } from './SyncQueueRepository'

const params: ChatParams = {
  temperature: null,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  const now = nowIso()
  return {
    id: newId(),
    title: 'Test conversation',
    systemPrompt: '',
    providerId: newId(),
    modelId: newId(),
    modelLabel: 'Test model',
    params,
    pinned: false,
    archived: false,
    temporary: false,
    createdAt: now,
    updatedAt: now,
    lastMessageAt: now,
    messageCount: 0,
    lastError: null,
    sync: null,
    ...overrides,
  }
}

function message(
  conversationId: string,
  sequence: number,
  overrides: Partial<Message> = {},
): Message {
  const now = nowIso()
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
    finishReason: 'stop',
    error: null,
    latencyMs: null,
    firstTokenMs: null,
    createdAt: now,
    updatedAt: now,
    deletedAt: null,
    ...overrides,
  }
}

function provider(overrides: Partial<Provider> = {}): Provider {
  const now = nowIso()
  return {
    id: newId(),
    presetId: 'openrouter',
    label: 'OpenRouter',
    kind: 'openai-compatible',
    baseUrl: 'https://openrouter.ai/api/v1',
    authType: 'bearer',
    secretId: null,
    headers: {},
    enabled: true,
    browserCors: 'unknown',
    metadata: null,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function model(providerId: string, overrides: Partial<Model> = {}): Model {
  const now = nowIso()
  return {
    id: newId(),
    providerId,
    remoteId: 'openrouter/auto',
    label: 'Auto',
    contextWindow: null,
    maxOutputTokens: null,
    capabilities: ['text'],
    pricing: null,
    canonicalSlug: null,
    inputModalities: ['text'],
    outputModalities: ['text'],
    supportedParameters: [],
    metadata: null,
    hidden: false,
    pinned: false,
    source: 'preset',
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function attachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: newId(),
    messageId: null,
    kind: 'file',
    mimeType: 'text/plain',
    name: 'notes.txt',
    size: 5,
    sha256: newId(),
    storageKey: `attachments/${newId()}`,
    width: null,
    height: null,
    createdAt: nowIso(),
    ...overrides,
  }
}

function backupConfig(overrides: Partial<BackupConfig> = {}): BackupConfig {
  return {
    enabled: false,
    backend: 's3',
    endpoint: null,
    region: null,
    bucket: 'chatui-backups',
    prefix: 'devices/',
    credentialSecretId: null,
    encryption: 'aes-256-gcm',
    kdf: 'pbkdf2-sha256',
    kdfSalt: null,
    lastSyncAt: null,
    lastManifestEtag: null,
    deviceId: newId(),
    ...overrides,
  }
}

describe('storage repositories', () => {
  let db: IDBPDatabase<ChatUiDB>
  let databaseName: string

  beforeEach(async () => {
    databaseName = `chatui-repository-${newId()}`
    db = await openDatabase(databaseName)
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase(databaseName)
  })

  describe('ConversationRepository', () => {
    it('creates, reads, updates, lists, and deletes conversations', async () => {
      const repository = new ConversationRepository(db)
      const created = await repository.create(conversation({ title: 'First' }))

      expect(await repository.get(created.id)).toEqual(created)

      const updated = await repository.update(created.id, { title: 'Renamed', pinned: true })
      expect(updated.title).toBe('Renamed')
      expect(updated.pinned).toBe(true)
      expect((await repository.get(created.id))?.pinned).toBe(true)

      const page = await repository.list()
      expect(page.items.map((item) => item.id)).toEqual([created.id])

      await repository.delete(created.id)
      expect(await repository.get(created.id)).toBeNull()
    })

    it('paginates conversations by updatedAt with a stable cursor', async () => {
      const repository = new ConversationRepository(db)
      const first = await repository.create(
        conversation({ title: 'A', updatedAt: '2026-01-01T00:00:00.000Z' }),
      )
      const second = await repository.create(
        conversation({ title: 'B', updatedAt: '2026-01-02T00:00:00.000Z' }),
      )
      const third = await repository.create(
        conversation({ title: 'C', updatedAt: '2026-01-03T00:00:00.000Z' }),
      )

      const page1 = await repository.list({ limit: 2 })
      expect(page1.items.map((item) => item.id)).toEqual([third.id, second.id])
      expect(page1.nextCursor).not.toBeNull()

      const page2 = await repository.list({ limit: 2, cursor: page1.nextCursor })
      expect(page2.items.map((item) => item.id)).toEqual([first.id])
      expect(page2.nextCursor).toBeNull()
    })

    it('deletes a conversation and its messages together', async () => {
      const conversations = new ConversationRepository(db)
      const messages = new MessageRepository(db)
      const created = await conversations.create(conversation())
      const appended = message(created.id, 1)
      await messages.appendMessage(appended)

      await conversations.delete(created.id)

      expect(await conversations.get(created.id)).toBeNull()
      expect(await messages.get(appended.id)).toBeNull()
    })
  })

  describe('MessageRepository', () => {
    it('appends a message and updates conversation metadata in one transaction', async () => {
      const conversations = new ConversationRepository(db)
      const messages = new MessageRepository(db)
      const created = await conversations.create(
        conversation({ messageCount: 0, lastMessageAt: '2026-01-01T00:00:00.000Z' }),
      )
      const appended = message(created.id, 1, {
        createdAt: '2026-01-02T00:00:00.000Z',
        updatedAt: '2026-01-02T00:00:00.000Z',
      })

      const result = await messages.appendMessage(appended)

      expect(result.message).toEqual(appended)
      expect(result.conversation.messageCount).toBe(1)
      expect(result.conversation.lastMessageAt).toBe(appended.createdAt)
      expect(await messages.get(appended.id)).toEqual(appended)

      const stored = await conversations.get(created.id)
      expect(stored?.messageCount).toBe(1)
      expect(stored?.lastMessageAt).toBe(appended.createdAt)
    })

    it('does not double-count a re-appended message', async () => {
      const conversations = new ConversationRepository(db)
      const messages = new MessageRepository(db)
      const created = await conversations.create(conversation())
      const appended = message(created.id, 1)

      await messages.appendMessage(appended)
      await messages.appendMessage(appended)

      expect((await conversations.get(created.id))?.messageCount).toBe(1)
    })

    it('rolls back an append when the conversation is missing', async () => {
      const messages = new MessageRepository(db)
      const orphan = message(newId(), 1)

      await expect(messages.appendMessage(orphan)).rejects.toThrow(/not found/)
      expect(await messages.get(orphan.id)).toBeNull()
    })

    it('defaults to the last 50 messages and supports an older page', async () => {
      const conversations = new ConversationRepository(db)
      const messages = new MessageRepository(db)
      const created = await conversations.create(conversation())

      for (let sequence = 1; sequence <= 60; sequence += 1) {
        await db.put('messages', message(created.id, sequence))
      }

      const page = await messages.listByConversation(created.id)
      expect(page.items).toHaveLength(50)
      expect(page.items[0].sequence).toBe(11)
      expect(page.items[49].sequence).toBe(60)
      expect(page.nextCursor).toBe(11)

      const older = await messages.listByConversation(created.id, {
        beforeSequence: page.nextCursor,
      })
      expect(older.items.map((item) => item.sequence)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
      expect(older.nextCursor).toBeNull()
      expect(await messages.count(created.id)).toBe(60)
    })
  })

  describe('AttachmentRepository', () => {
    it('creates, reads, lists, and deletes attachment metadata', async () => {
      const repository = new AttachmentRepository(db)
      const messageId = newId()
      const first = attachment({ messageId })
      const second = attachment({ messageId: null })

      await repository.create(first)
      await repository.create(second)

      expect(await repository.get(first.id)).toEqual(first)
      expect((await repository.list({ messageId })).map((item) => item.id)).toEqual([first.id])
      expect((await repository.list({ messageId: null })).map((item) => item.id)).toEqual([
        second.id,
      ])
      expect(await repository.list()).toHaveLength(2)

      await repository.delete(first.id)
      expect(await repository.get(first.id)).toBeNull()
    })
  })

  describe('ProviderRepository', () => {
    it('creates, reads, updates, lists, and deletes providers', async () => {
      const repository = new ProviderRepository(db)
      const created = await repository.create(provider({ label: 'OpenRouter' }))

      expect(await repository.get(created.id)).toEqual(created)

      const updated = await repository.update(created.id, { label: 'Renamed', enabled: false })
      expect(updated.label).toBe('Renamed')
      expect(updated.enabled).toBe(false)
      expect((await repository.list({ enabled: false })).map((item) => item.id)).toEqual([
        created.id,
      ])

      await repository.delete(created.id)
      expect(await repository.get(created.id)).toBeNull()
    })

    it('cascades provider deletion to models and secrets', async () => {
      const providers = new ProviderRepository(db)
      const models = new ModelRepository(db)
      const secrets = new SecretRepository(db)
      const secretId = newId()
      const created = await providers.create(provider({ secretId }))
      const createdModel = await models.create(model(created.id))
      const now = nowIso()
      await secrets.put({
        id: secretId,
        iv: 'iv',
        ciphertext: 'ciphertext',
        createdAt: now,
        updatedAt: now,
      })

      await providers.delete(created.id)

      expect(await providers.get(created.id)).toBeNull()
      expect(await models.get(createdModel.id)).toBeNull()
      expect(await secrets.get(secretId)).toBeNull()
    })
  })

  describe('ModelRepository', () => {
    it('creates, reads, updates, lists, and deletes models', async () => {
      const repository = new ModelRepository(db)
      const providerId = newId()
      const created = await repository.create(model(providerId, { remoteId: 'openrouter/auto' }))

      expect(await repository.get(created.id)).toEqual(created)

      const updated = await repository.update(created.id, { label: 'Auto 2' })
      expect(updated.label).toBe('Auto 2')
      expect((await repository.list({ providerId })).map((item) => item.id)).toEqual([created.id])

      await repository.delete(created.id)
      expect(await repository.get(created.id)).toBeNull()
    })

    it('enforces unique (providerId, remoteId) on create and update', async () => {
      const repository = new ModelRepository(db)
      const providerId = newId()
      const first = await repository.create(model(providerId, { remoteId: 'shared' }))

      await expect(
        repository.create(model(providerId, { remoteId: 'shared' })),
      ).rejects.toBeInstanceOf(ModelConflictError)

      const other = await repository.create(model(providerId, { remoteId: 'other' }))
      await expect(repository.update(other.id, { remoteId: 'shared' })).rejects.toBeInstanceOf(
        ModelConflictError,
      )

      expect((await repository.get(first.id))?.remoteId).toBe('shared')
    })
  })

  describe('SettingsRepository', () => {
    it('gets, sets, and lists settings', async () => {
      const repository = new SettingsRepository(db)

      expect(await repository.get('theme')).toBeNull()

      await repository.set('theme', 'dark')
      expect(await repository.get<string>('theme')).toBe('dark')

      const list = await repository.list()
      expect(list).toHaveLength(1)
      expect(list[0]).toMatchObject({ key: 'theme', value: 'dark' })
    })
  })

  describe('SecretRepository', () => {
    it('gets, puts, and deletes ciphertext records', async () => {
      const repository = new SecretRepository(db)
      const now = nowIso()
      const record = {
        id: newId(),
        iv: 'iv',
        ciphertext: 'ciphertext',
        createdAt: now,
        updatedAt: now,
      }

      await repository.put(record)
      expect(await repository.get(record.id)).toEqual(record)

      await repository.delete(record.id)
      expect(await repository.get(record.id)).toBeNull()
    })
  })

  describe('BackupConfigRepository', () => {
    it('gets and puts backup configuration', async () => {
      const repository = new BackupConfigRepository(db)

      expect(await repository.get()).toBeNull()

      const config = backupConfig()
      const record = await repository.put(config)
      expect(record).toMatchObject({ ...config, id: DEFAULT_BACKUP_CONFIG_ID })
      expect(await repository.get()).toEqual(record)
    })
  })

  describe('SyncQueueRepository', () => {
    it('enqueues, lists, updates attempts, and deletes sync queue items', async () => {
      const repository = new SyncQueueRepository(db)
      const conversationId = newId()
      const item = await repository.enqueue({
        conversationId,
        operation: 'upsert',
        payload: { title: 'queued' },
      })

      expect(item.attempts).toBe(0)
      expect((await repository.list({ conversationId })).map((entry) => entry.id)).toEqual([
        item.id,
      ])

      const updated = await repository.updateAttempts(item.id, 1, { lastError: 'network' })
      expect(updated.attempts).toBe(1)
      expect(updated.lastError).toBe('network')

      await repository.delete(item.id)
      expect(await repository.list()).toHaveLength(0)
    })
  })
})
