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

        val created = repository.create(conversation)

        assertThat(repository.get(conversation.id)).isEqualTo(created)
    }

    @Test
    fun `create assigns a 32 hex storage key and revision 1`() = runTest {
        val created = repository.create(RepositoryTestData.conversation())

        assertThat(created.storageKey).matches("^[0-9a-f]{32}$")
        assertThat(created.revision).isEqualTo(1)
        assertThat(repository.get(created.id)!!.storageKey).isEqualTo(created.storageKey)
        assertThat(repository.getByStorageKey(created.storageKey)!!.id).isEqualTo(created.id)
    }

    @Test
    fun `create generates unique storage keys`() = runTest {
        val first = repository.create(RepositoryTestData.conversation(id = "conversation-1"))
        val second = repository.create(RepositoryTestData.conversation(id = "conversation-2"))

        assertThat(first.storageKey).isNotEqualTo(second.storageKey)
    }

    @Test
    fun `update preserves storage key and increments revision`() = runTest {
        val created = repository.create(RepositoryTestData.conversation())

        val updated = repository.update(created.copy(title = "Renamed"))

        assertThat(updated.storageKey).isEqualTo(created.storageKey)
        assertThat(updated.revision).isEqualTo(2)
        assertThat(repository.get(created.id)!!.revision).isEqualTo(2)
    }

    @Test
    fun `upsert increments revision for an existing conversation`() = runTest {
        val created = repository.create(RepositoryTestData.conversation())

        val upserted = repository.upsert(created.copy(title = "Renamed"))

        assertThat(upserted.storageKey).isEqualTo(created.storageKey)
        assertThat(upserted.revision).isEqualTo(2)
    }

    @Test
    fun `rename pin and archive increment revision`() = runTest {
        val created = repository.create(RepositoryTestData.conversation())

        repository.rename(created.id, "Renamed")
        assertThat(repository.get(created.id)!!.revision).isEqualTo(2)

        repository.setPinned(created.id, true)
        assertThat(repository.get(created.id)!!.revision).isEqualTo(3)

        repository.setArchived(created.id, true)
        assertThat(repository.get(created.id)!!.revision).isEqualTo(4)
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
