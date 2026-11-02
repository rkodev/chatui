@file:UseSerializers(InstantIso8601Serializer::class)

package dev.chatui.core.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

/** Normalized error codes from spec section 6.2. */
@Serializable
enum class ChatErrorCode {
    @SerialName("auth")
    AUTH,

    @SerialName("permission")
    PERMISSION,

    @SerialName("rate_limit")
    RATE_LIMIT,

    @SerialName("quota")
    QUOTA,

    @SerialName("model_not_found")
    MODEL_NOT_FOUND,

    @SerialName("context_length")
    CONTEXT_LENGTH,

    @SerialName("content_filter")
    CONTENT_FILTER,

    @SerialName("invalid_request")
    INVALID_REQUEST,

    @SerialName("network")
    NETWORK,

    @SerialName("cors")
    CORS,

    @SerialName("timeout")
    TIMEOUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("provider")
    PROVIDER,

    @SerialName("parse")
    PARSE,

    @SerialName("storage")
    STORAGE,

    @SerialName("unknown")
    UNKNOWN,
}

/**
 * Normalized chat error from spec section 6.2.
 *
 * User-facing messages must be actionable and must not include keys or raw
 * headers.
 */
@Serializable
data class ChatError(
    val code: ChatErrorCode,
    val message: String,
    val providerId: String?,
    val status: Int?,
    val retryable: Boolean,
    val retryAfterSeconds: Double?,
    val requestId: String?,
    val details: JsonObject?,
    val at: Instant,
)
