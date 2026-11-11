import type { IDBPDatabase } from 'idb'
import { nowIso, type Conversation, type Message, type MessageStatus } from '../domain'
import { fromConversationRecord, runTransaction, toConversationRecord, type ChatUiDB } from './db'

export interface MessageListOptions {
  limit?: number
  /** Exclusive sequence cursor: return messages older than this sequence. */
  beforeSequence?: number | null
}

export interface MessagePage {
  items: Message[]
  /** Oldest loaded sequence, or null when no older page exists. */
  nextCursor: number | null
}

export interface AppendMessageResult {
  message: Message
  conversation: Conversation
}

export const DEFAULT_MESSAGE_LIMIT = 50

export class MessageRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async get(id: string): Promise<Message | null> {
    return (await this.db.get('messages', id)) ?? null
  }

  /**
   * Return the newest `limit` messages for a conversation in ascending
   * sequence order. Pass `nextCursor` as `beforeSequence` to load an older
   * page.
   */
  async listByConversation(
    conversationId: string,
    options: MessageListOptions = {},
  ): Promise<MessagePage> {
    const limit = options.limit ?? DEFAULT_MESSAGE_LIMIT
    if (limit <= 0) {
      throw new Error('Message list limit must be positive')
    }

    const upperSequence = options.beforeSequence ?? Number.POSITIVE_INFINITY
    const range = IDBKeyRange.bound(
      [conversationId, Number.NEGATIVE_INFINITY],
      [conversationId, upperSequence],
      false,
      true,
    )

    const transaction = this.db.transaction('messages', 'readonly')
    const index = transaction.store.index('conversationId+sequence')
    const collected: Message[] = []
    let cursor = await index.openCursor(range, 'prev')

    while (cursor && collected.length <= limit) {
      collected.push(cursor.value)
      cursor = await cursor.continue()
    }

    await transaction.done

    const hasOlderPage = collected.length > limit
    const items = hasOlderPage ? collected.slice(0, limit) : collected
    items.reverse()

    return {
      items,
      nextCursor: hasOlderPage && items.length > 0 ? items[0].sequence : null,
    }
  }

  /**
   * Persist a message and update the conversation's `lastMessageAt`,
   * `messageCount`, and `updatedAt` in one transaction. Re-appending an
   * existing message id does not increment the count.
   */
  async appendMessage(message: Message): Promise<AppendMessageResult> {
    return runTransaction(
      this.db,
      ['messages', 'conversations'],
      'readwrite',
      async (transaction) => {
        const messages = transaction.objectStore('messages')
        const conversations = transaction.objectStore('conversations')

        const existingMessage = await messages.get(message.id)
        const existingConversation = await conversations.get(message.conversationId)
        if (!existingConversation) {
          throw new Error(`Conversation ${message.conversationId} not found`)
        }

        await messages.put(message)

        const conversation = fromConversationRecord(existingConversation)
        const updated: Conversation = {
          ...conversation,
          lastMessageAt:
            message.createdAt > conversation.lastMessageAt
              ? message.createdAt
              : conversation.lastMessageAt,
          messageCount: existingMessage ? conversation.messageCount : conversation.messageCount + 1,
          revision: conversation.revision + 1,
          updatedAt: nowIso(),
        }

        await conversations.put(toConversationRecord(updated))
        return { message, conversation: updated }
      },
    )
  }

  /**
   * Delete every message after `sequence` and update conversation metadata.
   *
   * Used by edit-last-user. The target message itself is kept; the caller
   * rewrites it and appends the replacement assistant response.
   */
  async truncateAfter(
    conversationId: string,
    sequence: number,
  ): Promise<{ deleted: number; conversation: Conversation }> {
    return runTransaction(
      this.db,
      ['messages', 'conversations'],
      'readwrite',
      async (transaction) => {
        const messages = transaction.objectStore('messages')
        const conversations = transaction.objectStore('conversations')
        const existingConversation = await conversations.get(conversationId)

        if (!existingConversation) {
          throw new Error(`Conversation ${conversationId} not found`)
        }

        const index = messages.index('conversationId+sequence')
        const range = IDBKeyRange.bound(
          [conversationId, sequence],
          [conversationId, Number.POSITIVE_INFINITY],
          true,
          false,
        )
        let cursor = await index.openCursor(range)
        let deleted = 0

        while (cursor) {
          await cursor.delete()
          deleted += 1
          cursor = await cursor.continue()
        }

        const remaining = await messages.index('conversationId').count(conversationId)
        const lastCursor = await index.openCursor(
          IDBKeyRange.bound(
            [conversationId, Number.NEGATIVE_INFINITY],
            [conversationId, Number.POSITIVE_INFINITY],
          ),
          'prev',
        )
        const conversation = fromConversationRecord(existingConversation)
        const updated: Conversation = {
          ...conversation,
          messageCount: remaining,
          lastMessageAt: lastCursor ? lastCursor.value.createdAt : conversation.createdAt,
          revision: conversation.revision + 1,
          updatedAt: nowIso(),
        }

        await conversations.put(toConversationRecord(updated))
        return { deleted, conversation: updated }
      },
    )
  }

  async count(conversationId: string): Promise<number> {
    return this.db.countFromIndex('messages', 'conversationId', conversationId)
  }

  /**
   * Return every message with the given status.
   *
   * The v1 schema has no status index, so this is a bounded full-store scan.
   * Startup recovery uses it once per launch to find messages left in
   * `streaming`; callers should not use it on a hot path. An optional
   * `conversationIds` filter keeps the scan bounded when the caller only cares
   * about the active or last conversations.
   */
  async listByStatus(
    status: MessageStatus,
    options: { conversationIds?: readonly string[] } = {},
  ): Promise<Message[]> {
    const allowed =
      options.conversationIds === undefined ? null : new Set(options.conversationIds)
    const transaction = this.db.transaction('messages', 'readonly')
    const items: Message[] = []
    let cursor = await transaction.store.openCursor()

    while (cursor) {
      const message = cursor.value

      if (message.status === status && (allowed === null || allowed.has(message.conversationId))) {
        items.push(message)
      }

      cursor = await cursor.continue()
    }

    await transaction.done
    return items
  }
}
