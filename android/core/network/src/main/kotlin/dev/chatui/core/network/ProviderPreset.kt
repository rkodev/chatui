package dev.chatui.core.network

import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provider preset from `contracts/providers.json`.
 *
 * The values are never duplicated in Kotlin source. This type only describes
 * the contract shape so the registry can validate the checked-in document.
 */
@Serializable
data class ProviderPreset(
    val id: String,
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val auth: ProviderAuth,
    val keyValidation: KeyValidation?,
    val modelDiscovery: ModelDiscovery,
    val defaultHeaders: Map<String, String>,
    val attributionHeaders: Map<String, String>,
    val browserCors: BrowserCors,
    val docsUrl: String?,
    val defaultModelId: String,
    val default: Boolean,
    val reference: Boolean,
    val required: Boolean,
)

/** Authentication shape advertised by a provider preset. */
@Serializable
data class ProviderAuth(
    val type: ProviderAuthType,
    val headerName: String? = null,
    val scheme: String? = null,
    val queryParam: String? = null,
)

/** Key-validation method. The contract currently allows only `GET`. */
@Serializable
enum class KeyValidationMethod {
    @SerialName("GET")
    GET,
}

/** Optional key-validation endpoint for a provider preset. */
@Serializable
data class KeyValidation(
    val method: KeyValidationMethod,
    val path: String,
)

/** Model-discovery response format. */
@Serializable
enum class ModelDiscoveryFormat {
    @SerialName("openrouter")
    OPENROUTER,

    @SerialName("openai")
    OPENAI,

    @SerialName("anthropic")
    ANTHROPIC,

    @SerialName("gemini")
    GEMINI,

    @SerialName("ollama-tags")
    OLLAMA_TAGS,
}

/** Model-discovery endpoint and cache policy for a provider preset. */
@Serializable
data class ModelDiscovery(
    val path: String,
    val format: ModelDiscoveryFormat,
    val public: Boolean,
    val cacheTtlSeconds: Int,
)
