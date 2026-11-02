package dev.chatui.core.database

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.dao.SecretDao
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SecretDaoTest : DatabaseTestBase() {
    private val dao: SecretDao
        get() = db.secretDao()

    @Test
    fun `store and read ciphertext`() = runTest {
        val secret = TestData.secret(ciphertext = "base64-ciphertext")
        dao.upsert(secret)

        assertThat(dao.getById(secret.id)).isEqualTo(secret)
    }

    @Test
    fun `list all secrets`() = runTest {
        dao.upsert(TestData.secret(id = "secret-b"))
        dao.upsert(TestData.secret(id = "secret-a"))

        assertThat(dao.getAll().map { it.id }).containsExactly("secret-a", "secret-b").inOrder()
    }

    @Test
    fun `secrets table has no plaintext column`() = runTest {
        val cursor = db.openHelper.readableDatabase.query("PRAGMA table_info(secrets)")
        val columns = buildList {
            while (cursor.moveToNext()) {
                add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        cursor.close()

        assertThat(columns).containsExactly("id", "ciphertext", "createdAt", "updatedAt")
    }

    @Test
    fun `delete one and delete all`() = runTest {
        dao.upsert(TestData.secret(id = "secret-1"))
        dao.upsert(TestData.secret(id = "secret-2"))

        dao.deleteById("secret-1")
        assertThat(dao.getAll().map { it.id }).containsExactly("secret-2")

        dao.deleteAll()
        assertThat(dao.getAll()).isEmpty()
    }
}
