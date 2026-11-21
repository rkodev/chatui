package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Theme
import androidx.compose.ui.text.AnnotatedString
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

    @Test
    fun `toggles wrap and scroll while preserving exact copy`() {
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

        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Wrap)
            .assertContentDescriptionEquals("Wrap")
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Wrap).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Wrap)
            .assertContentDescriptionEquals("Scroll")

        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Copy).performClick()
        composeRule.waitForIdle()

        assertThat(copied).isEqualTo(code)
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Code).assertTextEquals(code)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `explicit dark app code overrides a light system and updates when the app theme changes`() {
        var theme by mutableStateOf(Theme.DARK)
        val code = "val answer = \"hello\""
        composeRule.setContent {
            ChatUiTheme(themeMode = theme) { ChatCodeBlock(code, "kotlin") }
        }
        val dark = highlightMarkdownCode(code, normalizeCodeLanguage("kotlin"), true)!!
        val light = highlightMarkdownCode(code, normalizeCodeLanguage("kotlin"), false)!!
        assertThat(dark.spanStyles).isNotEqualTo(light.spanStyles)
        assertThat(renderedCode().spanStyles).isEqualTo(dark.spanStyles)

        theme = Theme.LIGHT
        assertThat(renderedCode().spanStyles).isEqualTo(light.spanStyles)
    }

    @Test
    @Config(qualifiers = "night")
    fun `explicit light app code overrides a dark system`() {
        val code = "val answer = 42"
        composeRule.setContent {
            ChatUiTheme(themeMode = Theme.LIGHT) { ChatCodeBlock(code, "kotlin") }
        }
        assertThat(renderedCode().spanStyles)
            .isEqualTo(highlightMarkdownCode(code, normalizeCodeLanguage("kotlin"), false)!!.spanStyles)
    }

    @Test
    fun `unchanged highlight work is reused and content language and theme each invalidate it`() {
        var frame by mutableStateOf(0)
        var code by mutableStateOf("val answer = 42")
        var language by mutableStateOf("kotlin")
        var dark by mutableStateOf(false)
        val calls = mutableListOf<Pair<Int, String>>()
        composeRule.setContent {
            val currentFrame = frame
            val highlighted = rememberCodeHighlight(
                code, normalizeCodeLanguage(language), dark,
                highlighter = { source, _, _ ->
                    calls += currentFrame to source
                    AnnotatedString(source)
                },
            )
            Text(highlighted!!)
        }
        composeRule.runOnIdle { assertThat(calls).hasSize(1); frame++ }
        composeRule.runOnIdle { assertThat(calls).hasSize(1); code += "\nprintln(answer)" }
        composeRule.runOnIdle { assertThat(calls).hasSize(2); language = "python" }
        composeRule.runOnIdle { assertThat(calls).hasSize(3); dark = true }
        composeRule.runOnIdle { assertThat(calls).hasSize(4) }
    }

    @Test
    fun `code is content sized and a streamed update preserves the chosen wrap mode and exact copy`() {
        var code by mutableStateOf("val message = \"${"a long string ".repeat(30)}\"")
        var copied = ""
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                Column(Modifier.width(320.dp)) {
                    ChatCodeBlock(code, "kotlin", modifier = Modifier.testTag("code-block"), onCopy = { copied = it })
                }
            }
        }
        val bounds = composeRule.onNodeWithTag(ChatCodeBlockTestTags.Code).getUnclippedBoundsInRoot()
        assertThat((bounds.bottom - bounds.top).value).isLessThan(80f)
        val block = composeRule.onNodeWithTag("code-block").getUnclippedBoundsInRoot()
        assertThat((block.bottom - block.top).value).isLessThan(160f)
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Wrap).performClick()
        code += "\nprintln(message)"
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Wrap).assertContentDescriptionEquals("Scroll")
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Copy).performClick()
        assertThat(copied).isEqualTo(code)
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Code).assertTextEquals(code)
    }

    private fun renderedCode(): AnnotatedString = composeRule
        .onNodeWithTag(ChatCodeBlockTestTags.Code)
        .fetchSemanticsNode().config[SemanticsProperties.Text].single()
}
