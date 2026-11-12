import type { IDBPDatabase, IDBPTransaction, StoreNames } from 'idb'
import { newStorageKey } from '../domain'
import type { ChatUiDB } from './db'

export interface MigrationContext {
  db: IDBPDatabase<ChatUiDB>
  transaction: IDBPTransaction<ChatUiDB, StoreNames<ChatUiDB>[], 'versionchange'>
  oldVersion: number
  newVersion: number
}

export interface Migration {
  version: number
  description: string
  up: (context: MigrationContext) => void
}

/**
 * Version 1 creates every store and index from spec 8.5, plus the
 * `attachmentBlobs` OPFS fallback store documented in `db.ts`.
 */
function createV1({ db }: MigrationContext): void {
  const conversations = db.createObjectStore('conversations', { keyPath: 'id' })
  conversations.createIndex('updatedAt', 'updatedAt')
  conversations.createIndex('lastMessageAt', 'lastMessageAt')
  conversations.createIndex('archived', 'archived')
  conversations.createIndex('pinned', 'pinned')

  const messages = db.createObjectStore('messages', { keyPath: 'id' })
  messages.createIndex('conversationId+sequence', ['conversationId', 'sequence'])
  messages.createIndex('conversationId', 'conversationId')
  messages.createIndex('createdAt', 'createdAt')

  const attachments = db.createObjectStore('attachments', { keyPath: 'id' })
  attachments.createIndex('sha256', 'sha256')
  attachments.createIndex('messageId', 'messageId')
  attachments.createIndex('createdAt', 'createdAt')

  const providers = db.createObjectStore('providers', { keyPath: 'id' })
  providers.createIndex('presetId', 'presetId')
  providers.createIndex('enabled', 'enabled')

  const models = db.createObjectStore('models', { keyPath: 'id' })
  models.createIndex('providerId+remoteId', ['providerId', 'remoteId'], { unique: true })
  models.createIndex('providerId', 'providerId')

  db.createObjectStore('settings', { keyPath: 'key' })
  db.createObjectStore('secrets', { keyPath: 'id' })
  db.createObjectStore('backupConfig', { keyPath: 'id' })

  const syncQueue = db.createObjectStore('syncQueue', { keyPath: 'id' })
  syncQueue.createIndex('conversationId', 'conversationId')
  syncQueue.createIndex('attempts', 'attempts')

  // Spec 8.5 extension: content-addressed Blob fallback when OPFS is unavailable.
  db.createObjectStore('attachmentBlobs', { keyPath: 'sha256' })
}

/**
 * Version 2 adds the durable sync fields and deletion tombstones.
 *
 * Existing conversations are backfilled in place. The migration never deletes
 * or rewrites unrelated records, and it leaves already-valid values untouched.
 */
function createV2({ transaction }: MigrationContext): void {
  const tombstones = transaction.db.createObjectStore('tombstones', { keyPath: 'id' })
  tombstones.createIndex('conversationId', 'conversationId')
  tombstones.createIndex('deletedAt', 'deletedAt')

  const conversations = transaction.objectStore('conversations')

  void (async () => {
    let cursor = await conversations.openCursor()

    while (cursor) {
      const record = cursor.value
      let changed = false

      if (typeof record.storageKey !== 'string' || !/^[0-9a-f]{32}$/u.test(record.storageKey)) {
        record.storageKey = newStorageKey()
        changed = true
      }

      if (!Number.isInteger(record.revision) || record.revision < 1) {
        record.revision = 1
        changed = true
      }

      if (changed) {
        await cursor.update(record)
      }

      cursor = await cursor.continue()
    }
  })()
}

export const migrations: readonly Migration[] = [
  {
    version: 1,
    description: 'Create the v1 chatui schema',
    up: createV1,
  },
  {
    version: 2,
    description: 'Add conversation storage keys, revisions, and tombstones',
    up: createV2,
  },
]

/**
 * Apply every migration in `(oldVersion, newVersion]` in ascending order.
 *
 * The optional list exists so tests can exercise the upgrade harness without
 * adding production migrations beyond v1.
 */
export function runMigrations(
  db: IDBPDatabase<ChatUiDB>,
  oldVersion: number,
  newVersion: number,
  transaction: IDBPTransaction<ChatUiDB, StoreNames<ChatUiDB>[], 'versionchange'>,
  migrationList: readonly Migration[] = migrations,
): void {
  for (const migration of migrationList) {
    if (migration.version > oldVersion && migration.version <= newVersion) {
      migration.up({ db, transaction, oldVersion, newVersion })
    }
  }
}
