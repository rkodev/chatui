import type { IDBPDatabase } from 'idb'
import { newId, newStorageKey, nowIso, type Conversation, type Message } from '../domain'
import { fromConversationRecord, runTransaction, toConversationRecord, type ChatUiDB } from './db'

export interface NewConversation extends Omit<
  Conversation,
  'id' | 'createdAt' | 'updatedAt' | 'lastMessageAt' | 'messageCount' | 'storageKey' | 'revision'
> {
  id?: string
  createdAt?: string
  updatedAt?: string
  lastMessageAt?: string
  messageCount?: number
  storageKey?: string
  revision?: number
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

export interface ConversationSearchOptions {
  /** Maximum number of conversation groups returned. */
  limit?: number
  /** Maximum conversations inspected for title matches, newest first. */
  conversationScanLimit?: number
  /** Maximum messages inspected for text matches, newest first. */
  messageScanLimit?: number
  /** Maximum message snippets attached to one conversation group. */
  messageMatchLimit?: number
}

export interface ConversationSearchMessageMatch {
  messageId: string
  sequence: number
  role: Message['role']
  snippet: string
}

export interface ConversationSearchGroup {
  conversation: Conversation
  titleMatch: boolean
  messageMatches: ConversationSearchMessageMatch[]
}

export interface ConversationSearchResult {
  query: string
  groups: ConversationSearchGroup[]
  /** True when the scan or result cap stopped a search before exhausting the store. */
  truncated: boolean
  scannedConversations: number
  scannedMessages: number
}

interface ConversationCursor {
  updatedAt: string
  id: string
}

const DEFAULT_CONVERSATION_LIMIT = 50

/**
 * Bounded cursor-scan search limits.
 *
 * The v1 schema has no full-text index. Search therefore walks the newest
 * conversations and messages through existing indexes and stops at these caps.
 * Combined with UI debounce this keeps a keystroke-independent, bounded amount
 * of work per query instead of scanning the entire message store.
 */
export const DEFAULT_SEARCH_LIMIT = 20
export const DEFAULT_SEARCH_CONVERSATION_SCAN_LIMIT = 500
export const DEFAULT_SEARCH_MESSAGE_SCAN_LIMIT = 2000
export const DEFAULT_SEARCH_MESSAGE_MATCH_LIMIT = 3

function normalizeSearchQuery(query: string): string {
  return query.replace(/\s+/gu, ' ').trim().toLowerCase()
}

function messageText(message: Message): string {
  return message.parts
    .map((part) => (part.type === 'text' ? part.text : ''))
    .filter((text) => text !== '')
    .join(' ')
}

/** Build a short context window around the first match in `text`. */
export function searchSnippet(text: string, normalizedQuery: string, context = 48): string {
  const collapsed = text.replace(/\s+/gu, ' ').trim()
  const index = collapsed.toLowerCase().indexOf(normalizedQuery)

  if (index < 0) {
    return collapsed.slice(0, context * 2)
  }

  const start = Math.max(0, index - context)
  const end = Math.min(collapsed.length, index + normalizedQuery.length + context)
  const body = collapsed.slice(start, end)

  return `${start > 0 ? '…' : ''}${body}${end < collapsed.length ? '…' : ''}`
}

function groupTimestamp(group: ConversationSearchGroup): string {
  return group.conversation.lastMessageAt || group.conversation.updatedAt
}

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
      storageKey: input.storageKey ?? newStorageKey(),
      revision: input.revision ?? 1,
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

  /**
   * Search conversation titles and message text with a bounded cursor scan.
   *
   * Titles are checked over the newest `conversationScanLimit` conversations.
   * Message text is checked over the newest `messageScanLimit` messages. Both
   * walks use existing indexes, so no schema migration or write-path index
   * maintenance is required. Results are grouped by conversation, newest
   * conversation first, with up to `messageMatchLimit` snippets per group.
   */
  async search(
    query: string,
    options: ConversationSearchOptions = {},
  ): Promise<ConversationSearchResult> {
    const normalized = normalizeSearchQuery(query)
    const limit = options.limit ?? DEFAULT_SEARCH_LIMIT
    const conversationScanLimit =
      options.conversationScanLimit ?? DEFAULT_SEARCH_CONVERSATION_SCAN_LIMIT
    const messageScanLimit = options.messageScanLimit ?? DEFAULT_SEARCH_MESSAGE_SCAN_LIMIT
    const messageMatchLimit = options.messageMatchLimit ?? DEFAULT_SEARCH_MESSAGE_MATCH_LIMIT

    if (limit <= 0 || conversationScanLimit <= 0 || messageScanLimit <= 0) {
      throw new Error('Conversation search limits must be positive')
    }

    const empty: ConversationSearchResult = {
      query: query.trim(),
      groups: [],
      truncated: false,
      scannedConversations: 0,
      scannedMessages: 0,
    }

    if (normalized === '') {
      return empty
    }

    const transaction = this.db.transaction(['conversations', 'messages'], 'readonly')
    const conversations = transaction.objectStore('conversations')
    const messages = transaction.objectStore('messages')
    const groupsById = new Map<string, ConversationSearchGroup>()
    let scannedConversations = 0
    let scannedMessages = 0
    let conversationLimitReached = false
    let messageLimitReached = false

    let conversationCursor = await conversations.index('updatedAt').openCursor(null, 'prev')

    while (conversationCursor && scannedConversations < conversationScanLimit) {
      const conversation = fromConversationRecord(conversationCursor.value)
      scannedConversations += 1

      if (conversation.title.toLowerCase().includes(normalized)) {
        groupsById.set(conversation.id, {
          conversation,
          titleMatch: true,
          messageMatches: [],
        })
      }

      conversationCursor = await conversationCursor.continue()
    }

    if (conversationCursor) {
      conversationLimitReached = true
    }

    let messageCursor = await messages.index('createdAt').openCursor(null, 'prev')

    while (messageCursor && scannedMessages < messageScanLimit) {
      const message = messageCursor.value
      scannedMessages += 1
      const text = messageText(message)

      if (text.toLowerCase().includes(normalized)) {
        let group = groupsById.get(message.conversationId)

        if (!group) {
          const record = await conversations.get(message.conversationId)

          if (record) {
            group = {
              conversation: fromConversationRecord(record),
              titleMatch: false,
              messageMatches: [],
            }
            groupsById.set(message.conversationId, group)
          }
        }

        if (group && group.messageMatches.length < messageMatchLimit) {
          group.messageMatches.push({
            messageId: message.id,
            sequence: message.sequence,
            role: message.role,
            snippet: searchSnippet(text, normalized),
          })
        }
      }

      messageCursor = await messageCursor.continue()
    }

    if (messageCursor) {
      messageLimitReached = true
    }

    await transaction.done

    const groups = [...groupsById.values()].sort((left, right) =>
      groupTimestamp(right).localeCompare(groupTimestamp(left)),
    )

    for (const group of groups) {
      group.messageMatches.sort((left, right) => right.sequence - left.sequence)
    }

    return {
      query: query.trim(),
      groups: groups.slice(0, limit),
      truncated:
        conversationLimitReached || messageLimitReached || groups.length > limit,
      scannedConversations,
      scannedMessages,
    }
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

      const existingConversation = fromConversationRecord(existing)
      const updated: Conversation = {
        ...existingConversation,
        ...patch,
        id,
        createdAt: existing.createdAt,
        storageKey: patch.storageKey ?? existingConversation.storageKey,
        revision: patch.revision ?? existingConversation.revision + 1,
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
