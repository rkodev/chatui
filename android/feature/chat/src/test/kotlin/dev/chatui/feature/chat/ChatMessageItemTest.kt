package dev.chatui.feature.chat

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMessageItemTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val message = testMessage(
        id = "assistant-1",
        role = MessageRole.ASSISTANT,
        text = "The answer.",
        status = MessageStatus.COMPLETE,
    ).copy(
        parts = listOf(
            ContentPart.Reasoning("Private reasoning."),
            ContentPart.Text("The answer."),
        ),
    )

    @Test
    fun `renders reasoning separately from the answer and does not replay it`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatMessageItem(message = message, showActions = false)
            }
        }

        composeRule.onNodeWithText("The answer.", substring = true).assertExists()
        composeRule.onNodeWithText("Private reasoning.", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatMessageTestTags.Reasoning).assertExists()

        composeRule.onNodeWithTag(ChatMessageTestTags.Reasoning).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatMessageTestTags.ReasoningText)
            .assertTextEquals("Private reasoning.")
        composeRule.onNodeWithText("The answer.", substring = true).assertExists()
        assertThat(message.copyableText()).isEqualTo("The answer.")
    }
}
