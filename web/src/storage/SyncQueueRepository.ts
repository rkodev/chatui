import type { IDBPDatabase } from 'idb'
import { newId, nowIso } from '../domain'
import { runTransaction, type ChatUiDB, type SyncQueueItem, type SyncQueueOperation } from './db'

export interface NewSyncQueueItem {
  id?: string
  conversationId: string
  operation: SyncQueueOperation
  payload?: unknown
  attempts?: number
  lastAttemptAt?: string | null
  nextAttemptAt?: string | null
  lastError?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface SyncQueueListOptions {
  conversationId?: string
  limit?: number
}

export interface SyncQueueAttemptPatch {
  lastAttemptAt?: string | null
  nextAttemptAt?: string | null
  lastError?: string | null
}

export class SyncQueueRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async enqueue(input: NewSyncQueueItem): Promise<SyncQueueItem> {
    const now = nowIso()
    const item: SyncQueueItem = {
      id: input.id ?? newId(),
      conversationId: input.conversationId,
      operation: input.operation,
      payload: input.payload ?? null,
      attempts: input.attempts ?? 0,
      lastAttemptAt: input.lastAttemptAt ?? null,
      nextAttemptAt: input.nextAttemptAt ?? null,
      lastError: input.lastError ?? null,
      createdAt: input.createdAt ?? now,
      updatedAt: input.updatedAt ?? now,
    }

    await this.db.put('syncQueue', item)
    return item
  }

  async list(options: SyncQueueListOptions = {}): Promise<SyncQueueItem[]> {
    const items =
      options.conversationId === undefined
        ? await this.db.getAll('syncQueue')
        : await this.db.getAllFromIndex('syncQueue', 'conversationId', options.conversationId)

    return options.limit === undefined ? items : items.slice(0, options.limit)
  }

  async updateAttempts(
    id: string,
    attempts: number,
    patch: SyncQueueAttemptPatch = {},
  ): Promise<SyncQueueItem> {
    return runTransaction(this.db, ['syncQueue'], 'readwrite', async (transaction) => {
      const store = transaction.objectStore('syncQueue')
      const existing = await store.get(id)
      if (!existing) {
        throw new Error(`Sync queue item ${id} not found`)
      }

      const updated: SyncQueueItem = {
        ...existing,
        attempts,
        ...patch,
        updatedAt: nowIso(),
      }

      await store.put(updated)
      return updated
    })
  }

  async delete(id: string): Promise<void> {
    await this.db.delete('syncQueue', id)
  }
}
