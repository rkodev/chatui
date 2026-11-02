package dev.chatui.core.database

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.dao.AttachmentDao
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AttachmentDaoTest : DatabaseTestBase() {
    private val dao: AttachmentDao
        get() = db.attachmentDao()

    @Test
    fun `insert and read attachment metadata`() = runTest {
        val attachment = TestData.attachment()
        dao.upsert(attachment)

        assertThat(dao.getById(attachment.id)).isEqualTo(attachment)
    }

    @Test
    fun `find by sha256 supports content addressing`() = runTest {
        dao.upsert(TestData.attachment(id = "attachment-1", sha256 = "same"))
        dao.upsert(TestData.attachment(id = "attachment-2", sha256 = "same"))
        dao.upsert(TestData.attachment(id = "attachment-3", sha256 = "other"))

        assertThat(dao.findBySha256("same").map { it.id })
            .containsExactly("attachment-1", "attachment-2")
    }

    @Test
    fun `list attachments for a message`() = runTest {
        dao.upsert(TestData.attachment(id = "attachment-1", messageId = "message-1"))
        dao.upsert(TestData.attachment(id = "attachment-2", messageId = "message-2"))

        assertThat(dao.forMessage("message-1").map { it.id }).containsExactly("attachment-1")
    }

    @Test
    fun `orphan attachments have no message id`() = runTest {
        dao.upsert(TestData.attachment(id = "attachment-1", messageId = null))
        dao.upsert(TestData.attachment(id = "attachment-2", messageId = "message-1"))

        assertThat(dao.orphans().map { it.id }).containsExactly("attachment-1")
    }

    @Test
    fun `delete by id and by message`() = runTest {
        dao.upsert(TestData.attachment(id = "attachment-1", messageId = "message-1"))
        dao.upsert(TestData.attachment(id = "attachment-2", messageId = "message-1"))

        dao.deleteForMessage("message-1")

        assertThat(dao.forMessage("message-1")).isEmpty()
    }
}
