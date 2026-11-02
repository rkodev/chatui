package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.chatui.core.database.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/** Conversation list, update, and delete operations from spec 9.5. */
@Dao
abstract class ConversationDao {
    @Query(
        "SELECT * FROM conversations " +
            "ORDER BY pinned DESC, lastMessageAt DESC",
    )
    abstract fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        "SELECT * FROM conversations WHERE archived = 0 " +
            "ORDER BY pinned DESC, lastMessageAt DESC",
    )
    abstract fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(conversation: ConversationEntity)

    @Update
    abstract suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateTitle(id: String, title: String, updatedAt: String)

    @Query("UPDATE conversations SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun setPinned(id: String, pinned: Boolean, updatedAt: String)

    @Query("UPDATE conversations SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun setArchived(id: String, archived: Boolean, updatedAt: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query(
        "DELETE FROM attachments WHERE messageId IN " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId)",
    )
    abstract suspend fun deleteAttachmentsForConversation(conversationId: String)

    @Query(
        "DELETE FROM messages_fts WHERE messageId IN " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId)",
    )
    abstract suspend fun deleteFtsForConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun deleteMessagesForConversation(conversationId: String)

    /**
     * Hard-delete a conversation and its dependent rows. Phase 1 may hard-delete
     * immediately; the `deletedAt` column remains for future sync tombstones.
     */
    @Transaction
    open suspend fun deleteWithChildren(id: String) {
        deleteAttachmentsForConversation(id)
        deleteFtsForConversation(id)
        deleteMessagesForConversation(id)
        deleteById(id)
    }
}
