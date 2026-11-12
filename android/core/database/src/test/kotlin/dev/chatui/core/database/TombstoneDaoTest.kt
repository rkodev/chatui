package dev.chatui.core.database

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.dao.TombstoneDao
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TombstoneDaoTest : DatabaseTestBase() {
    private val dao: TombstoneDao
        get() = db.tombstoneDao()

    @Test
    fun `insert and list tombstones`() = runTest {
        dao.insert(TestData.tombstone(id = "tombstone-1"))
        dao.insert(TestData.tombstone(id = "tombstone-2", conversationId = "conversation-2"))

        assertThat(dao.all().map { it.id }).containsExactly("tombstone-1", "tombstone-2")
    }

    @Test
    fun `list tombstones for one conversation`() = runTest {
        dao.insert(TestData.tombstone(id = "tombstone-1", conversationId = "conversation-1"))
        dao.insert(TestData.tombstone(id = "tombstone-2", conversationId = "conversation-2"))

        assertThat(dao.forConversation("conversation-1").map { it.id })
            .containsExactly("tombstone-1")
    }

    @Test
    fun `delete tombstone by id`() = runTest {
        dao.insert(TestData.tombstone(id = "tombstone-1"))
        dao.insert(TestData.tombstone(id = "tombstone-2"))

        dao.deleteById("tombstone-1")

        assertThat(dao.all().map { it.id }).containsExactly("tombstone-2")
    }

    @Test
    fun `delete tombstones for a conversation`() = runTest {
        dao.insert(TestData.tombstone(id = "tombstone-1", conversationId = "conversation-1"))
        dao.insert(TestData.tombstone(id = "tombstone-2", conversationId = "conversation-2"))

        dao.deleteForConversation("conversation-1")

        assertThat(dao.all().map { it.id }).containsExactly("tombstone-2")
    }
}
