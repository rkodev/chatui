package dev.chatui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
import dev.chatui.shell.ChatUiApp
import dev.chatui.shell.ShellTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shell applies persisted dark theme and compact density`() {
        composeRule.setContent {
            ChatUiApp(
                settings = AndroidSettings(
                    theme = Theme.DARK,
                    density = Density.COMPACT,
                ),
            )
        }

        composeRule.onNodeWithTag(
            ShellTestTags.appearance(Theme.DARK, Density.COMPACT),
        ).assertExists()
        composeRule.onNodeWithText(
            "Conversation history and search arrive in M1.",
        ).assertIsDisplayed()
    }
}
