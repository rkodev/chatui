package dev.chatui

import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.Conversation
import dev.chatui.feature.conversations.ConversationService
import dev.chatui.feature.conversations.CreateConversationResult
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/** Lightweight conversation facade for shell composition tests. */
class FakeConversationService(
    conversations: List<Conversation> = emptyList(),
    private val createdConversation: Conversation? = null,
) : ConversationService {
    private val conversationsFlow = MutableStateFlow(conversations)
    private val archivedFlow = MutableStateFlow(emptyList<Conversation>())
    private val active = MutableStateFlow<String?>(null)

    override fun observeConversations(): Flow<List<Conversation>> = conversationsFlow

    override fun observeArchivedConversations(): Flow<List<Conversation>> = archivedFlow

    override fun observeLastActiveConversationId(): Flow<String?> = active

    override suspend fun createConversation(title: String?): CreateConversationResult =
        createdConversation?.let { CreateConversationResult.Created(it) }
            ?: CreateConversationResult.NotConfigured("No provider is configured.")

    override suspend fun renameConversation(id: String, title: String): Conversation =
        conversationsFlow.value.first { it.id == id }.copy(title = title)

    override suspend fun deleteConversation(id: String) {
        conversationsFlow.value = conversationsFlow.value.filterNot { it.id == id }
    }

    override suspend fun setLastActiveConversation(id: String?) {
        active.value = id
    }

    override suspend fun getLastActiveConversation(): Conversation? {
        val id = active.value ?: return null
        val conversation = conversationsFlow.value.firstOrNull { it.id == id }
        if (conversation == null) {
            active.value = null
        }
        return conversation
    }

    override suspend fun getConversation(id: String): Conversation? =
        conversationsFlow.value.firstOrNull { it.id == id }
}

/** Deterministic conversation fixture for shell tests. */
fun fakeConversation(
    id: String = "conversation-1",
    title: String = "Conversation",
): Conversation {
    val now = Instant.parse("2026-11-03T12:00:00Z")
    return Conversation(
        id = id,
        title = title,
        systemPrompt = "",
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model One",
        params = ChatParams(
            temperature = null,
            topP = null,
            maxOutputTokens = null,
            stop = emptyList(),
            reasoningEffort = null,
            streaming = true,
            extra = JsonObject(emptyMap()),
        ),
        pinned = false,
        archived = false,
        temporary = false,
        createdAt = now,
        updatedAt = now,
        lastMessageAt = now,
        messageCount = 0,
        lastError = null,
        sync = null,
    )
}
