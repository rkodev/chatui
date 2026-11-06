import type { IDBPDatabase } from 'idb'
import { z } from 'zod'
import type {
  Attachment,
  ChatParams,
  Conversation,
  Message,
  Model,
  Settings,
} from '../../domain'
import { base64ToBytes } from '../../platform/crypto'
import {
  runTransaction,
  toConversationRecord,
  toProviderRecord,
  type ChatUiDB,
} from '../../storage'
import { storageKeyFor } from '../../storage/AttachmentStore'
import {
  chatExportSchema,
  type ChatExport,
  type ExportAttachment,
} from './exportService'

/**
 * Maximum accepted archive size before parsing.
 *
 * The limit is deliberately generous enough for a full history with base64
 * attachment bytes, while still bounding the memory a hostile file can claim.
 */
export const MAX_IMPORT_BYTES = 50 * 1024 * 1024

/** Maximum decoded size of one attachment restored from an archive. */
export const MAX_IMPORT_ATTACHMENT_BYTES = 20 * 1024 * 1024

/** Maximum attachment file name length, matching the picker rules. */
export const MAX_IMPORT_ATTACHMENT_NAME_LENGTH = 255

export const IMPORT_MODES = ['merge', 'replace'] as const

export type ImportMode = (typeof IMPORT_MODES)[number]

export type ImportErrorCode =
  | 'too_large'
  | 'invalid_json'
  | 'schema'
  | 'invalid_id'
  | 'duplicate_id'
  | 'invalid_reference'
  | 'unsafe_attachment_name'
  | 'invalid_attachment'
  | 'invalid_mode'

export class ImportValidationError extends Error {
  readonly code: ImportErrorCode

  constructor(code: ImportErrorCode, message: string) {
    super(message)
    this.name = 'ImportValidationError'
    this.code = code
  }
}

export interface ImportOptions {
  /** Merge is the default. Replace clears local data first. */
  mode?: ImportMode
}

export interface ImportResult {
  mode: ImportMode
  conversations: number
  messages: number
  attachments: number
  providers: number
  models: number
  attachmentBytes: number
}

export interface ImportServiceLike {
  importFile(file: File, options?: ImportOptions): Promise<ImportResult>
  importText(text: string, options?: ImportOptions): Promise<ImportResult>
}

export interface ImportAttachmentStore {
  get(storageKey: string): Promise<Uint8Array>
  put(bytes: Uint8Array, sha256: string): Promise<string>
  delete(storageKey: string): Promise<void>
  cleanupOrphans(referencedSha256: Set<string>): Promise<string[]>
}

export interface ImportServiceOptions {
  db: IDBPDatabase<ChatUiDB>
  attachmentStore: ImportAttachmentStore
  now?: () => string
  /** Test override for the archive size limit. */
  maxBytes?: number
  /** Test override for one attachment's decoded size limit. */
  maxAttachmentBytes?: number
}

const UNSAFE_ATTACHMENT_NAME = /[\\/:*?"<>|]/u
const SHA256_PATTERN = /^[0-9a-f]{64}$/iu
const MAX_ID_LENGTH = 256

function hasControlCharacter(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0

    if (code < 0x20 || code === 0x7f) {
      return true
    }
  }

  return false
}

function schemaMessage(error: unknown): string {
  if (error instanceof z.ZodError) {
    return error.issues
      .map((issue) => `${issue.path.length === 0 ? '$' : issue.path.join('.')}: ${issue.message}`)
      .join('; ')
  }

  return error instanceof Error ? error.message : String(error)
}

function validateId(id: string, label: string): void {
  if (id.length > MAX_ID_LENGTH || hasControlCharacter(id)) {
    throw new ImportValidationError('invalid_id', `${label} id is not a safe identifier.`)
  }
}

function uniqueIds(values: readonly string[], label: string): Set<string> {
  const seen = new Set<string>()

  for (const value of values) {
    validateId(value, label)

    if (seen.has(value)) {
      throw new ImportValidationError('duplicate_id', `Duplicate ${label} id: ${value}`)
    }

    seen.add(value)
  }

  return seen
}

function validateAttachmentName(name: string): void {
  if (
    name.length === 0 ||
    name.length > MAX_IMPORT_ATTACHMENT_NAME_LENGTH ||
    name.trim() !== name ||
    name === '.' ||
    name === '..' ||
    hasControlCharacter(name) ||
    UNSAFE_ATTACHMENT_NAME.test(name)
  ) {
    throw new ImportValidationError(
      'unsafe_attachment_name',
      `Unsafe attachment name: ${JSON.stringify(name)}`,
    )
  }
}

function validateAttachmentMetadata(attachment: ExportAttachment): void {
  validateAttachmentName(attachment.name)

  if (!SHA256_PATTERN.test(attachment.sha256)) {
    throw new ImportValidationError(
      'invalid_attachment',
      `Attachment ${attachment.id} has an invalid SHA-256 value.`,
    )
  }
}

function modelKey(model: Pick<Model, 'providerId' | 'remoteId'>): string {
  return `${model.providerId}\u0000${model.remoteId}`
}

/**
 * Validate the archive shape and every cross-record reference before any write.
 *
 * This is the untrusted boundary: it never executes imported content and it
 * rejects duplicate identities, dangling conversation/message/provider/model
 * references, missing attachment references, and unsafe attachment names.
 */
export function validateImportArchive(value: unknown): ChatExport {
  let archive: ChatExport

  try {
    archive = chatExportSchema.parse(value) as ChatExport
  } catch (error) {
    throw new ImportValidationError('schema', schemaMessage(error))
  }

  const conversationIds = uniqueIds(
    archive.conversations.map((conversation) => conversation.id),
    'conversation',
  )
  uniqueIds(
    archive.messages.map((message) => message.id),
    'message',
  )
  const attachmentIds = uniqueIds(
    archive.attachments.map((attachment) => attachment.id),
    'attachment',
  )
  const providerIds = uniqueIds(
    archive.providers.map((provider) => provider.id),
    'provider',
  )
  uniqueIds(
    archive.models.map((model) => model.id),
    'model',
  )

  const modelsById = new Map<string, Model>()
  const modelRemoteKeys = new Set<string>()

  for (const model of archive.models) {
    if (!providerIds.has(model.providerId)) {
      throw new ImportValidationError(
        'invalid_reference',
        `Model ${model.id} references missing provider ${model.providerId}.`,
      )
    }

    const key = modelKey(model)
    if (modelRemoteKeys.has(key)) {
      throw new ImportValidationError(
        'duplicate_id',
        `Duplicate model identity ${model.providerId}/${model.remoteId}.`,
      )
    }

    modelRemoteKeys.add(key)
    modelsById.set(model.id, model)
  }

  const messageSequences = new Set<string>()

  for (const message of archive.messages) {
    if (!conversationIds.has(message.conversationId)) {
      throw new ImportValidationError(
        'invalid_reference',
        `Message ${message.id} references missing conversation ${message.conversationId}.`,
      )
    }

    const sequenceKey = `${message.conversationId}\u0000${message.sequence}`
    if (messageSequences.has(sequenceKey)) {
      throw new ImportValidationError(
        'duplicate_id',
        `Duplicate message sequence ${message.sequence} in conversation ${message.conversationId}.`,
      )
    }
    messageSequences.add(sequenceKey)

    if (message.providerId !== null && !providerIds.has(message.providerId)) {
      throw new ImportValidationError(
        'invalid_reference',
        `Message ${message.id} references missing provider ${message.providerId}.`,
      )
    }

    if (message.modelId !== null) {
      const model = modelsById.get(message.modelId)

      if (!model) {
        throw new ImportValidationError(
          'invalid_reference',
          `Message ${message.id} references missing model ${message.modelId}.`,
        )
      }

      if (message.providerId !== null && model.providerId !== message.providerId) {
        throw new ImportValidationError(
          'invalid_reference',
          `Message ${message.id} model ${message.modelId} does not belong to provider ${message.providerId}.`,
        )
      }
    }

    for (const part of message.parts) {
      if (part.type === 'image' || part.type === 'file') {
        if (!attachmentIds.has(part.attachmentId)) {
          throw new ImportValidationError(
            'invalid_reference',
            `Message ${message.id} references missing attachment ${part.attachmentId}.`,
          )
        }
      }
    }
  }

  for (const conversation of archive.conversations) {
    if (!providerIds.has(conversation.providerId)) {
      throw new ImportValidationError(
        'invalid_reference',
        `Conversation ${conversation.id} references missing provider ${conversation.providerId}.`,
      )
    }

    const model = modelsById.get(conversation.modelId)

    if (!model) {
      throw new ImportValidationError(
        'invalid_reference',
        `Conversation ${conversation.id} references missing model ${conversation.modelId}.`,
      )
    }

    if (model.providerId !== conversation.providerId) {
      throw new ImportValidationError(
        'invalid_reference',
        `Conversation ${conversation.id} model ${conversation.modelId} does not belong to provider ${conversation.providerId}.`,
      )
    }
  }

  for (const attachment of archive.attachments) {
    validateAttachmentMetadata(attachment)
  }

  const settings = archive.settings

  if (settings.defaultProviderId !== null && !providerIds.has(settings.defaultProviderId)) {
    throw new ImportValidationError(
      'invalid_reference',
      `Settings reference missing provider ${settings.defaultProviderId}.`,
    )
  }

  if (settings.defaultModelId !== null) {
    const model = modelsById.get(settings.defaultModelId)

    if (!model) {
      throw new ImportValidationError(
        'invalid_reference',
        `Settings reference missing model ${settings.defaultModelId}.`,
      )
    }

    if (
      settings.defaultProviderId !== null &&
      model.providerId !== settings.defaultProviderId
    ) {
      throw new ImportValidationError(
        'invalid_reference',
        `Settings model ${settings.defaultModelId} does not belong to provider ${settings.defaultProviderId}.`,
      )
    }
  }

  if (
    settings.lastActiveConversationId !== null &&
    !conversationIds.has(settings.lastActiveConversationId)
  ) {
    throw new ImportValidationError(
      'invalid_reference',
      `Settings reference missing conversation ${settings.lastActiveConversationId}.`,
    )
  }

  return archive
}

function sha256Hex(bytes: Uint8Array): Promise<string> {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  return crypto.subtle.digest('SHA-256', copy.buffer).then((digest) =>
    Array.from(new Uint8Array(digest))
      .map((byte) => byte.toString(16).padStart(2, '0'))
      .join(''),
  )
}

async function decodeAttachmentBytes(
  attachment: ExportAttachment,
  maxBytes: number,
): Promise<Uint8Array | null> {
  if (attachment.dataBase64 === undefined) {
    return null
  }

  let bytes: Uint8Array

  try {
    bytes = base64ToBytes(attachment.dataBase64)
  } catch {
    throw new ImportValidationError(
      'invalid_attachment',
      `Attachment ${attachment.id} has invalid base64 data.`,
    )
  }

  if (bytes.byteLength > maxBytes) {
    throw new ImportValidationError(
      'too_large',
      `Attachment ${attachment.id} is larger than the ${maxBytes} byte import limit.`,
    )
  }

  if (bytes.byteLength !== attachment.size) {
    throw new ImportValidationError(
      'invalid_attachment',
      `Attachment ${attachment.id} byte length does not match its declared size.`,
    )
  }

  const digest = await sha256Hex(bytes)

  if (digest !== attachment.sha256.toLowerCase()) {
    throw new ImportValidationError(
      'invalid_attachment',
      `Attachment ${attachment.id} bytes do not match its SHA-256 value.`,
    )
  }

  return bytes
}

function cloneParams(params: ChatParams): ChatParams {
  return {
    ...params,
    stop: [...params.stop],
    extra: structuredClone(params.extra),
  }
}

function remapSettings(settings: Settings, remapModelId: (id: string) => string): Settings {
  return {
    ...settings,
    defaultModelId:
      settings.defaultModelId === null ? null : remapModelId(settings.defaultModelId),
    defaultParams: cloneParams(settings.defaultParams),
    backup: settings.backup === null ? null : { ...settings.backup },
  }
}

interface ModelPlan {
  models: Model[]
  remapModelId: (id: string) => string
}

/**
 * Merge models by their domain identity `(providerId, remoteId)`.
 *
 * A local model with the same remote identity keeps its local id, and imported
 * references are remapped to it. This avoids creating a second row for the same
 * provider/model pair when two devices generated different local ids.
 */
function planModels(imported: readonly Model[], existing: readonly Model[]): ModelPlan {
  const byRemote = new Map(existing.map((model) => [modelKey(model), model]))
  const remap = new Map<string, string>()
  const models: Model[] = []

  for (const model of imported) {
    const local = byRemote.get(modelKey(model))

    if (local !== undefined && local.id !== model.id) {
      remap.set(model.id, local.id)
      continue
    }

    models.push(model)
  }

  return {
    models,
    remapModelId: (id) => remap.get(id) ?? id,
  }
}

interface AttachmentReference {
  messageId: string
  kind: 'image' | 'file'
  createdAt: string
}

function buildAttachments(
  archive: ChatExport,
  messages: readonly Message[],
  now: string,
): Attachment[] {
  const references = new Map<string, AttachmentReference>()

  for (const message of messages) {
    for (const part of message.parts) {
      if (part.type === 'image' || part.type === 'file') {
        if (!references.has(part.attachmentId)) {
          references.set(part.attachmentId, {
            messageId: message.id,
            kind: part.type,
            createdAt: message.createdAt,
          })
        }
      }
    }
  }

  return archive.attachments.map((attachment) => {
    const reference = references.get(attachment.id)
    const sha256 = attachment.sha256.toLowerCase()

    return {
      id: attachment.id,
      messageId: reference?.messageId ?? null,
      kind: reference?.kind ?? (attachment.mimeType.startsWith('image/') ? 'image' : 'file'),
      mimeType: attachment.mimeType,
      name: attachment.name,
      size: attachment.size,
      sha256,
      storageKey: storageKeyFor(sha256),
      width: null,
      height: null,
      createdAt: reference?.createdAt ?? now,
    }
  })
}

/**
 * Safe `chatui.export` import.
 *
 * Validation and attachment byte verification happen before any write. Merge
 * is the default and upserts by id without deleting local records. Replace
 * clears the imported data stores inside the same transaction that writes the
 * archive. If the transaction fails, newly written attachment bytes are
 * removed so a partial import cannot survive.
 */
export class ImportService implements ImportServiceLike {
  private readonly db: IDBPDatabase<ChatUiDB>
  private readonly attachmentStore: ImportAttachmentStore
  private readonly now: () => string
  private readonly maxBytes: number
  private readonly maxAttachmentBytes: number

  constructor(options: ImportServiceOptions) {
    this.db = options.db
    this.attachmentStore = options.attachmentStore
    this.now = options.now ?? (() => new Date().toISOString())
    this.maxBytes = options.maxBytes ?? MAX_IMPORT_BYTES
    this.maxAttachmentBytes = options.maxAttachmentBytes ?? MAX_IMPORT_ATTACHMENT_BYTES
  }

  async importFile(file: File, options: ImportOptions = {}): Promise<ImportResult> {
    if (file.size > this.maxBytes) {
      throw new ImportValidationError(
        'too_large',
        `The archive is larger than the ${this.maxBytes} byte import limit.`,
      )
    }

    return this.importText(await file.text(), options)
  }

  async importText(text: string, options: ImportOptions = {}): Promise<ImportResult> {
    const mode = options.mode ?? 'merge'

    if (!IMPORT_MODES.includes(mode)) {
      throw new ImportValidationError('invalid_mode', `Unknown import mode: ${String(mode)}`)
    }

    const encoded = new TextEncoder().encode(text)

    if (encoded.byteLength > this.maxBytes) {
      throw new ImportValidationError(
        'too_large',
        `The archive is larger than the ${this.maxBytes} byte import limit.`,
      )
    }

    let parsed: unknown

    try {
      parsed = JSON.parse(text)
    } catch {
      throw new ImportValidationError('invalid_json', 'The archive is not valid JSON.')
    }

    const archive = validateImportArchive(parsed)
    const decoded = new Map<string, Uint8Array>()

    for (const attachment of archive.attachments) {
      const bytes = await decodeAttachmentBytes(attachment, this.maxAttachmentBytes)

      if (bytes !== null) {
        decoded.set(attachment.id, bytes)
      }
    }

    return this.apply(archive, decoded, mode)
  }

  private async apply(
    archive: ChatExport,
    decoded: Map<string, Uint8Array>,
    mode: ImportMode,
  ): Promise<ImportResult> {
    const now = this.now()
    const existingModels = await this.db.getAll('models')
    const modelPlan = planModels(archive.models, existingModels)
    const conversations: Conversation[] = archive.conversations.map((conversation) => ({
      ...conversation,
      modelId: modelPlan.remapModelId(conversation.modelId),
      params: cloneParams(conversation.params),
    }))
    const messages: Message[] = archive.messages.map((message) => ({
      ...message,
      modelId: message.modelId === null ? null : modelPlan.remapModelId(message.modelId),
      parts: structuredClone(message.parts),
    }))
    const settings = remapSettings(archive.settings, modelPlan.remapModelId)
    const attachments = buildAttachments(archive, messages, now)
    const existingReferenced = new Set(
      (await this.db.getAll('attachments')).map((attachment) => attachment.sha256),
    )
    const written: string[] = []

    try {
      for (const [attachmentId, bytes] of decoded) {
        const attachment = archive.attachments.find((item) => item.id === attachmentId)

        if (attachment === undefined) {
          continue
        }

        const sha256 = attachment.sha256.toLowerCase()
        const storageKey = storageKeyFor(sha256)
        let existed = false

        try {
          await this.attachmentStore.get(storageKey)
          existed = true
        } catch {
          existed = false
        }

        await this.attachmentStore.put(bytes, sha256)

        if (!existed) {
          written.push(sha256)
        }
      }

      await runTransaction(
        this.db,
        ['conversations', 'messages', 'attachments', 'providers', 'models', 'settings'],
        'readwrite',
        async (transaction) => {
          if (mode === 'replace') {
            await Promise.all([
              transaction.objectStore('conversations').clear(),
              transaction.objectStore('messages').clear(),
              transaction.objectStore('attachments').clear(),
              transaction.objectStore('providers').clear(),
              transaction.objectStore('models').clear(),
              transaction.objectStore('settings').clear(),
            ])
          }

          const providers = transaction.objectStore('providers')
          for (const provider of archive.providers) {
            await providers.put(toProviderRecord(provider))
          }

          const models = transaction.objectStore('models')
          for (const model of modelPlan.models) {
            await models.put(model)
          }

          const conversationStore = transaction.objectStore('conversations')
          for (const conversation of conversations) {
            await conversationStore.put(toConversationRecord(conversation))
          }

          const messageStore = transaction.objectStore('messages')
          for (const message of messages) {
            await messageStore.put(message)
          }

          const attachmentStore = transaction.objectStore('attachments')
          for (const attachment of attachments) {
            await attachmentStore.put(attachment)
          }

          const settingsStore = transaction.objectStore('settings')
          const existingKeys =
            mode === 'replace' ? new Set<string>() : new Set(await settingsStore.getAllKeys())

          for (const [key, value] of Object.entries(settings)) {
            if (!existingKeys.has(key)) {
              await settingsStore.put({ key, value, updatedAt: now })
            }
          }
        },
      )
    } catch (error) {
      await this.cleanupWritten(written, existingReferenced)
      throw error
    }

    if (mode === 'replace') {
      const referenced = new Set(attachments.map((attachment) => attachment.sha256))
      await this.attachmentStore.cleanupOrphans(referenced).catch(() => undefined)
    }

    return {
      mode,
      conversations: conversations.length,
      messages: messages.length,
      attachments: attachments.length,
      providers: archive.providers.length,
      models: modelPlan.models.length,
      attachmentBytes: decoded.size,
    }
  }

  private async cleanupWritten(written: readonly string[], existing: Set<string>): Promise<void> {
    for (const sha256 of new Set(written)) {
      if (existing.has(sha256)) {
        continue
      }

      try {
        await this.attachmentStore.delete(storageKeyFor(sha256))
      } catch {
        // Best effort: the transaction already failed, and an orphaned byte
        // blob is preferable to masking the original import error.
      }
    }
  }
}
