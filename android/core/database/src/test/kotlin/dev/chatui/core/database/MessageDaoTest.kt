package dev.chatui.core.database

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.dao.MessageDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MessageDaoTest : DatabaseTestBase() {
    private val dao: MessageDao
        get() = db.messageDao()

    @Test
    fun `insert and read message`() = runTest {
        val message = TestData.message(id = "message-1", sequence = 1)
        dao.insert(message, "hello")

        assertThat(dao.getById("message-1")).isEqualTo(message)
    }

    @Test
    fun `latest page defaults to fifty newest messages`() = runTest {
        repeat(60) { index ->
            dao.insert(
                TestData.message(id = "message-$index", sequence = index.toLong()),
                "text $index",
            )
        }

        val page = dao.latest("conversation-1")

        assertThat(page).hasSize(MessageDao.DEFAULT_PAGE_SIZE)
        assertThat(page.first().sequence).isEqualTo(59L)
        assertThat(page.last().sequence).isEqualTo(10L)
    }

    @Test
    fun `older page paginates before the oldest sequence`() = runTest {
        repeat(60) { index ->
            dao.insert(
                TestData.message(id = "message-$index", sequence = index.toLong()),
                "text $index",
            )
        }

        val newest = dao.latest("conversation-1")
        val older = dao.older("conversation-1", newest.last().sequence)

        assertThat(older).hasSize(10)
        assertThat(older.first().sequence).isEqualTo(9L)
        assertThat(older.last().sequence).isEqualTo(0L)
    }

    @Test
    fun `soft deleted messages are excluded from reads`() = runTest {
        dao.insert(TestData.message(id = "visible", sequence = 1), "visible")
        dao.insert(
            TestData.message(
                id = "deleted",
                sequence = 2,
                deletedAt = "2026-11-02T01:00:00Z",
            ),
            "deleted",
        )

        assertThat(dao.latest("conversation-1").map { it.id }).containsExactly("visible")
        assertThat(dao.count("conversation-1")).isEqualTo(1)
    }

    @Test
    fun `update replaces message fields`() = runTest {
        dao.insert(TestData.message(id = "message-1", sequence = 1), "hello")
        val updated = TestData.message(
            id = "message-1",
            sequence = 1,
            status = "error",
            updatedAt = "2026-11-02T02:00:00Z",
        )

        dao.update(updated, "hello")

        assertThat(dao.getById("message-1")).isEqualTo(updated)
    }

    @Test
    fun `delete removes message and search row`() = runTest {
        dao.insert(TestData.message(id = "message-1", sequence = 1), "hello")

        dao.delete("message-1")

        assertThat(dao.getById("message-1")).isNull()
        assertThat(dao.search("hello")).isEmpty()
    }

    @Test
    fun `count and max sequence reflect stored messages`() = runTest {
        dao.insert(TestData.message(id = "message-1", sequence = 1), "one")
        dao.insert(TestData.message(id = "message-2", sequence = 7), "two")

        assertThat(dao.count("conversation-1")).isEqualTo(2)
        assertThat(dao.maxSequence("conversation-1")).isEqualTo(7L)
    }

    @Test
    fun `observe conversation emits ordered messages`() = runTest {
        dao.insert(TestData.message(id = "message-2", sequence = 2), "two")
        dao.insert(TestData.message(id = "message-1", sequence = 1), "one")

        val sequences = dao.observeConversation("conversation-1").first().map { it.sequence }

        assertThat(sequences).containsExactly(1L, 2L).inOrder()
    }
}
