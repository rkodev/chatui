package dev.chatui.feature.chat

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatCodeBlockTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows language label and copies the exact source text`() {
        var copied: String? = null
        val code = "val answer = 42\nprintln(answer)"

        composeRule.setContent {
            ChatUiTheme {
                ChatCodeBlock(
                    code = code,
                    language = "kotlin",
                    onCopy = { copied = it },
                )
            }
        }

        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Language).assertTextEquals("kotlin")
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Code).assertTextEquals(code)
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Copy).performClick()
        composeRule.waitForIdle()

        assertThat(copied).isEqualTo(code)
    }

    @Test
    fun `unknown languages fall back to a plain code label`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatCodeBlock(code = "not code", language = "made-up")
            }
        }

        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Language).assertTextEquals("made-up")
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Code).assertTextEquals("not code")
    }
}
