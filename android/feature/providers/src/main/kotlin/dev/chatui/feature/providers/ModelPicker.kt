package dev.chatui.feature.providers

import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Pure model-picker selectors.
 *
 * The picker keeps pinned models at the top, then groups the remaining models
 * by provider. Hidden models are excluded unless the caller is managing
 * models. Search matches the label, remote id, and canonical slug.
 */
data class ModelPickerGroup(
    val key: String,
    val label: String,
    val pinned: Boolean,
    val models: List<Model>,
)

data class ModelAvailability(
    val available: Boolean,
    val message: String?,
)

private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

/** True when a model matches a free-text query. */
fun modelMatchesQuery(model: Model, query: String): Boolean {
    val needle = normalize(query)
    if (needle.isEmpty()) {
        return true
    }

    return listOf(model.label, model.remoteId, model.canonicalSlug ?: "")
        .map(::normalize)
        .any { it.contains(needle) }
}

private fun compareModels(left: Model, right: Model): Int {
    val byLabel = left.label.compareTo(right.label, ignoreCase = true)
    return if (byLabel != 0) byLabel else left.remoteId.compareTo(right.remoteId)
}

/**
 * Build the picker groups.
 *
 * Pinned models appear first in a dedicated group. The remaining models are
 * grouped by provider id and sorted by provider label. Models are never
 * deduplicated by canonical slug because distinct OpenRouter ids remain
 * separately selectable.
 */
fun buildModelPickerGroups(
    models: List<Model>,
    providers: List<Provider>,
    query: String = "",
    includeHidden: Boolean = false,
): List<ModelPickerGroup> {
    val providerLabels = providers.associate { it.id to it.label }
    val visible = models.filter { model ->
        (includeHidden || !model.hidden) && modelMatchesQuery(model, query)
    }
    val pinned = visible.filter { it.pinned }.sortedWith(::compareModels)
    val groups = mutableListOf<ModelPickerGroup>()

    if (pinned.isNotEmpty()) {
        groups += ModelPickerGroup(
            key = "pinned",
            label = "Pinned",
            pinned = true,
            models = pinned,
        )
    }

    val byProvider = linkedMapOf<String, MutableList<Model>>()
    for (model in visible) {
        if (model.pinned) {
            continue
        }
        byProvider.getOrPut(model.providerId) { mutableListOf() } += model
    }

    val providerGroups = byProvider.map { (providerId, providerModels) ->
        ModelPickerGroup(
            key = providerId,
            label = providerLabels[providerId] ?: providerId,
            pinned = false,
            models = providerModels.sortedWith(::compareModels),
        )
    }.sortedBy { it.label.lowercase(Locale.ROOT) }

    return groups + providerGroups
}

/** Capability chip labels for a model row. */
fun modelCapabilityChips(model: Model): List<String> = model.capabilities.map { capability ->
    when (capability.name) {
        "TEXT" -> "Text"
        "VISION" -> "Vision"
        "FILES" -> "Files"
        "TOOLS" -> "Tools"
        "REASONING" -> "Reasoning"
        "STREAMING" -> "Streaming"
        else -> capability.name.lowercase(Locale.ROOT)
    }
}

/**
 * Warn when a selected model cannot be used for a send.
 *
 * The check is deliberately non-substituting: callers show the warning and
 * keep the user's selection instead of silently choosing another model.
 */
fun modelAvailability(model: Model?, models: List<Model>): ModelAvailability {
    if (model == null) {
        return ModelAvailability(
            available = false,
            message = "The selected model is no longer available. " +
                "Choose another model before sending.",
        )
    }

    val current = models.firstOrNull { it.id == model.id }
        ?: return ModelAvailability(
            available = false,
            message = "Model ${model.remoteId} is no longer in the catalog. " +
                "Choose another model before sending.",
        )

    if (OutputModality.TEXT !in current.outputModalities) {
        return ModelAvailability(
            available = false,
            message = "Model ${current.remoteId} does not produce text output. " +
                "Choose another model before sending.",
        )
    }

    return ModelAvailability(available = true, message = null)
}

// ---------------------------------------------------------------------------
// Display formatting
// ---------------------------------------------------------------------------

private fun trimFixed(value: String): String =
    value.trimEnd('0').trimEnd('.')

/** Format a USD amount without losing small per-million prices. */
fun formatUsd(value: Double?): String {
    if (value == null || !value.isFinite()) {
        return "—"
    }
    if (value == 0.0) {
        return "$0"
    }

    val absolute = kotlin.math.abs(value)
    val digits = when {
        absolute >= 1 -> 2
        absolute >= 0.01 -> 4
        else -> 6
    }
    return "$" + trimFixed(String.format(Locale.US, "%.${digits}f", value))
}

/** Format a per-million-token price, or "Free" when the effective price is zero. */
fun formatPricePerMillion(value: Double?): String {
    if (value == null || !value.isFinite()) {
        return "—"
    }
    if (value == 0.0) {
        return "Free"
    }
    return "${formatUsd(value)} / M"
}

/** True when a model has no effective input, output, or per-request price. */
fun isFreeModel(model: Model): Boolean {
    if (model.remoteId.endsWith(":free")) {
        return true
    }

    val pricing = model.pricing ?: return false
    return effectivePrice(pricing) == 0.0
}

private fun effectivePrice(pricing: ModelPricing): Double =
    (pricing.inputPerMTok ?: 0.0) +
        (pricing.outputPerMTok ?: 0.0) +
        (pricing.perRequest ?: 0.0)

/** Human-readable price line for a model row. */
fun formatModelPrice(model: Model): String {
    if (isFreeModel(model)) {
        return "Free"
    }

    val pricing = model.pricing ?: return "Price unavailable"
    val parts = mutableListOf<String>()

    pricing.inputPerMTok?.let { parts += "${formatPricePerMillion(it)} input" }
    pricing.outputPerMTok?.let { parts += "${formatPricePerMillion(it)} output" }
    pricing.perRequest?.takeIf { it > 0 }?.let { parts += "${formatUsd(it)} / request" }

    return if (parts.isEmpty()) "Price unavailable" else parts.joinToString(" · ")
}

/** Compact context-window label, for example "128K context". */
fun formatContextWindow(value: Int?): String {
    if (value == null || value <= 0) {
        return "Context unknown"
    }
    if (value >= 1_000_000) {
        return "${trimFixed(String.format(Locale.US, "%.1f", value / 1_000_000.0))}M context"
    }
    if (value >= 1_000) {
        return "${trimFixed(String.format(Locale.US, "%.1f", value / 1_000.0))}K context"
    }
    return "$value context"
}

/** Input and output modality summary. */
fun formatModalities(model: Model): String {
    val input = model.inputModalities
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { it.name.lowercase(Locale.ROOT) }
        ?: "none"
    val output = model.outputModalities
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { it.name.lowercase(Locale.ROOT) }
        ?: "none"
    return "$input in · $output out"
}

/** CORS status copy for provider details. */
fun formatCors(value: BrowserCors): String = when (value) {
    BrowserCors.SUPPORTED -> "Supported"
    BrowserCors.UNSUPPORTED -> "Not supported"
    BrowserCors.UNKNOWN -> "Unknown"
}

/** Free-tier copy for provider details. */
fun formatFreeTier(value: Boolean?): String = when (value) {
    null -> "Unknown"
    true -> "Yes"
    false -> "No"
}

/** Format the non-secret `/key` rate-limit object for display. */
fun formatRateLimit(value: JsonObject?): String? {
    if (value == null) {
        return null
    }

    val requests = (value["requests"] as? JsonPrimitive)?.intOrNull
    val interval = (value["interval"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

    if (requests != null && interval != null) {
        return "$requests requests per $interval"
    }

    if (value.isEmpty()) {
        return null
    }

    return value.entries.joinToString(", ") { (key, entry) ->
        val rendered = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: entry.toString()
        "$key: $rendered"
    }
}

/** Moderation note from the cached catalog metadata, when present. */
fun moderationLabel(model: Model): String {
    val topProvider = model.metadata?.get("topProvider") as? JsonObject
    val moderated = (topProvider?.get("isModerated") as? JsonPrimitive)?.booleanOrNull
    return when (moderated) {
        true -> "Moderated"
        false -> "Not moderated"
        null -> "Not reported"
    }
}

/** Upstream context from the cached catalog metadata, when present. */
fun upstreamContext(model: Model): String {
    val topProvider = model.metadata?.get("topProvider") as? JsonObject
    val context = (topProvider?.get("contextLength") as? JsonPrimitive)?.intOrNull
    return formatContextWindow(context)
}
