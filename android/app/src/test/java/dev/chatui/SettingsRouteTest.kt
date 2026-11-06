package dev.chatui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.feature.settings.SettingsRootScreen
import dev.chatui.feature.settings.SettingsTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings root exposes every section and the provider entry point`() {
        var openedProviders = 0
        var openedAppearance = 0

        composeRule.setContent {
            ChatUiTheme {
                SettingsRootScreen(
                    onOpenAppearance = { openedAppearance += 1 },
                    onOpenChatDefaults = {},
                    onOpenProviders = { openedProviders += 1 },
                    onOpenData = {},
                    onOpenBackup = {},
                    onOpenAbout = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.Providers).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.Appearance).performClick()

        assertThat(openedProviders).isEqualTo(1)
        assertThat(openedAppearance).isEqualTo(1)
    }
}
