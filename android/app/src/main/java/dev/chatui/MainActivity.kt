package dev.chatui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.shell.ChatUiApp
import javax.inject.Inject

/** Single activity that hosts the Compose shell. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsDataStore.settings.collectAsStateWithLifecycle(
                initialValue = AndroidSettings(),
            )
            ChatUiApp(settings = settings)
        }
    }
}
