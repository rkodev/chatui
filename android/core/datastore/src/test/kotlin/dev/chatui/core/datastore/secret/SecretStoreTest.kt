package dev.chatui.core.datastore.secret

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.dao.SecretDao
import dev.chatui.core.database.entity.SecretEntity
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretStoreTest {
    private lateinit var db: ChatUiDatabase
    private lateinit var dao: SecretDao
    private lateinit var store: SecretStore
    private var now: Instant = Instant.parse("2026-11-02T00:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = ChatUiDatabase.inMemory(context)
        dao = db.secretDao()
        store = SecretStore(dao = dao, cipher = inMemorySecretCipher(), clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `put and get round trip`() = runTest {
        store.put("provider-1", "sk-test-123")

        assertThat(store.get("provider-1")).isEqualTo("sk-test-123")
    }

    @Test
    fun `ciphertext differs from plaintext`() = runTest {
        val plaintext = "sk-test-123"

        store.put("provider-1", plaintext)

        val entity = dao.getById("provider-1")
        assertThat(entity).isNotNull()
        assertThat(entity!!.ciphertext).isNotEqualTo(plaintext)
        assertThat(entity.ciphertext).doesNotContain(plaintext)
    }

    @Test
    fun `put preserves createdAt and updates updatedAt`() = runTest {
        store.put("provider-1", "first")
        val createdAt = dao.getById("provider-1")!!.createdAt

        now = now.plusSeconds(60)
        store.put("provider-1", "second")

        val entity = dao.getById("provider-1")!!
        assertThat(entity.createdAt).isEqualTo(createdAt)
        assertThat(entity.updatedAt).isEqualTo(now.toString())
    }

    @Test
    fun `forget key deletes one secret`() = runTest {
        store.put("a", "one")
        store.put("b", "two")

        store.forgetKey("a")

        assertThat(store.get("a")).isNull()
        assertThat(store.get("b")).isEqualTo("two")
    }

    @Test
    fun `forget all deletes every secret`() = runTest {
        store.put("a", "one")
        store.put("b", "two")

        store.forgetAll()

        assertThat(dao.getAll()).isEmpty()
    }

    @Test
    fun `missing secret returns null`() = runTest {
        assertThat(store.get("missing")).isNull()
    }

    @Test
    fun `wrong keyset fails cleanly`() = runTest {
        val plaintext = "sk-test-123"
        store.put("provider-1", plaintext)
        val otherStore = SecretStore(dao = dao, cipher = inMemorySecretCipher())

        val error = runCatching { otherStore.get("provider-1") }.exceptionOrNull()

        assertThat(error).isInstanceOf(SecretCipherException::class.java)
        assertThat(error?.message).doesNotContain(plaintext)
    }

    @Test
    fun `missing keyset fails cleanly`() = runTest {
        store.put("provider-1", "sk-test-123")
        val failingStore = SecretStore(dao = dao, cipher = FailingSecretCipher())

        val error = runCatching { failingStore.get("provider-1") }.exceptionOrNull()

        assertThat(error).isInstanceOf(SecretCipherException::class.java)
    }

    @Test
    fun `invalid ciphertext fails cleanly`() = runTest {
        dao.upsert(
            SecretEntity(
                id = "bad",
                ciphertext = "not-base64",
                createdAt = now.toString(),
                updatedAt = now.toString(),
            ),
        )

        val error = runCatching { store.get("bad") }.exceptionOrNull()

        assertThat(error).isInstanceOf(SecretCipherException::class.java)
    }

    @Test
    fun `blank id is rejected`() = runTest {
        val error = runCatching { store.put(" ", "sk-test-123") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
