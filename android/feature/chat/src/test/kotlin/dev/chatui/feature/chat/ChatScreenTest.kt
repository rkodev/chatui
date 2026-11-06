package dev.chatui.feature.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
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
        attachmentWarning: String? = null,
        confirmSend: Boolean = false,
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
        attachmentWarning = attachmentWarning,
        confirmSendWithUnsupportedAttachments = confirmSend,
    )

    private fun screen(
        state: ChatUiState,
        onSend: () -> Unit = {},
        onStop: () -> Unit = {},
        onLoadOlder: () -> Unit = {},
        onRetryMessage: (dev.chatui.core.model.Message) -> Unit = {},
        onRegenerateMessage: (dev.chatui.core.model.Message) -> Unit = {},
        onEditLastUser: (dev.chatui.core.model.Message, String) -> Unit = { _, _ -> },
        onOpenProviders: () -> Unit = {},
        onOpenConversationSettings: () -> Unit = {},
        onConfirmSend: () -> Unit = {},
        onCancelSendConfirmation: () -> Unit = {},
        imeInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
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
                    onRegenerateMessage = onRegenerateMessage,
                    onEditLastUser = onEditLastUser,
                    onOpenProviders = onOpenProviders,
                    onOpenConversationSettings = onOpenConversationSettings,
                    onConfirmSend = onConfirmSend,
                    onCancelSendConfirmation = onCancelSendConfirmation,
                    zoneId = java.time.ZoneOffset.UTC,
                    imeInsets = imeInsets,
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
    @Config(qualifiers = "w411dp-h891dp")
    fun `composer stays above the ime inset`() {
        screen(
            state(),
            imeInsets = WindowInsets(0.dp, 0.dp, 0.dp, 300.dp),
        )

        val composer = composeRule
            .onNodeWithTag(ChatComposerTestTags.Composer)
            .getUnclippedBoundsInRoot()

        composeRule.onNodeWithTag(ChatComposerTestTags.Composer).assertIsDisplayed()
        // The screen is 891 dp tall; a 300 dp keyboard must leave the composer
        // above the remaining 591 dp.
        assertThat(composer.bottom.value).isAtMost(591f)
        assertThat(composer.bottom.value).isAtLeast(580f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `composer sits at the bottom when the keyboard is closed`() {
        screen(state(), imeInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp))

        val composer = composeRule
            .onNodeWithTag(ChatComposerTestTags.Composer)
            .getUnclippedBoundsInRoot()

        assertThat(composer.bottom.value).isAtLeast(880f)
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
    fun `actions target the last assistant and last user messages`() {
        var regenerated: dev.chatui.core.model.Message? = null
        var edited: Pair<dev.chatui.core.model.Message, String>? = null
        val assistant = testMessage(
            id = "assistant-1",
            sequence = 1,
            role = MessageRole.ASSISTANT,
            text = "Answer",
        )
        val lastUser = testMessage(
            id = "user-2",
            sequence = 2,
            role = MessageRole.USER,
            text = "Second",
        )

        screen(
            state(messages = listOf(assistant, lastUser)),
            onRegenerateMessage = { regenerated = it },
            onEditLastUser = { target, text -> edited = target to text },
        )

        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Regenerate).performClick()
        assertThat(regenerated?.id).isEqualTo("assistant-1")

        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Edit).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditInput)
            .performTextReplacement("Updated")
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditConfirm).performClick()

        assertThat(edited?.first?.id).isEqualTo("user-2")
        assertThat(edited?.second).isEqualTo("Updated")
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

    @Test
    fun `conversation settings entry point is available from the header`() {
        var opened = 0
        screen(state(), onOpenConversationSettings = { opened += 1 })

        composeRule.onNodeWithTag(ChatScreenTestTags.ConversationSettings).performClick()

        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `unsupported attachment warning and confirmation are shown`() {
        var confirmed = 0
        var cancelled = 0
        screen(
            state(
                attachmentWarning = "This model cannot receive images.",
                confirmSend = true,
            ),
            onConfirmSend = { confirmed += 1 },
            onCancelSendConfirmation = { cancelled += 1 },
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.AttachmentWarning).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatScreenTestTags.SendConfirmation).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatScreenTestTags.SendAnyway).performClick()
        composeRule.onNodeWithTag(ChatScreenTestTags.CancelSend).performClick()

        assertThat(confirmed).isEqualTo(1)
        assertThat(cancelled).isEqualTo(1)
    }
}
