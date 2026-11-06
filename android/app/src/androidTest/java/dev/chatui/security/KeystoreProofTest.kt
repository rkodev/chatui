package dev.chatui.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.datastore.secret.TinkSecretCipher
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the production Tink path uses the Android Keystore and persists only
 * ciphertext.
 *
 * Every value here is a synthetic sentinel. Never replace it with a real key.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreProofTest {
    private lateinit var context: Context
    private lateinit var database: ChatUiDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ChatUiDatabase.inMemory(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun secondCipherInstanceDecryptsAndroidKeystoreCiphertext() {
        val cipher = TinkSecretCipher.androidKeystore(context)

        val ciphertext = cipher.encrypt(SENTINEL)

        assertFalse("ciphertext must not embed the plaintext", ciphertext.contains(SENTINEL))
        val secondInstance = TinkSecretCipher.androidKeystore(context)
        assertEquals(SENTINEL, secondInstance.decrypt(ciphertext))
    }

    @Test
    fun roomStoresOnlyCiphertextForSentinel() = runBlocking {
        val store = SecretStore(
            dao = database.secretDao(),
            cipher = TinkSecretCipher.androidKeystore(context),
        )

        store.put(SECRET_ID, SENTINEL)

        val entity = database.secretDao().getById(SECRET_ID)
        assertNotNull("expected a stored secret row", entity)
        assertFalse("Room must not store plaintext", entity!!.ciphertext == SENTINEL)
        assertFalse("Room ciphertext must not contain plaintext", entity.ciphertext.contains(SENTINEL))
        assertEquals("the persisted ciphertext must decrypt", SENTINEL, store.get(SECRET_ID))

        store.delete(SECRET_ID)
    }

    @Test
    fun keysetPreferencesDoNotContainSentinel() {
        // Building the cipher ensures the Keystore-backed keyset is written.
        TinkSecretCipher.androidKeystore(context).encrypt(SENTINEL)

        val prefsFile = File(
            context.dataDir,
            "shared_prefs/${TinkSecretCipher.DEFAULT_PREFERENCES_NAME}.xml",
        )
        assertTrue(
            "keyset preferences file was not written: ${prefsFile.absolutePath}",
            prefsFile.exists(),
        )

        val raw = String(prefsFile.readBytes(), Charsets.ISO_8859_1)
        assertFalse("keyset preferences must not contain the sentinel", raw.contains(SENTINEL))
    }

    private companion object {
        const val SENTINEL = "m3-sentinel-not-a-real-key"
        const val SECRET_ID = "m3-keystore-proof"
    }
}
