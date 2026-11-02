package dev.chatui.core.model

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class SerializationTest {
    private val at = Instant.parse("2026-11-02T12:34:56.789Z")
    private val metadata = buildJsonObject {
        put("source", JsonPrimitive("test"))
        put("nested", buildJsonObject { put("enabled", JsonPrimitive(true)) })
    }
    private val params = ChatParams(
        temperature = 0.7,
        topP = null,
        maxOutputTokens = 1024,
        stop = listOf("\n"),
        reasoningEffort = ReasoningEffort.MEDIUM,
        streaming = true,
        extra = buildJsonObject { put("openrouter", buildJsonObject { put("route", JsonPrimitive("fallback")) }) },
    )
    private val chatError = ChatError(
        code = ChatErrorCode.RATE_LIMIT,
        message = "Rate limited",
        providerId = "provider-1",
        status = 429,
        retryable = true,
        retryAfterSeconds = 12.5,
        requestId = "req-1",
        details = metadata,
        at = at,
    )

    private inline fun <reified T> roundTrip(value: T): T =
        ChatUiJson.decodeFromString(ChatUiJson.encodeToString(value))

    @Test
    fun `provider round trips`() {
        val provider = Provider(
            id = "0190a0b0-0000-7000-8000-000000000001",
            presetId = "openrouter",
            label = "OpenRouter",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://openrouter.ai/api/v1",
            authType = ProviderAuthType.BEARER,
            secretId = "secret-1",
            headers = mapOf("HTTP-Referer" to "https://example.com"),
            enabled = true,
            browserCors = BrowserCors.SUPPORTED,
            metadata = metadata,
            createdAt = at,
            updatedAt = at,
        )

        assertThat(roundTrip(provider)).isEqualTo(provider)
    }

    @Test
    fun `model round trips`() {
        val model = Model(
            id = "0190a0b0-0000-7000-8000-000000000002",
            providerId = "provider-1",
            remoteId = "openai/gpt-4o",
            label = "GPT-4o",
            contextWindow = 128_000,
            maxOutputTokens = 16_384,
            capabilities = listOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.STREAMING),
            pricing = ModelPricing(inputPerMTok = 2.5, outputPerMTok = 10.0, currency = "USD"),
            canonicalSlug = "openai/gpt-4o",
            inputModalities = listOf(InputModality.TEXT, InputModality.IMAGE),
            outputModalities = listOf(OutputModality.TEXT),
            supportedParameters = listOf("temperature", "top_p"),
            metadata = metadata,
            hidden = false,
            pinned = true,
            source = ModelSource.DISCOVERED,
            createdAt = at,
            updatedAt = at,
        )

        assertThat(roundTrip(model)).isEqualTo(model)
    }

    @Test
    fun `conversation and sync meta round trip`() {
        val conversation = Conversation(
            id = "0190a0b0-0000-7000-8000-000000000003",
            title = "Hello",
            systemPrompt = "Be concise",
            providerId = "provider-1",
            modelId = "model-1",
            modelLabel = "GPT-4o",
            params = params,
            pinned = false,
            archived = false,
            temporary = true,
            createdAt = at,
            updatedAt = at,
            lastMessageAt = at,
            messageCount = 2,
            lastError = chatError,
            sync = SyncMeta(SyncState.PENDING, at, "rev-1"),
        )

        assertThat(roundTrip(conversation)).isEqualTo(conversation)
    }

    @Test
    fun `message and every content part round trip`() {
        val parts = listOf(
            ContentPart.Text("hello"),
            ContentPart.Image("attachment-1", "image/png", "a diagram"),
            ContentPart.File("attachment-2", "application/pdf", "report.pdf"),
            ContentPart.Reasoning("thinking"),
            ContentPart.ToolCall("call-1", "search", "{\"q\":\"kotlin\"}"),
            ContentPart.ToolResult("call-1", "result"),
        )
        val message = Message(
            id = "0190a0b0-0000-7000-8000-000000000004",
            conversationId = "conversation-1",
            sequence = 7,
            role = MessageRole.ASSISTANT,
            parts = parts,
            status = MessageStatus.COMPLETE,
            providerId = "provider-1",
            modelId = "model-1",
            modelLabel = "GPT-4o",
            usage = Usage(10, 20, 30, 0.001, false),
            finishReason = FinishReason.STOP,
            error = chatError,
            latencyMs = 1200,
            firstTokenMs = 300,
            createdAt = at,
            updatedAt = at,
            deletedAt = null,
        )

        assertThat(roundTrip(message)).isEqualTo(message)
        assertThat(roundTrip(parts)).isEqualTo(parts)
    }

    @Test
    fun `attachment round trips`() {
        val attachment = Attachment(
            id = "0190a0b0-0000-7000-8000-000000000005",
            messageId = "message-1",
            kind = AttachmentKind.IMAGE,
            mimeType = "image/png",
            name = "diagram.png",
            size = 2048,
            sha256 = "abc123",
            storageKey = "attachments/abc123",
            width = 640,
            height = 480,
            createdAt = at,
        )

        assertThat(roundTrip(attachment)).isEqualTo(attachment)
    }

    @Test
    fun `settings and backup config round trip`() {
        val backup = BackupConfig(
            enabled = true,
            backend = BackupBackend.S3,
            endpoint = "https://s3.example.com",
            region = "us-east-1",
            bucket = "chatui",
            prefix = "backups/",
            credentialSecretId = "secret-2",
            encryption = BackupEncryption.AES_256_GCM,
            kdf = BackupKdf.ARGON2ID,
            kdfSalt = "salt",
            lastSyncAt = at,
            lastManifestEtag = "etag",
            deviceId = "device-1",
        )
        val settings = Settings(
            theme = Theme.DARK,
            density = Density.COMPACT,
            chatFontSize = ChatFontSize.LG,
            sendKey = SendKey.MOD_ENTER,
            defaultProviderId = "provider-1",
            defaultModelId = "model-1",
            defaultSystemPrompt = "Be helpful",
            defaultParams = params,
            rememberKeysOnWeb = false,
            showTokenUsage = true,
            loadRemoteImages = false,
            lastActiveConversationId = "conversation-1",
            backup = backup,
            onboardingCompleted = true,
        )

        assertThat(roundTrip(settings)).isEqualTo(settings)
        assertThat(roundTrip(backup)).isEqualTo(backup)
    }

    @Test
    fun `chat error round trips`() {
        assertThat(roundTrip(chatError)).isEqualTo(chatError)
    }

    @Test
    fun `every stream event round trips`() {
        val events = listOf(
            StreamEvent.Start("response-1"),
            StreamEvent.TextDelta("hello"),
            StreamEvent.ReasoningDelta("thinking"),
            StreamEvent.ToolCallDelta(0, "call-1", "search", "{\"q\":"),
            StreamEvent.UsageEvent(Usage(1, 2, 3, 0.01, true)),
            StreamEvent.Finish(FinishReason.LENGTH),
            StreamEvent.ErrorEvent(chatError),
        )

        for (event in events) {
            assertThat(roundTrip<StreamEvent>(event)).isEqualTo(event)
        }
    }

    @Test
    fun `enums use contract serial names`() {
        assertThat(ChatUiJson.encodeToString(ProviderKind.OPENAI_COMPATIBLE)).isEqualTo("\"openai-compatible\"")
        assertThat(ChatUiJson.encodeToString(ProviderAuthType.X_API_KEY)).isEqualTo("\"x-api-key\"")
        assertThat(ChatUiJson.encodeToString(ChatErrorCode.RATE_LIMIT)).isEqualTo("\"rate_limit\"")
        assertThat(ChatUiJson.encodeToString(FinishReason.CONTENT_FILTER)).isEqualTo("\"content_filter\"")
        assertThat(ChatUiJson.encodeToString(MessageStatus.STREAMING)).isEqualTo("\"streaming\"")
        assertThat(ChatUiJson.encodeToString(BackupEncryption.AES_256_GCM)).isEqualTo("\"aes-256-gcm\"")
    }

    @Test
    fun `sealed types use contract discriminators`() {
        assertThat(ChatUiJson.encodeToString<ContentPart>(ContentPart.ToolCall("id", "name", "{}")))
            .contains("\"type\":\"tool-call\"")
        assertThat(ChatUiJson.encodeToString<StreamEvent>(StreamEvent.TextDelta("hi")))
            .isEqualTo("{\"type\":\"text-delta\",\"text\":\"hi\"}")
        assertThat(ChatUiJson.encodeToString<StreamEvent>(StreamEvent.UsageEvent(Usage(1, 2, 3, null, false))))
            .contains("\"type\":\"usage\"")
    }

    @Test
    fun `timestamps serialize as ISO 8601`() {
        val encoded = ChatUiJson.encodeToString(chatError)
        assertThat(encoded).contains("\"at\":\"2026-11-02T12:34:56.789Z\"")
    }
}
