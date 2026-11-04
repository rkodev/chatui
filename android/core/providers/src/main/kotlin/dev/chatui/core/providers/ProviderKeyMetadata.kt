package dev.chatui.core.providers

import dev.chatui.core.model.Model
import dev.chatui.core.model.Provider
import dev.chatui.core.network.ProviderKeyInfo
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Non-secret `/key` metadata cached on a provider record.
 *
 * The API key is never part of this shape. [validatedAt] records when the
 * metadata was last refreshed.
 */
data class ProviderKeyMetadata(
    val label: String? = null,
    val usageUsd: Double? = null,
    val limitUsd: Double? = null,
    val limitRemainingUsd: Double? = null,
    val isFreeTier: Boolean? = null,
    val rateLimit: JsonObject? = null,
    val validatedAt: Instant? = null,
) {
    /** Serialize to the `provider.metadata.key` object. */
    fun toJsonObject(): JsonObject {
        val values = linkedMapOf<String, JsonElement>()

        label?.let { values["label"] = JsonPrimitive(it) }
        usageUsd?.let { values["usageUsd"] = JsonPrimitive(it) }
        limitUsd?.let { values["limitUsd"] = JsonPrimitive(it) }
        limitRemainingUsd?.let { values["limitRemainingUsd"] = JsonPrimitive(it) }
        isFreeTier?.let { values["isFreeTier"] = JsonPrimitive(it) }
        rateLimit?.let { values["rateLimit"] = it }
        validatedAt?.let { values["validatedAt"] = JsonPrimitive(it.toString()) }

        return JsonObject(values)
    }

    companion object {
        /** Build metadata from an adapter key response. */
        fun fromKeyInfo(
            info: ProviderKeyInfo,
            validatedAt: Instant,
        ): ProviderKeyMetadata = ProviderKeyMetadata(
            label = info.label,
            usageUsd = info.usageUsd,
            limitUsd = info.limitUsd,
            limitRemainingUsd = info.limitRemainingUsd,
            isFreeTier = info.isFreeTier,
            rateLimit = info.rateLimit,
            validatedAt = validatedAt,
        )
    }
}

/** Read the cached `/key` metadata from a provider record. */
fun getProviderKeyMetadata(provider: Provider): ProviderKeyMetadata? {
    val key = provider.metadata?.get("key") as? JsonObject ?: return null

    return ProviderKeyMetadata(
        label = key.stringOrNull("label"),
        usageUsd = key.doubleOrNull("usageUsd"),
        limitUsd = key.doubleOrNull("limitUsd"),
        limitRemainingUsd = key.doubleOrNull("limitRemainingUsd"),
        isFreeTier = key.booleanOrNull("isFreeTier"),
        rateLimit = key["rateLimit"] as? JsonObject,
        validatedAt = key.stringOrNull("validatedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
    )
}

/** Remove only the cached key metadata, preserving other provider metadata. */
internal fun withoutKeyMetadata(metadata: JsonObject?): JsonObject? {
    if (metadata == null) {
        return null
    }

    val next = metadata.toMutableMap()
    next.remove("key")
    return if (next.isEmpty()) null else JsonObject(next)
}

/** Merge key metadata into a provider's existing metadata. */
internal fun withKeyMetadata(
    metadata: JsonObject?,
    keyMetadata: ProviderKeyMetadata,
): JsonObject {
    val next = metadata?.toMutableMap() ?: mutableMapOf()
    next["key"] = keyMetadata.toJsonObject()
    return JsonObject(next)
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

/** Result of a catalog refresh. */
data class RefreshModelsResult(
    val models: List<Model>,
    val created: Int,
    val updated: Int,
)

/** Result of the M1-01.2 default-model selection rule. */
data class DefaultModelSelection(
    val modelId: String?,
    val remoteId: String?,
    val notice: String?,
)
