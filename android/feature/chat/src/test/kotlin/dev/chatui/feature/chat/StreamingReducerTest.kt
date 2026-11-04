package dev.chatui.feature.chat

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Usage
import java.time.Instant
import org.junit.Test

class StreamingReducerTest {
    private val start = Instant.parse("2026-11-03T12:00:00Z")

    @Test
    fun `text and reasoning deltas append in order`() {
        var state = createStreamingState("conversation-1", "message-1", start)
        state = reduceStream(state, StreamEvent.Start("response-1"), start.plusMillis(10))
        state = reduceStream(state, StreamEvent.ReasoningDelta("think "), start.plusMillis(20))
        state = reduceStream(state, StreamEvent.TextDelta("Hello"), start.plusMillis(30))
        state = reduceStream(state, StreamEvent.TextDelta(" world"), start.plusMillis(40))

        assertThat(state.responseId).isEqualTo("response-1")
        assertThat(state.started).isTrue()
        assertThat(state.reasoning).isEqualTo("think ")
        assertThat(state.text).isEqualTo("Hello world")
        assertThat(state.firstTokenAt).isEqualTo(start.plusMillis(20))
    }

    @Test
    fun `usage and finish reduce into the final state`() {
        val usage = Usage(
            inputTokens = 10,
            outputTokens = 20,
            totalTokens = 30,
            costUsd = 0.001,
            estimated = false,
        )
        var state = createStreamingState("conversation-1", "message-1", start)
        state = reduceStream(state, StreamEvent.TextDelta("Hi"), start.plusMillis(10))
        state = reduceStream(state, StreamEvent.UsageEvent(usage), start.plusMillis(20))
        state = reduceStream(state, StreamEvent.Finish(FinishReason.STOP), start.plusMillis(30))

        assertThat(state.usage).isEqualTo(usage)
        assertThat(state.finishReason).isEqualTo(FinishReason.STOP)
        assertThat(state.status).isEqualTo(MessageStatus.COMPLETE)
        assertThat(state.done).isTrue()
        assertThat(state.completedAt).isEqualTo(start.plusMillis(30))
    }

    @Test
    fun `error preserves partial text`() {
        val error = ChatError(
            code = ChatErrorCode.PROVIDER,
            message = "Provider failed",
            providerId = "provider-1",
            status = 500,
            retryable = true,
            retryAfterSeconds = null,
            requestId = null,
            details = null,
            at = start,
        )
        var state = createStreamingState("conversation-1", "message-1", start)
        state = reduceStream(state, StreamEvent.TextDelta("Partial"), start.plusMillis(10))
        state = reduceStream(state, StreamEvent.ErrorEvent(error), start.plusMillis(20))

        assertThat(state.text).isEqualTo("Partial")
        assertThat(state.error).isEqualTo(error)
        assertThat(state.status).isEqualTo(MessageStatus.ERROR)
        assertThat(state.done).isTrue()
    }

    @Test
    fun `tool call deltas are ignored`() {
        val state = createStreamingState("conversation-1", "message-1", start)
        val next = reduceStream(
            state,
            StreamEvent.ToolCallDelta(
                index = 0,
                id = "call-1",
                name = "tool",
                argumentsDelta = "{}",
            ),
            start.plusMillis(10),
        )

        assertThat(next).isEqualTo(state)
    }
}
