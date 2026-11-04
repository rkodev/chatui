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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.SendKey
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
}
