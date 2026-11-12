package dev.chatui.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Deletion tombstone from spec 13.5.
 *
 * Tombstones are retained until the remote manifest confirms the deletion.
 * [messageId] is null for a conversation-level deletion.
 */
@Entity(
    tableName = "tombstones",
    indices = [
        Index("conversationId"),
        Index("messageId"),
    ],
)
data class TombstoneEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val messageId: String?,
    val deletedAt: String,
    val deviceId: String,
)
