package dev.chatui.feature.chat

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
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
class ChatScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")

    private fun state(
        messages: List<dev.chatui.core.model.Message> = emptyList(),
        streaming: StreamingState? = null,
        availability: ChatAvailability = ChatAvailability(available = true),
        draft: String = "",
        hasOlder: Boolean = false,
        announcement: String = "",
        error: ChatError? = null,
    ) = ChatUiState(
        conversationId = "conversation-1",
        conversation = testConversation(),
        messages = messages,
        streaming = streaming,
        draft = draft,
        loading = false,
        hasOlder = hasOlder,
        availability = availability,
        announcement = announcement,
        error = error,
    )

    private fun screen(
        state: ChatUiState,
        onSend: () -> Unit = {},
        onStop: () -> Unit = {},
        onLoadOlder: () -> Unit = {},
        onRetryMessage: (dev.chatui.core.model.Message) -> Unit = {},
        onOpenProviders: () -> Unit = {},
    ) {
        composeRule.setContent {
            ChatUiTheme {
                ChatScreen(
                    state = state,
                    onDraftChange = {},
                    onSend = onSend,
                    onStop = onStop,
                    onLoadOlder = onLoadOlder,
                    onRetryMessage = onRetryMessage,
                    onDismissError = {},
                    onOpenProviders = onOpenProviders,
                    zoneId = java.time.ZoneOffset.UTC,
                )
            }
        }
    }

    @Test
    fun `loading and not-found states render`() {
        screen(ChatUiState(conversationId = "conversation-1", loading = true))
        composeRule.onNodeWithTag(ChatScreenTestTags.Loading).assertIsDisplayed()
    }

    @Test
    fun `empty conversation shows the welcome state and composer`() {
        screen(state())

        composeRule.onNodeWithTag(ChatScreenTestTags.Empty).assertIsDisplayed()
        composeRule.onNodeWithText("Start the conversation").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Composer).assertIsDisplayed()
    }

    @Test
    fun `no provider disables the composer and opens providers`() {
        var opened = 0
        screen(
            state(
                availability = ChatAvailability(
                    available = false,
                    reason = ChatUnavailableReason.NO_PROVIDER,
                    message = "No provider is configured.",
                ),
            ),
            onOpenProviders = { opened += 1 },
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.NoProvider).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatScreenTestTags.OpenProviders).performClick()
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertIsNotEnabled()

        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `model unavailable shows its state`() {
        screen(
            state(
                availability = ChatAvailability(
                    available = false,
                    reason = ChatUnavailableReason.MODEL_UNAVAILABLE,
                    message = "The selected model is unavailable. Choose another model.",
                ),
            ),
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.ModelUnavailable).assertIsDisplayed()
    }

    @Test
    fun `cancelled and interrupted messages explain what happened`() {
        val cancelled = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.CANCELLED,
            text = "Partial",
        )
        val interrupted = testMessage(
            id = "assistant-2",
            sequence = 3,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.CANCELLED,
            text = "Recovered",
        ).copy(
            error = ChatError(
                code = ChatErrorCode.CANCELLED,
                message = "The app closed before this response finished. " +
                    "Partial content is preserved.",
                providerId = null,
                status = null,
                retryable = false,
                retryAfterSeconds = null,
                requestId = null,
                details = null,
                at = now,
            ),
        )

        screen(state(messages = listOf(cancelled, interrupted)))

        composeRule.onNodeWithText("Response cancelled. Partial content is preserved.")
            .assertExists()
        composeRule.onNodeWithText(
            "The app closed before this response finished. Partial content is preserved.",
        ).assertExists()
    }

    @Test
    fun `error message renders an inline card and retry intent`() {
        var retried: dev.chatui.core.model.Message? = null
        val failed = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.ERROR,
            text = "Partial",
        ).copy(
            error = ChatError(
                code = ChatErrorCode.RATE_LIMIT,
                message = "Rate limited",
                providerId = "provider-1",
                status = 429,
                retryable = true,
                retryAfterSeconds = 30.0,
                requestId = null,
                details = null,
                at = now,
            ),
        )

        screen(
            state(messages = listOf(failed)),
            onRetryMessage = { retried = it },
        )

        composeRule.onNodeWithTag(ChatMessageTestTags.Error).assertIsDisplayed()
        composeRule.onNodeWithText("Rate limited").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatMessageTestTags.Retry).performClick()

        assertThat(retried?.id).isEqualTo("assistant-1")
    }

    @Test
    fun `streaming row replaces the persisted placeholder`() {
        val user = testMessage(
            id = "user-1",
            sequence = 1,
            role = MessageRole.USER,
            text = "Hello",
        )
        val placeholder = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.STREAMING,
            text = "",
        )
        val streaming = createStreamingState(
            conversationId = "conversation-1",
            messageId = "assistant-1",
            startedAt = now,
        ).copy(text = "Hi", status = MessageStatus.STREAMING)

        screen(state(messages = listOf(user, placeholder), streaming = streaming))

        composeRule.onNodeWithTag(ChatMessageListTestTags.Streaming).assertIsDisplayed()
        composeRule.onNodeWithText("Hi").assertIsDisplayed()
        composeRule.onAllNodesWithTag(ChatMessageTestTags.text("assistant-1"))
            .assertCountEquals(1)
    }

    @Test
    fun `older messages are loaded on demand`() {
        var loads = 0
        screen(
            state(
                messages = listOf(
                    testMessage(id = "user-1", sequence = 1, role = MessageRole.USER, text = "Hi"),
                ),
                hasOlder = true,
            ),
            onLoadOlder = { loads += 1 },
        )

        composeRule.onNodeWithTag(ChatMessageListTestTags.Older).performClick()

        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `announcement is exposed as a polite live region`() {
        screen(state(announcement = "Response complete"))

        composeRule.onNodeWithTag(ChatScreenTestTags.Announcement)
            .assertContentDescriptionEquals("Response complete")
    }
}
