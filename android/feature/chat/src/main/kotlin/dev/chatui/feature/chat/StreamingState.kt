package dev.chatui.feature.chat

import dev.chatui.core.model.ChatError
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Usage
import java.time.Instant

/**
 * Ephemeral state for one active assistant response.
 *
 * The runner reduces normalized provider events into this shape locally and
 * publishes snapshots to the ViewModel. Persistence is separate and bounded.
 */
data class StreamingState(
    val conversationId: String,
    val messageId: String,
    val responseId: String? = null,
    val started: Boolean = false,
    val text: String = "",
    val reasoning: String = "",
    val usage: Usage? = null,
    val finishReason: FinishReason? = null,
    val error: ChatError? = null,
    val status: MessageStatus = MessageStatus.STREAMING,
    val done: Boolean = false,
    val startedAt: Instant,
    val firstTokenAt: Instant? = null,
    val completedAt: Instant? = null,
    val updatedAt: Instant,
)

/** Create the initial streaming state for a new assistant message. */
fun createStreamingState(
    conversationId: String,
    messageId: String,
    startedAt: Instant,
): StreamingState = StreamingState(
    conversationId = conversationId,
    messageId = messageId,
    startedAt = startedAt,
    updatedAt = startedAt,
)

/**
 * Reduce one normalized stream event into the next streaming state.
 *
 * The function is pure: it never mutates the previous state and never touches
 * persistence. Text and reasoning deltas append in arrival order. The first
 * text or reasoning delta records the first-token timestamp.
 */
fun reduceStream(
    state: StreamingState,
    event: StreamEvent,
    now: Instant,
): StreamingState = when (event) {
    is StreamEvent.Start -> state.copy(
        responseId = event.responseId,
        started = true,
        updatedAt = now,
    )

    is StreamEvent.TextDelta -> state.copy(
        text = state.text + event.text,
        firstTokenAt = state.firstTokenAt ?: now,
        updatedAt = now,
    )

    is StreamEvent.ReasoningDelta -> state.copy(
        reasoning = state.reasoning + event.text,
        firstTokenAt = state.firstTokenAt ?: now,
        updatedAt = now,
    )

    is StreamEvent.UsageEvent -> state.copy(
        usage = event.usage,
        updatedAt = now,
    )

    is StreamEvent.Finish -> state.copy(
        finishReason = event.reason,
        status = if (state.error != null) MessageStatus.ERROR else MessageStatus.COMPLETE,
        done = true,
        completedAt = state.completedAt ?: now,
        updatedAt = now,
    )

    is StreamEvent.ErrorEvent -> state.copy(
        error = event.error,
        status = MessageStatus.ERROR,
        done = true,
        completedAt = state.completedAt ?: now,
        updatedAt = now,
    )

    // M1 renders text and reasoning only. Tool calls are preserved by the
    // adapter contract but are not part of the streaming presentation yet.
    is StreamEvent.ToolCallDelta -> state
}
