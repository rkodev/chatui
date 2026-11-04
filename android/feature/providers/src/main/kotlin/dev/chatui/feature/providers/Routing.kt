package dev.chatui.feature.providers

import dev.chatui.core.model.ChatParams
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Curated OpenRouter routing preferences.
 *
 * The values live in `conversation.params.extra.openrouter.provider`, which is
 * the same passthrough object the request builder already serializes. When
 * [enabled] is false the provider object is omitted entirely so OpenRouter
 * uses its defaults.
 */
enum class RoutingSort(val wireValue: String) {
    PRICE("price"),
    THROUGHPUT("throughput"),
    LATENCY("latency"),
}

data class RoutingPreferences(
    val enabled: Boolean = false,
    val allowFallbacks: Boolean = true,
    val sort: RoutingSort? = null,
    val denyDataCollection: Boolean = false,
)

val DEFAULT_ROUTING_PREFERENCES: RoutingPreferences = RoutingPreferences()

/** Build the OpenRouter `provider` object, or null when routing is inactive. */
fun buildProviderRouting(preferences: RoutingPreferences): JsonObject? {
    if (!preferences.enabled) {
        return null
    }

    val provider = linkedMapOf<String, JsonElement>()
    provider["allow_fallbacks"] = JsonPrimitive(preferences.allowFallbacks)
    preferences.sort?.let { provider["sort"] = JsonPrimitive(it.wireValue) }
    if (preferences.denyDataCollection) {
        provider["data_collection"] = JsonPrimitive("deny")
    }

    return JsonObject(provider)
}

/** Human-readable routing summary, or null when no preference is active. */
fun routingSummary(preferences: RoutingPreferences): String? {
    if (!preferences.enabled) {
        return null
    }

    val parts = mutableListOf(
        if (preferences.allowFallbacks) "Fallbacks on" else "Fallbacks off",
    )

    when (preferences.sort) {
        RoutingSort.PRICE -> parts += "cheapest provider"
        RoutingSort.THROUGHPUT -> parts += "highest throughput"
        RoutingSort.LATENCY -> parts += "lowest latency"
        null -> Unit
    }

    if (preferences.denyDataCollection) {
        parts += "data collection denied"
    }

    return parts.joinToString(", ")
}

/** Read routing preferences from conversation parameters. */
fun readRoutingPreferences(params: ChatParams): RoutingPreferences {
    val extra = params.extra["openrouter"] as? JsonObject
        ?: return DEFAULT_ROUTING_PREFERENCES
    val provider = extra["provider"] as? JsonObject
        ?: return DEFAULT_ROUTING_PREFERENCES

    val sort = (provider["sort"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.let { wire -> RoutingSort.entries.firstOrNull { it.wireValue == wire } }

    return RoutingPreferences(
        enabled = true,
        allowFallbacks = (provider["allow_fallbacks"] as? JsonPrimitive)?.booleanOrNull ?: true,
        sort = sort,
        denyDataCollection = (provider["data_collection"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content == "deny",
    )
}

/**
 * Return a copy of [params] with routing preferences applied.
 *
 * Other advanced OpenRouter fields (`models`, `route`, `reasoning`) are
 * preserved. Disabling routing removes only the curated `provider` object.
 */
fun withRoutingPreferences(
    params: ChatParams,
    preferences: RoutingPreferences,
): ChatParams {
    val extra = params.extra.toMutableMap()
    val openrouter = (extra["openrouter"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val provider = buildProviderRouting(preferences)

    if (provider == null) {
        openrouter.remove("provider")
    } else {
        openrouter["provider"] = provider
    }

    if (openrouter.isEmpty()) {
        extra.remove("openrouter")
    } else {
        extra["openrouter"] = JsonObject(openrouter)
    }

    return params.copy(extra = JsonObject(extra))
}
