package dev.chatui.core.network

import dev.chatui.core.model.InputModality
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.OutputModality
import kotlinx.serialization.json.JsonObject

/**
 * Provider-neutral model catalog entry returned by
 * [ProviderAdapter.listModels].
 *
 * This is intentionally smaller than the persisted `Model` entity: the caller
 * assigns local IDs, provider IDs, timestamps, and source.
 */
data class ModelInfo(
    val remoteId: String,
    val label: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val capabilities: List<ModelCapability> = emptyList(),
    val pricing: ModelPricing? = null,
    val canonicalSlug: String? = null,
    val inputModalities: List<InputModality> = emptyList(),
    val outputModalities: List<OutputModality> = emptyList(),
    val supportedParameters: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    val isFree: Boolean = false,
)
