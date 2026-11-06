package dev.chatui.feature.chat

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import org.junit.Test

class ConversationShareFormatterTest {
    @Test
    fun `text format includes messages in order with role labels`() {
        val messages = listOf(
            testMessage(id = "user-1", sequence = 1, role = MessageRole.USER, text = "Hello"),
            testMessage(
                id = "assistant-1",
                sequence = 2,
                role = MessageRole.ASSISTANT,
                text = "Hi there",
            ),
        )

        val text = ConversationShareFormatter.format(
            title = "My chat",
            messages = messages,
            format = ConversationShareFormat.TEXT,
        )

        assertThat(text).isEqualTo(
            "My chat\n\nUser:\nHello\n\nAssistant:\nHi there",
        )
    }

    @Test
    fun `markdown format keeps message text and adds role headings`() {
        val messages = listOf(
            testMessage(id = "user-1", sequence = 1, role = MessageRole.USER, text = "**Hello**"),
            testMessage(
                id = "assistant-1",
                sequence = 2,
                role = MessageRole.ASSISTANT,
                text = "- one\n- two",
            ),
        )

        val text = ConversationShareFormatter.format(
            title = "My chat",
            messages = messages,
            format = ConversationShareFormat.MARKDOWN,
        )

        assertThat(text).isEqualTo(
            "# My chat\n\n**User**\n\n**Hello**\n\n**Assistant**\n\n- one\n- two",
        )
    }

    @Test
    fun `reasoning system tool and deleted messages are excluded`() {
        val messages = listOf(
            testMessage(
                id = "system-1",
                sequence = 1,
                role = MessageRole.SYSTEM,
                text = "hidden system prompt",
            ),
            testMessage(
                id = "user-1",
                sequence = 2,
                role = MessageRole.USER,
                text = "Visible question",
            ).copy(
                parts = listOf(
                    ContentPart.Text("Visible question"),
                    ContentPart.Reasoning("hidden chain of thought"),
                ),
            ),
            testMessage(
                id = "tool-1",
                sequence = 3,
                role = MessageRole.TOOL,
                text = "tool output",
            ),
            testMessage(
                id = "assistant-1",
                sequence = 4,
                role = MessageRole.ASSISTANT,
                text = "Visible answer",
            ),
            testMessage(
                id = "user-2",
                sequence = 5,
                role = MessageRole.USER,
                text = "deleted text",
            ).copy(deletedAt = TEST_NOW),
        )

        val text = ConversationShareFormatter.format("Chat", messages)

        assertThat(text).contains("Visible question")
        assertThat(text).contains("Visible answer")
        assertThat(text).doesNotContain("hidden system prompt")
        assertThat(text).doesNotContain("hidden chain of thought")
        assertThat(text).doesNotContain("tool output")
        assertThat(text).doesNotContain("deleted text")
    }

    @Test
    fun `attachment bytes storage keys and provider metadata are excluded`() {
        val message = testMessage(
            id = "assistant-1",
            sequence = 1,
            role = MessageRole.ASSISTANT,
            text = "Answer",
        ).copy(
            parts = listOf(
                ContentPart.Text("Answer"),
                ContentPart.Image(
                    attachmentId = "attachment-image-1",
                    mimeType = "image/png",
                    alt = "screenshot.png",
                ),
                ContentPart.File(
                    attachmentId = "attachment-file-1",
                    mimeType = "text/plain",
                    name = "notes.txt",
                ),
            ),
            providerId = "provider-secret-id",
            modelId = "model-secret-id",
            modelLabel = "Secret Model",
        )

        val text = ConversationShareFormatter.format("Chat", listOf(message))

        assertThat(text).contains("[Image: screenshot.png]")
        assertThat(text).contains("[File: notes.txt]")
        assertThat(text).doesNotContain("attachment-image-1")
        assertThat(text).doesNotContain("attachment-file-1")
        assertThat(text).doesNotContain("image/png")
        assertThat(text).doesNotContain("text/plain")
        assertThat(text).doesNotContain("provider-secret-id")
        assertThat(text).doesNotContain("model-secret-id")
        assertThat(text).doesNotContain("Secret Model")
    }

    @Test
    fun `sensitive or path-like attachment names are omitted`() {
        val message = testMessage(
            id = "user-1",
            sequence = 1,
            role = MessageRole.USER,
            text = "See attached",
        ).copy(
            parts = listOf(
                ContentPart.Text("See attached"),
                ContentPart.File(
                    attachmentId = "file-1",
                    mimeType = "text/plain",
                    name = "api-key.txt",
                ),
                ContentPart.Image(
                    attachmentId = "image-1",
                    mimeType = "image/png",
                    alt = "/data/user/0/dev.chatui/files/secret.png",
                ),
            ),
        )

        val text = ConversationShareFormatter.format("Chat", listOf(message))

        assertThat(text).contains("[File]")
        assertThat(text).contains("[Image]")
        assertThat(text).doesNotContain("api-key.txt")
        assertThat(text).doesNotContain("/data/user")
        assertThat(text).doesNotContain("secret.png")
    }

    @Test
    fun `empty conversation produces a safe title-only result`() {
        assertThat(
            ConversationShareFormatter.format("", emptyList(), ConversationShareFormat.TEXT),
        ).isEqualTo("Conversation")
        assertThat(
            ConversationShareFormatter.format("", emptyList(), ConversationShareFormat.MARKDOWN),
        ).isEqualTo("# Conversation")
    }

    @Test
    fun `blank and cancelled messages do not produce empty role blocks`() {
        val messages = listOf(
            testMessage(
                id = "user-1",
                sequence = 1,
                role = MessageRole.USER,
                text = "   ",
            ),
            testMessage(
                id = "assistant-1",
                sequence = 2,
                role = MessageRole.ASSISTANT,
                text = "Partial",
                status = MessageStatus.CANCELLED,
            ),
        )

        val text = ConversationShareFormatter.format("Chat", messages)

        assertThat(text).isEqualTo("Chat\n\nAssistant:\nPartial")
    }
}
