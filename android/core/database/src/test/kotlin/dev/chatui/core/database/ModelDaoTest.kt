package dev.chatui.core.database

import android.database.sqlite.SQLiteConstraintException
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.dao.ModelDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelDaoTest : DatabaseTestBase() {
    private val dao: ModelDao
        get() = db.modelDao()

    @Test
    fun `insert and read model`() = runTest {
        val model = TestData.model()
        dao.insert(model)

        assertThat(dao.getById(model.id)).isEqualTo(model)
        assertThat(dao.findByRemoteId("provider-1", "remote-1")).isEqualTo(model)
    }

    @Test
    fun `unique provider and remote id constraint is enforced`() = runTest {
        dao.insert(TestData.model(id = "model-1", remoteId = "shared"))

        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.insert(TestData.model(id = "model-2", remoteId = "shared"))
            }
        }
    }

    @Test
    fun `same remote id is allowed for different providers`() = runTest {
        dao.insert(TestData.model(id = "model-1", providerId = "provider-1", remoteId = "shared"))
        dao.insert(TestData.model(id = "model-2", providerId = "provider-2", remoteId = "shared"))

        assertThat(dao.forProvider("provider-1").map { it.id }).containsExactly("model-1")
        assertThat(dao.forProvider("provider-2").map { it.id }).containsExactly("model-2")
    }

    @Test
    fun `upsert replaces an existing model`() = runTest {
        dao.insert(TestData.model(label = "Old"))
        dao.upsert(TestData.model(label = "New"))

        assertThat(dao.getById("model-1")!!.label).isEqualTo("New")
    }

    @Test
    fun `observe provider models`() = runTest {
        dao.insert(TestData.model(id = "model-b", remoteId = "remote-b", label = "B"))
        dao.insert(TestData.model(id = "model-a", remoteId = "remote-a", label = "A"))

        val labels = dao.observeForProvider("provider-1").first().map { it.label }

        assertThat(labels).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `delete for provider removes only that provider models`() = runTest {
        dao.insert(TestData.model(id = "model-1", providerId = "provider-1"))
        dao.insert(TestData.model(id = "model-2", providerId = "provider-2"))

        dao.deleteForProvider("provider-1")

        assertThat(dao.getById("model-1")).isNull()
        assertThat(dao.getById("model-2")).isNotNull()
    }
}
