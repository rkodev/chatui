@file:UseSerializers(InstantIso8601Serializer::class)

package dev.chatui.core.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Message author role from spec section 6.2. */
@Serializable
enum class MessageRole {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("tool")
    TOOL,
}

/** Message lifecycle status from spec section 6.2. */
@Serializable
enum class MessageStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("streaming")
    STREAMING,

    @SerialName("complete")
    COMPLETE,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("error")
    ERROR,
}

/** Normalized finish reason from spec section 7.2. */
@Serializable
enum class FinishReason {
    @SerialName("stop")
    STOP,

    @SerialName("length")
    LENGTH,

    @SerialName("content_filter")
    CONTENT_FILTER,

    @SerialName("tool_calls")
    TOOL_CALLS,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("error")
    ERROR,

    @SerialName("unknown")
    UNKNOWN,
}

/**
 * Content part union from spec section 6.2.
 *
 * Phase 1 renders and persists text, image, file, and reasoning. Tool parts are
 * reserved and may be ignored by UI code but must round-trip through export.
 */
@Serializable
sealed interface ContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart

    @Serializable
    @SerialName("image")
    data class Image(
        val attachmentId: String,
        val mimeType: String,
        val alt: String,
    ) : ContentPart

    @Serializable
    @SerialName("file")
    data class File(
        val attachmentId: String,
        val mimeType: String,
        val name: String,
    ) : ContentPart

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : ContentPart

    @Serializable
    @SerialName("tool-call")
    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
    ) : ContentPart

    @Serializable
    @SerialName("tool-result")
    data class ToolResult(
        val toolCallId: String,
        val content: String,
    ) : ContentPart
}

/** Attachment kind from spec section 6.2. */
@Serializable
enum class AttachmentKind {
    @SerialName("image")
    IMAGE,

    @SerialName("file")
    FILE,
}

/** Attachment metadata from spec section 6.2. Binary data lives outside the model. */
@Serializable
data class Attachment(
    val id: String,
    val messageId: String?,
    val kind: AttachmentKind,
    val mimeType: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val storageKey: String,
    val width: Int?,
    val height: Int?,
    val createdAt: Instant,
)

/** Token and cost usage from spec section 6.2. */
@Serializable
data class Usage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val costUsd: Double?,
    val estimated: Boolean,
)

/** Message entity from spec section 6.2. */
@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val sequence: Long,
    val role: MessageRole,
    val parts: List<ContentPart>,
    val status: MessageStatus,
    val providerId: String?,
    val modelId: String?,
    val modelLabel: String?,
    val usage: Usage?,
    val finishReason: FinishReason?,
    val error: ChatError?,
    val latencyMs: Long?,
    val firstTokenMs: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)
