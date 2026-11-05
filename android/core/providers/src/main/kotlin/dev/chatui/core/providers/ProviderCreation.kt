package dev.chatui.core.providers

import dev.chatui.core.model.ProviderAuthType
import java.net.URI
import java.net.URISyntaxException

/** Input for creating a provider from an enabled contract preset. */
data class CreatePresetProviderInput(
    val presetId: String,
    val apiKey: String? = null,
    val label: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Input for creating a Custom OpenAI-compatible provider.
 *
 * [baseUrl] and [authType] are required. [headers] may contain non-secret
 * headers only; the key is stored separately in the secret store.
 */
data class CreateCustomProviderInput(
    val baseUrl: String,
    val authType: ProviderAuthType,
    val apiKey: String? = null,
    val label: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** Header names that must never be supplied as non-secret custom headers. */
internal val FORBIDDEN_HEADER_NAMES: Set<String> = setOf(
    "authorization",
    "proxy-authorization",
    "x-api-key",
    "x-goog-api-key",
    "api-key",
    "cookie",
    "set-cookie",
    "x-auth-token",
    "x-access-token",
)

/** RFC 7230 token characters accepted for header names. */
internal val HEADER_NAME_PATTERN = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")

internal fun authHeaderName(authType: ProviderAuthType): String? = when (authType) {
    ProviderAuthType.BEARER -> "authorization"
    ProviderAuthType.X_API_KEY -> "x-api-key"
    ProviderAuthType.QUERY_KEY -> null
    ProviderAuthType.NONE -> null
}

/**
 * Validate a Custom provider base URL.
 *
 * Only absolute http(s) URLs without credentials, a query string, or a
 * fragment are accepted. The returned value is trimmed.
 */
internal fun validateBaseUrl(value: String): String {
    val trimmed = value.trim()

    if (trimmed.isEmpty()) {
        throw invalidInput("Enter a base URL, for example https://api.example.com/v1.")
    }

    val uri = try {
        URI(trimmed)
    } catch (error: URISyntaxException) {
        throw invalidInput(
            "Enter a valid http(s) base URL, for example https://api.example.com/v1.",
            error,
        )
    }

    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        throw invalidInput("Base URL must start with http:// or https://.")
    }

    if (uri.host.isNullOrBlank()) {
        throw invalidInput(
            "Enter a valid http(s) base URL, for example https://api.example.com/v1.",
        )
    }

    if (uri.userInfo != null) {
        throw invalidInput(
            "Base URL must not include credentials. Store the key with the auth style instead.",
        )
    }

    if (uri.rawQuery != null || uri.rawFragment != null) {
        throw invalidInput("Base URL must not include a query string or fragment.")
    }

    return trimmed
}

/** Validate the auth style accepted by a Custom provider. */
internal fun validateCustomAuthType(value: ProviderAuthType): ProviderAuthType = when (value) {
    ProviderAuthType.BEARER,
    ProviderAuthType.X_API_KEY,
    ProviderAuthType.NONE,
    -> value

    ProviderAuthType.QUERY_KEY -> throw invalidInput(
        "Query-string API keys are not supported. Choose bearer or x-api-key.",
    )
}

/**
 * Validate the key for an auth style.
 *
 * `none` must not carry a key; `bearer` and `x-api-key` require one.
 */
internal fun validateApiKey(authType: ProviderAuthType, apiKey: String?): String? {
    if (authType == ProviderAuthType.NONE) {
        if (!apiKey.isNullOrBlank()) {
            throw invalidInput(
                "This authentication style does not use an API key. " +
                    "Remove the key or choose bearer or x-api-key.",
            )
        }
        return null
    }

    if (authType == ProviderAuthType.QUERY_KEY) {
        throw invalidInput(
            "Query-string API keys are not supported. Choose bearer or x-api-key.",
        )
    }

    val trimmed = apiKey?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        throw invalidInput("Enter an API key for the selected authentication style.")
    }

    return trimmed
}

/**
 * Validate non-secret headers.
 *
 * Authentication headers are rejected so a custom header can never shadow or
 * leak the key. Values must be non-blank and must not contain line breaks.
 */
internal fun validateHeaders(
    headers: Map<String, String>,
    authType: ProviderAuthType,
): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val authHeader = authHeaderName(authType)

    for ((rawName, rawValue) in headers) {
        val name = rawName.trim()

        if (name.isEmpty()) {
            throw invalidInput("Header names must not be empty.")
        }

        if (!HEADER_NAME_PATTERN.matches(name)) {
            throw invalidInput("Header \"$name\" is not a valid HTTP header name.")
        }

        val lower = name.lowercase()
        if (lower in FORBIDDEN_HEADER_NAMES || lower == authHeader) {
            throw invalidInput(
                "Header \"$name\" is reserved for authentication. " +
                    "Remove it and use the auth style instead.",
            )
        }

        val value = rawValue.trim()
        if (value.isEmpty()) {
            throw invalidInput("Header \"$name\" must have a value.")
        }

        if (value.contains('\r') || value.contains('\n')) {
            throw invalidInput("Header \"$name\" must not contain line breaks.")
        }

        result[name] = value
    }

    return result
}

private fun invalidInput(message: String, cause: Throwable? = null): ProviderServiceException =
    ProviderServiceException(
        ProviderServiceErrorCode.INVALID_INPUT,
        message,
        cause = cause,
    )
