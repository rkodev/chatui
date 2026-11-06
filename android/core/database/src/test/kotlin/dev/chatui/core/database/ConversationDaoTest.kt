package dev.chatui.core.database

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.dao.ConversationDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationDaoTest : DatabaseTestBase() {
    private val dao: ConversationDao
        get() = db.conversationDao()

    @Test
    fun `insert and read conversation`() = runTest {
        val conversation = TestData.conversation()
        dao.upsert(conversation)

        assertThat(dao.getById(conversation.id)).isEqualTo(conversation)
    }

    @Test
    fun `list orders pinned first then most recent`() = runTest {
        dao.upsert(TestData.conversation(id = "old", lastMessageAt = "2026-01-01T00:00:00Z"))
        dao.upsert(TestData.conversation(id = "new", lastMessageAt = "2026-02-01T00:00:00Z"))
        dao.upsert(
            TestData.conversation(
                id = "pinned",
                pinned = true,
                lastMessageAt = "2025-01-01T00:00:00Z",
            ),
        )

        val ids = dao.observeAll().first().map { it.id }

        assertThat(ids).containsExactly("pinned", "new", "old").inOrder()
    }

    @Test
    fun `active list excludes archived conversations`() = runTest {
        dao.upsert(TestData.conversation(id = "active"))
        dao.upsert(TestData.conversation(id = "archived", archived = true))

        val ids = dao.observeActive().first().map { it.id }

        assertThat(ids).containsExactly("active")
    }

    @Test
    fun `archived list contains only archived conversations`() = runTest {
        dao.upsert(TestData.conversation(id = "active"))
        dao.upsert(TestData.conversation(id = "archived", archived = true))

        val ids = dao.observeArchived().first().map { it.id }

        assertThat(ids).containsExactly("archived")
    }

    @Test
    fun `update title and flags`() = runTest {
        dao.upsert(TestData.conversation())

        dao.updateTitle("conversation-1", "Renamed", "2026-03-01T00:00:00Z")
        dao.setPinned("conversation-1", true, "2026-03-02T00:00:00Z")
        dao.setArchived("conversation-1", true, "2026-03-03T00:00:00Z")

        val updated = dao.getById("conversation-1")!!
        assertThat(updated.title).isEqualTo("Renamed")
        assertThat(updated.pinned).isTrue()
        assertThat(updated.archived).isTrue()
        assertThat(updated.updatedAt).isEqualTo("2026-03-03T00:00:00Z")
    }

    @Test
    fun `delete with children removes messages attachments and fts rows`() = runTest {
        dao.upsert(TestData.conversation())
        db.messageDao().insert(TestData.message(id = "message-1", sequence = 1), "hello")
        db.attachmentDao().upsert(TestData.attachment(messageId = "message-1"))

        dao.deleteWithChildren("conversation-1")

        assertThat(dao.getById("conversation-1")).isNull()
        assertThat(db.messageDao().getById("message-1")).isNull()
        assertThat(db.attachmentDao().getById("attachment-1")).isNull()
        assertThat(db.messageDao().search("hello")).isEmpty()
    }
}
