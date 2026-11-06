import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import {
  newId,
  nowIso,
  type ChatParams,
  type Message,
  type Model,
  type Provider,
} from '../../domain'
import { deleteDatabase, openDatabase, type ChatUiDB } from '../../storage/db'
import { ConversationRepository } from '../../storage/ConversationRepository'
import { MessageRepository } from '../../storage/MessageRepository'
import { ModelRepository } from '../../storage/ModelRepository'
import { ProviderRepository } from '../../storage/ProviderRepository'
import { SettingsRepository } from '../../storage/SettingsRepository'
import { ModelService } from '../providers'
import { DEFAULT_SETTINGS, SettingsService } from '../settings'
import {
  ConversationService,
  ConversationServiceError,
  DEFAULT_CONVERSATION_TITLE,
  MAX_TITLE_CODE_POINTS,
  deriveTitle,
} from './index'

const params: ChatParams = {
  temperature: null,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
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

describe('deriveTitle', () => {
  it('returns the default title for blank input', () => {
    expect(deriveTitle('')).toBe(DEFAULT_CONVERSATION_TITLE)
    expect(deriveTitle('   \n\t  ')).toBe(DEFAULT_CONVERSATION_TITLE)
  })

  it('trims and collapses whitespace including newlines', () => {
    expect(deriveTitle('  Hello\n\n  world  ')).toBe('Hello world')
  })

  it('caps the title at 60 Unicode code points', () => {
    const result = deriveTitle('a'.repeat(MAX_TITLE_CODE_POINTS + 10))

    expect(result).toHaveLength(MAX_TITLE_CODE_POINTS)
    expect(Array.from(result)).toHaveLength(MAX_TITLE_CODE_POINTS)
  })

  it('counts non-Latin text and emoji by code point', () => {
    const chinese = deriveTitle('你好世界'.repeat(20))
    const emoji = deriveTitle('😀'.repeat(MAX_TITLE_CODE_POINTS + 10))

    expect(Array.from(chinese)).toHaveLength(MAX_TITLE_CODE_POINTS)
    expect(Array.from(emoji)).toHaveLength(MAX_TITLE_CODE_POINTS)
  })
})

describe('ConversationService', () => {
  let db: IDBPDatabase<ChatUiDB>
  let databaseName: string
  let conversations: ConversationRepository
  let messages: MessageRepository
  let providers: ProviderRepository
  let models: ModelRepository
  let settings: SettingsService
  let service: ConversationService
  let clock: number

  function createService(): ConversationService {
    const modelService = new ModelService({
      providers,
      models,
      adapter: {
        validateKey: async () => {
          throw new Error('not used')
        },
        listModels: async () => {
          throw new Error('not used')
        },
      },
    })

    return new ConversationService({
      conversations,
      messages,
      providers,
      models: modelService,
      settings,
      nowIso: () => new Date(Date.UTC(2026, 0, 1, 0, 0, clock++)).toISOString(),
    })
  }

  async function openRepositories(): Promise<void> {
    conversations = new ConversationRepository(db)
    messages = new MessageRepository(db)
    providers = new ProviderRepository(db)
    models = new ModelRepository(db)
    settings = new SettingsService(new SettingsRepository(db))
    service = createService()
  }

  async function configureDefaults(): Promise<{ provider: Provider; model: Model }> {
    const createdProvider = await providers.create(provider())
    const createdModel = await models.create(model(createdProvider.id))
    await settings.setDefaults({
      providerId: createdProvider.id,
      modelId: createdModel.id,
      systemPrompt: 'Be concise.',
      params,
    })
    return { provider: createdProvider, model: createdModel }
  }

  beforeEach(async () => {
    databaseName = `chatui-conversations-${newId()}`
    db = await openDatabase(databaseName)
    clock = 0
    await openRepositories()
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase(databaseName)
  })

  it('creates a conversation from typed defaults', async () => {
    const { provider: createdProvider, model: createdModel } = await configureDefaults()

    const result = await service.createConversation()

    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }

    expect(result.conversation).toMatchObject({
      title: DEFAULT_CONVERSATION_TITLE,
      systemPrompt: 'Be concise.',
      providerId: createdProvider.id,
      modelId: createdModel.id,
      modelLabel: createdModel.label,
      params,
      pinned: false,
      archived: false,
      temporary: false,
      messageCount: 0,
      lastError: null,
      sync: null,
    })
    expect(await service.getConversation(result.conversation.id)).toEqual(result.conversation)
  })

  it('falls back to the first enabled provider and its default model', async () => {
    const createdProvider = await providers.create(provider())
    const createdModel = await models.create(model(createdProvider.id))

    const result = await service.createConversation()

    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }

    expect(result.conversation.providerId).toBe(createdProvider.id)
    expect(result.conversation.modelId).toBe(createdModel.id)
  })

  it('returns a typed not-configured result when no provider exists', async () => {
    const created = await service.createConversation()
    const ensured = await service.ensureConversationForSend()

    expect(created).toMatchObject({ ok: false, reason: 'not_configured' })
    expect(ensured).toMatchObject({ ok: false, reason: 'not_configured' })
  })

  it('returns a typed not-configured result when no model is available', async () => {
    const createdProvider = await providers.create(provider())
    await settings.setDefaults({ providerId: createdProvider.id, modelId: null })

    const result = await service.createConversation()

    expect(result).toMatchObject({ ok: false, reason: 'not_configured' })
  })

  it('ensures an existing conversation or creates a safe new chat', async () => {
    await configureDefaults()
    const created = await service.createConversation()

    expect(created.ok).toBe(true)
    if (!created.ok) {
      return
    }

    const existing = await service.ensureConversationForSend(created.conversation.id)
    expect(existing).toMatchObject({
      ok: true,
      created: false,
      conversation: { id: created.conversation.id },
    })

    const missing = await service.ensureConversationForSend('missing-conversation')
    expect(missing.ok).toBe(true)
    if (!missing.ok) {
      return
    }

    expect(missing.created).toBe(true)
    expect(missing.conversation.id).not.toBe(created.conversation.id)
    expect(await settings.getLastActiveConversationId()).toBe(missing.conversation.id)
  })

  it('renames and deletes a conversation, including its messages', async () => {
    await configureDefaults()
    const created = await service.createConversation()

    expect(created.ok).toBe(true)
    if (!created.ok) {
      return
    }

    const renamed = await service.renameConversation(created.conversation.id, '  Renamed  ')
    expect(renamed.title).toBe('Renamed')
    expect((await service.getConversation(created.conversation.id))?.title).toBe('Renamed')

    const appended = await service.appendMessage(
      message(created.conversation.id, 1, { parts: [{ type: 'text', text: 'Hello' }] }),
    )

    await service.setLastActiveConversation(created.conversation.id)
    await service.deleteConversation(created.conversation.id)

    expect(await service.getConversation(created.conversation.id)).toBeNull()
    expect(await messages.get(appended.message.id)).toBeNull()
    expect(await settings.getLastActiveConversationId()).toBeNull()
  })

  it('persists pin and archive flags without changing the updated timestamp', async () => {
    await configureDefaults()
    const created = await service.createConversation()

    expect(created.ok).toBe(true)
    if (!created.ok) {
      return
    }

    const pinned = await service.setConversationPinned(created.conversation.id, true)
    const archived = await service.setConversationArchived(created.conversation.id, true)

    expect(pinned.pinned).toBe(true)
    expect(pinned.updatedAt).toBe(created.conversation.updatedAt)
    expect(archived.archived).toBe(true)
    expect(archived.updatedAt).toBe(created.conversation.updatedAt)

    const reloaded = await service.getConversation(created.conversation.id)
    expect(reloaded).toMatchObject({ pinned: true, archived: true })

    const active = await service.listConversations({ archived: false })
    expect(active.items).toEqual([])
    const archive = await service.listConversations({ archived: true })
    expect(archive.items.map((item) => item.id)).toEqual([created.conversation.id])

    await expect(service.setConversationPinned('missing', true)).rejects.toMatchObject({
      code: 'not_found',
    })
    await expect(service.setConversationArchived('missing', true)).rejects.toMatchObject({
      code: 'not_found',
    })
  })

  it('rejects blank rename titles and missing conversations', async () => {
    await configureDefaults()
    const created = await service.createConversation()

    expect(created.ok).toBe(true)
    if (!created.ok) {
      return
    }

    await expect(service.renameConversation(created.conversation.id, '   ')).rejects.toMatchObject({
      code: 'invalid_input',
    })
    await expect(service.renameConversation('missing', 'Title')).rejects.toBeInstanceOf(
      ConversationServiceError,
    )
  })

  it('derives the title from the first user message and caps it', async () => {
    await configureDefaults()
    const created = await service.createConversation()

    expect(created.ok).toBe(true)
    if (!created.ok) {
      return
    }

    const longText = `  ${'a'.repeat(MAX_TITLE_CODE_POINTS + 10)}\n\nsecond line  `
    const result = await service.appendMessage(
      message(created.conversation.id, 1, {
        parts: [{ type: 'text', text: longText }],
      }),
    )

    expect(result.conversation.title).toHaveLength(MAX_TITLE_CODE_POINTS)
    expect(result.conversation.messageCount).toBe(1)
    expect((await service.getConversation(created.conversation.id))?.title).toHaveLength(
      MAX_TITLE_CODE_POINTS,
    )
  })

  it('stores provider/model snapshots on assistant messages', async () => {
    const { provider: createdProvider, model: createdModel } = await configureDefaults()
    const created = await service.createConversation()

    expect(created.ok).toBe(true)
    if (!created.ok) {
      return
    }

    const assistant = await service.appendMessage(
      message(created.conversation.id, 2, {
        role: 'assistant',
        parts: [{ type: 'text', text: 'Hi there' }],
      }),
    )

    expect(assistant.message).toMatchObject({
      providerId: createdProvider.id,
      modelId: createdModel.id,
      modelLabel: createdModel.label,
    })
    expect(await messages.get(assistant.message.id)).toMatchObject({
      providerId: createdProvider.id,
      modelId: createdModel.id,
      modelLabel: createdModel.label,
    })

    const explicit = await service.appendMessage(
      message(created.conversation.id, 3, {
        role: 'assistant',
        providerId: 'other-provider',
        modelId: 'other-model',
        modelLabel: 'Other',
      }),
    )

    expect(explicit.message).toMatchObject({
      providerId: 'other-provider',
      modelId: 'other-model',
      modelLabel: 'Other',
    })
  })

  it('rejects appending to a missing conversation', async () => {
    await expect(service.appendMessage(message('missing', 1))).rejects.toMatchObject({
      code: 'not_found',
    })
  })

  it('paginates conversations newest-first through the service', async () => {
    await configureDefaults()
    const first = await service.createConversation({ title: 'A' })
    const second = await service.createConversation({ title: 'B' })
    const third = await service.createConversation({ title: 'C' })

    expect(first.ok && second.ok && third.ok).toBe(true)
    if (!first.ok || !second.ok || !third.ok) {
      return
    }

    const page1 = await service.listConversations({ limit: 2 })
    expect(page1.items.map((item) => item.id)).toEqual([
      third.conversation.id,
      second.conversation.id,
    ])
    expect(page1.nextCursor).not.toBeNull()

    const page2 = await service.listConversations({ limit: 2, cursor: page1.nextCursor })
    expect(page2.items.map((item) => item.id)).toEqual([first.conversation.id])
    expect(page2.nextCursor).toBeNull()
  })

  it('persists the last-active conversation across a simulated reload', async () => {
    await configureDefaults()
    const created = await service.createConversation()

    expect(created.ok).toBe(true)
    if (!created.ok) {
      return
    }

    await service.setLastActiveConversation(created.conversation.id)

    db.close()
    db = await openDatabase(databaseName)
    await openRepositories()

    expect((await service.getLastActiveConversation())?.id).toBe(created.conversation.id)
  })

  it('clears a stale last-active pointer', async () => {
    await settings.setLastActiveConversationId('missing')

    expect(await service.getLastActiveConversation()).toBeNull()
    expect(await settings.getLastActiveConversationId()).toBeNull()
  })

  it('does not share mutable default params between conversations', async () => {
    await configureDefaults()
    const first = await service.createConversation()
    const second = await service.createConversation()

    expect(first.ok && second.ok).toBe(true)
    if (!first.ok || !second.ok) {
      return
    }

    first.conversation.params.stop.push('STOP')
    first.conversation.params.extra.changed = true

    expect(second.conversation.params.stop).toEqual([])
    expect(second.conversation.params.extra).toEqual({})
    expect(DEFAULT_SETTINGS.defaultParams.stop).toEqual([])
  })
})
