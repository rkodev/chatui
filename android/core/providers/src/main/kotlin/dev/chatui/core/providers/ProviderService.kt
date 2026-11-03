package dev.chatui.core.providers

import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.Provider
import dev.chatui.core.model.newId
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderRegistry
import java.time.Clock
import kotlinx.coroutines.flow.Flow

/**
 * Provider lifecycle service.
 *
 * The service owns secret writes and provider-record updates so UI code never
 * handles crypto or plaintext persistence directly. Provider records contain
 * only `secretId`; the key itself lives in the secret store.
 */
class ProviderService(
    private val providers: ProviderRepository,
    private val secrets: SecretStore,
    private val gateway: ProviderGateway,
    private val registry: ProviderRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val generateId: () -> String = ::newId,
) {
    /** Observe every provider, including disabled ones. */
    fun observeProviders(): Flow<List<Provider>> = providers.observeAll()

    suspend fun getProvider(providerId: String): Provider? = providers.get(providerId)

    /**
     * Create an OpenRouter provider and store its key.
     *
     * The provider record is written with `metadata: null` and a `secretId`;
     * the plaintext key is written only to the secret store. If the provider
     * write fails, the newly written secret is removed.
     */
    suspend fun createOpenRouterProvider(
        apiKey: String,
        label: String? = null,
    ): Provider {
        val key = requireNonEmpty(apiKey, "apiKey")
        val preset = registry.getPreset(OPENROUTER_PRESET_ID)
            ?: throw ProviderServiceException(
                ProviderServiceErrorCode.INVALID_INPUT,
                "OpenRouter preset is missing.",
            )

        val id = generateId()
        val secretId = generateId()
        val now = clock.instant()
        val provider = Provider(
            id = id,
            presetId = preset.id,
            label = label?.trim()?.takeIf { it.isNotEmpty() } ?: preset.label,
            kind = preset.kind,
            baseUrl = preset.baseUrl,
            authType = preset.auth.type,
            secretId = secretId,
            headers = emptyMap(),
            enabled = true,
            browserCors = preset.browserCors,
            metadata = null,
            createdAt = now,
            updatedAt = now,
        )

        secrets.put(secretId, key)

        return try {
            providers.upsert(provider)
        } catch (error: Throwable) {
            runCatching { secrets.delete(secretId) }
            throw error
        }
    }

    /**
     * Validate the stored key with `GET /key` and cache the normalized
     * metadata on the provider record.
     *
     * Adapter errors propagate unchanged so the UI can map `ChatErrorCode` to
     * actionable copy. The key is never included in the provider record.
     */
    suspend fun validateProviderKey(providerId: String): Provider {
        val provider = requireProvider(providerId)
        val secretId = provider.secretId
            ?: throw ProviderServiceException(
                ProviderServiceErrorCode.MISSING_KEY,
                "This provider has no stored key. Add a key before validating.",
                providerId,
            )
        val apiKey = secrets.get(secretId)
            ?: throw ProviderServiceException(
                ProviderServiceErrorCode.MISSING_KEY,
                "The stored key is unavailable. Re-enter the key to continue.",
                providerId,
            )

        val keyInfo = gateway.validateKey(ProviderContext(provider, apiKey))
        val metadata = withKeyMetadata(
            provider.metadata,
            ProviderKeyMetadata.fromKeyInfo(keyInfo, clock.instant()),
        )

        return providers.upsert(
            provider.copy(
                metadata = metadata,
                updatedAt = clock.instant(),
            ),
        )
    }

    /**
     * Replace the stored key and clear stale validation metadata.
     *
     * The new secret is written before the provider points at it. The old
     * secret is removed only after the provider update succeeds.
     */
    suspend fun rotateKey(providerId: String, apiKey: String): Provider {
        val provider = requireProvider(providerId)
        val key = requireNonEmpty(apiKey, "apiKey")
        val secretId = generateId()

        secrets.put(secretId, key)

        val updated = try {
            providers.upsert(
                provider.copy(
                    secretId = secretId,
                    metadata = withoutKeyMetadata(provider.metadata),
                    updatedAt = clock.instant(),
                ),
            )
        } catch (error: Throwable) {
            runCatching { secrets.delete(secretId) }
            throw error
        }

        // The provider now points at the new secret. A failure to remove the
        // old ciphertext must not roll back a successful rotation.
        provider.secretId?.let { oldSecretId ->
            runCatching { secrets.delete(oldSecretId) }
        }

        return updated
    }

    /** Remove the stored key and clear cached key metadata. */
    suspend fun removeKey(providerId: String): Provider {
        val provider = requireProvider(providerId)

        provider.secretId?.let { secrets.delete(it) }

        return providers.upsert(
            provider.copy(
                secretId = null,
                metadata = withoutKeyMetadata(provider.metadata),
                updatedAt = clock.instant(),
            ),
        )
    }

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean): Provider {
        val provider = requireProvider(providerId)

        return providers.upsert(
            provider.copy(
                enabled = enabled,
                updatedAt = clock.instant(),
            ),
        )
    }

    /**
     * Delete a provider and its dependent records.
     *
     * `ProviderRepository.delete` cascades models and the referenced secret.
     * The explicit secret delete keeps the secret store consistent even when
     * the provider row was already removed.
     */
    suspend fun deleteProvider(providerId: String) {
        val provider = providers.get(providerId) ?: return

        providers.delete(providerId)

        provider.secretId?.let { secretId ->
            runCatching { secrets.delete(secretId) }
        }
    }

    private suspend fun requireProvider(providerId: String): Provider =
        providers.get(providerId)
            ?: throw ProviderServiceException(
                ProviderServiceErrorCode.NOT_FOUND,
                "Provider $providerId was not found.",
                providerId,
            )

    private fun requireNonEmpty(value: String, field: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            throw ProviderServiceException(
                ProviderServiceErrorCode.INVALID_INPUT,
                "$field must not be empty.",
            )
        }
        return trimmed
    }

    companion object {
        /** The only provider preset enabled in M1. */
        const val OPENROUTER_PRESET_ID: String = "openrouter"
    }
}
