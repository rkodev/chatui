package dev.chatui.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Attachment metadata row from spec 9.5. Binary content lives in
 * `filesDir/attachments/<sha256>`; only metadata is stored here.
 */
@Entity(
    tableName = "attachments",
    indices = [
        Index("sha256"),
        Index("messageId"),
    ],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String?,
    val kind: String,
    val mimeType: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val storageKey: String,
    val width: Int?,
    val height: Int?,
    val createdAt: String,
)
