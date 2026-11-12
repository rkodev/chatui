import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import {
  newId,
  type Attachment,
  type ChatParams,
  type Conversation,
  type Message,
  type Model,
  type Provider,
} from '../domain'
import {
  AttachmentRepository,
  ConversationRepository,
  ExportReader,
  MessageRepository,
  ModelRepository,
  ProviderRepository,
  SecretRepository,
  SettingsRepository,
  deleteDatabase,
  openDatabase,
  type ChatUiDB,
} from './index'
import { DEFAULT_SETTINGS, SettingsService } from '../features/settings/settingsService'

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
  const now = '2026-01-01T00:00:00.000Z'
  return {
    id: newId(),
    title: 'Export conversation',
    systemPrompt: '',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'Model one',
    params,
    pinned: false,
    archived: false,
    temporary: false,
    createdAt: now,
    updatedAt: now,
    lastMessageAt: now,
    messageCount: 0,
    lastError: null,
    storageKey: '0123456789abcdef0123456789abcdef',
    revision: 1,
    sync: null,
    ...overrides,
  }
}

function message(
  conversationId: string,
  sequence: number,
  overrides: Partial<Message> = {},
): Message {
  const now = '2026-01-01T00:00:00.000Z'
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
    createdAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  }
}

function provider(overrides: Partial<Provider> = {}): Provider {
  const now = '2026-01-01T00:00:00.000Z'
  return {
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
    metadata: null,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function model(overrides: Partial<Model> = {}): Model {
  const now = '2026-01-01T00:00:00.000Z'
  return {
    id: 'model-1',
    providerId: 'provider-1',
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
    ...overrides,
  }
}

describe('ExportReader', () => {
  let db: IDBPDatabase<ChatUiDB>
  let databaseName: string
  let reader: ExportReader

  beforeEach(async () => {
    databaseName = `chatui-export-reader-${newId()}`
    db = await openDatabase(databaseName)
    reader = new ExportReader({
      db,
      settings: new SettingsService(new SettingsRepository(db)),
    })
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase(databaseName)
  })

  async function seed() {
    await new ProviderRepository(db).create(provider())
    await new ModelRepository(db).create(model())
    await new SettingsRepository(db).set('onboardingCompleted', true)

    const conversations = new ConversationRepository(db)
    const messages = new MessageRepository(db)
    const attachments = new AttachmentRepository(db)
    const first = conversation({
      id: 'conv-1',
      title: 'First',
      createdAt: '2026-01-01T00:00:00.000Z',
      updatedAt: '2026-01-02T00:00:00.000Z',
    })
    const second = conversation({
      id: 'conv-2',
      title: 'Second',
      createdAt: '2026-01-03T00:00:00.000Z',
      updatedAt: '2026-01-04T00:00:00.000Z',
    })
    await conversations.create(first)
    await conversations.create(second)

    const image = attachment({
      id: 'att-image',
      messageId: 'msg-2',
      kind: 'image',
      mimeType: 'image/png',
      name: 'pixel.png',
      size: 68,
    })
    const file = attachment({
      id: 'att-file',
      messageId: 'msg-1',
      name: 'notes.txt',
      size: 17,
    })
    const orphan = attachment({ id: 'att-orphan', name: 'stale.txt' })

    await messages.appendMessage(
      message('conv-1', 1, {
        id: 'msg-2',
        role: 'assistant',
        providerId: 'provider-1',
        modelId: 'model-1',
        modelLabel: 'GPT-4o mini',
        parts: [
          { type: 'text', text: 'Second in sequence.' },
          {
            type: 'image',
            attachmentId: image.id,
            mimeType: 'image/png',
            alt: 'A pixel',
          },
        ],
      }),
    )
    await messages.appendMessage(
      message('conv-1', 0, {
        id: 'msg-1',
        parts: [{ type: 'file', attachmentId: file.id, mimeType: 'text/plain', name: 'notes.txt' }],
      }),
    )
    await messages.appendMessage(
      message('conv-2', 0, {
        id: 'msg-3',
        parts: [{ type: 'text', text: 'Other chat' }],
      }),
    )
    await attachments.create(image)
    await attachments.create(file)
    await attachments.create(orphan)

    return { image, file, orphan }
  }

  it('reads every store except secrets with deterministic ordering', async () => {
    await seed()
    await new SecretRepository(db).put({
      id: 'secret-1',
      iv: 'iv-value',
      ciphertext: 'plaintext-looking-ciphertext',
      createdAt: '2026-01-01T00:00:00.000Z',
      updatedAt: '2026-01-01T00:00:00.000Z',
    })

    const snapshot = await reader.readAll()

    expect(snapshot.conversations.map((item) => item.id)).toEqual(['conv-1', 'conv-2'])
    expect(snapshot.messages.map((item) => item.id)).toEqual(['msg-1', 'msg-2', 'msg-3'])
    expect(snapshot.attachments.map((item) => item.id)).toEqual([
      'att-file',
      'att-image',
      'att-orphan',
    ])
    expect(snapshot.settings).toEqual({ ...DEFAULT_SETTINGS, onboardingCompleted: true })
    expect(JSON.stringify(snapshot)).not.toContain('plaintext-looking-ciphertext')
  })

  it('scopes a conversation to its messages and referenced attachments', async () => {
    await seed()

    const snapshot = await reader.readConversation('conv-1')

    expect(snapshot?.conversations.map((item) => item.id)).toEqual(['conv-1'])
    expect(snapshot?.messages.map((item) => item.sequence)).toEqual([0, 1])
    expect(snapshot?.messages.map((item) => item.id)).toEqual(['msg-1', 'msg-2'])
    expect(snapshot?.attachments.map((item) => item.id)).toEqual(['att-file', 'att-image'])
  })

  it('returns null for an unknown conversation', async () => {
    expect(await reader.readConversation('missing')).toBeNull()
  })

  it('lists conversations newest-first for export entry points', async () => {
    await seed()

    expect((await reader.listConversations()).map((item) => item.id)).toEqual(['conv-2', 'conv-1'])
  })
})
