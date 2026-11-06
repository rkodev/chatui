package dev.chatui.core.database.repository

import dev.chatui.core.database.dao.AttachmentDao
import dev.chatui.core.model.Attachment

/**
 * Domain repository for attachment metadata.
 *
 * Binary content lives in [dev.chatui.core.database.attachment.AttachmentStorage];
 * this repository only owns the Room rows.
 */
class AttachmentRepository(
    private val dao: AttachmentDao,
) {
    suspend fun get(id: String): Attachment? = dao.getById(id)?.toDomain()

    suspend fun forMessage(messageId: String): List<Attachment> =
        dao.forMessage(messageId).map { it.toDomain() }

    suspend fun all(): List<Attachment> = dao.all().map { it.toDomain() }

    suspend fun orphans(): List<Attachment> = dao.orphans().map { it.toDomain() }

    suspend fun findBySha256(sha256: String): List<Attachment> =
        dao.findBySha256(sha256).map { it.toDomain() }

    suspend fun upsert(attachment: Attachment) {
        dao.upsert(attachment.toEntity())
    }

    /** Persist a batch of metadata rows in one transaction. */
    suspend fun upsertAll(attachments: List<Attachment>) {
        dao.upsertAll(attachments.map { it.toEntity() })
    }

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    suspend fun deleteForMessage(messageId: String) {
        dao.deleteForMessage(messageId)
    }

    /** Bind draft attachments to a persisted message atomically. */
    suspend fun attachToMessage(ids: List<String>, messageId: String) {
        dao.attachToMessage(ids, messageId)
    }
}
