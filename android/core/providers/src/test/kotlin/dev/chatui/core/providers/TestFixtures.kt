package dev.chatui.core.providers

import dev.chatui.core.datastore.secret.SecretCipher
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
import dev.chatui.core.network.ModelInfo
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderKeyInfo
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonObject

/** Reversible test cipher whose ciphertext never contains the plaintext. */
class FakeSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String =
        Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

    override fun decrypt(ciphertext: String): String =
        String(Base64.getDecoder().decode(ciphertext), Charsets.UTF_8)
}

/** Gateway fake that returns fixture data without a network request. */
class FakeProviderGateway : ProviderGateway {
    var keyInfo: ProviderKeyInfo = ProviderKeyInfo(
        label = "Test key",
        usageUsd = 1.5,
        limitUsd = 10.0,
        limitRemainingUsd = 8.5,
        isFreeTier = false,
    )
    var models: List<ModelInfo> = emptyList()
    var validateError: Throwable? = null
    var listError: Throwable? = null
    var lastForceRefresh: Boolean? = null
    var lastContext: ProviderContext? = null

    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo {
        lastContext = context
        validateError?.let { throw it }
        return keyInfo
    }

    override suspend fun listModels(
        context: ProviderContext,
        forceRefresh: Boolean,
    ): List<ModelInfo> {
        lastContext = context
        lastForceRefresh = forceRefresh
        listError?.let { throw it }
        return models
    }
}

fun modelInfo(
    remoteId: String,
    label: String = remoteId,
    outputModalities: List<OutputModality> = listOf(OutputModality.TEXT),
): ModelInfo = ModelInfo(
    remoteId = remoteId,
    label = label,
    contextWindow = 128_000,
    maxOutputTokens = 4_096,
    capabilities = listOf(ModelCapability.TEXT),
    inputModalities = listOf(InputModality.TEXT),
    outputModalities = outputModalities,
)

fun model(
    id: String,
    providerId: String,
    remoteId: String,
    label: String = remoteId,
    hidden: Boolean = false,
    pinned: Boolean = false,
    source: ModelSource = ModelSource.DISCOVERED,
    outputModalities: List<OutputModality> = listOf(OutputModality.TEXT),
    metadata: JsonObject? = null,
    now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
): Model = Model(
    id = id,
    providerId = providerId,
    remoteId = remoteId,
    label = label,
    contextWindow = 128_000,
    maxOutputTokens = 4_096,
    capabilities = listOf(ModelCapability.TEXT),
    pricing = null,
    canonicalSlug = null,
    inputModalities = listOf(InputModality.TEXT),
    outputModalities = outputModalities,
    supportedParameters = emptyList(),
    metadata = metadata,
    hidden = hidden,
    pinned = pinned,
    source = source,
    createdAt = now,
    updatedAt = now,
)
