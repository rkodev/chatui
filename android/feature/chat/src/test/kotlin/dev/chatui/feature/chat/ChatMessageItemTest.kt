package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.size.Size
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Usage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun `streaming has one static generation indicator before and after the first token`() {
        val waiting = testMessage(
            id = "assistant-waiting",
            role = MessageRole.ASSISTANT,
            status = MessageStatus.STREAMING,
            text = "",
        )
        val streaming = testMessage(
            id = "assistant-streaming",
            role = MessageRole.ASSISTANT,
            status = MessageStatus.STREAMING,
            text = "Partial answer",
        )

        var current by mutableStateOf(waiting)

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageItem(
                    message = current,
                    showActions = false,
                    isActiveStream = true,
                )
            }
        }

        composeRule.onNodeWithTag(ChatMessageTestTags.Typing).assertExists()
        composeRule.onNodeWithTag(ChatMessageTestTags.Caret).assertDoesNotExist()

        current = streaming
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatMessageTestTags.Caret).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatMessageTestTags.Typing).assertExists()

    }

    @Test
    fun `renders file attachments with name and size`() {
        val attachment = attachment(kind = AttachmentKind.FILE, name = "notes.txt", size = 2048)
        val withFile = message.copy(
            parts = listOf(
                ContentPart.Text("See attached."),
                ContentPart.File(attachment.id, attachment.mimeType, attachment.name),
            ),
        )

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageItem(
                    message = withFile,
                    showActions = false,
                    attachments = mapOf(attachment.id to attachment),
                )
            }
        }

        composeRule.onNodeWithTag(ChatMessageTestTags.attachment(attachment.id)).assertExists()
        composeRule.onNodeWithText("notes.txt").assertExists()
        composeRule.onNodeWithText("2.0 KB").assertExists()
    }

    @Test
    fun `message and attachment rows expose accessible descriptions`() {
        val attachment = attachment(kind = AttachmentKind.FILE, name = "notes.txt", size = 2048)
        val withFile = message.copy(
            parts = listOf(
                ContentPart.Text("See attached."),
                ContentPart.File(attachment.id, attachment.mimeType, attachment.name),
            ),
        )

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageItem(
                    message = withFile,
                    showActions = false,
                    attachments = mapOf(attachment.id to attachment),
                )
            }
        }

        composeRule.onNodeWithTag(ChatMessageTestTags.message("assistant-1"))
            .assertContentDescriptionEquals("Assistant message")
        composeRule.onNodeWithTag(ChatMessageTestTags.attachment(attachment.id))
            .assertContentDescriptionEquals("File attachment notes.txt")
    }

    @Test
    fun `renders an image fallback when local bytes are unavailable`() {
        val attachment = attachment(kind = AttachmentKind.IMAGE, name = "photo.png", size = 10)
        val withImage = message.copy(
            parts = listOf(ContentPart.Image(attachment.id, attachment.mimeType, attachment.name)),
        )

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageItem(
                    message = withImage,
                    showActions = false,
                    attachments = mapOf(attachment.id to attachment),
                )
            }
        }

        composeRule.onNodeWithTag(ChatMessageTestTags.AttachmentImage).assertExists()
        composeRule.onNodeWithText("photo.png").assertExists()
    }

    @Test
    fun `local image requests are bounded to the display size`() = runBlocking {
        val file = File.createTempFile("chat-image", ".png")
        val request = chatImageRequest(
            context = ApplicationProvider.getApplicationContext(),
            file = file,
            widthPx = 1080,
            heightPx = 960,
        )

        assertThat(request.data).isEqualTo(file)
        assertThat(request.sizeResolver.size()).isEqualTo(Size(1080, 960))
        file.delete()
        Unit
    }

    @Test
    fun `narrow large-font responses use the reading width without an avatar gutter`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChatUiTheme(reducedMotion = true) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        ChatMessageItem(
                            message = testMessage(
                                id = "answer", role = MessageRole.ASSISTANT,
                                text = "A detailed and readable response. ".repeat(12),
                            ),
                            showActions = false,
                        )
                        ChatMessageItem(
                            message = testMessage(id = "question", text = "A longer question. ".repeat(12)),
                            showActions = false,
                        )
                        ChatMessageItem(
                            message = testMessage(id = "system", role = MessageRole.SYSTEM, text = "Instructions"),
                            showActions = false,
                        )
                        ChatMessageItem(
                            message = testMessage(id = "tool", role = MessageRole.TOOL, text = "Tool result"),
                            showActions = false,
                        )
                    }
                }
            }
        }

        val answer = composeRule.onNodeWithTag(ChatMessageTestTags.bubble("answer")).getUnclippedBoundsInRoot()
        val question = composeRule.onNodeWithTag(ChatMessageTestTags.bubble("question")).getUnclippedBoundsInRoot()
        assertThat((answer.right - answer.left).value).isAtLeast(312f)
        assertThat(answer.left.value).isWithin(1f).of(0f)
        assertThat((question.right - question.left).value).isAtMost(320f * 0.88f + 1f)
        assertThat(question.right.value).isWithin(1f).of(320f)
        composeRule.onNodeWithTag(ChatMessageTestTags.avatar("answer")).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatMessageTestTags.message("system"))
            .assertContentDescriptionEquals("System message")
        composeRule.onNodeWithTag(ChatMessageTestTags.message("tool"))
            .assertContentDescriptionEquals("Tool message")
    }

    @Test
    @Config(qualifiers = "w1000dp-h1200dp")
    fun `wide responses stay within the transcript reading bound`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    ChatMessageItem(
                        message = message.copy(parts = listOf(ContentPart.Text("A wide answer. ".repeat(30)))),
                        showActions = false,
                    )
                }
            }
        }
        val bounds = composeRule.onNodeWithTag(ChatMessageTestTags.message(message.id)).getUnclippedBoundsInRoot()
        assertThat((bounds.right - bounds.left).value).isEqualTo(760f)
        assertThat(bounds.left.value).isGreaterThan(0f)
    }

    @Test
    fun `footer wraps stored attribution and optional usage at two hundred percent text`() {
        var showUsage by mutableStateOf(false)
        val snapshot = message.copy(
            modelLabel = "Previously selected model with a long descriptive name",
            providerId = "original-provider",
            usage = Usage(120, 45, 165, 0.004, false),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChatUiTheme(reducedMotion = true) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        ChatMessageItem(message = snapshot, canRegenerate = true, showTokenUsage = showUsage)
                    }
                }
            }
        }
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Overflow)
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithTag(ChatMessageTestTags.Meta)
            .assertTextEquals("${snapshot.modelLabel} \u00b7 original-provider")
            .assertHeightIsAtLeast(72.dp)
        composeRule.onNodeWithTag(ChatMessageTestTags.Usage).assertDoesNotExist()

        showUsage = true
        composeRule.onNodeWithTag(ChatMessageTestTags.Usage)
            .assertTextEquals("120 input \u00b7 45 output \u00b7 165 total tokens \u00b7 \$0.0040")
    }

    @Test
    fun `long press edit keeps its dialog mounted after the menu closes`() {
        var edits = 0
        val user = testMessage(id = "editable-user", role = MessageRole.USER, text = "Original")
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ChatMessageItem(
                    message = user,
                    canEditLastUser = true,
                    onEditLastUser = { _, _ -> edits++ },
                )
            }
        }
        composeRule.onNodeWithTag(ChatMessageTestTags.bubble(user.id))
            .performTouchInput { longClick(Offset(2f, 2f)) }
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Edit).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Menu).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditDialog).assertExists()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditCancel).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditDialog).assertDoesNotExist()
        assertThat(edits).isEqualTo(0)
    }

    private fun attachment(
        kind: AttachmentKind,
        name: String,
        size: Long,
    ): Attachment = Attachment(
        id = "attachment-1",
        messageId = "assistant-1",
        kind = kind,
        mimeType = if (kind == AttachmentKind.IMAGE) "image/png" else "text/plain",
        name = name,
        size = size,
        sha256 = "sha",
        storageKey = "attachments/sha",
        width = null,
        height = null,
        createdAt = Instant.parse("2026-11-05T12:00:00Z"),
    )
}
