package dev.chatui.feature.chat

import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [ChatConversationSource] backed by the application conversation repository. */
class RepositoryChatConversationSource(
    private val repository: ConversationRepository,
) : ChatConversationSource {
    override suspend fun getConversation(id: String): Conversation? = repository.get(id)

    override fun observeConversation(id: String): Flow<Conversation?> =
        repository.observeAll().map { conversations ->
            conversations.firstOrNull { it.id == id }
        }
}
