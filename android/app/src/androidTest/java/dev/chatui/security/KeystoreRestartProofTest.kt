package dev.chatui.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.datastore.secret.TinkSecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual two-phase process-restart proof.
 *
 * The normal connected suite skips this test because it needs an external
 * force-stop between phases. Run it manually:
 *
 * ```
 * adb shell am instrument -w \
 *   -e class dev.chatui.security.KeystoreRestartProofTest#restartPhase \
 *   -e phase store dev.chatui.test/androidx.test.runner.AndroidJUnitRunner
 * adb shell am force-stop dev.chatui
 * adb shell am instrument -w \
 *   -e class dev.chatui.security.KeystoreRestartProofTest#restartPhase \
 *   -e phase decrypt dev.chatui.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The sentinel is synthetic. Never replace it with a real key.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreRestartProofTest {
    @Test
    fun restartPhase() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        assumeTrue(
            "manual restart proof; pass -e phase store or -e phase decrypt",
            phase == "store" || phase == "decrypt",
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = ChatUiDatabase.builder(context, RESTART_DATABASE).build()
        try {
            val store = SecretStore(
                dao = database.secretDao(),
                cipher = TinkSecretCipher.androidKeystore(context),
            )
            when (phase) {
                "store" -> {
                    store.put(SECRET_ID, SENTINEL)
                    assertEquals(SENTINEL, store.get(SECRET_ID))
                }
                "decrypt" -> {
                    assertEquals(SENTINEL, store.get(SECRET_ID))
                    store.delete(SECRET_ID)
                }
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val SENTINEL = "m3-sentinel-not-a-real-key"
        const val SECRET_ID = "m3-keystore-restart"
        const val RESTART_DATABASE = "m3-keystore-restart.db"
    }
}
