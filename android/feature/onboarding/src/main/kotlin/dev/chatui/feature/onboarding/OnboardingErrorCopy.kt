package dev.chatui.feature.onboarding

import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.network.OpenRouterRequestException
import dev.chatui.core.providers.ProviderServiceErrorCode
import dev.chatui.core.providers.ProviderServiceException

private const val AUTH_MESSAGE = "Your API key was rejected. Check the key and try again."
private const val NETWORK_MESSAGE =
    "The request could not reach OpenRouter. Check your connection and try again."

private fun redact(message: String, secret: String?): String {
    if (secret.isNullOrEmpty()) {
        return message
    }

    return message.replace(secret, "[redacted]")
}

private fun copyForCode(
    code: ChatErrorCode,
    rawMessage: String,
    secret: String?,
): OnboardingErrorCopy {
    val details = redact(rawMessage, secret)

    return when (code) {
        ChatErrorCode.AUTH -> OnboardingErrorCopy(
            title = "API key rejected",
            message = AUTH_MESSAGE,
            details = details,
        )

        ChatErrorCode.PERMISSION -> OnboardingErrorCopy(
            title = "Permission denied",
            message = "This key does not have permission to use OpenRouter. " +
                "Check the key permissions and try again.",
            details = details,
        )

        ChatErrorCode.QUOTA -> OnboardingErrorCopy(
            title = "Insufficient credits",
            message = "OpenRouter reports insufficient credits. " +
                "Add credits or choose a free model.",
            details = details,
        )

        ChatErrorCode.RATE_LIMIT -> OnboardingErrorCopy(
            title = "Rate limited",
            message = "OpenRouter is rate limiting requests. Wait a moment and try again.",
            details = details,
        )

        ChatErrorCode.MODEL_NOT_FOUND -> OnboardingErrorCopy(
            title = "Model unavailable",
            message = "The selected model is unavailable. Choose another model.",
            details = details,
        )

        ChatErrorCode.CONTEXT_LENGTH -> OnboardingErrorCopy(
            title = "Conversation too long",
            message = "The conversation is too long for this model. " +
                "Start a new chat or reduce the context.",
            details = details,
        )

        ChatErrorCode.CONTENT_FILTER -> OnboardingErrorCopy(
            title = "Request blocked",
            message = "OpenRouter blocked the request. Check the key and try again.",
            details = details,
        )

        ChatErrorCode.INVALID_REQUEST -> OnboardingErrorCopy(
            title = "Request rejected",
            message = "OpenRouter rejected the request. Check the key and try again.",
            details = details,
        )

        ChatErrorCode.NETWORK,
        ChatErrorCode.CORS,
        -> OnboardingErrorCopy(
            title = "Could not reach OpenRouter",
            message = NETWORK_MESSAGE,
            details = details,
        )

        ChatErrorCode.TIMEOUT -> OnboardingErrorCopy(
            title = "Request timed out",
            message = "The request timed out. Check your connection and try again.",
            details = details,
        )

        ChatErrorCode.CANCELLED -> OnboardingErrorCopy(
            title = "Connection cancelled",
            message = "The connection was cancelled. Try again.",
            details = details,
        )

        ChatErrorCode.PARSE -> OnboardingErrorCopy(
            title = "Unexpected response",
            message = "OpenRouter returned an unexpected response. Try again.",
            details = details,
        )

        ChatErrorCode.STORAGE -> OnboardingErrorCopy(
            title = "Could not save the key",
            message = "ChatUI could not save the key on this device. Try again.",
            details = details,
        )

        ChatErrorCode.PROVIDER,
        ChatErrorCode.UNKNOWN,
        -> OnboardingErrorCopy(
            title = "Could not connect",
            message = "OpenRouter could not complete the request. Try again.",
            details = details,
        )
    }
}

private fun copyForProviderService(
    error: ProviderServiceException,
    secret: String?,
): OnboardingErrorCopy {
    val details = redact(error.message ?: "", secret)

    return when (error.code) {
        ProviderServiceErrorCode.MISSING_KEY -> OnboardingErrorCopy(
            title = "Key unavailable",
            message = "The key could not be read from this device. " +
                "Re-enter the key and try again.",
            details = details,
        )

        ProviderServiceErrorCode.INVALID_INPUT -> OnboardingErrorCopy(
            title = "Check the key",
            message = "Enter a valid OpenRouter API key and try again.",
            details = details,
        )

        ProviderServiceErrorCode.NOT_FOUND -> OnboardingErrorCopy(
            title = "Provider not found",
            message = "The provider record was not found. Try connecting again.",
            details = details,
        )
    }
}

private fun copyForOnboarding(
    error: OnboardingException,
    secret: String?,
): OnboardingErrorCopy {
    val details = redact(error.message ?: "", secret)

    return when (error.code) {
        OnboardingErrorCode.INVALID_INPUT -> OnboardingErrorCopy(
            title = "Enter an API key",
            message = error.message ?: "Enter an OpenRouter API key to continue.",
            details = null,
        )

        OnboardingErrorCode.NO_MODEL -> OnboardingErrorCopy(
            title = "No model available",
            message = error.message ?: "No text model is available for OpenRouter.",
            details = null,
        )

        OnboardingErrorCode.STORAGE -> OnboardingErrorCopy(
            title = "Could not save setup",
            message = error.message ?: "ChatUI could not save the setup on this device.",
            details = details,
        )
    }
}

/**
 * Map any onboarding failure to plain-language, actionable copy.
 *
 * The typed key is redacted from every message and detail string so it can
 * never be echoed back into the UI or a diagnostics panel.
 */
internal fun describeOnboardingError(
    error: Throwable,
    secret: String?,
): OnboardingErrorCopy = when (error) {
    is OpenRouterRequestException -> copyForCode(error.code, error.message, secret)
    is ProviderServiceException -> copyForProviderService(error, secret)
    is OnboardingException -> copyForOnboarding(error, secret)
    else -> OnboardingErrorCopy(
        title = "Could not connect",
        message = "Something went wrong while connecting. Try again.",
        details = redact(error.message ?: "", secret),
    )
}

/** Non-blocking catalog failure copy. Connecting remains available. */
internal fun describeCatalogError(error: Throwable): String {
    val suffix = " You can still connect; models will load after validation."

    return if (
        error is OpenRouterRequestException &&
        (error.code == ChatErrorCode.CORS || error.code == ChatErrorCode.NETWORK)
    ) {
        "Could not load the model catalog. Check your connection.$suffix"
    } else {
        "Could not load the model catalog.$suffix"
    }
}
