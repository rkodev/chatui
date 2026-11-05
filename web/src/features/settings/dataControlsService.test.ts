import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import type { Attachment, Conversation, Message, Model, Provider } from '../../domain'
import { createCrypto } from '../../platform/crypto'
import { createFakeOpfsRoot } from '../../platform/testing/fakeOpfs'
import { createOpfsAttachmentFileSystem } from '../../platform/files'
import {
  deleteDatabase,
  openDatabase,
  toConversationRecord,
  toProviderRecord,
  type ChatUiDB,
} from '../../storage'
import { AttachmentStore, storageKeyFor } from '../../storage/AttachmentStore'
import { SecretStore, rememberRecordId } from '../../storage/SecretStore'
import { DataControlsService } from './dataControlsService'
import { createDataControlsService } from './dataControlsServiceFactory'

const now = '2026-11-05T12:00:00.000Z'
const fastCrypto = createCrypto({ pbkdf2Iterations: 1000 })

const provider: Provider = {
  id: 'provider-1',
  presetId: 'openai',
  label: 'OpenAI',
  kind: 'openai-compatible',
  baseUrl: 'https://api.openai.com/v1',
  authType: 'bearer',
  secretId: 'secret-1',
  headers: {},
  enabled: true,
  browserCors: 'unknown',
  metadata: {
    key: {
      label: 'Work key',
      usageUsd: 1,
      limitUsd: null,
      limitRemainingUsd: null,
      isFreeTier: false,
      rateLimit: null,
      validatedAt: now,
    },
  },
  createdAt: now,
  updatedAt: now,
}

const model: Model = {
  id: 'model-1',
  providerId: provider.id,
  remoteId: 'gpt-4o-mini',
  label: 'GPT-4o mini',
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
  source: 'discovered',
  createdAt: now,
  updatedAt: now,
}

const conversation: Conversation = {
  id: 'conv-1',
  title: 'Data controls',
  systemPrompt: '',
  providerId: provider.id,
  modelId: model.id,
  modelLabel: model.label,
  params: {
    temperature: null,
    topP: null,
    maxOutputTokens: null,
    stop: [],
    reasoningEffort: null,
    streaming: true,
    extra: {},
  },
  pinned: false,
  archived: false,
  temporary: false,
  createdAt: now,
  updatedAt: now,
  lastMessageAt: now,
  messageCount: 1,
  lastError: null,
  sync: null,
}

const message: Message = {
  id: 'msg-1',
  conversationId: conversation.id,
  sequence: 0,
  role: 'user',
  parts: [{ type: 'text', text: 'Hello' }],
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
}

const attachment: Attachment = {
  id: 'att-1',
  messageId: message.id,
  kind: 'file',
  mimeType: 'text/plain',
  name: 'notes.txt',
  size: 5,
  sha256: '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824',
  storageKey: storageKeyFor('2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824'),
  width: null,
  height: null,
  createdAt: now,
}

async function seed(db: IDBPDatabase<ChatUiDB>) {
  await db.put('conversations', toConversationRecord(conversation))
  await db.put('messages', message)
  await db.put('attachments', attachment)
  await db.put('providers', toProviderRecord(provider))
  await db.put('models', model)
  await db.put('syncQueue', {
    id: 'sync-1',
    conversationId: conversation.id,
    operation: 'upsert',
    payload: {},
    attempts: 0,
    lastAttemptAt: null,
    nextAttemptAt: null,
    lastError: null,
    createdAt: now,
    updatedAt: now,
  })
  await new SecretStore({ db, crypto: fastCrypto }).setSecret('secret-1', 'sk-test', 'remember')
}

describe('DataControlsService', () => {
  let db: IDBPDatabase<ChatUiDB>
  let root: ReturnType<typeof createFakeOpfsRoot>
  let attachmentStore: AttachmentStore

  beforeEach(async () => {
    await deleteDatabase()
    db = await openDatabase()
    root = createFakeOpfsRoot()
    attachmentStore = new AttachmentStore({
      db,
      capabilities: { opfs: true },
      fileSystem: createOpfsAttachmentFileSystem(root),
    })
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase()
  })

  it('reports counts and uses attachment metadata instead of reading bytes', async () => {
    await seed(db)
    await attachmentStore.put(new TextEncoder().encode('hello'), attachment.sha256)
    await db.put('attachmentBlobs', {
      sha256: 'fallback-only',
      blob: new Blob([new Uint8Array(4096)]),
      size: 4096,
      createdAt: now,
    })

    const service = new DataControlsService({
      db,
      attachmentStore,
      keyService: {
        listProviders: async () => [provider],
        removeKey: async () => provider,
      },
    })
    const usage = await service.getUsage()

    expect(usage.conversations.count).toBe(1)
    expect(usage.messages.count).toBe(1)
    expect(usage.attachments).toEqual({ count: 1, bytes: 5 })
    expect(usage.secrets.count).toBe(1)
    expect(usage.providers.count).toBe(1)
    expect(usage.models.count).toBe(1)
    expect(usage.totalBytes).toBeGreaterThan(5)
  })

  it('deduplicates attachment sizes by SHA-256', async () => {
    await seed(db)
    await db.put('attachments', { ...attachment, id: 'att-2', messageId: null })

    const service = new DataControlsService({
      db,
      attachmentStore,
      keyService: {
        listProviders: async () => [],
        removeKey: async () => provider,
      },
    })

    expect((await service.getUsage()).attachments).toEqual({ count: 2, bytes: 5 })
  })

  it('clears conversations, messages, attachments, providers, models, keys, and bytes', async () => {
    await seed(db)
    await attachmentStore.put(new TextEncoder().encode('hello'), attachment.sha256)

    const service = new DataControlsService({
      db,
      attachmentStore,
      keyService: {
        listProviders: async () => [provider],
        removeKey: async () => provider,
      },
    })
    const result = await service.clearAll()

    expect(result).toEqual({
      conversations: 1,
      messages: 1,
      attachments: 1,
      secrets: 1,
      providers: 1,
      models: 1,
    })
    expect(await db.count('conversations')).toBe(0)
    expect(await db.count('messages')).toBe(0)
    expect(await db.count('attachments')).toBe(0)
    expect(await db.count('providers')).toBe(0)
    expect(await db.count('models')).toBe(0)
    expect(await db.count('secrets')).toBe(0)
    expect(await db.count('syncQueue')).toBe(0)
    expect(await db.count('attachmentBlobs')).toBe(0)
    expect([...root.files.keys()]).toEqual([])
  })

  it('lists provider keys without exposing secret values', async () => {
    await seed(db)

    const service = new DataControlsService({
      db,
      attachmentStore,
      keyService: {
        listProviders: async () => [provider],
        removeKey: async () => provider,
      },
    })

    expect(await service.listProviderKeys()).toEqual([
      {
        id: provider.id,
        label: provider.label,
        presetId: provider.presetId,
        hasKey: true,
        keyLabel: 'Work key',
      },
    ])
  })

  it('removes a key through provider management and clears cached metadata', async () => {
    await seed(db)
    const service = await createDataControlsService({ db, attachmentStore })

    await service.removeProviderKey(provider.id)

    const updated = await db.get('providers', provider.id)
    expect(updated?.secretId).toBeNull()
    expect(updated?.metadata).toBeNull()
    expect(await db.get('secrets', rememberRecordId('secret-1'))).toBeUndefined()
    expect(await service.listProviderKeys()).toEqual([
      {
        id: provider.id,
        label: provider.label,
        presetId: provider.presetId,
        hasKey: false,
        keyLabel: null,
      },
    ])
  })
})
