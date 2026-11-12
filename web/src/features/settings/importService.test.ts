import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import type { Attachment, Conversation, Message, Model, Provider } from '../../domain'
import { createOpfsAttachmentFileSystem } from '../../platform/files'
import { createFakeOpfsRoot } from '../../platform/testing/fakeOpfs'
import {
  deleteDatabase,
  ExportReader,
  openDatabase,
  SettingsRepository,
  toConversationRecord,
  toProviderRecord,
  type ChatUiDB,
  type ExportSnapshot,
} from '../../storage'
import { AttachmentStore, storageKeyFor } from '../../storage/AttachmentStore'
import { buildChatExport, type ChatExport } from './exportService'
import {
  ImportService,
  ImportValidationError,
  MAX_IMPORT_BYTES,
  validateImportArchive,
} from './importService'
import { DEFAULT_SETTINGS, SettingsService } from './settingsService'

const testDir = dirname(fileURLToPath(import.meta.url))
const now = '2026-11-04T12:00:00.000Z'
const attachmentBytes = new TextEncoder().encode('hello')
const attachmentSha = '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824'

const provider: Provider = {
  id: 'provider-openai',
  presetId: 'openai',
  label: 'OpenAI',
  kind: 'openai-compatible',
  baseUrl: 'https://api.openai.com/v1',
  authType: 'bearer',
  secretId: 'secret-1',
  headers: {},
  enabled: true,
  browserCors: 'unknown',
  metadata: null,
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
  capabilities: ['text', 'streaming'],
  pricing: null,
  canonicalSlug: null,
  inputModalities: ['text'],
  outputModalities: ['text'],
  supportedParameters: ['temperature'],
  metadata: null,
  hidden: false,
  pinned: false,
  source: 'discovered',
  createdAt: now,
  updatedAt: now,
}

const conversation: Conversation = {
  id: 'conv-1',
  title: 'Import me',
  systemPrompt: 'Be concise.',
  providerId: provider.id,
  modelId: model.id,
  modelLabel: model.label,
  params: {
    temperature: null,
    topP: null,
    maxOutputTokens: 256,
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
  messageCount: 2,
  lastError: null,
  storageKey: '0123456789abcdef0123456789abcdef',
  revision: 1,
  sync: null,
}

function message(sequence: number, overrides: Partial<Message> = {}): Message {
  return {
    id: `msg-${sequence}`,
    conversationId: conversation.id,
    sequence,
    role: sequence === 0 ? 'user' : 'assistant',
    parts:
      sequence === 0
        ? [
            { type: 'text', text: 'Read this.' },
            {
              type: 'file',
              attachmentId: 'att-1',
              mimeType: 'text/plain',
              name: 'notes.txt',
            },
          ]
        : [{ type: 'text', text: 'It says hello.' }],
    status: 'complete',
    providerId: sequence === 0 ? null : provider.id,
    modelId: sequence === 0 ? null : model.id,
    modelLabel: sequence === 0 ? null : model.label,
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

const attachment: Attachment = {
  id: 'att-1',
  messageId: 'msg-0',
  kind: 'file',
  mimeType: 'text/plain',
  name: 'notes.txt',
  size: attachmentBytes.byteLength,
  sha256: attachmentSha,
  storageKey: storageKeyFor(attachmentSha),
  width: null,
  height: null,
  createdAt: now,
}

function snapshot(): ExportSnapshot {
  return {
    conversations: [structuredClone(conversation)],
    messages: [message(0), message(1)],
    attachments: [structuredClone(attachment)],
    providers: [structuredClone(provider)],
    models: [structuredClone(model)],
    settings: structuredClone(DEFAULT_SETTINGS),
  }
}

async function archive(includeBytes = true): Promise<ChatExport> {
  return buildChatExport(
    snapshot(),
    { includeAttachmentBytes: includeBytes, exportedAt: now, appVersion: '0.1.0' },
    { get: async () => attachmentBytes },
  )
}

function createService(
  db: IDBPDatabase<ChatUiDB>,
  overrides: { maxBytes?: number; maxAttachmentBytes?: number } = {},
) {
  const root = createFakeOpfsRoot()
  const store = new AttachmentStore({
    db,
    capabilities: { opfs: true },
    fileSystem: createOpfsAttachmentFileSystem(root),
  })

  return {
    service: new ImportService({
      db,
      attachmentStore: store,
      now: () => now,
      ...overrides,
    }),
    store,
  }
}

async function seedLocalConversation(db: IDBPDatabase<ChatUiDB>) {
  const localProvider: Provider = {
    ...provider,
    id: 'local-provider',
    presetId: 'local',
    label: 'Local provider',
  }
  const localModel: Model = {
    ...model,
    id: 'local-model',
    providerId: localProvider.id,
    remoteId: 'local-remote',
  }
  const localConversation: Conversation = {
    ...conversation,
    id: 'local-conv',
    title: 'Local conversation',
    providerId: localProvider.id,
    modelId: localModel.id,
    modelLabel: localModel.label,
  }

  await db.put('providers', toProviderRecord(localProvider))
  await db.put('models', localModel)
  await db.put('conversations', toConversationRecord(localConversation))

  return { localProvider, localModel, localConversation }
}

async function counts(db: IDBPDatabase<ChatUiDB>) {
  return {
    conversations: await db.count('conversations'),
    messages: await db.count('messages'),
    attachments: await db.count('attachments'),
    providers: await db.count('providers'),
    models: await db.count('models'),
    settings: await db.count('settings'),
  }
}

function expectValidationCode(action: () => unknown, code: string): void {
  try {
    action()
    throw new Error('Expected validation to throw')
  } catch (error) {
    expect(error).toBeInstanceOf(ImportValidationError)
    expect((error as ImportValidationError).code).toBe(code)
  }
}

describe('ImportService', () => {
  let db: IDBPDatabase<ChatUiDB>

  beforeEach(async () => {
    await deleteDatabase()
    db = await openDatabase()
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase()
  })

  it('imports a valid archive and round-trips IDs, order, snapshots, and attachments', async () => {
    const { service, store } = createService(db)
    const result = await service.importText(JSON.stringify(await archive()))

    expect(result).toMatchObject({
      mode: 'merge',
      conversations: 1,
      messages: 2,
      attachments: 1,
      providers: 1,
      models: 1,
      attachmentBytes: 1,
    })

    const reader = new ExportReader({
      db,
      settings: new SettingsService(new SettingsRepository(db)),
    })
    const read = await reader.readAll()

    expect(read.conversations.map((item) => item.id)).toEqual(['conv-1'])
    expect(read.messages.map((item) => item.id)).toEqual(['msg-0', 'msg-1'])
    expect(read.messages.map((item) => item.sequence)).toEqual([0, 1])
    expect(read.conversations[0].modelLabel).toBe('GPT-4o mini')
    expect(read.messages[1].modelId).toBe('model-1')
    expect(read.messages[0].parts[1]).toMatchObject({
      type: 'file',
      attachmentId: 'att-1',
      name: 'notes.txt',
    })
    expect(read.attachments.map((item) => item.id)).toEqual(['att-1'])
    expect(read.attachments[0]).toMatchObject({
      messageId: 'msg-0',
      kind: 'file',
      sha256: attachmentSha,
      storageKey: storageKeyFor(attachmentSha),
    })
    expect(Array.from(await store.get(storageKeyFor(attachmentSha)))).toEqual(
      Array.from(attachmentBytes),
    )
  })

  it('accepts the shared contract fixture at the schema boundary', () => {
    const fixture = JSON.parse(
      readFileSync(
        resolve(testDir, '../../../../contracts/fixtures/expected/chatui-export.json'),
        'utf8',
      ),
    ) as unknown

    expect(() => validateImportArchive(fixture)).not.toThrow()
  })

  it('rejects invalid JSON and schema without mutating local data', async () => {
    await seedLocalConversation(db)
    const before = await counts(db)
    const { service } = createService(db)

    await expect(service.importText('{')).rejects.toMatchObject({ code: 'invalid_json' })
    await expect(
      service.importText(JSON.stringify({ format: 'not-chatui', formatVersion: 1 })),
    ).rejects.toMatchObject({ code: 'schema' })

    expect(await counts(db)).toEqual(before)
  })

  it('rejects archives over the size limit before parsing', async () => {
    await seedLocalConversation(db)
    const before = await counts(db)
    const { service } = createService(db, { maxBytes: 10 })

    await expect(service.importText(JSON.stringify(await archive()))).rejects.toMatchObject({
      code: 'too_large',
    })

    expect(MAX_IMPORT_BYTES).toBe(50 * 1024 * 1024)
    expect(await counts(db)).toEqual(before)
  })

  it('rejects duplicate IDs and duplicate model identities', async () => {
    const base = await archive()

    const duplicateConversation = structuredClone(base)
    duplicateConversation.conversations.push(structuredClone(duplicateConversation.conversations[0]))
    expectValidationCode(() => validateImportArchive(duplicateConversation), 'duplicate_id')

    const duplicateMessage = structuredClone(base)
    duplicateMessage.messages.push(structuredClone(duplicateMessage.messages[0]))
    expectValidationCode(() => validateImportArchive(duplicateMessage), 'duplicate_id')

    const duplicateAttachment = structuredClone(base)
    duplicateAttachment.attachments.push(structuredClone(duplicateAttachment.attachments[0]))
    expectValidationCode(() => validateImportArchive(duplicateAttachment), 'duplicate_id')

    const duplicateProvider = structuredClone(base)
    duplicateProvider.providers.push(structuredClone(duplicateProvider.providers[0]))
    expectValidationCode(() => validateImportArchive(duplicateProvider), 'duplicate_id')

    const duplicateModel = structuredClone(base)
    duplicateModel.models.push(structuredClone(duplicateModel.models[0]))
    expectValidationCode(() => validateImportArchive(duplicateModel), 'duplicate_id')

    const duplicateSequence = structuredClone(base)
    duplicateSequence.messages.push({
      ...structuredClone(duplicateSequence.messages[0]),
      id: 'msg-other',
    })
    expectValidationCode(() => validateImportArchive(duplicateSequence), 'duplicate_id')

    const duplicateRemote = structuredClone(base)
    duplicateRemote.models.push({
      ...structuredClone(duplicateRemote.models[0]),
      id: 'model-other',
    })
    expectValidationCode(() => validateImportArchive(duplicateRemote), 'duplicate_id')

    const unsafeId = structuredClone(base)
    unsafeId.conversations[0].id = 'bad\u0000id'
    expectValidationCode(() => validateImportArchive(unsafeId), 'invalid_id')
  })

  it('rejects unsafe attachment names', async () => {
    const base = await archive()
    const unsafeNames = [
      '../evil.txt',
      'dir/file.txt',
      'C:\\evil.txt',
      'bad\u0000name.txt',
      ' leading.txt',
      'trailing.txt ',
      '.',
      '..',
      'a'.repeat(256),
    ]

    for (const name of unsafeNames) {
      const candidate = structuredClone(base)
      candidate.attachments[0].name = name
      expectValidationCode(() => validateImportArchive(candidate), 'unsafe_attachment_name')
    }
  })

  it('rejects dangling conversation, message, provider, model, and attachment references', async () => {
    const base = await archive()

    const missingProvider = structuredClone(base)
    missingProvider.providers = []
    expectValidationCode(() => validateImportArchive(missingProvider), 'invalid_reference')

    const missingModel = structuredClone(base)
    missingModel.models = []
    expectValidationCode(() => validateImportArchive(missingModel), 'invalid_reference')

    const missingConversation = structuredClone(base)
    missingConversation.messages[0].conversationId = 'missing-conversation'
    expectValidationCode(() => validateImportArchive(missingConversation), 'invalid_reference')

    const missingAttachment = structuredClone(base)
    missingAttachment.messages[0].parts[1] = {
      type: 'file',
      attachmentId: 'missing-attachment',
      mimeType: 'text/plain',
      name: 'notes.txt',
    }
    expectValidationCode(() => validateImportArchive(missingAttachment), 'invalid_reference')

    const missingDefaultProvider = structuredClone(base)
    missingDefaultProvider.settings.defaultProviderId = 'missing-provider'
    expectValidationCode(() => validateImportArchive(missingDefaultProvider), 'invalid_reference')

    const missingLastActive = structuredClone(base)
    missingLastActive.settings.lastActiveConversationId = 'missing-conversation'
    expectValidationCode(() => validateImportArchive(missingLastActive), 'invalid_reference')
  })

  it('rejects attachment bytes that do not match size or SHA-256', async () => {
    await seedLocalConversation(db)
    const before = await counts(db)
    const { service } = createService(db)

    const wrongBytes = structuredClone(await archive())
    wrongBytes.attachments[0].dataBase64 = 'd29ybGQ='
    await expect(service.importText(JSON.stringify(wrongBytes))).rejects.toMatchObject({
      code: 'invalid_attachment',
    })

    const wrongSize = structuredClone(await archive())
    wrongSize.attachments[0].size = 4
    await expect(service.importText(JSON.stringify(wrongSize))).rejects.toMatchObject({
      code: 'invalid_attachment',
    })

    const invalidBase64 = structuredClone(await archive())
    invalidBase64.attachments[0].dataBase64 = 'not-base64!'
    await expect(service.importText(JSON.stringify(invalidBase64))).rejects.toMatchObject({
      code: 'invalid_attachment',
    })

    expect(await counts(db)).toEqual(before)
  })

  it('merges by default without duplicating records', async () => {
    await seedLocalConversation(db)
    const { service } = createService(db)
    const text = JSON.stringify(await archive())

    await service.importText(text)
    const afterFirst = await counts(db)

    expect(afterFirst).toMatchObject({
      conversations: 2,
      messages: 2,
      attachments: 1,
      providers: 2,
      models: 2,
    })
    expect(await db.get('conversations', 'local-conv')).toBeDefined()
    expect(await db.get('conversations', 'conv-1')).toBeDefined()

    await service.importText(text)
    expect(await counts(db)).toEqual(afterFirst)
  })

  it('remaps imported model ids to an existing model with the same provider and remote id', async () => {
    await db.put('providers', toProviderRecord(provider))
    const localModel: Model = { ...model, id: 'local-model' }
    await db.put('models', localModel)

    const candidate = structuredClone(await archive())
    candidate.settings.defaultProviderId = provider.id
    candidate.settings.defaultModelId = model.id

    const { service } = createService(db)
    await service.importText(JSON.stringify(candidate))

    expect(await db.count('models')).toBe(1)
    expect((await db.get('models', 'local-model'))?.remoteId).toBe('gpt-4o-mini')
    expect((await db.get('conversations', 'conv-1'))?.modelId).toBe('local-model')
    expect((await db.get('messages', 'msg-1'))?.modelId).toBe('local-model')
    expect(await new SettingsRepository(db).get('defaultModelId')).toBe('local-model')
  })

  it('keeps local settings in merge mode and applies missing keys', async () => {
    const settings = new SettingsRepository(db)
    await settings.set('theme', 'dark')

    const candidate = structuredClone(await archive())
    candidate.settings.density = 'compact'

    const { service } = createService(db)
    await service.importText(JSON.stringify(candidate))

    expect(await settings.get('theme')).toBe('dark')
    expect(await settings.get('density')).toBe('compact')
  })

  it('replaces local data only when replace mode is explicit', async () => {
    const { localConversation } = await seedLocalConversation(db)
    const oldSha = 'a'.repeat(64)
    const oldAttachment: Attachment = {
      ...attachment,
      id: 'old-attachment',
      sha256: oldSha,
      storageKey: storageKeyFor(oldSha),
    }
    await db.put('attachments', oldAttachment)
    const settings = new SettingsRepository(db)
    await settings.set('theme', 'dark')

    const { service, store } = createService(db)
    await store.put(new TextEncoder().encode('old'), oldSha)

    await service.importText(JSON.stringify(await archive()), { mode: 'replace' })

    expect(await db.get('conversations', localConversation.id)).toBeUndefined()
    expect(await db.get('conversations', 'conv-1')).toBeDefined()
    expect(await db.get('attachments', 'old-attachment')).toBeUndefined()
    expect(await db.get('attachments', 'att-1')).toBeDefined()
    expect(await settings.get('theme')).toBe('system')
    await expect(store.get(storageKeyFor(oldSha))).rejects.toThrow()
  })
})
