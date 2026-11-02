package dev.chatui.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Message row from spec 9.5.
 *
 * `parts`, `usage`, and `error` are JSON strings. The composite index on
 * `(conversationId, sequence)` backs both the newest-page query and older-page
 * pagination.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "sequence"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sequence: Long,
    val role: String,
    val partsJson: String,
    val status: String,
    val providerId: String?,
    val modelId: String?,
    val modelLabel: String?,
    val usageJson: String?,
    val finishReason: String?,
    val errorJson: String?,
    val latencyMs: Long?,
    val firstTokenMs: Long?,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
)
