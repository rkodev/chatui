package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.chatui.core.database.entity.ConversationEntity
import dev.chatui.core.database.entity.MessageEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Message reads and writes from spec 9.5.
 *
 * Reads default to [DEFAULT_PAGE_SIZE] (50) messages. The newest page is
 * ordered newest-first; older pages use the oldest sequence from the current
 * page as an exclusive cursor.
 *
 * FTS synchronization is performed by the write methods in this DAO so a
 * message row and its search row cannot diverge. Callers pass the plain text
 * extracted from `partsJson`.
 */
@Dao
abstract class MessageDao {
    @Query(
        "SELECT * FROM messages " +
            "WHERE conversationId = :conversationId AND deletedAt IS NULL " +
            "ORDER BY sequence DESC LIMIT :limit",
    )
    abstract suspend fun latestPage(conversationId: String, limit: Int): List<MessageEntity>

    /** Newest page, defaulting to 50 messages. */
    suspend fun latest(conversationId: String): List<MessageEntity> =
        latestPage(conversationId, DEFAULT_PAGE_SIZE)

    @Query(
        "SELECT * FROM messages " +
            "WHERE conversationId = :conversationId AND deletedAt IS NULL " +
            "AND sequence < :beforeSequence " +
            "ORDER BY sequence DESC LIMIT :limit",
    )
    abstract suspend fun olderPage(
        conversationId: String,
        beforeSequence: Long,
        limit: Int,
    ): List<MessageEntity>

    /** Older page before [beforeSequence], defaulting to 50 messages. */
    suspend fun older(conversationId: String, beforeSequence: Long): List<MessageEntity> =
        olderPage(conversationId, beforeSequence, DEFAULT_PAGE_SIZE)

    @Query(
        "SELECT * FROM messages " +
            "WHERE conversationId = :conversationId AND deletedAt IS NULL " +
            "ORDER BY sequence ASC",
    )
    abstract fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun getById(id: String): MessageEntity?

    @Query(
        "SELECT COUNT(*) FROM messages " +
            "WHERE conversationId = :conversationId AND deletedAt IS NULL",
    )
    abstract suspend fun count(conversationId: String): Int

    @Query("SELECT MAX(sequence) FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun maxSequence(conversationId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMessage(message: MessageEntity)

    @Query("INSERT INTO messages_fts(messageId, text) VALUES (:messageId, :text)")
    abstract suspend fun insertFts(messageId: String, text: String)

    @Update
    abstract suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages_fts WHERE messageId = :messageId")
    abstract suspend fun deleteFts(messageId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    abstract suspend fun deleteMessage(messageId: String)

    /** Insert a message and its search text atomically. */
    @Transaction
    open suspend fun insert(message: MessageEntity, searchText: String) {
        insertMessage(message)
        insertFts(message.id, searchText)
    }

    /** Update a message and replace its search text atomically. */
    @Transaction
    open suspend fun update(message: MessageEntity, searchText: String) {
        updateMessage(message)
        deleteFts(message.id)
        insertFts(message.id, searchText)
    }

    /** Delete a message and its search row atomically. */
    @Transaction
    open suspend fun delete(messageId: String) {
        deleteFts(messageId)
        deleteMessage(messageId)
    }

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract suspend fun getConversation(id: String): ConversationEntity?

    @Query(
        "UPDATE conversations SET lastMessageAt = :lastMessageAt, " +
            "messageCount = :messageCount, updatedAt = :updatedAt WHERE id = :id",
    )
    abstract suspend fun updateConversationMessageMetadata(
        id: String,
        lastMessageAt: String,
        messageCount: Int,
        updatedAt: String,
    )

    /**
     * Insert or update a message, synchronize its FTS row, and update the
     * conversation's `lastMessageAt`, `messageCount`, and `updatedAt` in one
     * transaction.
     *
     * Returns the updated conversation, or `null` when the conversation does
     * not exist. Re-appending an existing message id does not increment the
     * count.
     */
    @Transaction
    open suspend fun appendWithConversation(
        message: MessageEntity,
        searchText: String,
        updatedAt: String,
    ): ConversationEntity? {
        val existingMessage = getById(message.id)
        val conversation = getConversation(message.conversationId) ?: return null

        if (existingMessage == null) {
            insertMessage(message)
            insertFts(message.id, searchText)
        } else {
            updateMessage(message)
            deleteFts(message.id)
            insertFts(message.id, searchText)
        }

        val lastMessageAt = maxOf(
            Instant.parse(conversation.lastMessageAt),
            Instant.parse(message.createdAt),
        ).toString()
        val messageCount = if (existingMessage == null) {
            conversation.messageCount + 1
        } else {
            conversation.messageCount
        }

        updateConversationMessageMetadata(
            id = conversation.id,
            lastMessageAt = lastMessageAt,
            messageCount = messageCount,
            updatedAt = updatedAt,
        )

        return conversation.copy(
            lastMessageAt = lastMessageAt,
            messageCount = messageCount,
            updatedAt = updatedAt,
        )
    }

    /** Messages left in the `streaming` state, oldest first. */
    @Query(
        "SELECT * FROM messages WHERE status = 'streaming' AND deletedAt IS NULL " +
            "ORDER BY createdAt",
    )
    abstract suspend fun streaming(): List<MessageEntity>

    /** Mark every `streaming` message as `cancelled`; returns the affected row count. */
    @Query(
        "UPDATE messages SET status = 'cancelled', updatedAt = :updatedAt " +
            "WHERE status = 'streaming'",
    )
    abstract suspend fun cancelStreaming(updatedAt: String): Int

    @Query("UPDATE messages SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateStatus(id: String, status: String, updatedAt: String)

    @Query(
        "SELECT messages.* FROM messages " +
            "INNER JOIN messages_fts ON messages.id = messages_fts.messageId " +
            "WHERE messages_fts MATCH :query AND messages.deletedAt IS NULL " +
            "ORDER BY messages.sequence DESC LIMIT :limit",
    )
    abstract suspend fun searchPage(query: String, limit: Int): List<MessageEntity>

    /** Full-text search, defaulting to 50 results. */
    suspend fun search(query: String): List<MessageEntity> =
        searchPage(query, DEFAULT_PAGE_SIZE)

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}
