package dev.chatui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.ShellScaffold
import dev.chatui.shell.ShellTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h1280dp")
class ShellTransitionsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `navigation reaches a destination through the animated host`() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                navController = rememberNavController()
                ShellScaffold(
                    navController = navController,
                    startDestination = ShellDestination.Conversations.route,
                    conversationService = FakeConversationService(),
                )
            }
        }

        composeRule.onNodeWithTag(
            ShellTestTags.navigationItem(ShellDestination.Backup.route),
        ).performClick()

        composeRule.runOnIdle {
            assertThat(navController.currentDestination?.route)
                .isEqualTo(ShellDestination.Backup.route)
        }
    }
}
