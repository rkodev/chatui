package dev.chatui.feature.providers

import dev.chatui.core.model.ChatParams
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Protected request fields from spec 6.2.
 *
 * The request builders reject these in provider extras, and the editor rejects
 * them before persistence so users see the problem while editing rather than
 * at send time.
 */
val PROTECTED_EXTRA_FIELDS: List<String> =
    listOf("model", "messages", "stream", "stream_options")

/** Core generation controls and the provider parameter names they map to. */
enum class CoreParameterKey {
    TEMPERATURE,
    TOP_P,
    MAX_OUTPUT_TOKENS,
    STOP,
    REASONING_EFFORT,
}

/** One core generation control. */
data class CoreParameterDefinition(
    val key: CoreParameterKey,
    val label: String,
    /** Provider-native parameter names that can carry this value. */
    val supportedNames: List<String>,
)

/**
 * Core generation controls.
 *
 * A value is considered supported when the model reports at least one of these
 * names. Values are never deleted when unsupported; adapters omit them from the
 * request and the UI explains the omission.
 */
val CORE_PARAMETER_DEFINITIONS: List<CoreParameterDefinition> = listOf(
    CoreParameterDefinition(
        key = CoreParameterKey.TEMPERATURE,
        label = "Temperature",
        supportedNames = listOf("temperature"),
    ),
    CoreParameterDefinition(
        key = CoreParameterKey.TOP_P,
        label = "Top P",
        supportedNames = listOf("top_p"),
    ),
    CoreParameterDefinition(
        key = CoreParameterKey.MAX_OUTPUT_TOKENS,
        label = "Max output tokens",
        supportedNames = listOf("max_tokens"),
    ),
    CoreParameterDefinition(
        key = CoreParameterKey.STOP,
        label = "Stop sequences",
        supportedNames = listOf("stop"),
    ),
    CoreParameterDefinition(
        key = CoreParameterKey.REASONING_EFFORT,
        label = "Reasoning effort",
        supportedNames = listOf("reasoning", "reasoning_effort"),
    ),
)

/** Result of parsing the provider extras editor. */
data class ProviderExtrasParseResult(
    val ok: Boolean,
    val value: JsonObject? = null,
    val message: String? = null,
)

private val PrettyJson: Json = Json { prettyPrint = true }

/**
 * The shared extras namespace rule.
 *
 * Every adapter reads `params.extra[namespace]`, where the namespace is the
 * provider preset id. Gemini's preset id is `google`, matching its adapter.
 */
fun extraNamespaceForPreset(presetId: String): String = presetId

/** Read this provider's extras object, or an empty object when absent. */
fun readProviderExtras(params: ChatParams, namespace: String): JsonObject =
    params.extra[namespace] as? JsonObject ?: JsonObject(emptyMap())

/** Serialize this provider's extras for the JSON editor. */
fun stringifyProviderExtras(params: ChatParams, namespace: String): String {
    val extra = readProviderExtras(params, namespace)
    return if (extra.isEmpty()) "" else PrettyJson.encodeToString(JsonObject.serializer(), extra)
}

/**
 * Return a copy of [params] with one namespace replaced.
 *
 * An empty object removes the namespace entirely so conversations do not carry
 * meaningless `{}` entries. Other provider namespaces are preserved.
 */
fun withProviderExtras(
    params: ChatParams,
    namespace: String,
    extras: JsonObject,
): ChatParams {
    val extra = params.extra.toMutableMap()

    if (extras.isEmpty()) {
        extra.remove(namespace)
    } else {
        extra[namespace] = extras
    }

    return params.copy(extra = JsonObject(extra))
}

/**
 * Drop extra namespaces that belong to a different provider preset.
 *
 * Called when a model switch changes provider so stale advanced options cannot
 * silently follow the conversation into another provider. Core parameters are
 * preserved regardless of the new model's support list.
 */
fun pruneExtrasForPreset(extra: JsonObject, presetId: String): JsonObject {
    val namespace = extraNamespaceForPreset(presetId)
    val value = extra[namespace] ?: return JsonObject(emptyMap())
    return JsonObject(mapOf(namespace to value))
}

/**
 * Parse provider extras from the editor.
 *
 * Accepts an empty string as an empty object, rejects non-object JSON, and
 * rejects protected core fields with a message listing them.
 */
fun parseProviderExtras(text: String): ProviderExtrasParseResult {
    val trimmed = text.trim()

    if (trimmed.isEmpty()) {
        return ProviderExtrasParseResult(ok = true, value = JsonObject(emptyMap()))
    }

    val parsed = try {
        Json.parseToJsonElement(trimmed)
    } catch (_: SerializationException) {
        return ProviderExtrasParseResult(
            ok = false,
            message = "Provider extras must be valid JSON.",
        )
    } catch (_: IllegalArgumentException) {
        return ProviderExtrasParseResult(
            ok = false,
            message = "Provider extras must be valid JSON.",
        )
    }

    val objectValue = parsed as? JsonObject
        ?: return ProviderExtrasParseResult(
            ok = false,
            message = "Provider extras must be a JSON object.",
        )

    val protectedFields = PROTECTED_EXTRA_FIELDS
        .filter { field -> field in objectValue }
        .sorted()

    if (protectedFields.isNotEmpty()) {
        return ProviderExtrasParseResult(
            ok = false,
            message = "Provider extras cannot override protected fields: " +
                "${protectedFields.joinToString(", ")}.",
        )
    }

    return ProviderExtrasParseResult(ok = true, value = objectValue)
}

private fun hasValue(params: ChatParams, key: CoreParameterKey): Boolean = when (key) {
    CoreParameterKey.STOP -> params.stop.isNotEmpty()
    CoreParameterKey.TEMPERATURE -> params.temperature != null
    CoreParameterKey.TOP_P -> params.topP != null
    CoreParameterKey.MAX_OUTPUT_TOKENS -> params.maxOutputTokens != null
    CoreParameterKey.REASONING_EFFORT -> params.reasoningEffort != null
}

/** Core parameters with a value that the selected model does not support. */
fun unsupportedParameters(
    params: ChatParams,
    supportedParameters: List<String>,
): List<CoreParameterDefinition> {
    val supported = supportedParameters.toSet()

    return CORE_PARAMETER_DEFINITIONS.filter { definition ->
        hasValue(params, definition.key) &&
            definition.supportedNames.none { name -> name in supported }
    }
}

/** Human-readable explanation for ignored values, or null when all are used. */
fun unsupportedParameterNote(
    params: ChatParams,
    supportedParameters: List<String>,
): String? {
    val unsupported = unsupportedParameters(params, supportedParameters)

    if (unsupported.isEmpty()) {
        return null
    }

    val labels = unsupported.joinToString(", ") { it.label }
    return "This model ignores these values, so they are not sent: $labels."
}
