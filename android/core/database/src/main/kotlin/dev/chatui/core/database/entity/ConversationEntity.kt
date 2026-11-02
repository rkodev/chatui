package dev.chatui.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Conversation row from spec 9.5.
 *
 * Structured values (`params`, `lastError`) are stored as JSON strings so the
 * domain model can evolve without a Room migration. Timestamps are ISO 8601
 * UTC strings, matching the shared contract representation.
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index("updatedAt"),
        Index("lastMessageAt"),
        Index("archived"),
        Index("pinned"),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val systemPrompt: String,
    val providerId: String,
    val modelId: String,
    val modelLabel: String,
    val paramsJson: String,
    val pinned: Boolean,
    val archived: Boolean,
    val temporary: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val lastMessageAt: String,
    val messageCount: Int,
    val lastErrorJson: String?,
    val syncState: String?,
)
