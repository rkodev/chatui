package dev.chatui.core.database

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackupSyncDaoTest : DatabaseTestBase() {
    @Test
    fun `backup config round trip`() = runTest {
        val dao = db.backupConfigDao()
        val config = TestData.backupConfig()

        dao.upsert(config)

        assertThat(dao.getById("default")).isEqualTo(config)
        assertThat(dao.observe().first()).isEqualTo(config)
    }

    @Test
    fun `backup config delete`() = runTest {
        val dao = db.backupConfigDao()
        dao.upsert(TestData.backupConfig())

        dao.deleteById("default")

        assertThat(dao.getById("default")).isNull()
    }

    @Test
    fun `sync queue round trip and filtering`() = runTest {
        val dao = db.syncQueueDao()
        dao.upsert(TestData.syncQueueItem(id = "sync-1", conversationId = "conversation-1"))
        dao.upsert(TestData.syncQueueItem(id = "sync-2", conversationId = "conversation-2"))

        assertThat(dao.all().map { it.id }).containsExactly("sync-1", "sync-2")
        assertThat(dao.forConversation("conversation-1").map { it.id }).containsExactly("sync-1")

        dao.deleteById("sync-1")
        assertThat(dao.all().map { it.id }).containsExactly("sync-2")

        dao.clear()
        assertThat(dao.all()).isEmpty()
    }
}
