package dev.chatui.core.database.repository

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.SyncMeta
import dev.chatui.core.model.SyncState
import org.junit.Test

class MappingTest {
    @Test
    fun `conversation round trips through entity`() {
        val conversation = RepositoryTestData.conversation(
            pinned = true,
            archived = true,
            temporary = true,
            messageCount = 3,
            lastError = RepositoryTestData.chatError(),
            sync = SyncMeta(SyncState.SYNCED, null, null),
        )

        assertThat(conversation.toEntity().toDomain()).isEqualTo(conversation)
    }

    @Test
    fun `message round trips through entity`() {
        val message = RepositoryTestData.message(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                ContentPart.Text("answer"),
                ContentPart.Reasoning("thinking"),
                ContentPart.ToolCall("call-1", "search", """{"q":"x"}"""),
                ContentPart.ToolResult("call-1", "result"),
                ContentPart.Image("attachment-1", "image/png", "diagram"),
                ContentPart.File("attachment-2", "text/plain", "notes.txt"),
            ),
            status = MessageStatus.ERROR,
            providerId = "provider-1",
            modelId = "model-1",
            modelLabel = "Model One",
            usage = RepositoryTestData.usage(),
            finishReason = FinishReason.ERROR,
            error = RepositoryTestData.chatError(),
            latencyMs = 1200,
            firstTokenMs = 300,
            deletedAt = RepositoryTestData.NOW,
        )

        assertThat(message.toEntity().toDomain()).isEqualTo(message)
    }

    @Test
    fun `provider round trips through entity`() {
        val provider = RepositoryTestData.provider()

        assertThat(provider.toEntity().toDomain()).isEqualTo(provider)
    }

    @Test
    fun `model round trips through entity`() {
        val model = RepositoryTestData.model(
            hidden = true,
            pinned = true,
            source = ModelSource.MANUAL,
        )

        assertThat(model.toEntity().toDomain()).isEqualTo(model)
    }

    @Test
    fun `searchable text includes text reasoning and tool content`() {
        val text = searchableText(
            listOf(
                ContentPart.Text("visible answer"),
                ContentPart.Reasoning("hidden reasoning"),
                ContentPart.ToolCall("call-1", "search", """{"q":"query"}"""),
                ContentPart.ToolResult("call-1", "tool result"),
                ContentPart.Image("attachment-1", "image/png", "diagram alt"),
                ContentPart.File("attachment-2", "text/plain", "notes.txt"),
            ),
        )

        assertThat(text).contains("visible answer")
        assertThat(text).contains("hidden reasoning")
        assertThat(text).contains("search")
        assertThat(text).contains("tool result")
        assertThat(text).contains("diagram alt")
        assertThat(text).contains("notes.txt")
    }
}
