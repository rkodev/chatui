package dev.chatui.core.database

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.dao.MessageDao
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MessageFtsTest : DatabaseTestBase() {
    private val dao: MessageDao
        get() = db.messageDao()

    @Test
    fun `search finds inserted message text`() = runTest {
        dao.insert(
            TestData.message(id = "message-1", sequence = 1),
            "The quick brown fox jumps over the lazy dog",
        )

        val results = dao.search("brown")

        assertThat(results.map { it.id }).containsExactly("message-1")
    }

    @Test
    fun `search is case insensitive`() = runTest {
        dao.insert(TestData.message(id = "message-1", sequence = 1), "Hello World")

        assertThat(dao.search("hello").map { it.id }).containsExactly("message-1")
        assertThat(dao.search("WORLD").map { it.id }).containsExactly("message-1")
    }

    @Test
    fun `search only returns matching messages`() = runTest {
        dao.insert(TestData.message(id = "message-1", sequence = 1), "alpha beta")
        dao.insert(TestData.message(id = "message-2", sequence = 2), "gamma delta")

        assertThat(dao.search("beta").map { it.id }).containsExactly("message-1")
        assertThat(dao.search("delta").map { it.id }).containsExactly("message-2")
    }

    @Test
    fun `update reindexes search text`() = runTest {
        dao.insert(TestData.message(id = "message-1", sequence = 1), "old text")
        val updated = TestData.message(id = "message-1", sequence = 1)

        dao.update(updated, "new text")

        assertThat(dao.search("old")).isEmpty()
        assertThat(dao.search("new").map { it.id }).containsExactly("message-1")
    }

    @Test
    fun `delete removes search text`() = runTest {
        dao.insert(TestData.message(id = "message-1", sequence = 1), "searchable")

        dao.delete("message-1")

        assertThat(dao.search("searchable")).isEmpty()
    }

    @Test
    fun `search excludes soft deleted messages`() = runTest {
        dao.insert(
            TestData.message(
                id = "message-1",
                sequence = 1,
                deletedAt = "2026-11-02T01:00:00Z",
            ),
            "searchable",
        )

        assertThat(dao.search("searchable")).isEmpty()
    }

    @Test
    fun `search respects the result limit`() = runTest {
        repeat(5) { index ->
            dao.insert(
                TestData.message(id = "message-$index", sequence = index.toLong()),
                "common token",
            )
        }

        assertThat(dao.searchPage("common", 2)).hasSize(2)
    }
}
