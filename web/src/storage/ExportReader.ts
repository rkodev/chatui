import type { IDBPDatabase } from 'idb'
import type { Attachment, Conversation, Message, Model, Provider, Settings } from '../domain'
import { fromConversationRecord, fromProviderRecord, type ChatUiDB } from './db'

/**
 * Raw data collected for an export.
 *
 * This shape mirrors the contract arrays before the feature-level formatter
 * reduces attachments and adds optional bytes. It deliberately excludes the
 * `secrets` and `attachmentBlobs` stores: exports never read secret material.
 */
export interface ExportSnapshot {
  conversations: Conversation[]
  messages: Message[]
  attachments: Attachment[]
  providers: Provider[]
  models: Model[]
  settings: Settings
}

export interface ExportSettingsSource {
  get(): Promise<Settings>
}

export interface ExportReaderOptions {
  db: IDBPDatabase<ChatUiDB>
  settings: ExportSettingsSource
}

function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

function compareConversations(a: Conversation, b: Conversation): number {
  return compareText(a.createdAt, b.createdAt) || compareText(a.id, b.id)
}

function compareAttachments(a: Attachment, b: Attachment): number {
  return compareText(a.createdAt, b.createdAt) || compareText(a.id, b.id)
}

function compareProviders(a: Provider, b: Provider): number {
  return compareText(a.createdAt, b.createdAt) || compareText(a.id, b.id)
}

function compareModels(a: Model, b: Model): number {
  return (
    compareText(a.providerId, b.providerId) ||
    compareText(a.remoteId, b.remoteId) ||
    compareText(a.id, b.id)
  )
}

function referencedAttachmentIds(messages: readonly Message[]): Set<string> {
  const ids = new Set<string>()

  for (const message of messages) {
    for (const part of message.parts) {
      if (part.type === 'image' || part.type === 'file') {
        ids.add(part.attachmentId)
      }
    }
  }

  return ids
}

/**
 * Repository-level reader for export data.
 *
 * Conversations are ordered by creation time (then id), messages by sequence
 * within the corresponding conversation, and attachment/provider/model arrays
 * by their creation keys. Preserving these deterministic orders means an
 * export followed by an import can reconstruct the same history order.
 *
 * The reader never opens `secrets`, so API keys cannot enter an archive by
 * accident. Provider `secretId` values are opaque references only.
 */
export class ExportReader {
  private readonly db: IDBPDatabase<ChatUiDB>
  private readonly settings: ExportSettingsSource

  constructor(options: ExportReaderOptions) {
    this.db = options.db
    this.settings = options.settings
  }

  /** Conversations newest-first for the export entry points. */
  async listConversations(): Promise<Conversation[]> {
    const records = await this.db.getAll('conversations')
    return records
      .map(fromConversationRecord)
      .sort(
        (a, b) =>
          compareText(b.updatedAt, a.updatedAt) ||
          compareText(b.id, a.id) ||
          compareText(a.createdAt, b.createdAt),
      )
  }

  async readAll(): Promise<ExportSnapshot> {
    const [conversationRecords, messages, attachments, providerRecords, models, settings] =
      await Promise.all([
        this.db.getAll('conversations'),
        this.db.getAll('messages'),
        this.db.getAll('attachments'),
        this.db.getAll('providers'),
        this.db.getAll('models'),
        this.settings.get(),
      ])

    const conversations = conversationRecords.map(fromConversationRecord).sort(compareConversations)
    const conversationOrder = new Map(
      conversations.map((conversation, index) => [conversation.id, index]),
    )
    const sortedMessages = [...messages].sort(
      (a, b) =>
        (conversationOrder.get(a.conversationId) ?? Number.MAX_SAFE_INTEGER) -
          (conversationOrder.get(b.conversationId) ?? Number.MAX_SAFE_INTEGER) ||
        a.sequence - b.sequence ||
        compareText(a.id, b.id),
    )

    return {
      conversations,
      messages: sortedMessages,
      attachments: [...attachments].sort(compareAttachments),
      providers: providerRecords.map(fromProviderRecord).sort(compareProviders),
      models: [...models].sort(compareModels),
      settings,
    }
  }

  /**
   * Read one conversation with its messages and only the attachments those
   * messages reference. Historical model/provider snapshots stay on the
   * conversation and messages untouched.
   */
  async readConversation(conversationId: string): Promise<ExportSnapshot | null> {
    const record = await this.db.get('conversations', conversationId)

    if (!record) {
      return null
    }

    const allMessages = await this.db.getAllFromIndex('messages', 'conversationId', conversationId)
    const messages = [...allMessages].sort(
      (a, b) => a.sequence - b.sequence || compareText(a.id, b.id),
    )
    const referenced = referencedAttachmentIds(messages)
    const allAttachments = await this.db.getAll('attachments')
    const attachments = allAttachments
      .filter((attachment) => referenced.has(attachment.id))
      .sort(compareAttachments)

    const [providerRecords, models, settings] = await Promise.all([
      this.db.getAll('providers'),
      this.db.getAll('models'),
      this.settings.get(),
    ])

    return {
      conversations: [fromConversationRecord(record)],
      messages,
      attachments,
      providers: providerRecords.map(fromProviderRecord).sort(compareProviders),
      models: [...models].sort(compareModels),
      settings,
    }
  }
}
