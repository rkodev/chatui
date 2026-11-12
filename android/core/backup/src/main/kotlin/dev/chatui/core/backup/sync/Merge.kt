package dev.chatui.core.backup.sync

import dev.chatui.core.backup.BackupManifestBody
import dev.chatui.core.backup.ConversationPayload
import dev.chatui.core.backup.ConversationPayloadAttachment
import dev.chatui.core.backup.ConversationPayloadMessage
import dev.chatui.core.backup.ManifestAttachmentEntry
import dev.chatui.core.backup.ManifestConversationEntry
import dev.chatui.core.backup.ManifestTombstone
import dev.chatui.core.model.Tombstone

/**
 * Deterministic merge rules for the Android sync engine.
 *
 * The rules are frozen in spec section 13.5:
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

/** Options for [mergeConversation]. */
data class MergeConversationOptions(
    val localDeviceId: String,
    val remoteDeviceId: String,
    /** Conversation-level tombstones from the merged manifest. */
    val conversationTombstones: List<ManifestTombstone> = emptyList(),
    /** Message-level tombstones from the local tombstone store. */
    val messageTombstones: List<Tombstone> = emptyList(),
)

/** Result of merging one conversation payload. */
data class MergedConversation(
    val payload: ConversationPayload,
    /** True when a conversation tombstone is at or after the winning update. */
    val deleted: Boolean,
    /** True when duplicate sequences forced a deterministic renumber. */
    val renumbered: Boolean,
)

/** Result of merging two message sets. */
data class MergedMessages(
    val messages: List<ConversationPayloadMessage>,
    val renumbered: Boolean,
)

/** Result of merging two manifest indexes. */
data class MergedManifest(
    val conversations: List<ManifestConversationEntry>,
    val attachments: List<ManifestAttachmentEntry>,
    val tombstones: List<ManifestTombstone>,
)

/** Order messages by sequence, then creation time, then id. */
fun compareMessages(
    left: ConversationPayloadMessage,
    right: ConversationPayloadMessage,
): Int {
    val bySequence = left.sequence.compareTo(right.sequence)
    if (bySequence != 0) return bySequence
    val byCreatedAt = left.createdAt.compareTo(right.createdAt)
    if (byCreatedAt != 0) return byCreatedAt
    return left.id.compareTo(right.id)
}

private fun compareConversationEntries(
    left: ManifestConversationEntry,
    right: ManifestConversationEntry,
): Int {
    val byRevision = left.revision.compareTo(right.revision)
    if (byRevision != 0) return byRevision
    val byUpdatedAt = left.updatedAt.compareTo(right.updatedAt)
    if (byUpdatedAt != 0) return byUpdatedAt
    val byDevice = left.deviceId.compareTo(right.deviceId)
    if (byDevice != 0) return byDevice
    return left.id.compareTo(right.id)
}

private fun compareTombstones(left: ManifestTombstone, right: ManifestTombstone): Int {
    val byDeletedAt = left.deletedAt.compareTo(right.deletedAt)
    if (byDeletedAt != 0) return byDeletedAt
    val byDevice = left.deviceId.compareTo(right.deviceId)
    if (byDevice != 0) return byDevice
    return left.conversationId.compareTo(right.conversationId)
}

private fun latestMessageTombstones(
    tombstones: List<Tombstone>,
): Map<String, Tombstone> {
    val latest = mutableMapOf<String, Tombstone>()
    for (tombstone in tombstones) {
        val messageId = tombstone.messageId ?: continue
        val existing = latest[messageId]
        if (
            existing == null ||
            tombstone.deletedAt.isAfter(existing.deletedAt) ||
            (tombstone.deletedAt == existing.deletedAt && tombstone.deviceId > existing.deviceId)
        ) {
            latest[messageId] = tombstone
        }
    }
    return latest
}

private fun latestConversationTombstone(
    tombstones: List<ManifestTombstone>,
    conversationId: String,
): ManifestTombstone? {
    var latest: ManifestTombstone? = null
    for (tombstone in tombstones) {
        if (tombstone.conversationId != conversationId) continue
        if (
            latest == null ||
            tombstone.deletedAt > latest.deletedAt ||
            (tombstone.deletedAt == latest.deletedAt && tombstone.deviceId > latest.deviceId)
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
fun mergeMessages(
    local: List<ConversationPayloadMessage>,
    remote: List<ConversationPayloadMessage>,
    messageTombstones: List<Tombstone> = emptyList(),
): MergedMessages {
    val byId = mutableMapOf<String, ConversationPayloadMessage>()
    for (message in local + remote) {
        val existing = byId[message.id]
        if (
            existing == null ||
            message.updatedAt > existing.updatedAt ||
            (message.updatedAt == existing.updatedAt && message.id > existing.id)
        ) {
            byId[message.id] = message
        }
    }

    val tombstones = latestMessageTombstones(messageTombstones)
    val kept = byId.values.filter { message ->
        val tombstone = tombstones[message.id]
        tombstone == null ||
            java.time.Instant.parse(message.updatedAt).isAfter(tombstone.deletedAt)
    }.sortedWith(::compareMessages)

    val seen = mutableSetOf<Long>()
    var collision = false
    for (message in kept) {
        if (!seen.add(message.sequence)) {
            collision = true
        }
    }

    val messages = if (collision) {
        kept.mapIndexed { index, message -> message.copy(sequence = index.toLong()) }
    } else {
        kept
    }

    return MergedMessages(messages = messages, renumbered = collision)
}

private fun mergeAttachments(
    local: List<ConversationPayloadAttachment>,
    remote: List<ConversationPayloadAttachment>,
): List<ConversationPayloadAttachment> {
    val byId = mutableMapOf<String, ConversationPayloadAttachment>()
    for (attachment in local + remote) {
        byId.putIfAbsent(attachment.id, attachment)
    }
    return byId.values.sortedBy { it.id }
}

private fun metadataWins(
    local: ConversationPayload,
    remote: ConversationPayload,
    localDeviceId: String,
    remoteDeviceId: String,
): Boolean {
    val byUpdatedAt = remote.updatedAt.compareTo(local.updatedAt)
    if (byUpdatedAt != 0) return byUpdatedAt > 0
    return remoteDeviceId > localDeviceId
}

/**
 * Merge one conversation payload.
 *
 * Metadata is last-write-wins by `updatedAt` with `deviceId` as the tiebreaker.
 * Messages and attachments are merged as sets. A conversation tombstone at or
 * after the winning update marks the conversation deleted.
 */
fun mergeConversation(
    local: ConversationPayload,
    remote: ConversationPayload,
    options: MergeConversationOptions,
): MergedConversation {
    require(local.id == remote.id) {
        "Cannot merge conversations ${local.id} and ${remote.id}"
    }

    val remoteWins = metadataWins(
        local = local,
        remote = remote,
        localDeviceId = options.localDeviceId,
        remoteDeviceId = options.remoteDeviceId,
    )
    val winner = if (remoteWins) remote else local
    val mergedMessages = mergeMessages(
        local = local.messages,
        remote = remote.messages,
        messageTombstones = options.messageTombstones,
    )

    val mergedIds = mergedMessages.messages.map { it.id }.toSet()
    val tombstoned = options.messageTombstones
        .mapNotNull { it.messageId }
        .toSet()
    for (message in local.messages) {
        check(message.id in tombstoned || message.id in mergedIds) {
            "Merge dropped local message ${message.id}"
        }
    }

    val payload = winner.copy(
        messages = mergedMessages.messages,
        attachments = mergeAttachments(local.attachments, remote.attachments),
        revision = maxOf(local.revision, remote.revision),
    )

    val tombstone = latestConversationTombstone(
        tombstones = options.conversationTombstones,
        conversationId = local.id,
    )
    val deleted = tombstone != null && tombstone.deletedAt >= winner.updatedAt

    return MergedConversation(
        payload = payload,
        deleted = deleted,
        renumbered = mergedMessages.renumbered,
    )
}

/**
 * Merge two manifest indexes by conversation revision.
 *
 * This is only the index merge used by the sync engine. It does not upload,
 * download, or rewrite conversation payloads. A tombstone wins over a
 * conversation update at or before the deletion timestamp.
 */
fun mergeManifests(
    local: BackupManifestBody,
    remote: BackupManifestBody,
): MergedManifest {
    val conversations = mutableMapOf<String, ManifestConversationEntry>()
    for (entry in local.conversations + remote.conversations) {
        val existing = conversations[entry.id]
        if (existing == null || compareConversationEntries(entry, existing) > 0) {
            conversations[entry.id] = entry
        }
    }

    val attachments = mutableMapOf<String, ManifestAttachmentEntry>()
    for (entry in local.attachments + remote.attachments) {
        val existing = attachments[entry.sha256]
        if (existing == null || entry.createdAt > existing.createdAt) {
            attachments[entry.sha256] = entry
        }
    }

    val tombstones = mutableMapOf<String, ManifestTombstone>()
    for (entry in local.tombstones + remote.tombstones) {
        val existing = tombstones[entry.conversationId]
        if (existing == null || compareTombstones(entry, existing) > 0) {
            tombstones[entry.conversationId] = entry
        }
    }

    val mergedTombstones = tombstones.values.sortedWith(::compareTombstones)
    val deletedAtByConversation = mergedTombstones.associate {
        it.conversationId to it.deletedAt
    }

    val mergedConversations = conversations.values
        .filter { entry ->
            val deletedAt = deletedAtByConversation[entry.id]
            deletedAt == null || deletedAt < entry.updatedAt
        }
        .sortedBy { it.id }

    return MergedManifest(
        conversations = mergedConversations,
        attachments = attachments.values.sortedBy { it.sha256 },
        tombstones = mergedTombstones,
    )
}
