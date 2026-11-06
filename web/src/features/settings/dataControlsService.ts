import type { IDBPDatabase } from 'idb'
import type { Provider } from '../../domain'
import {
  DEVICE_KEY_RECORD_ID,
  PASSPHRASE_CHECK_RECORD_ID,
  PASSPHRASE_SALT_RECORD_ID,
} from '../../storage/SecretStore'
import { runTransaction, type ChatUiDB } from '../../storage/db'

/** One usage row: record count plus an approximate byte size. */
export interface StorageUsageEntry {
  count: number
  bytes: number
}

/**
 * Storage usage shown on `/settings/data`.
 *
 * Attachment bytes come from attachment metadata (`size`) deduplicated by
 * SHA-256. The `attachmentBlobs` store and OPFS are never read, so opening the
 * data screen cannot pull file contents into memory.
 */
export interface StorageUsage {
  conversations: StorageUsageEntry
  messages: StorageUsageEntry
  attachments: StorageUsageEntry
  secrets: StorageUsageEntry
  providers: StorageUsageEntry
  models: StorageUsageEntry
  totalBytes: number
}

/** Non-secret summary of one provider key for the data screen. */
export interface ProviderKeySummary {
  id: string
  label: string
  presetId: string
  hasKey: boolean
  keyLabel: string | null
}

export interface ClearAllResult {
  conversations: number
  messages: number
  attachments: number
  secrets: number
  providers: number
  models: number
}

/** Provider-management surface used for per-key deletion. */
export interface ProviderKeyServiceLike {
  listProviders(): Promise<Provider[]>
  removeKey(providerId: string): Promise<Provider>
}

/** Attachment cleanup surface used by clear-all. */
export interface AttachmentCleanupLike {
  cleanupOrphans(referencedSha256: Set<string>): Promise<string[]>
}

export interface DataControlsServiceOptions {
  db: IDBPDatabase<ChatUiDB>
  attachmentStore: AttachmentCleanupLike
  keyService: ProviderKeyServiceLike
}

export interface DataControlsServiceLike {
  getUsage(): Promise<StorageUsage>
  clearAll(): Promise<ClearAllResult>
  listProviderKeys(): Promise<ProviderKeySummary[]>
  removeProviderKey(providerId: string): Promise<void>
}

const RESERVED_SECRET_IDS = new Set([
  DEVICE_KEY_RECORD_ID,
  PASSPHRASE_SALT_RECORD_ID,
  PASSPHRASE_CHECK_RECORD_ID,
])

const CLEARED_STORES = [
  'conversations',
  'messages',
  'attachments',
  'providers',
  'models',
  'secrets',
  'syncQueue',
] as const

const encoder = new TextEncoder()

function byteLength(value: unknown): number {
  return encoder.encode(JSON.stringify(value)).byteLength
}

function keyLabel(provider: Provider): string | null {
  const value = provider.metadata?.key

  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }

  const label = (value as { label?: unknown }).label
  return typeof label === 'string' && label.trim() !== '' ? label : null
}

function sumBytes(entries: readonly StorageUsageEntry[]): number {
  return entries.reduce((total, entry) => total + entry.bytes, 0)
}

/**
 * Storage usage, clear-all, and per-key deletion for the web data screen.
 *
 * Clear-all removes conversations, messages, attachments, providers, models,
 * stored secrets, and the sync queue. Appearance and chat preferences are
 * intentionally kept so the app remains usable after a reset. Attachment bytes
 * are removed through the content-addressed store, which also clears the
 * IndexedDB fallback.
 */
export class DataControlsService implements DataControlsServiceLike {
  private readonly db: IDBPDatabase<ChatUiDB>
  private readonly attachmentStore: AttachmentCleanupLike
  private readonly keyService: ProviderKeyServiceLike

  constructor(options: DataControlsServiceOptions) {
    this.db = options.db
    this.attachmentStore = options.attachmentStore
    this.keyService = options.keyService
  }

  async getUsage(): Promise<StorageUsage> {
    const [conversationRecords, messages, attachments, providerRecords, models, secretRecords] =
      await Promise.all([
        this.db.getAll('conversations'),
        this.db.getAll('messages'),
        this.db.getAll('attachments'),
        this.db.getAll('providers'),
        this.db.getAll('models'),
        this.db.getAll('secrets'),
      ])

    const attachmentSizes = new Map<string, number>()
    for (const attachment of attachments) {
      if (!attachmentSizes.has(attachment.sha256)) {
        attachmentSizes.set(attachment.sha256, attachment.size)
      }
    }

    const persistedSecrets = secretRecords.filter((record) => !RESERVED_SECRET_IDS.has(record.id))

    const usage = {
      conversations: {
        count: conversationRecords.length,
        bytes: conversationRecords.reduce((total, record) => total + byteLength(record), 0),
      },
      messages: {
        count: messages.length,
        bytes: messages.reduce((total, record) => total + byteLength(record), 0),
      },
      attachments: {
        count: attachments.length,
        bytes: [...attachmentSizes.values()].reduce((total, size) => total + size, 0),
      },
      secrets: {
        count: persistedSecrets.length,
        bytes: persistedSecrets.reduce(
          (total, record) => total + (record.ciphertext?.length ?? 0),
          0,
        ),
      },
      providers: {
        count: providerRecords.length,
        bytes: providerRecords.reduce((total, record) => total + byteLength(record), 0),
      },
      models: {
        count: models.length,
        bytes: models.reduce((total, record) => total + byteLength(record), 0),
      },
    }

    return {
      ...usage,
      totalBytes: sumBytes(Object.values(usage)),
    }
  }

  async clearAll(): Promise<ClearAllResult> {
    const before = await this.getUsage()

    await runTransaction(this.db, [...CLEARED_STORES], 'readwrite', async (transaction) => {
      for (const store of CLEARED_STORES) {
        await transaction.objectStore(store).clear()
      }
    })

    await this.attachmentStore.cleanupOrphans(new Set())

    return {
      conversations: before.conversations.count,
      messages: before.messages.count,
      attachments: before.attachments.count,
      secrets: before.secrets.count,
      providers: before.providers.count,
      models: before.models.count,
    }
  }

  async listProviderKeys(): Promise<ProviderKeySummary[]> {
    const providers = await this.keyService.listProviders()

    return providers
      .map((provider) => ({
        id: provider.id,
        label: provider.label,
        presetId: provider.presetId,
        hasKey: provider.secretId !== null,
        keyLabel: keyLabel(provider),
      }))
      .sort((a, b) => a.label.localeCompare(b.label) || a.id.localeCompare(b.id))
  }

  async removeProviderKey(providerId: string): Promise<void> {
    await this.keyService.removeKey(providerId)
  }
}
