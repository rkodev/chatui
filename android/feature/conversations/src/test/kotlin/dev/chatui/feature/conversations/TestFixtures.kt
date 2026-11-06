package dev.chatui.feature.conversations

import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Density
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Settings
import dev.chatui.core.model.Theme
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
    pinned: Boolean = false,
    archived: Boolean = false,
): Conversation = Conversation(
    id = id,
    title = title,
    systemPrompt = "",
    providerId = "provider-1",
    modelId = "model-1",
    modelLabel = "Model One",
    params = testChatParams(),
    pinned = pinned,
    archived = archived,
    temporary = false,
    createdAt = lastMessageAt,
    updatedAt = lastMessageAt,
    lastMessageAt = lastMessageAt,
    messageCount = 0,
    lastError = null,
    sync = null,
)

fun testMessage(
    id: String = "message-1",
    conversationId: String = "conversation-1",
    sequence: Long = 1,
    role: MessageRole = MessageRole.USER,
    text: String = "hello",
    createdAt: Instant = TEST_NOW,
): Message = Message(
    id = id,
    conversationId = conversationId,
    sequence = sequence,
    role = role,
    parts = listOf(ContentPart.Text(text)),
    status = MessageStatus.COMPLETE,
    providerId = null,
    modelId = null,
    modelLabel = null,
    usage = null,
    finishReason = null,
    error = null,
    latencyMs = null,
    firstTokenMs = null,
    createdAt = createdAt,
    updatedAt = createdAt,
    deletedAt = null,
)

fun testSearchResult(
    conversation: Conversation = testConversation(),
    titleMatched: Boolean = false,
    messageMatches: List<ConversationSearchMessageMatch> = emptyList(),
): ConversationSearchResult = ConversationSearchResult(
    conversation = conversation,
    titleMatched = titleMatched,
    messageMatches = messageMatches,
)

fun testSearchMessageMatch(
    messageId: String = "message-1",
    sequence: Long = 1,
    role: MessageRole = MessageRole.USER,
    snippet: String = "hello",
    createdAt: Instant = TEST_NOW,
): ConversationSearchMessageMatch = ConversationSearchMessageMatch(
    messageId = messageId,
    sequence = sequence,
    role = role,
    snippet = snippet,
    createdAt = createdAt,
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
    var pinError: Throwable? = null
    var archiveError: Throwable? = null
    var lastRenamed: Pair<String, String>? = null
    var lastDeleted: String? = null
    var lastPinned: Pair<String, Boolean>? = null
    var lastArchived: Pair<String, Boolean>? = null
    var lastActiveSet: String? = null
    var searchResults: List<ConversationSearchResult> = emptyList()
    var searchError: Throwable? = null
    var searchDelayMs: Long = 0
    val searchCalls = mutableListOf<String>()

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationsFlow.map { conversations -> conversations.filterNot { it.archived } }

    override fun observeArchivedConversations(): Flow<List<Conversation>> =
        conversationsFlow.map { conversations -> conversations.filter { it.archived } }

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

    override suspend fun setPinned(id: String, pinned: Boolean): Conversation? {
        pinError?.let { throw it }
        lastPinned = id to pinned
        val existing = conversationsFlow.value.firstOrNull { it.id == id } ?: return null
        val updated = existing.copy(pinned = pinned)
        conversationsFlow.value = conversationsFlow.value.map {
            if (it.id == id) updated else it
        }
        return updated
    }

    override suspend fun setArchived(id: String, archived: Boolean): Conversation? {
        archiveError?.let { throw it }
        lastArchived = id to archived
        val existing = conversationsFlow.value.firstOrNull { it.id == id } ?: return null
        val updated = existing.copy(archived = archived)
        conversationsFlow.value = conversationsFlow.value.map {
            if (it.id == id) updated else it
        }
        return updated
    }

    override suspend fun setLastActiveConversation(id: String?) {
        lastActiveSet = id
        activeFlow.value = id
    }

    override suspend fun getLastActiveConversation(): Conversation? =
        activeFlow.value?.let { id -> conversationsFlow.value.firstOrNull { it.id == id } }

    override suspend fun getConversation(id: String): Conversation? =
        conversationsFlow.value.firstOrNull { it.id == id }

    override suspend fun searchConversations(
        query: String,
        limit: Int,
    ): List<ConversationSearchResult> {
        searchCalls += query
        if (searchDelayMs > 0) delay(searchDelayMs)
        searchError?.let { throw it }
        return searchResults
    }
}

fun testAttachment(
    id: String = "attachment-1",
    messageId: String? = "message-1",
    kind: AttachmentKind = AttachmentKind.FILE,
    mimeType: String = "text/plain",
    name: String = "notes.txt",
    size: Long = 5,
    sha256: String = "abc123",
    storageKey: String = "attachments/abc123",
    createdAt: Instant = TEST_NOW,
): Attachment = Attachment(
    id = id,
    messageId = messageId,
    kind = kind,
    mimeType = mimeType,
    name = name,
    size = size,
    sha256 = sha256,
    storageKey = storageKey,
    width = null,
    height = null,
    createdAt = createdAt,
)

fun testSettings(
    defaultProviderId: String? = "provider-1",
    defaultModelId: String? = "model-1",
    lastActiveConversationId: String? = "conversation-1",
): Settings = Settings(
    theme = Theme.SYSTEM,
    density = Density.COMFORTABLE,
    chatFontSize = ChatFontSize.MD,
    sendKey = SendKey.ENTER,
    defaultProviderId = defaultProviderId,
    defaultModelId = defaultModelId,
    defaultSystemPrompt = "",
    defaultParams = testChatParams(),
    rememberKeysOnWeb = false,
    showTokenUsage = true,
    loadRemoteImages = false,
    lastActiveConversationId = lastActiveConversationId,
    backup = null,
    onboardingCompleted = true,
)
