package dev.chatui.core.database.repository

import dev.chatui.core.database.dao.MessageDao
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageStatus
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Result of an atomic message append. */
data class AppendMessageResult(
    val message: Message,
    val conversation: Conversation,
)

/**
 * Domain repository for messages.
 *
 * Page reads return messages in ascending sequence order, which is the order
 * the chat UI renders. The DAO returns newest-first pages; this repository
 * reverses them at the boundary.
 */
class MessageRepository(
    private val dao: MessageDao,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun get(id: String): Message? = dao.getById(id)?.toDomain()

    /** Newest page, oldest message first. */
    suspend fun latest(
        conversationId: String,
        limit: Int = MessageDao.DEFAULT_PAGE_SIZE,
    ): List<Message> = dao.latestPage(conversationId, limit)
        .map { it.toDomain() }
        .asReversed()

    /** Older page before [beforeSequence], oldest message first. */
    suspend fun older(
        conversationId: String,
        beforeSequence: Long,
        limit: Int = MessageDao.DEFAULT_PAGE_SIZE,
    ): List<Message> = dao.olderPage(conversationId, beforeSequence, limit)
        .map { it.toDomain() }
        .asReversed()

    fun observeConversation(conversationId: String): Flow<List<Message>> =
        dao.observeConversation(conversationId)
            .map { messages -> messages.map { it.toDomain() } }

    suspend fun count(conversationId: String): Int = dao.count(conversationId)

    /**
     * Insert or update a message, synchronize its FTS row, and update the
     * conversation's `lastMessageAt`, `messageCount`, and `updatedAt` in one
     * transaction.
     *
     * @throws IllegalArgumentException when the conversation does not exist.
     */
    suspend fun appendMessage(message: Message): AppendMessageResult {
        val conversation = dao.appendWithConversation(
            message = message.toEntity(),
            searchText = searchableText(message.parts),
            updatedAt = clock.instant().toString(),
        ) ?: throw IllegalArgumentException(
            "Conversation ${message.conversationId} not found",
        )

        return AppendMessageResult(
            message = message,
            conversation = conversation.toDomain(),
        )
    }

    /** Update a message and replace its FTS text. */
    suspend fun updateMessage(message: Message): Message {
        dao.update(message.toEntity(), searchableText(message.parts))
        return message
    }

    suspend fun deleteMessage(id: String) = dao.delete(id)

    suspend fun search(
        query: String,
        limit: Int = MessageDao.DEFAULT_PAGE_SIZE,
    ): List<Message> = dao.searchPage(query, limit).map { it.toDomain() }

    /** Messages left in the `streaming` state, oldest first. */
    suspend fun streamingMessages(): List<Message> = dao.streaming().map { it.toDomain() }

    /**
     * Mark every `streaming` message as `cancelled`, preserving partial
     * content. Returns the number of rows updated.
     */
    suspend fun markStreamingCancelled(
        updatedAt: Instant = clock.instant(),
    ): Int = dao.cancelStreaming(updatedAt.toString())

    /** Mark one message as cancelled, preserving its content. */
    suspend fun markCancelled(
        id: String,
        updatedAt: Instant = clock.instant(),
    ) = dao.updateStatus(
        id = id,
        status = MessageStatus.CANCELLED.toStorageValue(),
        updatedAt = updatedAt.toString(),
    )
}
