package dev.chatui.feature.providers

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
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.providers.RefreshModelsResult
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject

val TEST_NOW: Instant = Instant.parse("2026-11-03T00:00:00Z")

fun testChatParams(extra: JsonObject = JsonObject(emptyMap())): ChatParams = ChatParams(
    temperature = null,
    topP = null,
    maxOutputTokens = null,
    stop = emptyList(),
    reasoningEffort = null,
    streaming = true,
    extra = extra,
)

fun testProvider(
    id: String = "provider-1",
    label: String = "OpenRouter",
    presetId: String = "openrouter",
    enabled: Boolean = true,
    secretId: String? = "secret-1",
    browserCors: BrowserCors = BrowserCors.SUPPORTED,
    metadata: JsonObject? = null,
): Provider = Provider(
    id = id,
    presetId = presetId,
    label = label,
    kind = ProviderKind.OPENAI_COMPATIBLE,
    baseUrl = "https://openrouter.ai/api/v1",
    authType = ProviderAuthType.BEARER,
    secretId = secretId,
    headers = emptyMap(),
    enabled = enabled,
    browserCors = browserCors,
    metadata = metadata,
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
)

fun testModel(
    id: String = "model-1",
    providerId: String = "provider-1",
    remoteId: String = "openrouter/auto",
    label: String = remoteId,
    contextWindow: Int? = 128_000,
    maxOutputTokens: Int? = 4_096,
    capabilities: List<ModelCapability> = listOf(ModelCapability.TEXT),
    pricing: ModelPricing? = null,
    canonicalSlug: String? = null,
    inputModalities: List<InputModality> = listOf(InputModality.TEXT),
    outputModalities: List<OutputModality> = listOf(OutputModality.TEXT),
    supportedParameters: List<String> = emptyList(),
    metadata: JsonObject? = null,
    hidden: Boolean = false,
    pinned: Boolean = false,
    source: ModelSource = ModelSource.DISCOVERED,
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
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
)

fun testConversation(
    id: String = "conversation-1",
    providerId: String = "provider-1",
    modelId: String = "model-1",
    modelLabel: String = "Model One",
    params: ChatParams = testChatParams(),
): Conversation = Conversation(
    id = id,
    title = "Conversation",
    systemPrompt = "",
    providerId = providerId,
    modelId = modelId,
    modelLabel = modelLabel,
    params = params,
    pinned = false,
    archived = false,
    temporary = false,
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
    lastMessageAt = TEST_NOW,
    messageCount = 0,
    lastError = null,
    sync = null,
)

fun testMessage(
    id: String = "message-1",
    providerId: String? = "provider-1",
    modelId: String? = "model-1",
    modelLabel: String? = "Model One",
): Message = Message(
    id = id,
    conversationId = "conversation-1",
    sequence = 1,
    role = MessageRole.ASSISTANT,
    parts = listOf(ContentPart.Text("Hello")),
    status = MessageStatus.COMPLETE,
    providerId = providerId,
    modelId = modelId,
    modelLabel = modelLabel,
    usage = null,
    finishReason = null,
    error = null,
    latencyMs = null,
    firstTokenMs = null,
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
    deletedAt = null,
)

/**
 * In-memory management facade for ViewModel and composable tests.
 *
 * It mirrors the production persistence semantics closely enough to assert
 * immediate model switching, defaults validation, and routing serialization.
 */
class FakeProviderManagementService : ProviderManagementService {
    val providersFlow = MutableStateFlow<List<Provider>>(listOf(testProvider()))
    var models: MutableList<Model> = mutableListOf(testModel())
    var conversations: MutableMap<String, Conversation> = mutableMapOf(
        "conversation-1" to testConversation(),
    )
    var defaults: ModelDefaults = ModelDefaults("provider-1", "model-1")
    var routing: MutableMap<String, RoutingPreferences> = mutableMapOf()
    var options: List<ProviderAvailability> = listOf(
        ProviderAvailability("openrouter", "OpenRouter", true, true, null),
        ProviderAvailability("openai", "OpenAI", false, false, "Coming later"),
    )
    var docsUrl: String? = "https://openrouter.ai/docs"
    var error: Throwable? = null
    var providersError: Throwable? = null
    var lastRotatedKey: String? = null
    var lastSelectedModelId: String? = null
    var lastRouting: RoutingPreferences? = null
    var refreshCalls: Int = 0

    override fun observeProviders(): Flow<List<Provider>> =
        providersError?.let { failure -> kotlinx.coroutines.flow.flow { throw failure } }
            ?: providersFlow

    override suspend fun listProviders(): List<Provider> = providersFlow.value

    override suspend fun getProvider(providerId: String): Provider? {
        error?.let { throw it }
        return providersFlow.value.firstOrNull { it.id == providerId }
    }

    override suspend fun validateProviderKey(providerId: String): Provider {
        error?.let { throw it }
        return requireProvider(providerId)
    }

    override suspend fun rotateKey(providerId: String, apiKey: String): Provider {
        error?.let { throw it }
        lastRotatedKey = apiKey
        return requireProvider(providerId)
    }

    override suspend fun removeKey(providerId: String): Provider {
        error?.let { throw it }
        val provider = requireProvider(providerId)
        val updated = provider.copy(secretId = null)
        providersFlow.value = providersFlow.value.map {
            if (it.id == providerId) updated else it
        }
        return updated
    }

    override suspend fun setProviderEnabled(providerId: String, enabled: Boolean): Provider {
        error?.let { throw it }
        val provider = requireProvider(providerId)
        val updated = provider.copy(enabled = enabled)
        providersFlow.value = providersFlow.value.map {
            if (it.id == providerId) updated else it
        }
        return updated
    }

    override suspend fun refreshModels(providerId: String): RefreshModelsResult {
        error?.let { throw it }
        refreshCalls += 1
        return RefreshModelsResult(models.toList(), 0, models.size)
    }

    override suspend fun listModels(providerId: String): List<Model> {
        error?.let { throw it }
        return models.filter { it.providerId == providerId }
    }

    override fun observeModels(providerId: String): Flow<List<Model>> =
        flowOf(models.filter { it.providerId == providerId })

    override suspend fun setModelPinned(modelId: String, pinned: Boolean): Model {
        error?.let { throw it }
        val index = models.indexOfFirst { it.id == modelId }
        val updated = models[index].copy(pinned = pinned)
        models[index] = updated
        return updated
    }

    override suspend fun setModelHidden(modelId: String, hidden: Boolean): Model {
        error?.let { throw it }
        val index = models.indexOfFirst { it.id == modelId }
        val updated = models[index].copy(hidden = hidden)
        models[index] = updated
        return updated
    }

    override suspend fun addManualModel(providerId: String, input: ManualModelInput): Model {
        error?.let { throw it }
        val model = testModel(
            id = "manual-${models.size}",
            providerId = providerId,
            remoteId = input.remoteId,
            label = input.label ?: input.remoteId,
            source = ModelSource.MANUAL,
        )
        models += model
        return model
    }

    override suspend fun getDefaults(): ModelDefaults = defaults

    override suspend fun setDefaults(providerId: String?, modelId: String?): ModelDefaults {
        error?.let { throw it }
        if (modelId != null) {
            val model = models.firstOrNull { it.id == modelId }
                ?: throw ProviderManagementException(
                    ProviderManagementErrorCode.NOT_FOUND,
                    "Model $modelId was not found.",
                )
            if (providerId == null || model.providerId != providerId) {
                throw ProviderManagementException(
                    ProviderManagementErrorCode.INVALID_INPUT,
                    "Model ${model.remoteId} does not belong to provider $providerId.",
                )
            }
        }
        defaults = ModelDefaults(providerId, modelId)
        return defaults
    }

    override suspend fun getConversation(conversationId: String): Conversation? {
        error?.let { throw it }
        return conversations[conversationId]
    }

    override suspend fun setConversationModel(
        conversationId: String,
        modelId: String,
    ): Conversation {
        error?.let { throw it }
        val conversation = conversations[conversationId]
            ?: throw ProviderManagementException(
                ProviderManagementErrorCode.NOT_FOUND,
                "Conversation $conversationId was not found.",
            )
        val model = models.firstOrNull { it.id == modelId }
            ?: throw ProviderManagementException(
                ProviderManagementErrorCode.NOT_FOUND,
                "Model $modelId was not found.",
            )
        lastSelectedModelId = modelId
        val updated = conversation.copy(
            providerId = model.providerId,
            modelId = model.id,
            modelLabel = model.label,
        )
        conversations[conversationId] = updated
        return updated
    }

    override suspend fun getConversationRouting(conversationId: String): RoutingPreferences =
        routing[conversationId] ?: DEFAULT_ROUTING_PREFERENCES

    override suspend fun setConversationRouting(
        conversationId: String,
        preferences: RoutingPreferences,
    ): Conversation {
        error?.let { throw it }
        lastRouting = preferences
        routing[conversationId] = preferences
        val conversation = conversations[conversationId]
            ?: throw ProviderManagementException(
                ProviderManagementErrorCode.NOT_FOUND,
                "Conversation $conversationId was not found.",
            )
        val updated = conversation.copy(
            params = withRoutingPreferences(conversation.params, preferences),
        )
        conversations[conversationId] = updated
        return updated
    }

    override suspend fun getDefaultModelWarning(): String? {
        val provider = providersFlow.value.firstOrNull { it.id == defaults.providerId }
            ?: return "No default provider is configured."
        if (!provider.enabled) {
            return "The default provider ${provider.label} is disabled."
        }
        val model = models.firstOrNull { it.id == defaults.modelId }
            ?: return "No default model is selected."
        if (model.providerId != provider.id) {
            return "The default model does not belong to the default provider."
        }
        return null
    }

    override suspend fun resolveConversationModel(
        conversation: Conversation,
    ): ResolvedConversationModel {
        val model = models.firstOrNull { it.id == conversation.modelId }
            ?: return ResolvedConversationModel.Unavailable("Model unavailable.")
        val provider = providersFlow.value.firstOrNull { it.id == model.providerId }
            ?: return ResolvedConversationModel.Unavailable("Provider unavailable.")
        return ResolvedConversationModel.Available(model, provider)
    }

    override fun providerOptions(): List<ProviderAvailability> = options

    override fun docsUrl(presetId: String): String? = docsUrl

    private fun requireProvider(providerId: String): Provider =
        providersFlow.value.firstOrNull { it.id == providerId }
            ?: throw ProviderManagementException(
                ProviderManagementErrorCode.NOT_FOUND,
                "Provider $providerId was not found.",
            )
}

/** Reversible test cipher whose ciphertext never contains the plaintext. */
class FakeSecretCipher : dev.chatui.core.datastore.secret.SecretCipher {
    override fun encrypt(plaintext: String): String =
        java.util.Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

    override fun decrypt(ciphertext: String): String =
        String(java.util.Base64.getDecoder().decode(ciphertext), Charsets.UTF_8)
}

/** Gateway fake that returns fixture data without a network request. */
class FakeProviderGateway : dev.chatui.core.providers.ProviderGateway {
    var keyInfo: dev.chatui.core.network.ProviderKeyInfo =
        dev.chatui.core.network.ProviderKeyInfo(
            label = "Test key",
            usageUsd = 1.5,
            limitUsd = 10.0,
            limitRemainingUsd = 8.5,
            isFreeTier = false,
        )
    var models: List<dev.chatui.core.network.ModelInfo> = emptyList()
    var validateError: Throwable? = null
    var listError: Throwable? = null

    override suspend fun validateKey(
        context: dev.chatui.core.network.ProviderContext,
    ): dev.chatui.core.network.ProviderKeyInfo {
        validateError?.let { throw it }
        return keyInfo
    }

    override suspend fun listModels(
        context: dev.chatui.core.network.ProviderContext,
        forceRefresh: Boolean,
    ): List<dev.chatui.core.network.ModelInfo> {
        listError?.let { throw it }
        return models
    }
}
