@file:UseSerializers(InstantIso8601Serializer::class)

package dev.chatui.core.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

/** Capability flags from spec section 6.2. */
@Serializable
enum class ModelCapability {
    @SerialName("text")
    TEXT,

    @SerialName("vision")
    VISION,

    @SerialName("files")
    FILES,

    @SerialName("tools")
    TOOLS,

    @SerialName("reasoning")
    REASONING,

    @SerialName("streaming")
    STREAMING,
}

/** Input modalities accepted by a model. */
@Serializable
enum class InputModality {
    @SerialName("text")
    TEXT,

    @SerialName("image")
    IMAGE,

    @SerialName("file")
    FILE,

    @SerialName("audio")
    AUDIO,

    @SerialName("video")
    VIDEO,
}

/** Output modalities produced by a model. */
@Serializable
enum class OutputModality {
    @SerialName("text")
    TEXT,

    @SerialName("image")
    IMAGE,

    @SerialName("audio")
    AUDIO,
}

/** Where a model record came from. */
@Serializable
enum class ModelSource {
    @SerialName("discovered")
    DISCOVERED,

    @SerialName("preset")
    PRESET,

    @SerialName("manual")
    MANUAL,
}

/**
 * Optional pricing fields. Absent fields are omitted from JSON rather than
 * encoded as `null`, matching the contract schema.
 */
@Serializable
data class ModelPricing(
    val inputPerMTok: Double? = null,
    val outputPerMTok: Double? = null,
    val perRequest: Double? = null,
    val currency: String? = null,
)

/**
 * Model entity from spec section 6.2.
 *
 * Models are unique on `(providerId, remoteId)`. Provider-specific catalog
 * fields belong in [metadata]; this type does not contain request payloads.
 */
@Serializable
data class Model(
    val id: String,
    val providerId: String,
    val remoteId: String,
    val label: String,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
    val capabilities: List<ModelCapability>,
    val pricing: ModelPricing?,
    val canonicalSlug: String?,
    val inputModalities: List<InputModality>,
    val outputModalities: List<OutputModality>,
    val supportedParameters: List<String>,
    val metadata: JsonObject?,
    val hidden: Boolean,
    val pinned: Boolean,
    val source: ModelSource,
    val createdAt: Instant,
    val updatedAt: Instant,
)
