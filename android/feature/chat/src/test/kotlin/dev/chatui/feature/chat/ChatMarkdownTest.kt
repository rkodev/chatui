package dev.chatui.feature.chat

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Scale
import coil3.size.Size
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.ChatFontSize
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
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
    private var loader: ImageLoader? = null
    private var density = 1f

    @After
    fun closeLoader() {
        loader?.shutdown()
    }

    private fun render(
        markdown: String,
        loadRemoteImages: Boolean = false,
        images: ControlledImages = ControlledImages(),
        modifier: Modifier = Modifier,
    ) {
        val imageLoader = imageLoader(images)
        composeRule.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalMarkdownImageLoader provides imageLoader) {
                ChatUiTheme {
                    ChatMarkdown(
                        content = markdown,
                        modifier = modifier,
                        parseImmediately = true,
                        loadRemoteImages = loadRemoteImages,
                    )
                }
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

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `pending failure and retry are distinct and the large-aspect image uses display-sized requests`() {
        val images = ControlledImages()
        render(
            "![Wide diagram](https://example.invalid/wide.png)",
            false,
            images,
            Modifier.width(180.dp).height(160.dp),
        )
        composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadImage).performClick()
        composeRule.waitForIdle()
        awaitAttempts(images, 1)
        composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadingImage).assertIsDisplayed()
        composeRule.onNodeWithText("Image unavailable.").assertDoesNotExist()
        val bounds = composeRule.onNodeWithTag(ChatMarkdownTestTags.RemoteImage).getUnclippedBoundsInRoot()
        assertThat(images.attempts.single().size).isEqualTo(
            Size(((bounds.right - bounds.left).value * density).toInt(), (160 * density).toInt()),
        )
        assertThat(images.attempts.single().request.scale).isEqualTo(Scale.FIT)

        images.attempts[0].succeed.complete(false)
        waitForTag(ChatMarkdownTestTags.ImageError)
        composeRule.onNodeWithText("[Image: Wide diagram]").assertExists()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadingImage).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.RetryImage).performClick()
        composeRule.waitForIdle()
        awaitAttempts(images, 2)
        composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadingImage).assertExists()
        assertThat(images.attempts[1].request.data).isEqualTo(images.attempts[0].request.data)
        assertThat(images.attempts[1].size).isEqualTo(images.attempts[0].size)
        images.attempts[1].succeed.complete(true)
        waitForTag(ChatMarkdownTestTags.LoadedImage)
        val image = composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadedImage).getUnclippedBoundsInRoot()
        assertThat((image.bottom - image.top).value).isAtMost(160f)
        assertThat((image.right - image.left).value).isAtMost((bounds.right - bounds.left).value)
        composeRule.onNodeWithTag(ChatMarkdownTestTags.ImageError).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `blocked and unsafe images never reach the image loader and consent stays with its owner`() {
        val images = ControlledImages()
        val imageLoader = imageLoader(images)
        var owner by mutableStateOf("conversation-a:message-a")
        var destination by mutableStateOf("https://example.invalid/cat.png")
        var allowImages by mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalMarkdownImageLoader provides imageLoader) {
                ChatUiTheme {
                    ChatMarkdown(
                        "![A cat]($destination)",
                        parseImmediately = true,
                        loadRemoteImages = allowImages,
                        imageOwnerKey = owner,
                    )
                }
            }
        }
        composeRule.onNodeWithTag(ChatMarkdownTestTags.BlockedImage).assertExists()
        assertThat(images.attempts).isEmpty()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadImage).performClick()
        composeRule.waitForIdle()
        awaitAttempts(images, 1)
        images.attempts[0].succeed.complete(true)
        waitForTag(ChatMarkdownTestTags.LoadedImage)

        owner = "conversation-b:message-b"
        composeRule.onNodeWithTag(ChatMarkdownTestTags.BlockedImage, useUnmergedTree = true).assertExists()
        assertThat(images.attempts).hasSize(1)
        destination = "file:///private/unsafe.png"
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(ChatMarkdownTestTags.LoadImage, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        allowImages = true
        composeRule.onNodeWithTag(ChatMarkdownTestTags.BlockedImage, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(ChatMarkdownTestTags.LoadImage, useUnmergedTree = true).assertDoesNotExist()
        assertThat(images.attempts).hasSize(1)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `ordinary recomposition reuses the loaded request while url size owner and disposal cancel pending work`() {
        val images = ControlledImages()
        val imageLoader = imageLoader(images)
        var destination by mutableStateOf("https://example.invalid/first.png")
        var width by mutableStateOf(160.dp)
        var owner by mutableStateOf("first")
        var font by mutableStateOf(ChatFontSize.MD)
        var shown by mutableStateOf(true)
        var allowImages by mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalMarkdownImageLoader provides imageLoader) {
                if (shown) {
                    ChatUiTheme {
                        Box(Modifier.width(width)) {
                            ChatMarkdown(
                                "![A diagram]($destination)",
                                chatFontSize = font,
                                parseImmediately = true,
                                loadRemoteImages = allowImages,
                                imageOwnerKey = owner,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        allowImages = true
        composeRule.waitForIdle()
        awaitAttempts(images, 1)
        images.attempts[0].succeed.complete(true)
        waitForTag(ChatMarkdownTestTags.LoadedImage)
        font = ChatFontSize.LG
        composeRule.waitForIdle()
        assertThat(images.attempts).hasSize(1)

        destination = "https://example.invalid/second.png"
        composeRule.waitForIdle()
        awaitAttempts(images, 2)
        width = 120.dp
        composeRule.waitForIdle()
        awaitAttempts(images, 3)
        assertThat(images.cancelled.get()).isAtLeast(1)
        owner = "second"
        composeRule.waitForIdle()
        awaitAttempts(images, 4)
        assertThat(images.cancelled.get()).isAtLeast(2)
        shown = false
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { images.cancelled.get() == 3 }
    }

    private fun imageLoader(images: ControlledImages): ImageLoader =
        ImageLoader.Builder(ApplicationProvider.getApplicationContext<Context>())
            .components { add(images) }
            .build().also { loader = it }

    private fun awaitAttempts(images: ControlledImages, count: Int) {
        try {
            composeRule.waitUntil(5_000) { images.attempts.size >= count }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError(
                "Expected $count requests, got ${images.attempts.map { it.request.data to it.size }}\n" +
                    composeRule.onRoot().printToString(),
                error,
            )
        }
        assertThat(images.attempts.map { it.request.data to it.size }).hasSize(count)
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class ControlledImages : Interceptor {
        data class Attempt(
            val request: ImageRequest,
            val size: Size,
            val succeed: CompletableDeferred<Boolean> = CompletableDeferred(),
        )

        val attempts = CopyOnWriteArrayList<Attempt>()
        val cancelled = AtomicInteger()

        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val attempt = Attempt(chain.request, chain.size).also { attempts += it }
            return try {
                if (attempt.succeed.await()) {
                    SuccessResult(ColorImage(0xff235789.toInt(), width = 8_000, height = 100), chain.request)
                } else {
                    ErrorResult(null, chain.request, IOException("Synthetic image failure"))
                }
            } catch (error: CancellationException) {
                cancelled.incrementAndGet()
                throw error
            }
        }
    }
}
