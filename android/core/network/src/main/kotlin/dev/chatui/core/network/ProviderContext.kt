package dev.chatui.core.network

import dev.chatui.core.model.Provider
import kotlinx.serialization.json.JsonObject

/**
 * Everything an adapter needs for one request.
 *
 * Adapters never persist data and never read settings directly. [apiKey] is
 * supplied by the caller from the secret store and must never be logged.
 */
data class ProviderContext(
    val provider: Provider,
    val apiKey: String?,
)

/** Normalized key introspection result from spec section 7.1. */
data class ProviderKeyInfo(
    val label: String? = null,
    val usageUsd: Double? = null,
    val limitUsd: Double? = null,
    val limitRemainingUsd: Double? = null,
    val isFreeTier: Boolean? = null,
    val rateLimit: JsonObject? = null,
)
