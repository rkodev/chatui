package dev.chatui.core.database.repository

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationRepositoryTest : DatabaseTestBase() {
    private val clock = Clock.fixed(
        Instant.parse("2026-11-02T12:00:00Z"),
        ZoneOffset.UTC,
    )

    private val repository: ConversationRepository
        get() = ConversationRepository(db.conversationDao(), clock)

    @Test
    fun `create and read conversation`() = runTest {
        val conversation = RepositoryTestData.conversation()

        repository.create(conversation)

        assertThat(repository.get(conversation.id)).isEqualTo(conversation)
    }

    @Test
    fun `observe all maps entities to domain`() = runTest {
        repository.create(RepositoryTestData.conversation(id = "conversation-1"))
        repository.create(RepositoryTestData.conversation(id = "conversation-2"))

        val ids = repository.observeAll().first().map { it.id }

        assertThat(ids).containsExactly("conversation-1", "conversation-2")
    }

    @Test
    fun `observe archived returns only archived conversations`() = runTest {
        repository.create(RepositoryTestData.conversation(id = "active"))
        repository.create(RepositoryTestData.conversation(id = "archived", archived = true))

        val ids = repository.observeArchived().first().map { it.id }

        assertThat(ids).containsExactly("archived")
    }

    @Test
    fun `rename pin and archive update timestamps`() = runTest {
        repository.create(RepositoryTestData.conversation())

        assertThat(repository.rename("conversation-1", "Renamed")).isTrue()
        assertThat(repository.setPinned("conversation-1", true)).isTrue()
        assertThat(repository.setArchived("conversation-1", true)).isTrue()

        val updated = repository.get("conversation-1")!!
        assertThat(updated.title).isEqualTo("Renamed")
        assertThat(updated.pinned).isTrue()
        assertThat(updated.archived).isTrue()
        assertThat(updated.updatedAt).isEqualTo(clock.instant())
    }

    @Test
    fun `rename missing conversation returns false`() = runTest {
        assertThat(repository.rename("missing", "Renamed")).isFalse()
    }

    @Test
    fun `delete removes messages and fts rows`() = runTest {
        repository.create(RepositoryTestData.conversation())
        val messages = MessageRepository(db.messageDao(), clock)
        messages.appendMessage(RepositoryTestData.message())

        repository.delete("conversation-1")

        assertThat(repository.get("conversation-1")).isNull()
        assertThat(db.messageDao().getById("message-1")).isNull()
        assertThat(db.messageDao().search("hello")).isEmpty()
    }
}
