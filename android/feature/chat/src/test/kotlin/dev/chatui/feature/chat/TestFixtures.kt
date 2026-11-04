package dev.chatui.feature.chat

import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
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
import dev.chatui.core.network.ProviderContext
import java.time.Instant
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
    enabled: Boolean = true,
): Provider = Provider(
    id = id,
    presetId = "openrouter",
    label = "OpenRouter",
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
): Model = Model(
    id = id,
    providerId = providerId,
    remoteId = remoteId,
    label = remoteId,
    contextWindow = 128_000,
    maxOutputTokens = 4_096,
    capabilities = listOf(ModelCapability.TEXT),
    pricing = null,
    canonicalSlug = null,
    inputModalities = listOf(InputModality.TEXT),
    outputModalities = listOf(OutputModality.TEXT),
    supportedParameters = emptyList(),
    metadata = null,
    hidden = false,
    pinned = false,
    source = ModelSource.DISCOVERED,
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
)

fun testConversation(
    id: String = "conversation-1",
    providerId: String = "provider-1",
    modelId: String = "model-1",
    messageCount: Int = 0,
): Conversation = Conversation(
    id = id,
    title = "Conversation",
    systemPrompt = "",
    providerId = providerId,
    modelId = modelId,
    modelLabel = "Model One",
    params = testChatParams(),
    pinned = false,
    archived = false,
    temporary = false,
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
    lastMessageAt = TEST_NOW,
    messageCount = messageCount,
    lastError = null,
    sync = null,
)

fun testMessage(
    id: String = "message-1",
    conversationId: String = "conversation-1",
    sequence: Long = 1,
    role: MessageRole = MessageRole.USER,
    text: String = "Hello",
    status: MessageStatus = MessageStatus.COMPLETE,
): Message = Message(
    id = id,
    conversationId = conversationId,
    sequence = sequence,
    role = role,
    parts = listOf(ContentPart.Text(text)),
    status = status,
    providerId = "provider-1",
    modelId = "model-1",
    modelLabel = "Model One",
    usage = null,
    finishReason = null,
    error = null,
    latencyMs = null,
    firstTokenMs = null,
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
    deletedAt = null,
)

fun testChatContext(
    provider: Provider = testProvider(),
    modelRemoteId: String = "openrouter/auto",
): ChatContext = ChatContext(
    providerContext = ProviderContext(provider, "test-key"),
    modelRemoteId = modelRemoteId,
    supportedParameters = emptyList(),
)
