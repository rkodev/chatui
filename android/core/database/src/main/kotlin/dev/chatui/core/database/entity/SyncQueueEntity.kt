package dev.chatui.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Future sync queue placeholder. M0 only needs a durable queue with retry
 * bookkeeping; M5 owns the final sync protocol.
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index("conversationId"),
        Index("attempts"),
    ],
)
data class SyncQueueEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val operation: String,
    val payloadJson: String,
    val attempts: Int,
    val lastAttemptAt: String?,
    val nextAttemptAt: String?,
    val lastError: String?,
    val createdAt: String,
    val updatedAt: String,
)
