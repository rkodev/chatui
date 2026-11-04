import type { IDBPDatabase } from 'idb'
import { newId, nowIso, type Attachment, type Message } from '../../domain'
import { bytesToBase64 } from '../../platform/crypto'
import type { ResolvedAttachment } from '../../providers/openaiCompatible'
import { runTransaction, type ChatUiDB } from '../../storage'
import { AttachmentStore, storageKeyFor } from '../../storage/AttachmentStore'
import { validateAttachmentFile, type AttachmentValidationError } from './attachmentRules'
import { processImage, type ImageProcessor } from './imageProcessing'

export interface AddAttachmentsResult {
  attachments: Attachment[]
  errors: Array<AttachmentValidationError & { fileName: string }>
}

export interface AttachmentServiceOptions {
  db: IDBPDatabase<ChatUiDB>
  store: AttachmentStore
  now?: () => string
  newId?: () => string
  imageProcessor?: ImageProcessor
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

function isTextMimeType(mimeType: string): boolean {
  return (
    /^text\//i.test(mimeType) || mimeType === 'application/json' || mimeType === 'application/xml'
  )
}

/**
 * Validate, process, hash, store, and persist attachment metadata.
 *
 * Bytes are written before metadata. Metadata for one batch commits in a single
 * IndexedDB transaction, so a failed write cannot leave a partially attached
 * message. Bytes that never gained metadata are removed by `cleanupOrphans`.
 */
export class AttachmentService {
  private readonly db: IDBPDatabase<ChatUiDB>
  private readonly store: AttachmentStore
  private readonly now: () => string
  private readonly generateId: () => string
  private readonly imageProcessor: ImageProcessor

  constructor(options: AttachmentServiceOptions) {
    this.db = options.db
    this.store = options.store
    this.now = options.now ?? nowIso
    this.generateId = options.newId ?? newId
    this.imageProcessor = options.imageProcessor ?? processImage
  }

  async addFiles(
    files: readonly File[],
    options: { messageId?: string | null } = {},
  ): Promise<AddAttachmentsResult> {
    const attachments: Attachment[] = []
    const errors: AddAttachmentsResult['errors'] = []

    for (const file of files) {
      const validation = validateAttachmentFile(file)

      if (!validation.ok) {
        errors.push({ ...validation.error, fileName: file.name })
        continue
      }

      try {
        const original = new Uint8Array(await file.arrayBuffer())
        let bytes: Uint8Array = original
        let mimeType = validation.value.mimeType
        let width: number | null = null
        let height: number | null = null

        if (validation.value.kind === 'image') {
          const processed = await this.imageProcessor({ bytes: original, mimeType })

          if (processed.width > 0 && processed.height > 0) {
            bytes = processed.bytes
            mimeType = processed.mimeType
            width = processed.width
            height = processed.height
          }
        }

        const sha256 = await sha256Hex(bytes)
        const storageKey = await this.store.put(bytes, sha256)

        attachments.push({
          id: this.generateId(),
          messageId: options.messageId ?? null,
          kind: validation.value.kind,
          mimeType,
          name: validation.value.name,
          size: bytes.byteLength,
          sha256,
          storageKey,
          width,
          height,
          createdAt: this.now(),
        })
      } catch (error) {
        errors.push({
          fileName: file.name,
          code: 'unsupported_type',
          message: error instanceof Error ? error.message : `${file.name} could not be processed.`,
        })
      }
    }

    if (attachments.length > 0) {
      await this.saveMany(attachments)
    }

    return { attachments, errors }
  }

  /** Persist a batch of metadata rows in one transaction. */
  async saveMany(attachments: readonly Attachment[]): Promise<void> {
    await runTransaction(this.db, ['attachments'], 'readwrite', async (transaction) => {
      const store = transaction.objectStore('attachments')

      for (const attachment of attachments) {
        await store.put(attachment)
      }
    })
  }

  async get(id: string): Promise<Attachment | null> {
    return (await this.db.get('attachments', id)) ?? null
  }

  async listAll(): Promise<Attachment[]> {
    return this.db.getAll('attachments')
  }

  async listForMessage(messageId: string): Promise<Attachment[]> {
    return this.db.getAllFromIndex('attachments', 'messageId', messageId)
  }

  /**
   * Bind draft attachments to a persisted message.
   *
   * Only the attachments in this message are updated; already-bound rows with
   * the same ids are idempotent.
   */
  async attachToMessage(attachmentIds: readonly string[], messageId: string): Promise<void> {
    if (attachmentIds.length === 0) {
      return
    }

    await runTransaction(this.db, ['attachments'], 'readwrite', async (transaction) => {
      const store = transaction.objectStore('attachments')

      for (const id of attachmentIds) {
        const attachment = await store.get(id)

        if (attachment === undefined) {
          throw new Error(`Attachment ${id} was not found.`)
        }

        await store.put({ ...attachment, messageId })
      }
    })
  }

  /**
   * Remove one attachment and its bytes when no other metadata row shares the
   * same content hash. Draft removals and message edits use this path.
   */
  async remove(id: string): Promise<void> {
    const attachment = await this.appGet(id)

    if (attachment === null) {
      return
    }

    await this.db.delete('attachments', id)
    await this.deleteBytesIfUnreferenced(attachment.sha256)
  }

  /** Remove every attachment bound to the given messages. */
  async deleteForMessages(messageIds: readonly string[]): Promise<void> {
    if (messageIds.length === 0) {
      return
    }

    const allowed = new Set(messageIds)
    const all = await this.listAll()
    const doomed = all.filter(
      (attachment) => attachment.messageId !== null && allowed.has(attachment.messageId),
    )

    for (const attachment of doomed) {
      await this.remove(attachment.id)
    }
  }

  /**
   * Resolve bytes for every image/file part in a request history.
   *
   * Missing metadata or missing bytes throw instead of sending a malformed
   * provider request.
   */
  async resolveForMessages(
    messages: readonly Message[],
  ): Promise<Record<string, ResolvedAttachment>> {
    const ids = new Set<string>()

    for (const message of messages) {
      for (const part of message.parts) {
        if (part.type === 'image' || part.type === 'file') {
          ids.add(part.attachmentId)
        }
      }
    }

    const resolved: Record<string, ResolvedAttachment> = {}

    for (const id of ids) {
      const attachment = await this.appGet(id)

      if (attachment === null) {
        throw new Error(`Attachment ${id} is missing from local storage.`)
      }

      const bytes = await this.store.get(attachment.storageKey)
      resolved[id] = {
        mimeType: attachment.mimeType,
        name: attachment.name,
        dataBase64: bytesToBase64(bytes),
      }
    }

    return resolved
  }

  /** Data URL for local thumbnail rendering. Never fetches a provider URL. */
  async getDataUrl(id: string): Promise<string | null> {
    const attachment = await this.appGet(id)

    if (attachment === null || attachment.kind !== 'image') {
      return null
    }

    const bytes = await this.store.get(attachment.storageKey)
    return `data:${attachment.mimeType};base64,${bytesToBase64(bytes)}`
  }

  /**
   * Remove metadata that no longer belongs to a message and bytes with no
   * metadata row. Draft rows (`messageId: null`) are treated as orphans on
   * startup because drafts are not restored across reloads.
   */
  async cleanupOrphans(): Promise<string[]> {
    const messages = await this.db.getAll('messages')
    const messageIds = new Set(messages.map((message) => message.id))
    const attachments = await this.listAll()
    const stale = attachments.filter(
      (attachment) => attachment.messageId === null || !messageIds.has(attachment.messageId),
    )

    if (stale.length > 0) {
      await runTransaction(this.db, ['attachments'], 'readwrite', async (transaction) => {
        const store = transaction.objectStore('attachments')

        for (const attachment of stale) {
          await store.delete(attachment.id)
        }
      })
    }

    const remaining = stale.length === 0 ? attachments : await this.listAll()
    const referenced = new Set(remaining.map((attachment) => attachment.sha256))
    return this.store.cleanupOrphans(referenced)
  }

  private async appGet(id: string): Promise<Attachment | null> {
    return (await this.db.get('attachments', id)) ?? null
  }

  private async deleteBytesIfUnreferenced(sha256: string): Promise<void> {
    const remaining = await this.db.getAllFromIndex('attachments', 'sha256', sha256)

    if (remaining.length > 0) {
      return
    }

    try {
      await this.store.delete(storageKeyFor(sha256))
    } catch {
      // Bytes may have already been removed by a previous cleanup.
    }
  }
}

/** True when the attachment is a text file that serializes as a text header. */
export function isTextAttachment(attachment: Attachment): boolean {
  return attachment.kind === 'file' && isTextMimeType(attachment.mimeType)
}
