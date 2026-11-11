package dev.chatui.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMessageActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        message: Message,
        canRegenerate: Boolean = false,
        canEditLastUser: Boolean = false,
        onRegenerate: (Message) -> Unit = {},
        onEditLastUser: (Message, String) -> Unit = { _, _ -> },
        onCopy: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            ChatUiTheme {
                ChatMessageActions(
                    message = message,
                    canRegenerate = canRegenerate,
                    canEditLastUser = canEditLastUser,
                    onRegenerate = onRegenerate,
                    onEditLastUser = onEditLastUser,
                    onCopy = onCopy,
                )
            }
        }
    }

    @Test
    fun `overflow menu exposes copy and regenerate`() {
        var copied: String? = null
        var regenerated: Message? = null
        val message = testMessage(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            text = "Answer",
        )

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageActions(
                    message = message,
                    canRegenerate = true,
                    onRegenerate = { regenerated = it },
                    onCopy = { copied = it },
                    inline = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Overflow).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Copy).performClick()
        composeRule.waitForIdle()

        assertThat(copied).isEqualTo("Answer")

        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Overflow).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Regenerate).performClick()

        assertThat(regenerated?.id).isEqualTo("assistant-1")
    }

    @Test
    fun `copies every text part with a blank line`() {
        var copied: String? = null
        val message = testMessage(text = "Hello").copy(
            parts = listOf(ContentPart.Text("Hello"), ContentPart.Text("World")),
        )

        render(message, onCopy = { copied = it })
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Copy).performClick()
        composeRule.waitForIdle()

        assertThat(copied).isEqualTo("Hello\n\nWorld")
    }

    @Test
    fun `regenerate is hidden when the message is not the last assistant`() {
        render(
            testMessage(id = "assistant-1", role = MessageRole.ASSISTANT, text = "Answer"),
            canRegenerate = false,
        )

        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Regenerate).assertDoesNotExist()
    }

    @Test
    fun `regenerate targets the last assistant message`() {
        var regenerated: Message? = null
        val message = testMessage(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            text = "Answer",
        )

        render(message, canRegenerate = true, onRegenerate = { regenerated = it })
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Regenerate).performClick()

        assertThat(regenerated?.id).isEqualTo("assistant-1")
    }

    @Test
    fun `edit warns about truncation and resends the edited text`() {
        var edited: Pair<Message, String>? = null
        val message = testMessage(
            id = "user-1",
            role = MessageRole.USER,
            text = "Original",
        )

        render(message, canEditLastUser = true, onEditLastUser = { target, text ->
            edited = target to text
        })

        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Edit).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditDialog).assertExists()
        composeRule.onNodeWithText(
            "Editing this message deletes every later message in this conversation. " +
                "This cannot be undone.",
        ).assertExists()

        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditInput)
            .performTextReplacement("Updated")
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditConfirm).performClick()
        composeRule.waitForIdle()

        assertThat(edited?.first?.id).isEqualTo("user-1")
        assertThat(edited?.second).isEqualTo("Updated")
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditDialog).assertDoesNotExist()
    }

    @Test
    fun `cancelling edit does not resend`() {
        var edits = 0

        render(
            testMessage(role = MessageRole.USER, text = "Original"),
            canEditLastUser = true,
            onEditLastUser = { _, _ -> edits += 1 },
        )

        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Edit).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditCancel).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditDialog).assertDoesNotExist()

        assertThat(edits).isEqualTo(0)
    }
}
