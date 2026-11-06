package dev.chatui.feature.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
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
class ChatMarkdownTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(markdown: String, loadRemoteImages: Boolean = false) {
        composeRule.setContent {
            ChatUiTheme {
                ChatMarkdown(
                    content = markdown,
                    parseImmediately = true,
                    loadRemoteImages = loadRemoteImages,
                )
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

    @Test
    fun `renders inline math as raw source without breaking the message`() {
        render("Euler: \$e^{i\\pi}+1=0\$.")

        composeRule.onNodeWithText("Euler:", substring = true).assertExists()
        composeRule.onNodeWithText("\$e^{i\\pi}+1=0\$", substring = true).assertExists()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.MathBlock).assertDoesNotExist()
    }

    @Test
    fun `renders standalone block math as a raw-source block`() {
        render("Before\n\n\$\$\n\\int_0^1 x^2 dx\n\$\$\n\nAfter")

        composeRule.onNodeWithText("Before").assertExists()
        composeRule.onNodeWithText("After").assertExists()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.MathBlock).assertExists()
        composeRule.onNodeWithText("\\int_0^1 x^2 dx", substring = true).assertExists()
    }

    @Test
    fun `renders fenced math as a raw-source block`() {
        render("```math\n\\frac{1}{2}\n```")

        composeRule.onNodeWithTag(ChatMarkdownTestTags.MathBlock).assertExists()
        composeRule.onNodeWithText("\\frac{1}{2}", substring = true).assertExists()
        composeRule.onNodeWithTag(ChatCodeBlockTestTags.Code).assertDoesNotExist()
    }

    @Test
    fun `keeps incomplete streaming math as raw text`() {
        render("Here is \$x^2")

        composeRule.onNodeWithText("Here is \$x^2", substring = true).assertExists()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.MathBlock).assertDoesNotExist()
    }

    @Test
    fun `offers a per-message load action for safe remote images`() {
        render("![A cat](https://example.com/cat.png)")

        composeRule.onNodeWithTag(ChatMarkdownTestTags.BlockedImage).assertExists()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadImage).assertExists()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.OpenImage).assertExists()
    }

    @Test
    fun `does not offer a load action for unsafe image sources`() {
        render("![A cat](javascript:alert(1))")

        composeRule.onNodeWithTag(ChatMarkdownTestTags.BlockedImage).assertExists()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadImage).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.OpenImage).assertDoesNotExist()
    }

    @Test
    fun `does not show the blocked note when remote images are enabled`() {
        render("![A cat](https://127.0.0.1:1/cat.png)", loadRemoteImages = true)

        composeRule.onNodeWithTag(ChatMarkdownTestTags.BlockedImage).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadImage).assertDoesNotExist()
    }

    @Test
    fun `renders unsafe links as inert text`() {
        render("[click](javascript:alert(1))")

        composeRule.onNodeWithText("click", substring = true).assertExists()
        composeRule.onNodeWithText("javascript:alert(1)", substring = true).assertDoesNotExist()
    }
}
