package dev.chatui.feature.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMarkdownTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(markdown: String) {
        composeRule.setContent {
            ChatUiTheme {
                ChatMarkdown(content = markdown, parseImmediately = true)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `renders GFM headings lists tables blockquotes task lists and inline code`() {
        render(
            """
            # Heading

            - first item
            - second item

            > quoted text

            | A | B |
            | - | - |
            | one | two |

            - [x] finished task

            Inline `code` here.
            """.trimIndent(),
        )

        composeRule.onNodeWithText("Heading").assertExists()
        composeRule.onNodeWithText("first item").assertExists()
        composeRule.onNodeWithText("quoted text").assertExists()
        composeRule.onNodeWithText("one", substring = true).assertExists()
        composeRule.onNodeWithText("two", substring = true).assertExists()
        composeRule.onNodeWithText("finished task").assertExists()
        composeRule.onNodeWithText("Inline", substring = true).assertExists()
    }

    @Test
    fun `renders raw html as inert text`() {
        render("<script>alert('xss')</script>\n\n<b>bold</b>")

        assertThat(
            composeRule.onAllNodesWithText("<script>", substring = true).fetchSemanticsNodes(),
        ).isNotEmpty()
        assertThat(
            composeRule.onAllNodesWithText("<b>bold</b>", substring = true).fetchSemanticsNodes(),
        ).isNotEmpty()
        composeRule.onNodeWithText("alert('xss')", substring = true).assertExists()
    }

    @Test
    fun `does not load remote images and shows alt text instead`() {
        render("![A cat](https://example.com/cat.png)")

        composeRule.onNodeWithText("[Image: A cat]", substring = true).assertExists()
        composeRule.onNodeWithText("Remote images are blocked.", substring = true).assertExists()
    }

    @Test
    fun `renders an incomplete streaming fence as code`() {
        render("Before\n```kotlin\nval x = 1")

        composeRule.onNodeWithText("Before").assertExists()
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Language).assertTextEquals("kotlin")
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Code).assertTextEquals("val x = 1")
        composeRule.onAllNodesWithText("```kotlin", substring = true).assertCountEquals(0)
    }

    @Test
    fun `renders fenced code with language label and exact source`() {
        render("```python\nprint(\"hi\")\n```")

        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Language).assertTextEquals("python")
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Code).assertTextEquals("print(\"hi\")")
    }
}
