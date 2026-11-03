package dev.chatui.core.database.repository

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MessageRepositoryTest : DatabaseTestBase() {
    private val appendTime = Instant.parse("2026-11-02T12:00:00Z")
    private val cancelTime = Instant.parse("2026-11-02T13:00:00Z")
    private val clock = Clock.fixed(appendTime, ZoneOffset.UTC)

    private val conversations: ConversationRepository
        get() = ConversationRepository(db.conversationDao(), clock)

    private val repository: MessageRepository
        get() = MessageRepository(db.messageDao(), clock)

    @Test
    fun `append updates conversation metadata atomically`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        val message = RepositoryTestData.message(
            createdAt = RepositoryTestData.NOW.plusSeconds(10),
            updatedAt = RepositoryTestData.NOW.plusSeconds(10),
        )

        val result = repository.appendMessage(message)

        assertThat(result.message).isEqualTo(message)
        assertThat(result.conversation.messageCount).isEqualTo(1)
        assertThat(result.conversation.lastMessageAt).isEqualTo(message.createdAt)
        assertThat(result.conversation.updatedAt).isEqualTo(appendTime)
        assertThat(conversations.get("conversation-1")).isEqualTo(result.conversation)
    }

    @Test
    fun `re-appending the same message does not increment the count`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        val message = RepositoryTestData.message()

        repository.appendMessage(message)
        val result = repository.appendMessage(
            message.copy(parts = listOf(ContentPart.Text("updated"))),
        )

        assertThat(result.conversation.messageCount).isEqualTo(1)
        assertThat(repository.get(message.id)!!.parts)
            .containsExactly(ContentPart.Text("updated"))
    }

    @Test
    fun `appending an older message keeps the newest last message time`() = runTest {
        val newest = RepositoryTestData.NOW.plusSeconds(100)
        conversations.create(
            RepositoryTestData.conversation(lastMessageAt = newest),
        )

        val result = repository.appendMessage(
            RepositoryTestData.message(
                createdAt = RepositoryTestData.NOW.plusSeconds(10),
                updatedAt = RepositoryTestData.NOW.plusSeconds(10),
            ),
        )

        assertThat(result.conversation.lastMessageAt).isEqualTo(newest)
    }

    @Test
    fun `latest and older pages return ascending sequence order`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        repeat(60) { index ->
            repository.appendMessage(
                RepositoryTestData.message(
                    id = "message-$index",
                    sequence = index.toLong(),
                    createdAt = RepositoryTestData.NOW.plusSeconds(index.toLong()),
                    updatedAt = RepositoryTestData.NOW.plusSeconds(index.toLong()),
                ),
            )
        }

        val latest = repository.latest("conversation-1")
        val older = repository.older("conversation-1", latest.first().sequence)

        assertThat(latest).hasSize(50)
        assertThat(latest.first().sequence).isEqualTo(10L)
        assertThat(latest.last().sequence).isEqualTo(59L)
        assertThat(older).hasSize(10)
        assertThat(older.first().sequence).isEqualTo(0L)
        assertThat(older.last().sequence).isEqualTo(9L)
    }

    @Test
    fun `append and update keep fts synchronized`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        val message = RepositoryTestData.message(
            parts = listOf(ContentPart.Text("The quick brown fox")),
        )

        repository.appendMessage(message)

        assertThat(repository.search("brown").map { it.id }).containsExactly(message.id)

        repository.updateMessage(
            message.copy(parts = listOf(ContentPart.Text("new text"))),
        )

        assertThat(repository.search("brown")).isEmpty()
        assertThat(repository.search("new").map { it.id }).containsExactly(message.id)
    }

    @Test
    fun `delete removes the message and its fts row`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        repository.appendMessage(
            RepositoryTestData.message(parts = listOf(ContentPart.Text("searchable"))),
        )

        repository.deleteMessage("message-1")

        assertThat(repository.get("message-1")).isNull()
        assertThat(repository.search("searchable")).isEmpty()
    }

    @Test
    fun `streaming messages can be queried and marked cancelled`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        repository.appendMessage(
            RepositoryTestData.message(
                id = "stream-1",
                sequence = 1,
                status = MessageStatus.STREAMING,
                parts = listOf(ContentPart.Text("partial")),
            ),
        )
        repository.appendMessage(
            RepositoryTestData.message(
                id = "stream-2",
                sequence = 2,
                status = MessageStatus.STREAMING,
                parts = listOf(ContentPart.Text("partial two")),
            ),
        )
        repository.appendMessage(
            RepositoryTestData.message(
                id = "complete",
                sequence = 3,
                status = MessageStatus.COMPLETE,
            ),
        )

        assertThat(repository.streamingMessages().map { it.id })
            .containsExactly("stream-1", "stream-2")

        assertThat(repository.markStreamingCancelled(cancelTime)).isEqualTo(2)

        assertThat(repository.streamingMessages()).isEmpty()
        val cancelled = repository.get("stream-1")!!
        assertThat(cancelled.status).isEqualTo(MessageStatus.CANCELLED)
        assertThat(cancelled.parts).containsExactly(ContentPart.Text("partial"))
        assertThat(cancelled.updatedAt).isEqualTo(cancelTime)
    }

    @Test
    fun `mark cancelled updates one message`() = runTest {
        conversations.create(RepositoryTestData.conversation())
        repository.appendMessage(
            RepositoryTestData.message(
                id = "stream-1",
                status = MessageStatus.STREAMING,
            ),
        )

        repository.markCancelled("stream-1", cancelTime)

        val cancelled = repository.get("stream-1")!!
        assertThat(cancelled.status).isEqualTo(MessageStatus.CANCELLED)
        assertThat(cancelled.updatedAt).isEqualTo(cancelTime)
    }

    @Test
    fun `append to a missing conversation fails`() = runTest {
        val error = runCatching {
            repository.appendMessage(
                RepositoryTestData.message(conversationId = "missing"),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
