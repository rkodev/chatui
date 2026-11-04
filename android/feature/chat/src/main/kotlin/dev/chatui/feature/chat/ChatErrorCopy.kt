package dev.chatui.feature.chat

import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import kotlinx.serialization.json.JsonObject

/** Plain-language copy for one normalized chat error. */
data class ChatErrorCopy(
    val title: String,
    val message: String,
    val details: String?,
)

private const val CORS_MESSAGE =
    "The request could not reach the provider. This may be a browser CORS restriction. " +
        "Try the Android app or a self-hosted proxy."

private fun detailsFor(error: ChatError): String? {
    error.details?.let { details: JsonObject -> return details.toString() }

    val parts = buildList {
        error.status?.let { add("HTTP $it") }
        error.requestId?.let { add("Request $it") }
        error.retryAfterSeconds?.let { add("Retry after ${it}s") }
    }

    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private fun copyForCode(code: ChatErrorCode): Pair<String, String> = when (code) {
    ChatErrorCode.AUTH -> "API key rejected" to
        "Your API key was rejected. Check the key and try again."
    ChatErrorCode.PERMISSION -> "Permission denied" to
        "This key does not have permission to use the provider. Check the key permissions."
    ChatErrorCode.RATE_LIMIT -> "Rate limited" to
        "This provider is rate limiting requests. Wait a moment and try again."
    ChatErrorCode.QUOTA -> "Insufficient credits" to
        "The provider reports insufficient credits. Add credits or choose a free model."
    ChatErrorCode.MODEL_NOT_FOUND -> "Model unavailable" to
        "The selected model is unavailable. Choose another model."
    ChatErrorCode.CONTEXT_LENGTH -> "Conversation too long" to
        "The conversation is too long for this model. Start a new chat or reduce the context."
    ChatErrorCode.CONTENT_FILTER -> "Request blocked" to
        "The provider blocked the request. Try a different prompt or model."
    ChatErrorCode.INVALID_REQUEST -> "Request rejected" to
        "The provider rejected the request. Check the model settings and try again."
    ChatErrorCode.NETWORK, ChatErrorCode.CORS -> "Could not reach the provider" to CORS_MESSAGE
    ChatErrorCode.TIMEOUT -> "Request timed out" to
        "The request timed out. Check your connection and try again."
    ChatErrorCode.CANCELLED -> "Response cancelled" to
        "The response was cancelled. Partial content is preserved."
    ChatErrorCode.PARSE -> "Unexpected response" to
        "The provider returned an unexpected response. Try again."
    ChatErrorCode.STORAGE -> "Could not save the message" to
        "ChatUI could not save the message on this device. Check storage permissions and try again."
    ChatErrorCode.PROVIDER, ChatErrorCode.UNKNOWN -> "Could not complete the request" to
        "The provider could not complete the request. Try again."
}

/**
 * Map a normalized chat error to actionable copy.
 *
 * Raw provider JSON is never the primary message; it is exposed only through
 * the optional details disclosure.
 */
fun describeChatError(error: ChatError): ChatErrorCopy {
    val (title, message) = copyForCode(error.code)
    return ChatErrorCopy(title = title, message = message, details = detailsFor(error))
}
