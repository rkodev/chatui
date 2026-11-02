package dev.chatui.core.database

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.dao.ProviderDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProviderDaoTest : DatabaseTestBase() {
    private val dao: ProviderDao
        get() = db.providerDao()

    @Test
    fun `insert and read provider`() = runTest {
        val provider = TestData.provider()
        dao.upsert(provider)

        assertThat(dao.getById(provider.id)).isEqualTo(provider)
    }

    @Test
    fun `observe providers ordered by label`() = runTest {
        dao.upsert(TestData.provider(id = "provider-b", label = "B"))
        dao.upsert(TestData.provider(id = "provider-a", label = "A"))

        val labels = dao.observeAll().first().map { it.label }

        assertThat(labels).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `delete with children removes models and secrets but keeps conversations`() = runTest {
        dao.upsert(TestData.provider(secretId = "secret-1"))
        db.modelDao().insert(TestData.model())
        db.secretDao().upsert(TestData.secret())
        db.conversationDao().upsert(TestData.conversation())

        dao.deleteWithChildren("provider-1")

        assertThat(dao.getById("provider-1")).isNull()
        assertThat(db.modelDao().getById("model-1")).isNull()
        assertThat(db.secretDao().getById("secret-1")).isNull()
        assertThat(db.conversationDao().getById("conversation-1")).isNotNull()
    }
}
