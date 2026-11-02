@file:UseSerializers(InstantIso8601Serializer::class)

package dev.chatui.core.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

/** Provider kind from spec section 6.2. */
@Serializable
enum class ProviderKind {
    @SerialName("openai-compatible")
    OPENAI_COMPATIBLE,

    @SerialName("anthropic")
    ANTHROPIC,

    @SerialName("google-gemini")
    GOOGLE_GEMINI,
}

/** How a provider authenticates requests. */
@Serializable
enum class ProviderAuthType {
    @SerialName("bearer")
    BEARER,

    @SerialName("x-api-key")
    X_API_KEY,

    @SerialName("query-key")
    QUERY_KEY,

    @SerialName("none")
    NONE,
}

/** Browser CORS capability advertised by a provider preset. */
@Serializable
enum class BrowserCors {
    @SerialName("supported")
    SUPPORTED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("unknown")
    UNKNOWN,
}

/**
 * Provider entity from spec section 6.2.
 *
 * [secretId] references the secret store; the key itself is never stored here.
 * [headers] may contain non-secret headers only.
 */
@Serializable
data class Provider(
    val id: String,
    val presetId: String,
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val authType: ProviderAuthType,
    val secretId: String?,
    val headers: Map<String, String>,
    val enabled: Boolean,
    val browserCors: BrowserCors,
    val metadata: JsonObject?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
