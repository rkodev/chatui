package dev.chatui.feature.chat

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageRole
import dev.chatui.core.network.NormalizedRole
import dev.chatui.core.network.ResolvedAttachment
import org.junit.Test

class ChatRequestBuilderTest {
    @Test
    fun `history is normalized in sequence order and reasoning is not replayed`() {
        val conversation = testConversation().copy(systemPrompt = "Be concise")
        val messages = listOf(
            testMessage(id = "assistant-1", sequence = 2, role = MessageRole.ASSISTANT, text = "Hi"),
            testMessage(id = "user-1", sequence = 1, role = MessageRole.USER, text = "Hello"),
            testMessage(id = "system-1", sequence = 0, role = MessageRole.SYSTEM, text = "Ignored"),
            testMessage(id = "empty-1", sequence = 3, role = MessageRole.ASSISTANT, text = ""),
        )

        val request = buildChatRequest(conversation, "openrouter/auto", messages)

        assertThat(request.model).isEqualTo("openrouter/auto")
        assertThat(request.system).isEqualTo("Be concise")
        assertThat(request.messages.map { it.role })
            .containsExactly(NormalizedRole.USER, NormalizedRole.ASSISTANT)
            .inOrder()
        assertThat(request.messages.first().parts)
            .containsExactly(ContentPart.Text("Hello"))
    }

    @Test
    fun `deleted messages are skipped`() {
        val conversation = testConversation()
        val deleted = testMessage(id = "user-1", sequence = 1).copy(deletedAt = TEST_NOW)

        val request = buildChatRequest(conversation, "openrouter/auto", listOf(deleted))

        assertThat(request.messages).isEmpty()
        assertThat(request.system).isNull()
    }

    @Test
    fun `image and file parts are preserved with resolved bytes`() {
        val conversation = testConversation()
        val message = testMessage(id = "user-1", sequence = 1).copy(
            parts = listOf(
                ContentPart.Text("Look"),
                ContentPart.Image("image-1", "image/png", "photo.png"),
                ContentPart.File("file-1", "text/plain", "notes.txt"),
            ),
        )
        val resolved = mapOf(
            "image-1" to ResolvedAttachment("image/png", "photo.png", "AAAA"),
            "file-1" to ResolvedAttachment("text/plain", "notes.txt", "Ym9keQ=="),
        )

        val request = buildChatRequest(
            conversation = conversation,
            modelRemoteId = "openrouter/auto",
            messages = listOf(message),
            attachments = resolved,
        )

        assertThat(request.messages.single().parts).containsExactly(
            ContentPart.Text("Look"),
            ContentPart.Image("image-1", "image/png", "photo.png"),
            ContentPart.File("file-1", "text/plain", "notes.txt"),
        ).inOrder()
        assertThat(request.attachments).isEqualTo(resolved)
    }

    @Test
    fun `image-only messages are not dropped`() {
        val conversation = testConversation()
        val message = testMessage(id = "user-1", sequence = 1).copy(
            parts = listOf(ContentPart.Image("image-1", "image/png", "photo.png")),
        )

        val request = buildChatRequest(conversation, "openrouter/auto", listOf(message))

        assertThat(request.messages).hasSize(1)
        assertThat(request.messages.single().parts)
            .containsExactly(ContentPart.Image("image-1", "image/png", "photo.png"))
    }
}
