package dev.chatui.feature.chat

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.chatui.core.database.recovery.INTERRUPTION_NOTE
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InterruptedMessageNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")

    @Test
    fun `renders the interruption explanation for a recovered message`() {
        val message = message(
            status = MessageStatus.CANCELLED,
            error = ChatError(
                code = ChatErrorCode.CANCELLED,
                message = INTERRUPTION_NOTE,
                providerId = "provider-1",
                status = null,
                retryable = false,
                retryAfterSeconds = null,
                requestId = null,
                details = null,
                at = now,
            ),
        )

        composeRule.setContent { InterruptedMessageNotice(message) }

        composeRule.onNodeWithTag(INTERRUPTED_MESSAGE_TAG)
            .assertTextEquals(INTERRUPTION_NOTE)
    }

    @Test
    fun `renders nothing for a complete message`() {
        composeRule.setContent {
            InterruptedMessageNotice(message(status = MessageStatus.COMPLETE))
        }

        composeRule.onNodeWithTag(INTERRUPTED_MESSAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun `renders nothing for a cancelled message without the interruption note`() {
        composeRule.setContent {
            InterruptedMessageNotice(
                message(
                    status = MessageStatus.CANCELLED,
                    error = ChatError(
                        code = ChatErrorCode.CANCELLED,
                        message = "Stopped by the user.",
                        providerId = null,
                        status = null,
                        retryable = false,
                        retryAfterSeconds = null,
                        requestId = null,
                        details = null,
                        at = now,
                    ),
                ),
            )
        }

        composeRule.onNodeWithTag(INTERRUPTED_MESSAGE_TAG).assertDoesNotExist()
    }

    private fun message(
        status: MessageStatus,
        error: ChatError? = null,
    ): Message = Message(
        id = "message-1",
        conversationId = "conversation-1",
        sequence = 1,
        role = MessageRole.ASSISTANT,
        parts = listOf(ContentPart.Text("Partial answer")),
        status = status,
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model One",
        usage = null,
        finishReason = null,
        error = error,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = now,
        updatedAt = now,
        deletedAt = null,
    )
}
