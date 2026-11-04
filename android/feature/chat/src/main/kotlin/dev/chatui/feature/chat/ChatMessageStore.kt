package dev.chatui.feature.chat

import dev.chatui.core.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Message persistence surface used by the chat runner.
 *
 * The production implementation wraps `MessageRepository`; tests use an
 * in-memory fake so the runner can be exercised without Room.
 */
interface ChatMessageStore {
    fun observeConversation(conversationId: String): Flow<List<Message>>

    /** Every non-deleted message in ascending sequence order. */
    suspend fun all(conversationId: String): List<Message>

    /** Newest page, oldest message first. */
    suspend fun latest(conversationId: String, limit: Int = 50): List<Message>

    suspend fun appendMessage(message: Message): Message

    suspend fun updateMessage(message: Message): Message

    suspend fun deleteMessage(id: String)
}
