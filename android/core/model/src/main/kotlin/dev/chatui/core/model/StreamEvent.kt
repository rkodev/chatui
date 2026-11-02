package dev.chatui.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Normalized stream event union from spec section 7.2.
 *
 * The UI reducer appends deltas to a streaming buffer and only updates the
 * persisted message at bounded intervals.
 */
@Serializable
sealed interface StreamEvent {
    @Serializable
    @SerialName("start")
    data class Start(val responseId: String?) : StreamEvent

    @Serializable
    @SerialName("text-delta")
    data class TextDelta(val text: String) : StreamEvent

    @Serializable
    @SerialName("reasoning-delta")
    data class ReasoningDelta(val text: String) : StreamEvent

    @Serializable
    @SerialName("tool-call-delta")
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String,
    ) : StreamEvent

    @Serializable
    @SerialName("usage")
    data class UsageEvent(val usage: Usage) : StreamEvent

    @Serializable
    @SerialName("finish")
    data class Finish(val reason: FinishReason) : StreamEvent

    @Serializable
    @SerialName("error")
    data class ErrorEvent(val error: ChatError) : StreamEvent
}
