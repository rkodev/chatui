package dev.chatui.core.providers

/**
 * Service-level failure that is safe to show to the UI.
 *
 * Adapter failures propagate unchanged so callers can map their
 * `ChatErrorCode` to actionable copy. This error covers missing records,
 * missing keys, and invalid service input.
 */
class ProviderServiceException(
    val code: ProviderServiceErrorCode,
    message: String,
    val providerId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Stable service error codes for UI mapping. */
enum class ProviderServiceErrorCode {
    NOT_FOUND,
    MISSING_KEY,
    INVALID_INPUT,
}
