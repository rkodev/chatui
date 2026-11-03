package dev.chatui.core.database.recovery

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.RepositoryTestData
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StreamingRecoveryServiceTest : DatabaseTestBase() {
    private val recoveryTime = Instant.parse("2026-11-03T12:00:00Z")
    private val clock = Clock.fixed(recoveryTime, ZoneOffset.UTC)

    private val conversations: ConversationRepository
        get() = ConversationRepository(db.conversationDao(), clock)

    private val messages: MessageRepository
        get() = MessageRepository(db.messageDao(), clock)

    private val service: StreamingRecoveryService
        get() = StreamingRecoveryService(messages, clock)

    @Test
    fun `marks a streaming message cancelled and preserves partial content`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        val streaming = RepositoryTestData.message(
            id = "stream-1",
            sequence = 1,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.STREAMING,
            providerId = "provider-1",
            parts = listOf(
                ContentPart.Reasoning("Thinking"),
                ContentPart.Text("Partial answer"),
            ),
        )
        messages.appendMessage(streaming)

        val result = service.recoverInterruptedMessages()

        assertThat(result.failed).isEmpty()
        assertThat(result.recovered).hasSize(1)
        val recovered = result.recovered.single()
        assertThat(recovered.id).isEqualTo(streaming.id)
        assertThat(recovered.conversationId).isEqualTo(streaming.conversationId)
        assertThat(recovered.sequence).isEqualTo(streaming.sequence)
        assertThat(recovered.status).isEqualTo(MessageStatus.CANCELLED)
        assertThat(recovered.finishReason).isEqualTo(FinishReason.CANCELLED)
        assertThat(recovered.parts).isEqualTo(streaming.parts)
        assertThat(recovered.updatedAt).isEqualTo(recoveryTime)
        assertThat(recovered.error?.code).isEqualTo(ChatErrorCode.CANCELLED)
        assertThat(recovered.error?.message).isEqualTo(INTERRUPTION_NOTE)
        assertThat(recovered.error?.providerId).isEqualTo("provider-1")
        assertThat(recovered.error?.retryable).isFalse()

        val stored = messages.get(streaming.id)!!
        assertThat(stored.status).isEqualTo(MessageStatus.CANCELLED)
        assertThat(stored.parts).containsExactly(
            ContentPart.Reasoning("Thinking"),
            ContentPart.Text("Partial answer"),
        )
    }

    @Test
    fun `leaves non-streaming messages untouched`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        val complete = RepositoryTestData.message(
            id = "complete",
            status = MessageStatus.COMPLETE,
            finishReason = FinishReason.STOP,
        )
        messages.appendMessage(complete)

        val result = service.recoverInterruptedMessages()

        assertThat(result.recovered).isEmpty()
        assertThat(result.failed).isEmpty()
        assertThat(messages.get(complete.id)).isEqualTo(complete)
    }

    @Test
    fun `is idempotent across repeated startup scans`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        messages.appendMessage(
            RepositoryTestData.message(
                id = "stream-1",
                status = MessageStatus.STREAMING,
                parts = listOf(ContentPart.Text("partial")),
            ),
        )

        val first = service.recoverInterruptedMessages()
        val second = service.recoverInterruptedMessages()

        assertThat(first.recovered).hasSize(1)
        assertThat(second.recovered).isEmpty()
        assertThat(second.failed).isEmpty()
        assertThat(messages.count("conversation-1")).isEqualTo(1)
        assertThat(messages.get("stream-1")!!.parts)
            .containsExactly(ContentPart.Text("partial"))
    }

    @Test
    fun `recovers streaming messages across conversations`() = runTest {
        conversations.create(RepositoryTestData.conversation(id = "conversation-1"))
        conversations.create(RepositoryTestData.conversation(id = "conversation-2"))
        messages.appendMessage(
            RepositoryTestData.message(
                id = "stream-1",
                conversationId = "conversation-1",
                status = MessageStatus.STREAMING,
            ),
        )
        messages.appendMessage(
            RepositoryTestData.message(
                id = "stream-2",
                conversationId = "conversation-2",
                status = MessageStatus.STREAMING,
            ),
        )

        val result = service.recoverInterruptedMessages()

        assertThat(result.recovered.map { it.id }).containsExactly("stream-1", "stream-2")
        assertThat(messages.streamingMessages()).isEmpty()
    }
}
