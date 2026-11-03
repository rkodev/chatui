package dev.chatui.feature.conversations

import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

val TEST_NOW: Instant = Instant.parse("2026-11-03T12:00:00Z")

fun testChatParams(): ChatParams = ChatParams(
    temperature = null,
    topP = null,
    maxOutputTokens = null,
    stop = emptyList(),
    reasoningEffort = null,
    streaming = true,
    extra = JsonObject(emptyMap()),
)

fun testProvider(
    id: String = "provider-1",
    label: String = "OpenRouter",
    enabled: Boolean = true,
): Provider = Provider(
    id = id,
    presetId = "openrouter",
    label = label,
    kind = ProviderKind.OPENAI_COMPATIBLE,
    baseUrl = "https://openrouter.ai/api/v1",
    authType = ProviderAuthType.BEARER,
    secretId = "secret-1",
    headers = emptyMap(),
    enabled = enabled,
    browserCors = BrowserCors.SUPPORTED,
    metadata = null,
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
)

fun testModel(
    id: String = "model-1",
    providerId: String = "provider-1",
    remoteId: String = "openrouter/auto",
    label: String = remoteId,
    hidden: Boolean = false,
): Model = Model(
    id = id,
    providerId = providerId,
    remoteId = remoteId,
    label = label,
    contextWindow = 128_000,
    maxOutputTokens = 4_096,
    capabilities = listOf(ModelCapability.TEXT),
    pricing = null,
    canonicalSlug = null,
    inputModalities = listOf(InputModality.TEXT),
    outputModalities = listOf(OutputModality.TEXT),
    supportedParameters = emptyList(),
    metadata = null,
    hidden = hidden,
    pinned = false,
    source = ModelSource.DISCOVERED,
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
)

fun testConversation(
    id: String = "conversation-1",
    title: String = "Conversation",
    lastMessageAt: Instant = TEST_NOW,
    archived: Boolean = false,
): Conversation = Conversation(
    id = id,
    title = title,
    systemPrompt = "",
    providerId = "provider-1",
    modelId = "model-1",
    modelLabel = "Model One",
    params = testChatParams(),
    pinned = false,
    archived = archived,
    temporary = false,
    createdAt = lastMessageAt,
    updatedAt = lastMessageAt,
    lastMessageAt = lastMessageAt,
    messageCount = 0,
    lastError = null,
    sync = null,
)

/**
 * In-memory conversation facade for ViewModel and composable tests.
 *
 * It mirrors the production semantics closely enough to assert grouping,
 * creation, rename, delete confirmation, and last-active tracking.
 */
class FakeConversationService : ConversationService {
    val conversationsFlow = MutableStateFlow<List<Conversation>>(emptyList())
    val activeFlow = MutableStateFlow<String?>(null)
    var createResult: CreateConversationResult = CreateConversationResult.NotConfigured(
        "No provider is configured.",
    )
    var renameError: Throwable? = null
    var deleteError: Throwable? = null
    var lastRenamed: Pair<String, String>? = null
    var lastDeleted: String? = null
    var lastActiveSet: String? = null

    override fun observeConversations(): Flow<List<Conversation>> = conversationsFlow

    override fun observeLastActiveConversationId(): Flow<String?> = activeFlow

    override suspend fun createConversation(title: String?): CreateConversationResult {
        val result = createResult
        if (result is CreateConversationResult.Created) {
            conversationsFlow.value = conversationsFlow.value + result.conversation
            activeFlow.value = result.conversation.id
        }
        return result
    }

    override suspend fun renameConversation(id: String, title: String): Conversation {
        renameError?.let { throw it }
        lastRenamed = id to title
        val existing = conversationsFlow.value.first { it.id == id }
        val updated = existing.copy(title = title)
        conversationsFlow.value = conversationsFlow.value.map {
            if (it.id == id) updated else it
        }
        return updated
    }

    override suspend fun deleteConversation(id: String) {
        deleteError?.let { throw it }
        lastDeleted = id
        conversationsFlow.value = conversationsFlow.value.filterNot { it.id == id }
        if (activeFlow.value == id) {
            activeFlow.value = null
        }
    }

    override suspend fun setLastActiveConversation(id: String?) {
        lastActiveSet = id
        activeFlow.value = id
    }

    override suspend fun getLastActiveConversation(): Conversation? =
        activeFlow.value?.let { id -> conversationsFlow.value.firstOrNull { it.id == id } }

    override suspend fun getConversation(id: String): Conversation? =
        conversationsFlow.value.firstOrNull { it.id == id }
}
