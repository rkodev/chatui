package dev.chatui.feature.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatScreenBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `back dismisses the send confirmation without sending`() {
        var cancelled = false
        var confirmed = false

        composeRule.setContent {
            ChatUiTheme {
                ChatScreen(
                    state = ChatUiState(
                        conversationId = "conversation-1",
                        conversation = testConversation(),
                        loading = false,
                        availability = ChatAvailability(available = true),
                        attachmentWarning = "The selected model cannot receive every attachment.",
                        confirmSendWithUnsupportedAttachments = true,
                    ),
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onLoadOlder = {},
                    onRetryMessage = {},
                    onDismissError = {},
                    onConfirmSend = { confirmed = true },
                    onCancelSendConfirmation = { cancelled = true },
                    imeInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }

        composeRule.onNodeWithTag(ChatScreenTestTags.SendConfirmation).assertIsDisplayed()
        val dialog = ShadowDialog.getLatestDialog()

        composeRule.runOnIdle { dialog.onBackPressed() }

        assertThat(cancelled).isTrue()
        assertThat(confirmed).isFalse()
    }
}
