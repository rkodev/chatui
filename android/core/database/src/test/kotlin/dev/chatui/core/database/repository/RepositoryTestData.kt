package dev.chatui.core.database.repository

import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Density
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Settings
import dev.chatui.core.model.SyncMeta
import dev.chatui.core.model.SyncState
import dev.chatui.core.model.Theme
import dev.chatui.core.model.Usage
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Deterministic domain builders shared by repository tests. */
internal object RepositoryTestData {
    val NOW: Instant = Instant.parse("2026-11-02T00:00:00Z")

    fun chatParams(): ChatParams = ChatParams(
        temperature = 0.7,
        topP = 0.9,
        maxOutputTokens = 1024,
        stop = listOf("STOP"),
        reasoningEffort = null,
        streaming = true,
        extra = buildJsonObject { put("openrouter", "route") },
    )

    fun conversation(
        id: String = "conversation-1",
        title: String = "Conversation",
        systemPrompt: String = "Be helpful",
        providerId: String = "provider-1",
        modelId: String = "model-1",
        modelLabel: String = "Model One",
        params: ChatParams = chatParams(),
        pinned: Boolean = false,
        archived: Boolean = false,
        temporary: Boolean = false,
        createdAt: Instant = NOW,
        updatedAt: Instant = NOW,
        lastMessageAt: Instant = NOW,
        messageCount: Int = 0,
        lastError: ChatError? = null,
        sync: SyncMeta? = null,
    ): Conversation = Conversation(
        id = id,
        title = title,
        systemPrompt = systemPrompt,
        providerId = providerId,
        modelId = modelId,
        modelLabel = modelLabel,
        params = params,
        pinned = pinned,
        archived = archived,
        temporary = temporary,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
        messageCount = messageCount,
        lastError = lastError,
        sync = sync,
    )

    fun message(
        id: String = "message-1",
        conversationId: String = "conversation-1",
        sequence: Long = 1,
        role: MessageRole = MessageRole.USER,
        parts: List<ContentPart> = listOf(ContentPart.Text("hello")),
        status: MessageStatus = MessageStatus.COMPLETE,
        providerId: String? = null,
        modelId: String? = null,
        modelLabel: String? = null,
        usage: Usage? = null,
        finishReason: FinishReason? = null,
        error: ChatError? = null,
        latencyMs: Long? = null,
        firstTokenMs: Long? = null,
        createdAt: Instant = NOW,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): Message = Message(
        id = id,
        conversationId = conversationId,
        sequence = sequence,
        role = role,
        parts = parts,
        status = status,
        providerId = providerId,
        modelId = modelId,
        modelLabel = modelLabel,
        usage = usage,
        finishReason = finishReason,
        error = error,
        latencyMs = latencyMs,
        firstTokenMs = firstTokenMs,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    fun provider(
        id: String = "provider-1",
        presetId: String = "openrouter",
        label: String = "OpenRouter",
        kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl: String = "https://openrouter.ai/api/v1",
        authType: ProviderAuthType = ProviderAuthType.BEARER,
        secretId: String? = "secret-1",
        headers: Map<String, String> = mapOf("HTTP-Referer" to "https://chatui.test"),
        enabled: Boolean = true,
        browserCors: BrowserCors = BrowserCors.UNKNOWN,
        metadata: JsonObject? = buildJsonObject { put("label", "test") },
        createdAt: Instant = NOW,
        updatedAt: Instant = NOW,
    ): Provider = Provider(
        id = id,
        presetId = presetId,
        label = label,
        kind = kind,
        baseUrl = baseUrl,
        authType = authType,
        secretId = secretId,
        headers = headers,
        enabled = enabled,
        browserCors = browserCors,
        metadata = metadata,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun model(
        id: String = "model-1",
        providerId: String = "provider-1",
        remoteId: String = "openrouter/auto",
        label: String = "Auto",
        contextWindow: Int? = 128_000,
        maxOutputTokens: Int? = 4_096,
        capabilities: List<ModelCapability> = listOf(
            ModelCapability.TEXT,
            ModelCapability.STREAMING,
        ),
        pricing: ModelPricing? = ModelPricing(
            inputPerMTok = 1.0,
            outputPerMTok = 2.0,
            currency = "USD",
        ),
        canonicalSlug: String? = "openrouter/auto",
        inputModalities: List<InputModality> = listOf(InputModality.TEXT),
        outputModalities: List<OutputModality> = listOf(OutputModality.TEXT),
        supportedParameters: List<String> = listOf("temperature", "top_p"),
        metadata: JsonObject? = buildJsonObject { put("is_moderated", false) },
        hidden: Boolean = false,
        pinned: Boolean = false,
        source: ModelSource = ModelSource.DISCOVERED,
        createdAt: Instant = NOW,
        updatedAt: Instant = NOW,
    ): Model = Model(
        id = id,
        providerId = providerId,
        remoteId = remoteId,
        label = label,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        capabilities = capabilities,
        pricing = pricing,
        canonicalSlug = canonicalSlug,
        inputModalities = inputModalities,
        outputModalities = outputModalities,
        supportedParameters = supportedParameters,
        metadata = metadata,
        hidden = hidden,
        pinned = pinned,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun chatError(): ChatError = ChatError(
        code = ChatErrorCode.RATE_LIMIT,
        message = "Rate limited",
        providerId = "provider-1",
        status = 429,
        retryable = true,
        retryAfterSeconds = 1.5,
        requestId = "request-1",
        details = buildJsonObject { put("reason", "quota") },
        at = NOW,
    )

    fun usage(): Usage = Usage(
        inputTokens = 10,
        outputTokens = 20,
        totalTokens = 30,
        costUsd = 0.001,
        estimated = false,
    )

    fun attachment(
        id: String = "attachment-1",
        messageId: String? = "message-1",
        kind: AttachmentKind = AttachmentKind.FILE,
        mimeType: String = "text/plain",
        name: String = "notes.txt",
        size: Long = 5,
        sha256: String = "abc123",
        storageKey: String = "attachments/abc123",
        createdAt: Instant = NOW,
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

    fun settings(): Settings = Settings(
        theme = Theme.SYSTEM,
        density = Density.COMFORTABLE,
        chatFontSize = ChatFontSize.MD,
        sendKey = SendKey.ENTER,
        defaultProviderId = "provider-1",
        defaultModelId = "model-1",
        defaultSystemPrompt = "",
        defaultParams = chatParams(),
        rememberKeysOnWeb = false,
        showTokenUsage = true,
        loadRemoteImages = false,
        lastActiveConversationId = "conversation-1",
        backup = null,
        onboardingCompleted = true,
    )
}
