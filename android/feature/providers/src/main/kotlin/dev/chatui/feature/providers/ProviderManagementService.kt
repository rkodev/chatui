package dev.chatui.feature.providers

import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.newId
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.providers.CreateCustomProviderInput
import dev.chatui.core.providers.CreatePresetProviderInput
import dev.chatui.core.providers.ModelService
import dev.chatui.core.providers.ProviderService
import dev.chatui.core.providers.RefreshModelsResult
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/** Global default provider/model pair. */
data class ModelDefaults(
    val providerId: String?,
    val modelId: String?,
)

/** A provider preset shown in the provider picker. */
data class ProviderAvailability(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val recommended: Boolean,
    val statusLabel: String?,
    val requiresKey: Boolean = true,
)

/** User-supplied model that is not present in the catalog. */
data class ManualModelInput(
    val remoteId: String,
    val label: String? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val capabilities: List<ModelCapability> = listOf(ModelCapability.TEXT),
    val pricing: ModelPricing? = null,
    val canonicalSlug: String? = null,
    val inputModalities: List<InputModality> = listOf(InputModality.TEXT),
    val outputModalities: List<OutputModality> = listOf(OutputModality.TEXT),
    val supportedParameters: List<String> = emptyList(),
    val metadata: JsonObject? = null,
)

/** Result of resolving the model a conversation would use for the next send. */
sealed interface ResolvedConversationModel {
    data class Available(val model: Model, val provider: Provider) : ResolvedConversationModel

    data class Unavailable(val message: String) : ResolvedConversationModel
}

/** Stable management failure codes for UI mapping. */
enum class ProviderManagementErrorCode {
    NOT_FOUND,
    INVALID_INPUT,
    UNAVAILABLE,
}

/** Service-level failure that is safe to show to the UI. */
class ProviderManagementException(
    val code: ProviderManagementErrorCode,
    message: String,
    val providerId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Provider, model, defaults, and routing facade used by the management UI.
 *
 * The facade keeps model switching transactional from the UI's perspective:
 * the conversation record is updated immediately, and past messages are never
 * rewritten. Assistant-message snapshots are owned by the chat runner.
 */
interface ProviderManagementService {
    fun observeProviders(): Flow<List<Provider>>

    suspend fun listProviders(): List<Provider>

    suspend fun getProvider(providerId: String): Provider?

    suspend fun createPresetProvider(input: CreatePresetProviderInput): Provider

    suspend fun createCustomProvider(input: CreateCustomProviderInput): Provider

    suspend fun validateProviderKey(providerId: String): Provider

    suspend fun rotateKey(providerId: String, apiKey: String): Provider

    suspend fun removeKey(providerId: String): Provider

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean): Provider

    suspend fun refreshModels(providerId: String): RefreshModelsResult

    suspend fun listModels(providerId: String): List<Model>

    fun observeModels(providerId: String): Flow<List<Model>>

    suspend fun setModelPinned(modelId: String, pinned: Boolean): Model

    suspend fun setModelHidden(modelId: String, hidden: Boolean): Model

    suspend fun addManualModel(providerId: String, input: ManualModelInput): Model

    suspend fun getDefaults(): ModelDefaults

    suspend fun setDefaults(providerId: String?, modelId: String?): ModelDefaults

    suspend fun getConversation(conversationId: String): Conversation?

    suspend fun setConversationModel(conversationId: String, modelId: String): Conversation

    /**
     * Persist the per-conversation system prompt and/or generation parameters.
     *
     * A null argument leaves that field unchanged. Values are written
     * immediately so the next send uses them.
     */
    suspend fun setConversationSettings(
        conversationId: String,
        systemPrompt: String? = null,
        params: ChatParams? = null,
    ): Conversation

    /** Hard-delete a conversation and clear a matching last-active pointer. */
    suspend fun deleteConversation(conversationId: String)

    suspend fun getConversationRouting(conversationId: String): RoutingPreferences

    suspend fun setConversationRouting(
        conversationId: String,
        preferences: RoutingPreferences,
    ): Conversation

    suspend fun getDefaultModelWarning(): String?

    suspend fun resolveConversationModel(conversation: Conversation): ResolvedConversationModel

    fun providerOptions(): List<ProviderAvailability>

    fun docsUrl(presetId: String): String?
}

/**
 * Production facade backed by the application-scoped provider services and
 * repositories.
 */
class DefaultProviderManagementService @Inject constructor(
    private val providers: ProviderRepository,
    private val models: ModelRepository,
    private val conversations: ConversationRepository,
    private val providerService: ProviderService,
    private val modelService: ModelService,
    private val settings: SettingsDataStore,
    private val registry: ProviderRegistry,
    private val clock: Clock,
) : ProviderManagementService {
    /** Overridable id generator for deterministic tests. */
    internal var generateId: () -> String = ::newId

    override fun observeProviders(): Flow<List<Provider>> = providers.observeAll()

    override suspend fun listProviders(): List<Provider> = providers.observeAll().first()

    override suspend fun getProvider(providerId: String): Provider? = providers.get(providerId)

    override suspend fun createPresetProvider(input: CreatePresetProviderInput): Provider =
        providerService.createPresetProvider(input)

    override suspend fun createCustomProvider(input: CreateCustomProviderInput): Provider =
        providerService.createCustomProvider(input)

    override suspend fun validateProviderKey(providerId: String): Provider =
        providerService.validateProviderKey(providerId)

    override suspend fun rotateKey(providerId: String, apiKey: String): Provider =
        providerService.rotateKey(providerId, apiKey)

    override suspend fun removeKey(providerId: String): Provider =
        providerService.removeKey(providerId)

    override suspend fun setProviderEnabled(providerId: String, enabled: Boolean): Provider =
        providerService.setProviderEnabled(providerId, enabled)

    override suspend fun refreshModels(providerId: String): RefreshModelsResult =
        modelService.refreshModels(providerId)

    override suspend fun listModels(providerId: String): List<Model> =
        modelService.listModels(providerId)

    override fun observeModels(providerId: String): Flow<List<Model>> =
        models.observeForProvider(providerId)

    override suspend fun setModelPinned(modelId: String, pinned: Boolean): Model {
        val model = requireModel(modelId)
        return models.update(model.copy(pinned = pinned, updatedAt = clock.instant()))
    }

    override suspend fun setModelHidden(modelId: String, hidden: Boolean): Model {
        val model = requireModel(modelId)
        return models.update(model.copy(hidden = hidden, updatedAt = clock.instant()))
    }

    override suspend fun addManualModel(providerId: String, input: ManualModelInput): Model {
        val provider = requireProvider(providerId)
        val remoteId = input.remoteId.trim()

        if (remoteId.isEmpty()) {
            throw ProviderManagementException(
                ProviderManagementErrorCode.INVALID_INPUT,
                "remoteId must not be empty.",
                providerId,
            )
        }

        if (models.findByRemoteId(provider.id, remoteId) != null) {
            throw ProviderManagementException(
                ProviderManagementErrorCode.INVALID_INPUT,
                "Model $remoteId already exists for this provider.",
                providerId,
            )
        }

        val now = clock.instant()
        val model = Model(
            id = generateId(),
            providerId = provider.id,
            remoteId = remoteId,
            label = input.label?.trim()?.takeIf { it.isNotEmpty() } ?: remoteId,
            contextWindow = input.contextWindow,
            maxOutputTokens = input.maxOutputTokens,
            capabilities = input.capabilities,
            pricing = input.pricing,
            canonicalSlug = input.canonicalSlug,
            inputModalities = input.inputModalities,
            outputModalities = input.outputModalities,
            supportedParameters = input.supportedParameters,
            metadata = input.metadata,
            hidden = false,
            pinned = false,
            source = ModelSource.MANUAL,
            createdAt = now,
            updatedAt = now,
        )

        return models.insert(model)
    }

    override suspend fun getDefaults(): ModelDefaults {
        val current = settings.current()
        return ModelDefaults(
            providerId = current.defaultProviderId,
            modelId = current.defaultModelId,
        )
    }

    /**
     * Persist global defaults.
     *
     * A model must exist and belong to the selected provider. Changing the
     * provider without naming a model clears a model that no longer belongs
     * to it instead of silently keeping a mismatched pair.
     */
    override suspend fun setDefaults(providerId: String?, modelId: String?): ModelDefaults {
        var nextProviderId = providerId
        var nextModelId = modelId

        if (modelId != null) {
            val model = requireModel(modelId)

            if (providerId == null) {
                throw ProviderManagementException(
                    ProviderManagementErrorCode.INVALID_INPUT,
                    "A model cannot be selected without a provider.",
                )
            }

            if (model.providerId != providerId) {
                throw ProviderManagementException(
                    ProviderManagementErrorCode.INVALID_INPUT,
                    "Model ${model.remoteId} does not belong to provider $providerId.",
                    providerId,
                )
            }

            nextProviderId = model.providerId
        } else if (providerId == null) {
            nextModelId = null
        } else {
            val current = settings.current()
            val currentModelId = current.defaultModelId
            if (currentModelId != null) {
                val model = models.get(currentModelId)
                if (model == null || model.providerId != providerId) {
                    nextModelId = null
                }
            }
        }

        if (nextProviderId != null) {
            requireProvider(nextProviderId)
        }

        settings.setDefaultProvider(nextProviderId, nextModelId)
        return ModelDefaults(providerId = nextProviderId, modelId = nextModelId)
    }

    override suspend fun getConversation(conversationId: String): Conversation? =
        conversations.get(conversationId)

    /**
     * Switch the model for one conversation and persist it immediately.
     *
     * The conversation record is the source of truth for the next send. Past
     * messages keep their own provider/model snapshots.
     */
    override suspend fun setConversationModel(
        conversationId: String,
        modelId: String,
    ): Conversation {
        val conversation = requireConversation(conversationId)
        val model = requireModel(modelId)
        val provider = requireProvider(model.providerId)

        if (!provider.enabled) {
            throw ProviderManagementException(
                ProviderManagementErrorCode.UNAVAILABLE,
                "Provider ${provider.label} is disabled. " +
                    "Enable it before selecting its models.",
                provider.id,
            )
        }

        val params = if (conversation.providerId != provider.id) {
            conversation.params.copy(
                extra = pruneExtrasForPreset(conversation.params.extra, provider.presetId),
            )
        } else {
            conversation.params
        }

        return conversations.update(
            conversation.copy(
                providerId = provider.id,
                modelId = model.id,
                modelLabel = model.label,
                params = params,
                updatedAt = clock.instant(),
            ),
        )
    }

    override suspend fun setConversationSettings(
        conversationId: String,
        systemPrompt: String?,
        params: ChatParams?,
    ): Conversation {
        val conversation = requireConversation(conversationId)

        return conversations.update(
            conversation.copy(
                systemPrompt = systemPrompt ?: conversation.systemPrompt,
                params = params ?: conversation.params,
                updatedAt = clock.instant(),
            ),
        )
    }

    override suspend fun deleteConversation(conversationId: String) {
        requireConversation(conversationId)
        conversations.delete(conversationId)

        if (settings.current().lastActiveConversationId == conversationId) {
            settings.setLastActiveConversation(null)
        }
    }

    override suspend fun getConversationRouting(conversationId: String): RoutingPreferences {
        val conversation = requireConversation(conversationId)
        return readRoutingPreferences(conversation.params)
    }

    override suspend fun setConversationRouting(
        conversationId: String,
        preferences: RoutingPreferences,
    ): Conversation {
        val conversation = requireConversation(conversationId)
        return conversations.update(
            conversation.copy(
                params = withRoutingPreferences(conversation.params, preferences),
                updatedAt = clock.instant(),
            ),
        )
    }

    /**
     * Warn when the global default model cannot be used.
     *
     * The warning is non-blocking and never substitutes another model.
     */
    override suspend fun getDefaultModelWarning(): String? {
        val defaults = getDefaults()

        if (defaults.providerId == null) {
            return "No default provider is configured."
        }

        val provider = providers.get(defaults.providerId)
            ?: return "The default provider is unavailable."

        if (!provider.enabled) {
            return "The default provider ${provider.label} is disabled."
        }

        if (defaults.modelId == null) {
            return "No default model is selected."
        }

        val model = models.get(defaults.modelId)
            ?: return "Default model ${defaults.modelId} is unavailable. " +
                "Choose another model before sending."

        if (model.providerId != provider.id) {
            return "The default model does not belong to the default provider."
        }

        return null
    }

    /**
     * Resolve the model a conversation would use for the next send.
     *
     * Missing or disabled models return an unavailable result. The caller must
     * show the warning and must not silently substitute another model.
     */
    override suspend fun resolveConversationModel(
        conversation: Conversation,
    ): ResolvedConversationModel {
        val modelId = conversation.modelId
            ?: return ResolvedConversationModel.Unavailable(
                "No model is selected for this conversation. " +
                    "Choose a model before sending.",
            )

        val model = models.get(modelId)
            ?: return ResolvedConversationModel.Unavailable(
                "Model $modelId is unavailable. Choose another model before sending.",
            )

        if (OutputModality.TEXT !in model.outputModalities) {
            return ResolvedConversationModel.Unavailable(
                "Model ${model.remoteId} does not produce text output. " +
                    "Choose another model before sending.",
            )
        }

        val provider = providers.get(model.providerId)
            ?: return ResolvedConversationModel.Unavailable(
                "Provider ${model.providerId} is unavailable. " +
                    "Choose another model before sending.",
            )

        if (!provider.enabled) {
            return ResolvedConversationModel.Unavailable(
                "Provider ${provider.label} is disabled. " +
                    "Enable it or choose another model before sending.",
            )
        }

        return ResolvedConversationModel.Available(model = model, provider = provider)
    }

    override fun providerOptions(): List<ProviderAvailability> =
        registry.getPresetAvailability().map { availability ->
            ProviderAvailability(
                id = availability.preset.id,
                label = availability.preset.label,
                enabled = availability.enabled,
                recommended = availability.preset.id == ProviderService.OPENROUTER_PRESET_ID,
                statusLabel = if (availability.enabled) null else "Coming later",
                requiresKey = availability.preset.auth.type != ProviderAuthType.NONE,
            )
        }

    override fun docsUrl(presetId: String): String? = registry.getPreset(presetId)?.docsUrl

    private suspend fun requireProvider(providerId: String): Provider =
        providers.get(providerId)
            ?: throw ProviderManagementException(
                ProviderManagementErrorCode.NOT_FOUND,
                "Provider $providerId was not found.",
                providerId,
            )

    private suspend fun requireModel(modelId: String): Model =
        models.get(modelId)
            ?: throw ProviderManagementException(
                ProviderManagementErrorCode.NOT_FOUND,
                "Model $modelId was not found.",
            )

    private suspend fun requireConversation(conversationId: String): Conversation =
        conversations.get(conversationId)
            ?: throw ProviderManagementException(
                ProviderManagementErrorCode.NOT_FOUND,
                "Conversation $conversationId was not found.",
            )
}

/** Binds the management facade into the singleton graph. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderManagementModule {
    @Binds
    @Singleton
    abstract fun bindProviderManagementService(
        impl: DefaultProviderManagementService,
    ): ProviderManagementService
}

/**
 * Entry point used by the shell to obtain the management facade without
 * depending on Hilt ViewModel navigation helpers.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProviderManagementEntryPoint {
    fun providerManagementService(): ProviderManagementService
}
