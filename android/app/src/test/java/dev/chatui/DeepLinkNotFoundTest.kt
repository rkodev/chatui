package dev.chatui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.feature.chat.ChatScreen
import dev.chatui.feature.chat.ChatScreenTestTags
import dev.chatui.feature.chat.ChatUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinkNotFoundTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a deep link to a missing conversation shows the not found state`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatScreen(
                    state = ChatUiState(
                        conversationId = "0190a5c4-1d2e-7abc-8def-0123456789ab",
                        conversation = null,
                        loading = false,
                    ),
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onLoadOlder = {},
                    onRetryMessage = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(ChatScreenTestTags.NotFound).assertIsDisplayed()
    }
}
