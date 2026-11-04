package dev.chatui.feature.chat

import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** [ChatMessageStore] backed by the application message repository. */
class RepositoryChatMessageStore(
    private val repository: MessageRepository,
) : ChatMessageStore {
    override fun observeConversation(conversationId: String): Flow<List<Message>> =
        repository.observeConversation(conversationId)

    override suspend fun all(conversationId: String): List<Message> =
        repository.observeConversation(conversationId).first()

    override suspend fun latest(conversationId: String, limit: Int): List<Message> =
        repository.latest(conversationId, limit)

    override suspend fun appendMessage(message: Message): Message =
        repository.appendMessage(message).message

    override suspend fun updateMessage(message: Message): Message =
        repository.updateMessage(message)

    override suspend fun deleteMessage(id: String) {
        repository.deleteMessage(id)
    }
}
