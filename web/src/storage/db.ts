import {
  deleteDB,
  openDB,
  type DBSchema,
  type IDBPDatabase,
  type IDBPTransaction,
  type StoreNames,
} from 'idb'
import type { Attachment, BackupConfig, Conversation, Message, Model, Provider } from '../domain'
import { runMigrations } from './migrations'

export const DB_NAME = 'chatui'
export const DB_VERSION = 1

/**
 * IndexedDB cannot index booleans, so the two indexed conversation flags are
 * persisted as 0/1 and mapped back to booleans by the repository. This mirrors
 * Room's INTEGER representation on Android.
 */
export interface ConversationRecord extends Omit<Conversation, 'archived' | 'pinned'> {
  archived: 0 | 1
  pinned: 0 | 1
}

/** Same boolean-index workaround as `ConversationRecord`. */
export interface ProviderRecord extends Omit<Provider, 'enabled'> {
  enabled: 0 | 1
}

export interface SettingRecord<T = unknown> {
  key: string
  value: T
  updatedAt: string
}

/**
 * A secret record stores either AES-GCM ciphertext (with its IV) or the
 * non-extractable device key used by remember mode. Plaintext secret values
 * never belong in this record.
 */
export interface SecretRecord {
  id: string
  iv: string | null
  ciphertext: string | null
  key?: CryptoKey
  createdAt: string
  updatedAt: string
}

export interface BackupConfigRecord extends BackupConfig {
  id: string
  updatedAt: string
}

export type SyncQueueOperation = 'upsert' | 'delete'

/**
 * Placeholder sync queue shape. M5 owns the final sync protocol; M0 only needs
 * a durable queue with retry bookkeeping.
 */
export interface SyncQueueItem {
  id: string
  conversationId: string
  operation: SyncQueueOperation
  payload: unknown
  attempts: number
  lastAttemptAt: string | null
  nextAttemptAt: string | null
  lastError: string | null
  createdAt: string
  updatedAt: string
}

/**
 * Extension to spec 8.5: the OPFS fallback byte store. It is keyed by SHA-256
 * and is only used when OPFS is unavailable or fails.
 */
export interface AttachmentBlobRecord {
  sha256: string
  blob: Blob
  size: number
  createdAt: string
}

export interface ChatUiDB extends DBSchema {
  conversations: {
    key: string
    value: ConversationRecord
    indexes: {
      updatedAt: string
      lastMessageAt: string
      archived: 0 | 1
      pinned: 0 | 1
    }
  }
  messages: {
    key: string
    value: Message
    indexes: {
      'conversationId+sequence': [string, number]
      conversationId: string
      createdAt: string
    }
  }
  attachments: {
    key: string
    value: Attachment
    indexes: {
      sha256: string
      messageId: string
      createdAt: string
    }
  }
  providers: {
    key: string
    value: ProviderRecord
    indexes: {
      presetId: string
      enabled: 0 | 1
    }
  }
  models: {
    key: string
    value: Model
    indexes: {
      'providerId+remoteId': [string, string]
      providerId: string
    }
  }
  settings: {
    key: string
    value: SettingRecord
  }
  secrets: {
    key: string
    value: SecretRecord
  }
  backupConfig: {
    key: string
    value: BackupConfigRecord
  }
  syncQueue: {
    key: string
    value: SyncQueueItem
    indexes: {
      conversationId: string
      attempts: number
    }
  }
  attachmentBlobs: {
    key: string
    value: AttachmentBlobRecord
  }
}

export type ChatUiStoreName = StoreNames<ChatUiDB>

export type ChatUiTransaction<Mode extends IDBTransactionMode = 'readonly'> = IDBPTransaction<
  ChatUiDB,
  ChatUiStoreName[],
  Mode
>

/**
 * Run a callback inside one IndexedDB transaction and wait for it to commit.
 * If the callback throws, the transaction is aborted so partial multi-record
 * writes cannot survive.
 */
export async function runTransaction<T, Mode extends IDBTransactionMode = 'readonly'>(
  db: IDBPDatabase<ChatUiDB>,
  stores: ChatUiStoreName[],
  mode: Mode,
  callback: (transaction: ChatUiTransaction<Mode>) => Promise<T>,
): Promise<T> {
  const transaction = db.transaction(stores, mode)

  try {
    const result = await callback(transaction)
    await transaction.done
    return result
  } catch (error) {
    try {
      transaction.abort()
    } catch {
      // The transaction may already have aborted or completed.
    }
    // Consume the abort rejection so it cannot surface as an unhandled error.
    await transaction.done.catch(() => undefined)
    throw error
  }
}

export function toConversationRecord(conversation: Conversation): ConversationRecord {
  return {
    ...conversation,
    archived: conversation.archived ? 1 : 0,
    pinned: conversation.pinned ? 1 : 0,
  }
}

export function fromConversationRecord(record: ConversationRecord): Conversation {
  return {
    ...record,
    archived: record.archived === 1,
    pinned: record.pinned === 1,
  }
}

export function toProviderRecord(provider: Provider): ProviderRecord {
  return {
    ...provider,
    enabled: provider.enabled ? 1 : 0,
  }
}

export function fromProviderRecord(record: ProviderRecord): Provider {
  return {
    ...record,
    enabled: record.enabled === 1,
  }
}

export function openDatabase(name: string = DB_NAME): Promise<IDBPDatabase<ChatUiDB>> {
  return openDB<ChatUiDB>(name, DB_VERSION, {
    upgrade(db, oldVersion, newVersion, transaction) {
      runMigrations(db, oldVersion, newVersion ?? DB_VERSION, transaction)
    },
  })
}

export function deleteDatabase(name: string = DB_NAME): Promise<void> {
  return deleteDB(name)
}
