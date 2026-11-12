/**
 * Deterministic merge rules for the web sync engine.
 *
 * The rules are frozen in spec section 13.5 and `DEC-M5-10`:
 *
 * - Messages are immutable and identified by UUID. Merge as a set, ordered by
 *   `sequence`, then `createdAt`, then `id`.
 * - Deletion tombstones win over an older update.
 * - Conversation metadata uses last-write-wins by `updatedAt`, with `deviceId`
 *   as the tiebreaker.
 * - Concurrent additions are both kept and sequences are renumbered
 *   deterministically.
 * - Local messages are never silently dropped.
 */

import type { Tombstone } from '../../domain'
import type {
  ConversationPayload,
  ConversationPayloadAttachment,
  ConversationPayloadMessage,
} from './conversationPayload'
import type { ManifestTombstone } from './manifest'

export interface MergeConversationOptions {
  localDeviceId: string
  remoteDeviceId: string
  /** Conversation-level tombstones from the merged manifest. */
  conversationTombstones?: readonly ManifestTombstone[]
  /** Message-level tombstones from the local tombstone store. */
  messageTombstones?: readonly Tombstone[]
}

export interface MergedConversation {
  payload: ConversationPayload
  /** True when a conversation tombstone is at or after the winning update. */
  deleted: boolean
  /** True when duplicate sequences forced a deterministic renumber. */
  renumbered: boolean
}

export interface MergedMessages {
  messages: ConversationPayloadMessage[]
  renumbered: boolean
}

/** Order messages by sequence, then creation time, then id. */
export function compareMessages(
  left: ConversationPayloadMessage,
  right: ConversationPayloadMessage,
): number {
  return (
    left.sequence - right.sequence ||
    left.createdAt.localeCompare(right.createdAt) ||
    left.id.localeCompare(right.id)
  )
}

function latestMessageTombstone(
  tombstones: readonly Tombstone[],
): Map<string, Tombstone> {
  const latest = new Map<string, Tombstone>()

  for (const tombstone of tombstones) {
    if (tombstone.messageId === undefined) {
      continue
    }

    const existing = latest.get(tombstone.messageId)
    if (
      existing === undefined ||
      tombstone.deletedAt > existing.deletedAt ||
      (tombstone.deletedAt === existing.deletedAt && tombstone.deviceId > existing.deviceId)
    ) {
      latest.set(tombstone.messageId, tombstone)
    }
  }

  return latest
}

function latestConversationTombstone(
  tombstones: readonly ManifestTombstone[],
  conversationId: string,
): ManifestTombstone | undefined {
  let latest: ManifestTombstone | undefined

  for (const tombstone of tombstones) {
    if (tombstone.conversationId !== conversationId) {
      continue
    }
    if (
      latest === undefined ||
      tombstone.deletedAt > latest.deletedAt ||
      (tombstone.deletedAt === latest.deletedAt && tombstone.deviceId > latest.deviceId)
    ) {
      latest = tombstone
    }
  }

  return latest
}

/**
 * Merge two message sets by UUID.
 *
 * A message id present on both sides is the same immutable message; the later
 * `updatedAt` wins so a local edit is not lost. A message tombstone removes the
 * message only when the deletion is at or after the message update. Duplicate
 * sequences are renumbered to `0..n-1` in the deterministic sort order.
 */
export function mergeMessages(
  local: readonly ConversationPayloadMessage[],
  remote: readonly ConversationPayloadMessage[],
  messageTombstones: readonly Tombstone[] = [],
): MergedMessages {
  const byId = new Map<string, ConversationPayloadMessage>()

  for (const message of [...local, ...remote]) {
    const existing = byId.get(message.id)
    if (
      existing === undefined ||
      message.updatedAt > existing.updatedAt ||
      (message.updatedAt === existing.updatedAt && message.id > existing.id)
    ) {
      byId.set(message.id, message)
    }
  }

  const tombstones = latestMessageTombstone(messageTombstones)
  const kept = [...byId.values()].filter((message) => {
    const tombstone = tombstones.get(message.id)
    return tombstone === undefined || message.updatedAt > tombstone.deletedAt
  })

  kept.sort(compareMessages)

  const seen = new Set<number>()
  let collision = false
  for (const message of kept) {
    if (seen.has(message.sequence)) {
      collision = true
    }
    seen.add(message.sequence)
  }

  const messages = collision
    ? kept.map((message, index) => ({ ...message, sequence: index }))
    : kept

  return { messages, renumbered: collision }
}

function mergeAttachments(
  local: readonly ConversationPayloadAttachment[],
  remote: readonly ConversationPayloadAttachment[],
): ConversationPayloadAttachment[] {
  const byId = new Map<string, ConversationPayloadAttachment>()

  for (const attachment of [...local, ...remote]) {
    if (!byId.has(attachment.id)) {
      byId.set(attachment.id, attachment)
    }
  }

  return [...byId.values()].sort((left, right) => left.id.localeCompare(right.id))
}

function metadataWins(
  local: ConversationPayload,
  remote: ConversationPayload,
  localDeviceId: string,
  remoteDeviceId: string,
): boolean {
  const byUpdatedAt = remote.updatedAt.localeCompare(local.updatedAt)
  if (byUpdatedAt !== 0) {
    return byUpdatedAt > 0
  }
  return remoteDeviceId > localDeviceId
}

/**
 * Merge one conversation payload.
 *
 * Metadata is last-write-wins by `updatedAt` with `deviceId` as the tiebreaker.
 * Messages and attachments are merged as sets. A conversation tombstone at or
 * after the winning update marks the conversation deleted.
 */
export function mergeConversation(
  local: ConversationPayload,
  remote: ConversationPayload,
  options: MergeConversationOptions,
): MergedConversation {
  if (local.id !== remote.id) {
    throw new Error(`Cannot merge conversations ${local.id} and ${remote.id}`)
  }

  const remoteWins = metadataWins(local, remote, options.localDeviceId, options.remoteDeviceId)
  const winner = remoteWins ? remote : local
  const { messages, renumbered } = mergeMessages(
    local.messages,
    remote.messages,
    options.messageTombstones,
  )

  const mergedIds = new Set(messages.map((message) => message.id))
  const tombstoned = new Set(
    (options.messageTombstones ?? [])
      .filter((tombstone) => tombstone.messageId !== undefined)
      .map((tombstone) => tombstone.messageId as string),
  )
  for (const message of local.messages) {
    if (!tombstoned.has(message.id) && !mergedIds.has(message.id)) {
      throw new Error(`Merge dropped local message ${message.id}`)
    }
  }

  const payload: ConversationPayload = {
    ...winner,
    messages,
    attachments: mergeAttachments(local.attachments, remote.attachments),
    revision: Math.max(local.revision, remote.revision),
  }

  const tombstone = latestConversationTombstone(
    options.conversationTombstones ?? [],
    local.id,
  )
  const deleted = tombstone !== undefined && tombstone.deletedAt >= winner.updatedAt

  return { payload, deleted, renumbered }
}
