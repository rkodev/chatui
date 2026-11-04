package dev.chatui.feature.onboarding

import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.model.Provider
import dev.chatui.core.network.ModelInfo
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderPreset
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.providers.ModelService
import dev.chatui.core.providers.ProviderGateway
import dev.chatui.core.providers.ProviderService
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Result of a successful onboarding connection. */
data class OnboardingResult(
    val providerId: String,
    val modelId: String,
    /** Non-blocking notice, for example when the preset default is unavailable. */
    val notice: String?,
)

/** Stable onboarding failure codes for UI mapping. */
enum class OnboardingErrorCode {
    INVALID_INPUT,
    NO_MODEL,
    STORAGE,
}

/**
 * Service-level onboarding failure that is safe to show to the UI.
 *
 * Adapter failures and provider-service failures propagate unchanged so the
 * error-copy helper can map their codes.
 */
class OnboardingException(
    val code: OnboardingErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Surface used by the onboarding ViewModel.
 *
 * The concrete [DefaultOnboardingService] is bound by Hilt. Tests inject a
 * fake so the ViewModel can be exercised without Room, DataStore, or network.
 */
interface OnboardingService {
    /** Provider presets shown in the picker. Only OpenRouter is enabled. */
    fun providerOptions(): List<ProviderOption>

    /** Load the public OpenRouter catalog. No key is required or sent. */
    suspend fun loadCatalog(): List<ModelInfo>

    /**
     * Create the OpenRouter provider, validate the key with `GET /key`,
     * refresh models, select a default, persist defaults, and mark onboarding
     * complete.
     *
     * Any failure before completion deletes the provider and restores the
     * previous defaults, so an invalid key stores nothing.
     */
    suspend fun connect(apiKey: String, label: String?): OnboardingResult
}

/**
 * First-run orchestration backed by the application-scoped provider services.
 *
 * The public catalog is loaded with no key. `/key` is the only accepted proof
 * that a key works; a successful catalog load never counts as validation.
 */
class DefaultOnboardingService @Inject constructor(
    private val providers: ProviderService,
    private val models: ModelService,
    private val gateway: ProviderGateway,
    private val registry: ProviderRegistry,
    private val settings: SettingsDataStore,
    private val clock: Clock,
) : OnboardingService {
    override fun providerOptions(): List<ProviderOption> =
        registry.getPresetAvailability().map { availability ->
            ProviderOption(
                id = availability.preset.id,
                label = availability.preset.label,
                enabled = availability.enabled,
                recommended = availability.preset.id == ProviderService.OPENROUTER_PRESET_ID,
                statusLabel = if (availability.enabled) null else "Coming later",
            )
        }

    override suspend fun loadCatalog(): List<ModelInfo> {
        val preset = registry.getPreset(ProviderService.OPENROUTER_PRESET_ID)
            ?: throw OnboardingException(
                OnboardingErrorCode.INVALID_INPUT,
                "OpenRouter is unavailable in this build.",
            )

        return gateway.listModels(
            ProviderContext(provider = previewProvider(preset), apiKey = null),
            forceRefresh = false,
        )
    }

    override suspend fun connect(apiKey: String, label: String?): OnboardingResult {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            throw OnboardingException(
                OnboardingErrorCode.INVALID_INPUT,
                "Enter an OpenRouter API key to continue.",
            )
        }

        val previous = settings.current()
        var providerId: String? = null

        try {
            val provider = providers.createOpenRouterProvider(
                apiKey = key,
                label = label?.trim()?.takeIf { it.isNotEmpty() },
            )
            providerId = provider.id

            // `/key` is the only accepted proof that the key works. Never
            // treat a stored key or a successful catalog load as validation.
            providers.validateProviderKey(provider.id)

            models.refreshModels(provider.id)
            val selection = models.selectDefaultModel(provider.id)

            val modelId = selection.modelId
            if (modelId == null) {
                throw OnboardingException(
                    OnboardingErrorCode.NO_MODEL,
                    selection.notice ?: "No text model is available for OpenRouter.",
                )
            }

            try {
                settings.setDefaultProvider(provider.id, modelId)
                settings.setOnboardingCompleted(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw OnboardingException(
                    OnboardingErrorCode.STORAGE,
                    "ChatUI could not save the setup on this device. Try again.",
                    error,
                )
            }

            return OnboardingResult(
                providerId = provider.id,
                modelId = modelId,
                notice = selection.notice,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            providerId?.let { id ->
                runCatching { providers.deleteProvider(id) }
            }
            runCatching {
                settings.setDefaultProvider(
                    previous.defaultProviderId,
                    previous.defaultModelId,
                )
            }
            throw error
        }
    }

    private fun previewProvider(preset: ProviderPreset): Provider {
        val now = clock.instant()
        return Provider(
            id = "openrouter-preview",
            presetId = preset.id,
            label = preset.label,
            kind = preset.kind,
            baseUrl = preset.baseUrl,
            authType = preset.auth.type,
            secretId = null,
            headers = emptyMap(),
            enabled = true,
            browserCors = preset.browserCors,
            metadata = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
