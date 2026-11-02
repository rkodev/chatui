package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chatui.core.database.entity.AttachmentEntity

/** Attachment metadata operations from spec 9.5. */
@Dao
abstract class AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE id = :id")
    abstract suspend fun getById(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY createdAt")
    abstract suspend fun forMessage(messageId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE sha256 = :sha256")
    abstract suspend fun findBySha256(sha256: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE messageId IS NULL")
    abstract suspend fun orphans(): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    abstract suspend fun deleteForMessage(messageId: String)
}
