package dev.chatui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.feature.chat.ChatDependencies
import dev.chatui.feature.chat.ChatRoute

/**
 * Debug-only test activity that hosts the real [ChatRoute] with fake
 * dependencies.
 *
 * The activity lives in the app's debug source set rather than androidTest
 * because an activity declared in the test APK resolves to the test package
 * process, and `ActivityScenario` refuses to launch it from the target app
 * process. The instrumentation test sets [ConfigChangeTestDependencies] before
 * launching this activity.
 */
object ConfigChangeTestDependencies {
    @Volatile
    var conversationId: String? = null

    @Volatile
    var dependencies: ChatDependencies? = null
}

/** Test-only host for the configuration-change lifecycle test. */
class ConfigChangeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conversationId = requireNotNull(ConfigChangeTestDependencies.conversationId) {
            "ConfigChangeTestDependencies.conversationId must be set before launch"
        }
        val dependencies = requireNotNull(ConfigChangeTestDependencies.dependencies) {
            "ConfigChangeTestDependencies.dependencies must be set before launch"
        }

        setContent {
            ChatUiTheme {
                ChatRoute(
                    conversationId = conversationId,
                    dependencies = dependencies,
                    onBack = {},
                    onOpenProviders = {},
                )
            }
        }
    }
}
