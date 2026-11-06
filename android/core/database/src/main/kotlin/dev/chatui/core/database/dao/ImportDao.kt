package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.chatui.core.database.entity.MessageEntity

/**
 * Cross-table operations used only by an explicit replace import.
 *
 * Keeping these queries in a dedicated DAO avoids widening the normal
 * conversation/message/provider/model DAOs with destructive bulk operations.
 * Secrets, backup configuration, and the sync queue are intentionally absent.
 */
@Dao
abstract class ImportDao {
    @Query("DELETE FROM conversations")
    abstract suspend fun clearConversations()

    @Query("DELETE FROM messages")
    abstract suspend fun clearMessages()

    @Query("DELETE FROM messages_fts")
    abstract suspend fun clearFts()

    @Query("DELETE FROM attachments")
    abstract suspend fun clearAttachments()

    @Query("DELETE FROM providers")
    abstract suspend fun clearProviders()

    @Query("DELETE FROM models")
    abstract suspend fun clearModels()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages_fts WHERE messageId = :messageId")
    abstract suspend fun deleteFts(messageId: String)

    @Query("INSERT INTO messages_fts(messageId, text) VALUES (:messageId, :text)")
    abstract suspend fun insertFts(messageId: String, text: String)

    /**
     * Insert or replace a message and its search text atomically.
     *
     * Import uses this instead of the normal append path because the archive
     * already carries conversation metadata and message order. Replacing an
     * existing message id must not leave a stale FTS row behind.
     */
    @Transaction
    open suspend fun importMessage(message: MessageEntity, searchText: String) {
        insertMessage(message)
        deleteFts(message.id)
        insertFts(message.id, searchText)
    }
}
