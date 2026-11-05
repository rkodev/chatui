package dev.chatui.core.providers

import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.Provider
import dev.chatui.core.model.newId
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.network.isPresetEnabledInM2
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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
     * Kept as a named entry point for onboarding. It delegates to the generic
     * preset path so OpenRouter and the other OpenAI-compatible presets share
     * validation and rollback behavior.
     */
    suspend fun createOpenRouterProvider(
        apiKey: String,
        label: String? = null,
    ): Provider = createPresetProvider(
        CreatePresetProviderInput(
            presetId = OPENROUTER_PRESET_ID,
            apiKey = apiKey,
            label = label,
        ),
    )

    /**
     * Create a provider from an enabled OpenAI-compatible contract preset.
     *
     * The provider record is written with a `secretId`; the plaintext key is
     * written only to the secret store. If the provider write fails, the newly
     * written secret is removed. Presets that do not use a key get no secret.
     */
    suspend fun createPresetProvider(input: CreatePresetProviderInput): Provider {
        val preset = registry.getPreset(input.presetId)
            ?: throw invalidInput("Unknown provider preset \"${input.presetId}\".")

        if (preset.id == CUSTOM_PRESET_ID) {
            throw invalidInput("Use createCustomProvider to add a Custom provider.")
        }

        if (!isPresetEnabledInM2(preset.id)) {
            throw invalidInput("${preset.label} is not available in M2.")
        }

        val apiKey = validateApiKey(preset.auth.type, input.apiKey)
        val label = resolveLabel(input.label, preset.label)
        val headers = validateHeaders(input.headers, preset.auth.type)

        return persistProvider(
            provider = Provider(
                id = generateId(),
                presetId = preset.id,
                label = label,
                kind = preset.kind,
                baseUrl = preset.baseUrl,
                authType = preset.auth.type,
                secretId = null,
                headers = headers,
                enabled = true,
                browserCors = preset.browserCors,
                metadata = null,
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
            ),
            apiKey = apiKey,
        )
    }

    /**
     * Create a Custom OpenAI-compatible provider.
     *
     * The base URL and auth style are required. Non-secret headers are stored
     * on the provider record; the key is stored only in the secret store.
     * Invalid input is rejected before any write, and a failed provider write
     * removes the newly written secret.
     */
    suspend fun createCustomProvider(input: CreateCustomProviderInput): Provider {
        val preset = registry.getPreset(CUSTOM_PRESET_ID)
            ?: throw invalidInput("Custom provider preset is missing.")

        if (!isPresetEnabledInM2(CUSTOM_PRESET_ID)) {
            throw invalidInput("Custom providers are not enabled in this build.")
        }

        val baseUrl = validateBaseUrl(input.baseUrl)
        val authType = validateCustomAuthType(input.authType)
        val apiKey = validateApiKey(authType, input.apiKey)
        val label = resolveLabel(input.label, preset.label)
        val headers = validateHeaders(input.headers, authType)

        return persistProvider(
            provider = Provider(
                id = generateId(),
                presetId = preset.id,
                label = label,
                kind = preset.kind,
                baseUrl = baseUrl,
                authType = authType,
                secretId = null,
                headers = headers,
                enabled = true,
                browserCors = preset.browserCors,
                metadata = null,
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
            ),
            apiKey = apiKey,
        )
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

    /**
     * Write the secret first, then the provider record.
     *
     * A failed secret write leaves no provider record. A failed provider write
     * deletes the newly written secret so no orphan ciphertext remains.
     */
    private suspend fun persistProvider(provider: Provider, apiKey: String?): Provider {
        val secretId = if (apiKey == null) null else generateId()
        val record = provider.copy(secretId = secretId)

        if (secretId != null && apiKey != null) {
            try {
                secrets.put(secretId, apiKey)
            } catch (error: Throwable) {
                throw ProviderServiceException(
                    ProviderServiceErrorCode.STORAGE,
                    "Could not save the API key on this device. Try again.",
                    cause = error,
                )
            }
        }

        return try {
            providers.upsert(record)
        } catch (error: Throwable) {
            if (secretId != null) {
                runCatching { secrets.delete(secretId) }
            }
            throw ProviderServiceException(
                ProviderServiceErrorCode.STORAGE,
                "Could not save the provider. Try again.",
                cause = error,
            )
        }
    }

    private suspend fun resolveLabel(label: String?, fallback: String): String {
        val resolved = label?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        val existing = providers.observeAll().first()

        if (existing.any { it.label.equals(resolved, ignoreCase = true) }) {
            throw invalidInput(
                "A provider named \"$resolved\" already exists. Choose a different label.",
            )
        }

        return resolved
    }

    private fun invalidInput(message: String): ProviderServiceException =
        ProviderServiceException(ProviderServiceErrorCode.INVALID_INPUT, message)

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
        /** The default, reference, and required provider preset. */
        const val OPENROUTER_PRESET_ID: String = "openrouter"

        /** The contract preset used for user-supplied OpenAI-compatible servers. */
        const val CUSTOM_PRESET_ID: String = "custom"
    }
}
