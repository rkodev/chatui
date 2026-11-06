package dev.chatui.core.network

import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Roles allowed in a normalized request from spec section 7.3. */
@Serializable
enum class NormalizedRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("tool")
    TOOL,
}

/** One normalized message. The system prompt is separate on the request. */
@Serializable
data class NormalizedMessage(
    val role: NormalizedRole,
    val parts: List<ContentPart>,
)

/**
 * Attachment bytes resolved by the caller before request serialization.
 *
 * The normalized request never carries bytes inside message parts; parts
 * reference attachments by id and the caller supplies the resolved bytes here.
 */
@Serializable
data class ResolvedAttachment(
    val mimeType: String,
    val name: String? = null,
    val dataBase64: String,
)

/**
 * Normalized chat request from spec section 7.3.
 *
 * The system prompt is separate from [messages]. Only completed or partial
 * assistant text is included in context; reasoning content is not sent back
 * unless a provider explicitly requires it within the same turn.
 */
@Serializable
data class NormalizedChatRequest(
    val model: String,
    val system: String?,
    val messages: List<NormalizedMessage>,
    val params: ChatParams,
    val attachments: Map<String, ResolvedAttachment> = emptyMap(),
)
