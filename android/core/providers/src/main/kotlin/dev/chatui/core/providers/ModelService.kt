package dev.chatui.core.providers

import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.Provider
import dev.chatui.core.model.newId
import dev.chatui.core.network.FallbackCatalogEntry
import dev.chatui.core.network.ModelInfo
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderPreset
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.network.catalogDefaultsForPreset
import dev.chatui.core.network.fallbackModelIdsFor
import dev.chatui.core.network.mapOpenAIModel
import dev.chatui.core.network.selectFallbackModel
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Model catalog and selection service.
 *
 * Discovery is delegated to the adapter. Persistence preserves user state
 * (`pinned`, `hidden`, and `source: manual`) across refreshes.
 */
class ModelService(
    private val providers: ProviderRepository,
    private val models: ModelRepository,
    private val secrets: SecretStore,
    private val gateway: ProviderGateway,
    private val registry: ProviderRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val generateId: () -> String = ::newId,
) {
    /**
     * Refresh the provider catalog and upsert models by `(providerId, remoteId)`.
     *
     * Existing rows keep their local id, `pinned`, `hidden`, and `source`
     * flags. Manual rows also keep their user-provided label and metadata.
     * Models that disappear from the catalog are not deleted, so cached and
     * manual entries remain selectable.
     */
    suspend fun refreshModels(providerId: String): RefreshModelsResult {
        val provider = requireProvider(providerId)
        val preset = registry.getPreset(provider.presetId)
        // Public catalogs (OpenRouter and the local presets) must work before
        // a key is validated, so they receive no key. Key-protected catalogs
        // (Anthropic, Gemini, and the hosted OpenAI-compatible presets) use
        // the stored key when one exists; a missing key still degrades to the
        // cached or fallback catalog instead of failing the refresh.
        val apiKey = if (preset?.modelDiscovery?.public == false) {
            provider.secretId?.let { secrets.get(it) }
        } else {
            null
        }
        val infos = try {
            gateway.listModels(
                ProviderContext(provider, apiKey = apiKey),
                forceRefresh = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return degradedRefresh(providerId, preset, error)
        }

        val existing = models.forProvider(providerId).associateBy { it.remoteId }
        val now = clock.instant()
        val refreshed = mutableListOf<Model>()
        var created = 0
        var updated = 0

        for (info in infos) {
            val current = existing[info.remoteId]
            val model = modelFromInfo(info, providerId, current, now)

            // Always use the flag-preserving upsert so a concurrent insert
            // cannot lose user state between the read and the write.
            val persisted = models.upsertPreservingFlags(model)
            if (current == null) {
                created += 1
            } else {
                updated += 1
            }

            refreshed += persisted
        }

        return RefreshModelsResult(
            models = refreshed,
            created = created,
            updated = updated,
        )
    }

    /**
     * Keep chat usable when discovery is blocked.
     *
     * Cached models are returned unchanged. When the cache is empty, the
     * preset's fallback list is persisted so model selection and onboarding
     * still work. The result carries a non-blocking notice; callers never need
     * to treat this as a send failure.
     */
    private suspend fun degradedRefresh(
        providerId: String,
        preset: ProviderPreset?,
        error: Throwable,
    ): RefreshModelsResult {
        val existing = models.forProvider(providerId)

        if (existing.isNotEmpty()) {
            val suffix = if (existing.size == 1) "" else "s"
            return RefreshModelsResult(
                models = existing,
                created = 0,
                updated = 0,
                degraded = true,
                notice = "Discovery unavailable; using ${existing.size} cached model$suffix.",
                refreshError = error,
            )
        }

        if (preset == null) {
            throw error
        }

        val fallbackIds = fallbackModelIdsFor(preset.id, preset)
        val fallbackModels = ensureFallbackModels(providerId, preset, fallbackIds)

        if (fallbackModels.isEmpty()) {
            throw error
        }

        val notice = if (fallbackIds.size == 1) {
            "Discovery unavailable; using fallback model ${fallbackIds[0]}."
        } else {
            "Discovery unavailable; using ${fallbackModels.size} fallback models."
        }

        return RefreshModelsResult(
            models = fallbackModels,
            created = fallbackModels.size,
            updated = 0,
            degraded = true,
            notice = notice,
            refreshError = error,
        )
    }

    /**
     * Persist fallback rows for a preset whose discovery is blocked.
     *
     * Rows are marked with `metadata.fallback` so default selection can tell a
     * placeholder from a discovered model. Existing rows are never overwritten.
     */
    private suspend fun ensureFallbackModels(
        providerId: String,
        preset: ProviderPreset,
        fallbackIds: List<String>,
    ): List<Model> {
        val existing = models.forProvider(providerId)
        val byRemoteId = existing.associateBy { it.remoteId }
        val defaults = catalogDefaultsForPreset(preset.id)
        val created = mutableListOf<Model>()

        for (remoteId in fallbackIds) {
            if (byRemoteId.containsKey(remoteId)) {
                continue
            }

            val info = mapOpenAIModel(
                buildJsonObject { put("id", remoteId) },
                defaults,
            ) ?: continue

            val model = modelFromInfo(
                info = info.copy(
                    metadata = buildJsonObject { put("fallback", JsonPrimitive(true)) },
                ),
                providerId = providerId,
                existing = null,
                now = clock.instant(),
            )
            created += models.upsertPreservingFlags(model)
        }

        return existing + created
    }

    /**
     * Select the default model using the shared fallback rule.
     *
     * 1. Use the preset `defaultModelId` when it exists in the catalog, even
     *    if the model is hidden.
     * 2. Otherwise use the first fallback id present in the catalog.
     * 3. Otherwise use the first non-hidden text-output model ordered by
     *    `remoteId` ascending.
     * 4. If no text model exists, return null and a non-blocking notice.
     *
     * When the catalog is empty, fallback rows are created so onboarding still
     * has a selectable model.
     */
    suspend fun selectDefaultModel(providerId: String): DefaultModelSelection {
        val provider = requireProvider(providerId)
        val preset = registry.getPreset(provider.presetId)
            ?: throw ProviderServiceException(
                ProviderServiceErrorCode.INVALID_INPUT,
                "Unknown provider preset ${provider.presetId}.",
                providerId,
            )

        var providerModels = models.forProvider(providerId)
        val fallbackModelIds = fallbackModelIdsFor(preset.id, preset)

        if (providerModels.isEmpty() && fallbackModelIds.isNotEmpty()) {
            providerModels = ensureFallbackModels(providerId, preset, fallbackModelIds)
        }

        // Fallback rows are marked in metadata. Treat them as an empty catalog
        // so the rule reports that discovery was unavailable instead of
        // pretending the placeholder is a discovered default.
        val onlyFallback = providerModels.isNotEmpty() &&
            providerModels.all { it.metadata?.get("fallback") == JsonPrimitive(true) }
        val catalog = if (onlyFallback) {
            emptyList()
        } else {
            providerModels.map { model ->
                FallbackCatalogEntry(
                    remoteId = model.remoteId,
                    hidden = model.hidden,
                    outputModalities = model.outputModalities,
                )
            }
        }

        val selection = selectFallbackModel(
            catalog = catalog,
            presetDefaultModelId = preset.defaultModelId,
            fallbackModelIds = fallbackModelIds,
        )

        if (selection.selectedModelId == null) {
            return DefaultModelSelection(
                modelId = null,
                remoteId = null,
                notice = selection.notice,
            )
        }

        val selected = providerModels.firstOrNull {
            it.remoteId == selection.selectedModelId
        }

        if (selected == null) {
            return DefaultModelSelection(
                modelId = null,
                remoteId = null,
                notice = selection.notice
                    ?: "Default model ${preset.defaultModelId} is unavailable; " +
                    "no text model is available.",
            )
        }

        return DefaultModelSelection(
            modelId = selected.id,
            remoteId = selected.remoteId,
            notice = selection.notice,
        )
    }

    suspend fun listModels(providerId: String): List<Model> = models.forProvider(providerId)

    suspend fun getModel(modelId: String): Model? = models.get(modelId)

    private fun modelFromInfo(
        info: ModelInfo,
        providerId: String,
        existing: Model?,
        now: Instant,
    ): Model {
        val isManual = existing?.source == ModelSource.MANUAL

        return Model(
            id = existing?.id ?: generateId(),
            providerId = providerId,
            remoteId = info.remoteId,
            // A manual model's label and metadata are user-owned. Catalog
            // refreshes update its capabilities and pricing but do not
            // overwrite those fields.
            label = if (isManual) existing.label else info.label,
            contextWindow = info.contextWindow,
            maxOutputTokens = info.maxOutputTokens,
            capabilities = info.capabilities,
            pricing = info.pricing,
            canonicalSlug = info.canonicalSlug,
            inputModalities = info.inputModalities,
            outputModalities = info.outputModalities,
            supportedParameters = info.supportedParameters,
            metadata = if (isManual) existing.metadata else info.metadata,
            hidden = existing?.hidden ?: false,
            pinned = existing?.pinned ?: false,
            source = existing?.source ?: ModelSource.DISCOVERED,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
    }

    private suspend fun requireProvider(providerId: String): Provider =
        providers.get(providerId)
            ?: throw ProviderServiceException(
                ProviderServiceErrorCode.NOT_FOUND,
                "Provider $providerId was not found.",
                providerId,
            )
}
