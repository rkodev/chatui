package dev.chatui.feature.chat

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil3.size.Size
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
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
