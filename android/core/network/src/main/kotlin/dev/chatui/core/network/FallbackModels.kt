package dev.chatui.core.network

import dev.chatui.core.model.OutputModality

/**
 * Fallback model lists used when discovery is blocked or unavailable.
 *
 * The shared fixture in `contracts/fixtures/expected/fallback-models.json`
 * defines these lists for presets with catalog fixtures. Every other preset
 * falls back to its contract `defaultModelId`, so onboarding still works when
 * `/models` cannot be reached. Custom providers use their contract default as
 * a placeholder until the user adds a real model.
 */
private val EXPLICIT_FALLBACK_MODEL_IDS: Map<String, List<String>> = mapOf(
    "openai" to listOf("gpt-4o-mini", "gpt-4o"),
    "anthropic" to listOf("claude-sonnet-4-5", "claude-haiku-4-5"),
    "google" to listOf("gemini-2.5-flash", "gemini-2.5-pro"),
    "ollama" to listOf("llama3.2:latest"),
)

/**
 * Return the ordered fallback model ids for one preset id.
 *
 * OpenRouter intentionally keeps its M1 behavior: an empty catalog is a setup
 * error, not a fallback, because `/models` is public and reliable.
 */
fun fallbackModelIdsFor(
    presetId: String,
    preset: ProviderPreset? = null,
): List<String> {
    EXPLICIT_FALLBACK_MODEL_IDS[presetId]?.let { return it.toList() }

    if (presetId == "openrouter") {
        return emptyList()
    }

    val defaultModelId = preset?.defaultModelId?.trim().orEmpty()
    return if (defaultModelId.isEmpty()) emptyList() else listOf(defaultModelId)
}

/** Minimal catalog entry used by the fallback selection rule. */
data class FallbackCatalogEntry(
    val remoteId: String,
    val hidden: Boolean,
    val outputModalities: List<OutputModality>,
)

/** Result of the shared fallback selection rule. */
data class FallbackSelection(
    val selectedModelId: String?,
    val notice: String?,
)

private fun FallbackCatalogEntry.isUsableTextModel(): Boolean =
    !hidden && OutputModality.TEXT in outputModalities

/**
 * Apply the shared fallback rule.
 *
 * 1. An empty catalog means discovery was blocked: pick the first fallback id,
 *    or report that no fallback exists.
 * 2. Use the preset default when it exists.
 * 3. Otherwise use the first fallback id present in the catalog.
 * 4. Otherwise use the first non-hidden text-output model by `remoteId`.
 * 5. Otherwise report that no text model is available.
 */
fun selectFallbackModel(
    catalog: List<FallbackCatalogEntry>,
    presetDefaultModelId: String,
    fallbackModelIds: List<String>,
): FallbackSelection {
    if (catalog.isEmpty()) {
        val fallback = fallbackModelIds.firstOrNull()
        return if (fallback == null) {
            FallbackSelection(
                selectedModelId = null,
                notice = "Discovery unavailable; no fallback model is available.",
            )
        } else {
            FallbackSelection(
                selectedModelId = fallback,
                notice = "Discovery unavailable; using fallback model $fallback.",
            )
        }
    }

    val presetDefault = catalog.firstOrNull { it.remoteId == presetDefaultModelId }
    if (presetDefault != null) {
        return FallbackSelection(selectedModelId = presetDefault.remoteId, notice = null)
    }

    for (id in fallbackModelIds) {
        val found = catalog.firstOrNull { it.remoteId == id && it.isUsableTextModel() }
        if (found != null) {
            return FallbackSelection(
                selectedModelId = found.remoteId,
                notice = "Default model $presetDefaultModelId is unavailable; " +
                    "using ${found.remoteId}.",
            )
        }
    }

    val firstText = catalog
        .filter { it.isUsableTextModel() }
        .minByOrNull { it.remoteId }

    if (firstText != null) {
        return FallbackSelection(
            selectedModelId = firstText.remoteId,
            notice = "Default model $presetDefaultModelId is unavailable; " +
                "using ${firstText.remoteId}.",
        )
    }

    return FallbackSelection(
        selectedModelId = null,
        notice = "Default model $presetDefaultModelId is unavailable; " +
            "no text model is available.",
    )
}
