import { afterEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { newId, nowIso, type ChatParams, type Message } from '../domain'
import { deleteDatabase, openDatabase, type ChatUiDB } from './db'
import { ConversationRepository, type NewConversation } from './ConversationRepository'
import { MessageRepository } from './MessageRepository'
import { TombstoneRepository } from './TombstoneRepository'

const databaseNames = new Set<string>()

function uniqueDatabaseName(): string {
  const name = `chatui-sync-${newId()}`
  databaseNames.add(name)
  return name
}

const params: ChatParams = {
  temperature: null,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

function newConversation(overrides: Partial<NewConversation> = {}): NewConversation {
  const now = nowIso()
  return {
    title: 'Sync conversation',
    systemPrompt: '',
    providerId: newId(),
    modelId: newId(),
    modelLabel: 'Model',
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

function message(conversationId: string, sequence: number): Message {
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
  }
}

async function openTestDatabase(): Promise<IDBPDatabase<ChatUiDB>> {
  return openDatabase(uniqueDatabaseName())
}

afterEach(async () => {
  for (const name of databaseNames) {
    await deleteDatabase(name)
  }
  databaseNames.clear()
})

describe('conversation sync fields', () => {
  it('assigns a unique 32-hex storageKey and revision 1 on create', async () => {
    const db = await openTestDatabase()
    const repository = new ConversationRepository(db)

    const first = await repository.create(newConversation())
    const second = await repository.create(newConversation())

    expect(first.storageKey).toMatch(/^[0-9a-f]{32}$/u)
    expect(second.storageKey).toMatch(/^[0-9a-f]{32}$/u)
    expect(first.storageKey).not.toBe(second.storageKey)
    expect(first.revision).toBe(1)
    expect(second.revision).toBe(1)

    db.close()
  })

  it('increments revision on conversation and message mutations', async () => {
    const db = await openTestDatabase()
    const conversations = new ConversationRepository(db)
    const messages = new MessageRepository(db)
    const created = await conversations.create(newConversation())

    const updated = await conversations.update(created.id, { title: 'Renamed' })
    expect(updated.revision).toBe(2)

    const appended = await messages.appendMessage(message(created.id, 0))
    expect(appended.conversation.revision).toBe(3)

    const truncated = await messages.truncateAfter(created.id, 0)
    expect(truncated.conversation.revision).toBe(4)

    db.close()
  })
})

describe('TombstoneRepository', () => {
  it('adds, lists, filters, and deletes tombstones', async () => {
    const db = await openTestDatabase()
    const repository = new TombstoneRepository(db)
    const conversationId = newId()
    const otherConversationId = newId()

    const first = await repository.add({
      conversationId,
      deletedAt: '2026-01-01T00:00:00.000Z',
      deviceId: 'device-a',
    })
    const second = await repository.add({
      conversationId,
      messageId: newId(),
      deletedAt: '2026-01-02T00:00:00.000Z',
      deviceId: 'device-a',
    })
    await repository.add({
      conversationId: otherConversationId,
      deletedAt: '2026-01-03T00:00:00.000Z',
      deviceId: 'device-b',
    })

    expect(first.id).toBeTruthy()
    expect(await repository.listAll()).toHaveLength(3)
    expect(await repository.listByConversation(conversationId)).toEqual([first, second])

    await repository.delete(first.id)
    expect(await repository.listByConversation(conversationId)).toEqual([second])
    expect(await repository.listAll()).toHaveLength(2)

    db.close()
  })
})
