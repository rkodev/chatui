import type { IDBPDatabase } from 'idb'
import { newId, nowIso, type Conversation } from '../domain'
import { fromConversationRecord, runTransaction, toConversationRecord, type ChatUiDB } from './db'

export interface NewConversation extends Omit<
  Conversation,
  'id' | 'createdAt' | 'updatedAt' | 'lastMessageAt' | 'messageCount'
> {
  id?: string
  createdAt?: string
  updatedAt?: string
  lastMessageAt?: string
  messageCount?: number
}

export interface ConversationListOptions {
  limit?: number
  cursor?: string | null
  archived?: boolean
  pinned?: boolean
}

export interface ConversationPage {
  items: Conversation[]
  nextCursor: string | null
}

interface ConversationCursor {
  updatedAt: string
  id: string
}

const DEFAULT_CONVERSATION_LIMIT = 50

function encodeCursor(cursor: ConversationCursor): string {
  return btoa(JSON.stringify(cursor))
}

function decodeCursor(value: string): ConversationCursor {
  try {
    const parsed = JSON.parse(atob(value)) as Partial<ConversationCursor>
    if (typeof parsed.updatedAt !== 'string' || typeof parsed.id !== 'string') {
      throw new Error('missing cursor fields')
    }
    return { updatedAt: parsed.updatedAt, id: parsed.id }
  } catch {
    throw new Error('Invalid conversation cursor')
  }
}

function matchesFilters(conversation: Conversation, options: ConversationListOptions): boolean {
  if (options.archived !== undefined && conversation.archived !== options.archived) {
    return false
  }
  if (options.pinned !== undefined && conversation.pinned !== options.pinned) {
    return false
  }
  return true
}

export class ConversationRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async create(input: NewConversation): Promise<Conversation> {
    const now = nowIso()
    const conversation: Conversation = {
      ...input,
      id: input.id ?? newId(),
      createdAt: input.createdAt ?? now,
      updatedAt: input.updatedAt ?? now,
      lastMessageAt: input.lastMessageAt ?? input.createdAt ?? now,
      messageCount: input.messageCount ?? 0,
    }

    await this.db.put('conversations', toConversationRecord(conversation))
    return conversation
  }

  async get(id: string): Promise<Conversation | null> {
    const record = await this.db.get('conversations', id)
    return record ? fromConversationRecord(record) : null
  }

  /**
   * List conversations newest-first by `updatedAt`.
   *
   * The cursor is base64-encoded JSON `{ updatedAt, id }`; `id` breaks ties
   * when several conversations share the same timestamp.
   */
  async list(options: ConversationListOptions = {}): Promise<ConversationPage> {
    const limit = options.limit ?? DEFAULT_CONVERSATION_LIMIT
    if (limit <= 0) {
      throw new Error('Conversation list limit must be positive')
    }

    const cursor = options.cursor ? decodeCursor(options.cursor) : null
    const transaction = this.db.transaction('conversations', 'readonly')
    const index = transaction.store.index('updatedAt')
    const items: Conversation[] = []
    let nextCursor: string | null = null
    let cursorValue = await index.openCursor(null, 'prev')

    while (cursorValue) {
      const record = cursorValue.value
      const conversation = fromConversationRecord(record)
      const afterCursor =
        cursor === null ||
        record.updatedAt < cursor.updatedAt ||
        (record.updatedAt === cursor.updatedAt && record.id < cursor.id)

      if (afterCursor && matchesFilters(conversation, options)) {
        if (items.length === limit) {
          const last = items[items.length - 1]
          nextCursor = encodeCursor({ updatedAt: last.updatedAt, id: last.id })
          break
        }
        items.push(conversation)
      }

      cursorValue = await cursorValue.continue()
    }

    await transaction.done
    return { items, nextCursor }
  }

  async update(
    id: string,
    patch: Partial<Omit<Conversation, 'id' | 'createdAt'>>,
  ): Promise<Conversation> {
    return runTransaction(this.db, ['conversations'], 'readwrite', async (transaction) => {
      const store = transaction.objectStore('conversations')
      const existing = await store.get(id)
      if (!existing) {
        throw new Error(`Conversation ${id} not found`)
      }

      const updated: Conversation = {
        ...fromConversationRecord(existing),
        ...patch,
        id,
        createdAt: existing.createdAt,
        updatedAt: patch.updatedAt ?? nowIso(),
      }

      await store.put(toConversationRecord(updated))
      return updated
    })
  }

  /**
   * Delete a conversation and its messages in one transaction. Attachment
   * metadata and bytes are left for the orphan-cleanup path.
   */
  async delete(id: string): Promise<void> {
    await runTransaction(
      this.db,
      ['conversations', 'messages'],
      'readwrite',
      async (transaction) => {
        await transaction.objectStore('conversations').delete(id)

        const messageIndex = transaction.objectStore('messages').index('conversationId')
        let cursor = await messageIndex.openCursor(IDBKeyRange.only(id))
        while (cursor) {
          await cursor.delete()
          cursor = await cursor.continue()
        }
      },
    )
  }
}
