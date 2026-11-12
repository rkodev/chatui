import { z } from 'zod'
import { nowIso, type Tombstone } from '../../domain'
import type { ExportSnapshot } from '../../storage'

export const MANIFEST_FORMAT_VERSION = 1

export interface ManifestConversationEntry {
  id: string
  storageKey: string
  revision: number
  updatedAt: string
  deletedAt: string | null
  deviceId: string
  objectKey: string
}

export interface ManifestAttachmentEntry {
  sha256: string
  size: number
  objectKey: string
  createdAt: string
}

export interface ManifestTombstone {
  conversationId: string
  deletedAt: string
  deviceId: string
}

export interface ManifestBody {
  formatVersion: typeof MANIFEST_FORMAT_VERSION
  createdAt: string
  updatedAt: string
  deviceId: string
  conversations: ManifestConversationEntry[]
  attachments: ManifestAttachmentEntry[]
  tombstones: ManifestTombstone[]
}

export interface ManifestIndexes {
  conversations: ManifestConversationEntry[]
  attachments: ManifestAttachmentEntry[]
  tombstones: ManifestTombstone[]
}

export interface BuildManifestOptions {
  deviceId: string
  tombstones?: readonly Tombstone[]
  createdAt?: string
  updatedAt?: string
}

export type ManifestErrorCode = 'unsupported-version' | 'invalid-manifest'

export class ManifestError extends Error {
  readonly code: ManifestErrorCode

  constructor(code: ManifestErrorCode, message: string) {
    super(message)
    this.name = 'ManifestError'
    this.code = code
  }
}

const isoDate = z.string().refine((value) => Number.isFinite(Date.parse(value)), {
  message: 'Expected an ISO date-time string',
})

const storageKeySchema = z.string().regex(/^[0-9a-f]{32}$/u)
const sha256Schema = z.string().regex(/^[0-9a-f]{64}$/u)

const conversationEntrySchema = z
  .object({
    id: z.string().min(1),
    storageKey: storageKeySchema,
    revision: z.number().int().min(1),
    updatedAt: isoDate,
    deletedAt: isoDate.nullable(),
    deviceId: z.string().min(1),
    objectKey: z.string().regex(/^conversations\/[0-9a-f]{32}\.enc$/u),
  })
  .strict()

const attachmentEntrySchema = z
  .object({
    sha256: sha256Schema,
    size: z.number().int().min(0),
    objectKey: z.string().regex(/^attachments\/[0-9a-f]{64}\.enc$/u),
    createdAt: isoDate,
  })
  .strict()

const tombstoneSchema = z
  .object({
    conversationId: z.string().min(1),
    deletedAt: isoDate,
    deviceId: z.string().min(1),
  })
  .strict()

const manifestSchema = z
  .object({
    formatVersion: z.literal(MANIFEST_FORMAT_VERSION),
    createdAt: isoDate,
    updatedAt: isoDate,
    deviceId: z.string().min(1),
    conversations: z.array(conversationEntrySchema),
    attachments: z.array(attachmentEntrySchema),
    tombstones: z.array(tombstoneSchema),
  })
  .strict()

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function compareConversationEntries(
  left: ManifestConversationEntry,
  right: ManifestConversationEntry,
): number {
  return (
    left.revision - right.revision ||
    left.updatedAt.localeCompare(right.updatedAt) ||
    left.deviceId.localeCompare(right.deviceId) ||
    left.id.localeCompare(right.id)
  )
}

function compareTombstones(left: ManifestTombstone, right: ManifestTombstone): number {
  return (
    left.deletedAt.localeCompare(right.deletedAt) ||
    left.deviceId.localeCompare(right.deviceId) ||
    left.conversationId.localeCompare(right.conversationId)
  )
}

/**
 * Build the plaintext manifest body from an export snapshot.
 *
 * Only index metadata is included: conversation identity, storage key,
 * revision, timestamps, attachment hashes, and tombstones. Message bodies and
 * provider secrets never enter the manifest.
 */
export function buildManifest(
  snapshot: ExportSnapshot,
  options: BuildManifestOptions,
): ManifestBody {
  if (options.deviceId.trim() === '') {
    throw new Error('A non-empty deviceId is required to build a manifest')
  }

  const createdAt = options.createdAt ?? nowIso()
  const updatedAt = options.updatedAt ?? createdAt

  const conversations = snapshot.conversations
    .map((conversation): ManifestConversationEntry => {
      if (!/^[0-9a-f]{32}$/u.test(conversation.storageKey)) {
        throw new Error(`Conversation ${conversation.id} has an invalid storageKey`)
      }
      if (!Number.isInteger(conversation.revision) || conversation.revision < 1) {
        throw new Error(`Conversation ${conversation.id} has an invalid revision`)
      }

      return {
        id: conversation.id,
        storageKey: conversation.storageKey,
        revision: conversation.revision,
        updatedAt: conversation.updatedAt,
        deletedAt: null,
        deviceId: options.deviceId,
        objectKey: `conversations/${conversation.storageKey}.enc`,
      }
    })
    .sort((left, right) => left.id.localeCompare(right.id))

  const attachments = snapshot.attachments
    .map((attachment): ManifestAttachmentEntry => ({
      sha256: attachment.sha256,
      size: attachment.size,
      objectKey: `attachments/${attachment.sha256}.enc`,
      createdAt: attachment.createdAt,
    }))
    .sort((left, right) => left.sha256.localeCompare(right.sha256))

  const tombstones = (options.tombstones ?? [])
    .map((tombstone): ManifestTombstone => ({
      conversationId: tombstone.conversationId,
      deletedAt: tombstone.deletedAt,
      deviceId: tombstone.deviceId,
    }))
    .sort(compareTombstones)

  return {
    formatVersion: MANIFEST_FORMAT_VERSION,
    createdAt,
    updatedAt,
    deviceId: options.deviceId,
    conversations,
    attachments,
    tombstones,
  }
}

/**
 * Parse and validate a manifest body. Unknown format versions are rejected
 * before schema validation so callers can distinguish them from malformed
 * manifests.
 */
export function parseManifest(bytes: Uint8Array | string): ManifestBody {
  let text: string
  if (typeof bytes === 'string') {
    text = bytes
  } else {
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    } catch {
      throw new ManifestError('invalid-manifest', 'Manifest is not valid UTF-8')
    }
  }

  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw new ManifestError('invalid-manifest', 'Manifest is not valid JSON')
  }

  if (!isRecord(value)) {
    throw new ManifestError('invalid-manifest', 'Manifest must be a JSON object')
  }

  const formatVersion = value.formatVersion
  if (typeof formatVersion !== 'number' || !Number.isInteger(formatVersion)) {
    throw new ManifestError('invalid-manifest', 'Manifest formatVersion must be an integer')
  }
  if (formatVersion !== MANIFEST_FORMAT_VERSION) {
    throw new ManifestError(
      'unsupported-version',
      `Unsupported manifest format version: ${formatVersion}`,
    )
  }

  const parsed = manifestSchema.safeParse(value)
  if (!parsed.success) {
    throw new ManifestError('invalid-manifest', parsed.error.message)
  }

  return parsed.data
}

/**
 * Merge two manifest indexes by conversation revision.
 *
 * This is intentionally only the index merge used by the sync engine. It does
 * not upload, download, or rewrite conversation payloads. A tombstone wins over
 * a conversation update at or before the deletion timestamp.
 */
export function mergeManifestIndexes(local: ManifestBody, remote: ManifestBody): ManifestIndexes {
  const conversations = new Map<string, ManifestConversationEntry>()

  for (const entry of [...local.conversations, ...remote.conversations]) {
    const existing = conversations.get(entry.id)
    if (!existing || compareConversationEntries(entry, existing) > 0) {
      conversations.set(entry.id, entry)
    }
  }

  const attachments = new Map<string, ManifestAttachmentEntry>()
  for (const entry of [...local.attachments, ...remote.attachments]) {
    const existing = attachments.get(entry.sha256)
    if (!existing || entry.createdAt > existing.createdAt) {
      attachments.set(entry.sha256, entry)
    }
  }

  const tombstones = new Map<string, ManifestTombstone>()
  for (const entry of [...local.tombstones, ...remote.tombstones]) {
    const existing = tombstones.get(entry.conversationId)
    if (!existing || compareTombstones(entry, existing) > 0) {
      tombstones.set(entry.conversationId, entry)
    }
  }

  const mergedTombstones = [...tombstones.values()].sort(compareTombstones)
  const deletedAtByConversation = new Map(
    mergedTombstones.map((tombstone) => [tombstone.conversationId, tombstone.deletedAt]),
  )

  const mergedConversations = [...conversations.values()]
    .filter((entry) => {
      const deletedAt = deletedAtByConversation.get(entry.id)
      return deletedAt === undefined || deletedAt < entry.updatedAt
    })
    .sort((left, right) => left.id.localeCompare(right.id))

  return {
    conversations: mergedConversations,
    attachments: [...attachments.values()].sort((left, right) =>
      left.sha256.localeCompare(right.sha256),
    ),
    tombstones: mergedTombstones,
  }
}
