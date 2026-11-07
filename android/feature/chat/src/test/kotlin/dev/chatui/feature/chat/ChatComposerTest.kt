package dev.chatui.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.input.key.Key
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.SendKey
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatComposerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `send is enabled for a non-blank draft and invokes the intent`() {
        var sends = 0
        var draft by mutableStateOf("")

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextInput("Hello")
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsEnabled().performClick()

        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `send is disabled for a blank draft`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsNotEnabled()
    }

    @Test
    fun `stop replaces send while streaming and invokes the stop intent`() {
        var stops = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = {},
                    onStop = { stops += 1 },
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = true,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatComposerTestTags.Stop)
            .assertIsDisplayed()
            .performClick()

        assertThat(stops).isEqualTo(1)
    }

    @Test
    fun `enter send-key mode sends from the IME action`() {
        var sends = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performImeAction()

        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `mod-enter mode does not send from a plain IME action`() {
        var sends = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.MOD_ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ImeAction,
                    ImeAction.Default,
                ),
            )

        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `unavailable and offline states disable and explain sending`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = false,
                    streaming = false,
                    sending = false,
                    offline = true,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Offline).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertIsNotEnabled()
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsNotEnabled()
    }

    @Test
    fun `attachments enable send and expose a remove action`() {
        var removed: String? = null
        val attachment = attachment()

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                    attachments = listOf(attachment),
                    onRemoveAttachment = { removed = it },
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.attachmentChip(attachment.id))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsEnabled()
        composeRule.onNodeWithTag(ChatComposerTestTags.removeAttachment(attachment.id))
            .performClick()

        assertThat(removed).isEqualTo(attachment.id)
    }

    @Test
    fun `attachment warning and error are rendered`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                    attachmentWarning = "This model cannot receive images.",
                    attachmentError = "photo.png is too large.",
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentWarning).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentError).assertIsDisplayed()
    }

    @Test
    fun `typing normal characters updates the draft without sending`() {
        var sends = 0
        var draft by mutableStateOf("")

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextInput("Hello")

        assertThat(draft).isEqualTo("Hello")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `ctrl enter sends in mod-enter mode`() {
        var sends = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.MOD_ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).requestFocus()
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) }
        }

        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `plain enter does not send in mod-enter mode`() {
        var sends = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.MOD_ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).requestFocus()
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performKeyInput {
            pressKey(Key.Enter)
        }

        assertThat(sends).isEqualTo(0)
    }

    private fun attachment(): Attachment = Attachment(
        id = "attachment-1",
        messageId = null,
        kind = AttachmentKind.FILE,
        mimeType = "text/plain",
        name = "notes.txt",
        size = 12,
        sha256 = "sha",
        storageKey = "attachments/sha",
        width = null,
        height = null,
        createdAt = Instant.parse("2026-11-05T12:00:00Z"),
    )
}
