import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { newId, nowIso, type ChatParams, type Conversation, type Message } from '../../domain'
import { deleteDatabase, openDatabase, type ChatUiDB } from '../../storage/db'
import { ConversationRepository } from '../../storage/ConversationRepository'
import { MessageRepository } from '../../storage/MessageRepository'
import { INTERRUPTION_NOTE, RecoveryService, type RecoveryMessageStore } from './recoveryService'

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
    title: 'Recovery test',
    systemPrompt: '',
    providerId: 'provider-1',
    modelId: 'model-1',
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

function message(conversationId: string, overrides: Partial<Message> = {}): Message {
  const now = nowIso()

  return {
    id: newId(),
    conversationId,
    sequence: 1,
    role: 'assistant',
    parts: [{ type: 'text', text: 'Partial answer' }],
    status: 'streaming',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'Test model',
    usage: null,
    finishReason: null,
    error: null,
    latencyMs: null,
    firstTokenMs: null,
    createdAt: now,
    updatedAt: now,
    deletedAt: null,
    ...overrides,
  }
}

class FakeMessageStore implements RecoveryMessageStore {
  readonly messages: Message[]
  readonly appended: Message[] = []
  failOnAppend = false

  constructor(messages: Message[]) {
    this.messages = messages
  }

  async listByStatus(
    status: Message['status'],
    options: { conversationIds?: readonly string[] } = {},
  ): Promise<Message[]> {
    const allowed = options.conversationIds === undefined ? null : new Set(options.conversationIds)

    return this.messages.filter(
      (item) => item.status === status && (allowed === null || allowed.has(item.conversationId)),
    )
  }

  async appendMessage(next: Message): Promise<unknown> {
    if (this.failOnAppend) {
      throw new Error('write failed')
    }

    this.appended.push(next)
    const index = this.messages.findIndex((item) => item.id === next.id)

    if (index >= 0) {
      this.messages[index] = next
    } else {
      this.messages.push(next)
    }

    return next
  }
}

describe('RecoveryService', () => {
  it('marks a streaming message cancelled and preserves its partial content', async () => {
    const streaming = message('conversation-1', {
      parts: [
        { type: 'reasoning', text: 'Thinking' },
        { type: 'text', text: 'Partial answer' },
      ],
    })
    const store = new FakeMessageStore([streaming])
    const service = new RecoveryService({
      messages: store,
      nowIso: () => '2026-11-03T12:00:00.000Z',
    })

    const result = await service.recoverInterruptedMessages()

    expect(result.failed).toEqual([])
    expect(result.recovered).toHaveLength(1)
    expect(result.recovered[0]).toMatchObject({
      id: streaming.id,
      conversationId: streaming.conversationId,
      sequence: streaming.sequence,
      status: 'cancelled',
      finishReason: 'cancelled',
      parts: streaming.parts,
      updatedAt: '2026-11-03T12:00:00.000Z',
    })
    expect(result.recovered[0].error).toMatchObject({
      code: 'cancelled',
      message: INTERRUPTION_NOTE,
      providerId: 'provider-1',
    })
    expect(store.appended).toHaveLength(1)
  })

  it('leaves non-streaming messages untouched', async () => {
    const complete = message('conversation-1', { status: 'complete', finishReason: 'stop' })
    const store = new FakeMessageStore([complete])
    const service = new RecoveryService({ messages: store })

    const result = await service.recoverInterruptedMessages()

    expect(result.recovered).toEqual([])
    expect(store.appended).toEqual([])
    expect(store.messages[0]).toBe(complete)
  })

  it('is idempotent across repeated startup scans', async () => {
    const store = new FakeMessageStore([message('conversation-1')])
    const service = new RecoveryService({ messages: store })

    const first = await service.recoverInterruptedMessages()
    const second = await service.recoverInterruptedMessages()

    expect(first.recovered).toHaveLength(1)
    expect(second.recovered).toEqual([])
    expect(store.appended).toHaveLength(1)
  })

  it('can bound the scan to specific conversations', async () => {
    const store = new FakeMessageStore([message('conversation-1'), message('conversation-2')])
    const service = new RecoveryService({
      messages: store,
      conversationIds: ['conversation-1'],
    })

    const result = await service.recoverInterruptedMessages()

    expect(result.recovered.map((item) => item.conversationId)).toEqual(['conversation-1'])
  })

  it('reports a failed write without losing the other recovered messages', async () => {
    const first = message('conversation-1')
    const second = message('conversation-2')
    const store = new FakeMessageStore([first, second])
    let calls = 0
    const original = store.appendMessage.bind(store)
    store.appendMessage = async (next: Message) => {
      calls += 1

      if (calls === 1) {
        throw new Error('write failed')
      }

      return original(next)
    }
    const service = new RecoveryService({ messages: store })

    const result = await service.recoverInterruptedMessages()

    expect(result.failed).toHaveLength(1)
    expect(result.recovered).toHaveLength(1)
  })
})

describe('RecoveryService with IndexedDB', () => {
  let db: IDBPDatabase<ChatUiDB>
  let databaseName: string

  beforeEach(async () => {
    databaseName = `chatui-recovery-${newId()}`
    db = await openDatabase(databaseName)
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase(databaseName)
  })

  it('persists the cancelled status and partial text through MessageRepository', async () => {
    const conversations = new ConversationRepository(db)
    const messages = new MessageRepository(db)
    const created = await conversations.create(conversation())
    const streaming = message(created.id, {
      parts: [{ type: 'text', text: 'The answer so far' }],
    })
    await messages.appendMessage(streaming)

    const service = new RecoveryService({ messages })
    const result = await service.recoverInterruptedMessages()

    expect(result.recovered).toHaveLength(1)

    const stored = await messages.get(streaming.id)
    expect(stored?.status).toBe('cancelled')
    expect(stored?.finishReason).toBe('cancelled')
    expect(stored?.parts).toEqual([{ type: 'text', text: 'The answer so far' }])
    expect(stored?.error?.message).toBe(INTERRUPTION_NOTE)
    expect(await messages.listByStatus('streaming')).toEqual([])
  })
})
