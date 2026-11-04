package dev.chatui.feature.chat

import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.model.Conversation

/** [ChatConversationSource] backed by the application conversation repository. */
class RepositoryChatConversationSource(
    private val repository: ConversationRepository,
) : ChatConversationSource {
    override suspend fun getConversation(id: String): Conversation? = repository.get(id)
}
