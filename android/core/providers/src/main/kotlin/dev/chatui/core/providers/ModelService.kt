package dev.chatui.core.providers

import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import dev.chatui.core.model.newId
import dev.chatui.core.network.ModelInfo
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderRegistry
import java.time.Clock
import java.time.Instant

/**
 * Model catalog and selection service.
 *
 * Discovery is delegated to the adapter. Persistence preserves user state
 * (`pinned`, `hidden`, and `source: manual`) across refreshes.
 */
class ModelService(
    private val providers: ProviderRepository,
    private val models: ModelRepository,
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
        // The OpenRouter catalog is public. Discovery must work before a key
        // is validated, so the adapter receives no key.
        val infos = gateway.listModels(
            ProviderContext(provider, apiKey = null),
            forceRefresh = true,
        )

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
     * Select the default model using the M1-01.2 fallback rule.
     *
     * 1. Use the preset `defaultModelId` when it exists in the catalog, even
     *    if the model is hidden.
     * 2. Otherwise use the first non-hidden text-output model ordered by
     *    `remoteId` ascending.
     * 3. If no text model exists, return null and a non-blocking notice.
     */
    suspend fun selectDefaultModel(providerId: String): DefaultModelSelection {
        val provider = requireProvider(providerId)
        val preset = registry.getPreset(provider.presetId)
            ?: throw ProviderServiceException(
                ProviderServiceErrorCode.INVALID_INPUT,
                "Unknown provider preset ${provider.presetId}.",
                providerId,
            )

        val providerModels = models.forProvider(providerId)
        val presetDefault = providerModels.firstOrNull {
            it.remoteId == preset.defaultModelId
        }

        if (presetDefault != null) {
            return DefaultModelSelection(
                modelId = presetDefault.id,
                remoteId = presetDefault.remoteId,
                notice = null,
            )
        }

        val fallback = providerModels
            .filter { model ->
                !model.hidden && OutputModality.TEXT in model.outputModalities
            }
            .minByOrNull { it.remoteId }

        if (fallback != null) {
            return DefaultModelSelection(
                modelId = fallback.id,
                remoteId = fallback.remoteId,
                notice = "Default model ${preset.defaultModelId} is unavailable; " +
                    "using ${fallback.remoteId}.",
            )
        }

        return DefaultModelSelection(
            modelId = null,
            remoteId = null,
            notice = "Default model ${preset.defaultModelId} is unavailable; " +
                "no text model is available.",
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
