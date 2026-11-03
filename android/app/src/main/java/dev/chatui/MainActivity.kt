package dev.chatui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.shell.ChatUiApp
import dev.chatui.startup.AppStartup
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Single activity that hosts the Compose shell. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var startup: AppStartup

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var startupComplete by remember { mutableStateOf(false) }

            // Recover interrupted streams before the shell renders so chat
            // interaction never starts against a stale `streaming` row.
            LaunchedEffect(Unit) {
                try {
                    startup.run()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Startup recovery failed", error)
                } finally {
                    startupComplete = true
                }
            }

            // Wait for the persisted settings before choosing the start
            // destination so a returning user never sees onboarding flash.
            val settings by settingsDataStore.settings.collectAsStateWithLifecycle(
                initialValue = null,
            )

            if (startupComplete) {
                settings?.let { loaded -> ChatUiApp(settings = loaded) }
            }
        }
    }

    private companion object {
        const val TAG = "ChatUiStartup"
    }
}
