package dev.chatui.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `root exposes every settings section`() {
        composeRule.setContent {
            ChatUiTheme {
                SettingsRootScreen(
                    onOpenAppearance = {},
                    onOpenChatDefaults = {},
                    onOpenProviders = {},
                    onOpenData = {},
                    onOpenBackup = {},
                    onOpenAbout = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.Appearance).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.ChatDefaults).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.Providers).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.Data).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.Backup).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.About).assertIsDisplayed()
    }

    @Test
    fun `appearance selection forwards the chosen theme`() {
        var selected: Theme? = null

        composeRule.setContent {
            ChatUiTheme {
                AppearanceSettingsScreen(
                    settings = AndroidSettings(),
                    onThemeChanged = { selected = it },
                    onDensityChanged = {},
                    onChatFontSizeChanged = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.theme("dark")).performClick()

        assertThat(selected).isEqualTo(Theme.DARK)
    }

    @Test
    fun `chat defaults forwards behavior toggles`() {
        var remoteImages: Boolean? = null

        composeRule.setContent {
            ChatUiTheme {
                ChatDefaultsSettingsScreen(
                    state = SettingsUiState(loading = false),
                    onDefaultProviderChanged = {},
                    onDefaultModelChanged = {},
                    onDefaultSystemPromptChanged = {},
                    onTemperatureChanged = {},
                    onTopPChanged = {},
                    onMaxOutputTokensChanged = {},
                    onStopChanged = {},
                    onReasoningEffortChanged = {},
                    onStreamingChanged = {},
                    onSendKeyChanged = {},
                    onShowTokenUsageChanged = {},
                    onLoadRemoteImagesChanged = { remoteImages = it },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.LoadRemoteImages)
            .performScrollTo()
            .performClick()

        assertThat(remoteImages).isFalse()
    }

    @Test
    fun `data screen renders`() {
        composeRule.setContent {
            ChatUiTheme { DataSettingsScreen() }
        }

        composeRule.onNodeWithTag(SettingsTestTags.Data).assertIsDisplayed()
    }

    @Test
    fun `backup screen renders`() {
        composeRule.setContent {
            ChatUiTheme { BackupSettingsScreen() }
        }

        composeRule.onNodeWithTag(SettingsTestTags.Backup).assertIsDisplayed()
    }

    @Test
    fun `about screen renders`() {
        composeRule.setContent {
            ChatUiTheme { AboutSettingsScreen() }
        }

        composeRule.onNodeWithTag(SettingsTestTags.About).assertIsDisplayed()
    }
}
