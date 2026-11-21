package dev.chatui.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density as DisplayDensity
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Theme
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.Density
import dev.chatui.core.model.SendKey
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

        composeRule.onNodeWithTag(SettingsTestTags.Appearance)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.ChatDefaults)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.Providers)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.Data)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.Backup)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.About)
            .performScrollTo()
            .assertIsDisplayed()
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
    fun `appearance forwards haptic and dynamic color toggles`() {
        var haptics: Boolean? = null
        var dynamicColor: Boolean? = null

        composeRule.setContent {
            ChatUiTheme {
                AppearanceSettingsScreen(
                    settings = AndroidSettings(),
                    onThemeChanged = {},
                    onDensityChanged = {},
                    onChatFontSizeChanged = {},
                    onHapticFeedbackChanged = { haptics = it },
                    onDynamicColorChanged = { dynamicColor = it },
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.HapticFeedback)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(SettingsTestTags.DynamicColor)
            .performScrollTo()
            .performClick()

        assertThat(haptics).isFalse()
        assertThat(dynamicColor).isTrue()
    }

    @Test
    fun `haptic feedback setting is reachable and announced as a toggle`() {
        composeRule.setContent {
            ChatUiTheme {
                AppearanceSettingsScreen(
                    settings = AndroidSettings(),
                    onThemeChanged = {},
                    onDensityChanged = {},
                    onChatFontSizeChanged = {},
                )
            }
        }

        composeRule.onNodeWithText("Haptic feedback")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.HapticFeedback)
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    androidx.compose.ui.state.ToggleableState.On,
                ),
            )
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

        composeRule.onNodeWithTag(SettingsTestTags.Privacy).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.LoadRemoteImages)
            .performScrollTo()
            .performClick()

        assertThat(remoteImages).isFalse()
    }

    @Test
    fun `model entry opens picker rather than an inline catalog and retains unavailable warning`() {
        var opened = 0
        composeRule.setContent {
            ChatUiTheme {
                Defaults(
                    state = SettingsUiState(
                        loading = false,
                        settings = AndroidSettings(defaultModelId = "missing-model"),
                        warning = "The selected model is hidden or unavailable.",
                    ),
                    openPicker = { opened++ },
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.DefaultModel).performClick()
        assertThat(opened).isEqualTo(1)
        composeRule.onNodeWithTag(SettingsTestTags.ModelWarning).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("missing-model").assertExists()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun `appearance choices wrap at large fonts with one preview and accessible targets`() {
        var settings by mutableStateOf(AndroidSettings())
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides DisplayDensity(1f, 2f)) {
                ChatUiTheme(density = settings.density) {
                    AppearanceSettingsScreen(
                        settings = settings,
                        onThemeChanged = { settings = settings.copy(theme = it) },
                        onDensityChanged = { settings = settings.copy(density = it) },
                        onChatFontSizeChanged = { settings = settings.copy(chatFontSize = it) },
                    )
                }
            }
        }
        Theme.entries.forEach {
            composeRule.onNodeWithTag(SettingsTestTags.theme(it.name.lowercase()))
                .performScrollTo().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        }
        Density.entries.forEach {
            composeRule.onNodeWithTag(SettingsTestTags.density(it.name.lowercase()))
                .performScrollTo().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        }
        ChatFontSize.entries.forEach {
            composeRule.onNodeWithTag(SettingsTestTags.fontSize(it.name.lowercase()))
                .performScrollTo().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        }
        composeRule.onNodeWithTag(SettingsTestTags.Preview).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `disclosures retain numeric edits and all moved controls remain available`() {
        var params by mutableStateOf(AndroidSettings.DEFAULT_CHAT_PARAMS)
        var prompt by mutableStateOf("")
        var sendKey: SendKey? = null
        var usage: Boolean? = null
        composeRule.setContent {
            ChatUiTheme {
                ChatDefaultsSettingsScreen(
                    state = SettingsUiState(
                        settings = AndroidSettings(defaultParams = params, defaultSystemPrompt = prompt),
                        loading = false,
                    ),
                    onDefaultProviderChanged = {},
                    onDefaultModelChanged = {},
                    onDefaultSystemPromptChanged = { prompt = it },
                    onTemperatureChanged = { params = params.copy(temperature = it.toDoubleOrNull()) },
                    onTopPChanged = {},
                    onMaxOutputTokensChanged = {},
                    onStopChanged = {},
                    onReasoningEffortChanged = {},
                    onStreamingChanged = {},
                    onSendKeyChanged = { sendKey = it },
                    onShowTokenUsageChanged = { usage = it },
                    onLoadRemoteImagesChanged = {},
                    onDismissError = {},
                )
            }
        }
        composeRule.onNodeWithTag(SettingsTestTags.DefaultSystemPrompt).performTextInput("Keep exact content")
        composeRule.onNodeWithTag(SettingsTestTags.Generation).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.DefaultTemperature).performScrollTo().performTextInput("0.25")
        listOf(
            SettingsTestTags.DefaultTopP, SettingsTestTags.DefaultMaxOutput,
            SettingsTestTags.DefaultStop, SettingsTestTags.reasoning("high"), SettingsTestTags.DefaultStreaming,
        ).forEach { composeRule.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithTag(SettingsTestTags.Generation).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.DefaultTemperature).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTestTags.Generation).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.DefaultTemperature).performScrollTo().assertTextContains("0.25")
        composeRule.onNodeWithTag(SettingsTestTags.Composer).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.sendKey("mod_enter")).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.Privacy).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.ShowTokenUsage).performScrollTo().performClick()
        assertThat(params.temperature).isEqualTo(0.25)
        assertThat(prompt).isEqualTo("Keep exact content")
        assertThat(sendKey).isEqualTo(SendKey.MOD_ENTER)
        assertThat(usage).isFalse()
    }

    @Test
    fun `initial load and unrelated emissions never claim a save`() {
        var settings by mutableStateOf(AndroidSettings())
        composeRule.setContent {
            ChatUiTheme {
                AppearanceSettingsScreen(
                    settings = settings,
                    onThemeChanged = {},
                    onDensityChanged = {},
                    onChatFontSizeChanged = {},
                )
            }
        }
        settings = settings.copy(lastActiveConversationId = "another-chat")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SettingsTestTags.Saved).assertDoesNotExist()
        composeRule.onNodeWithText("Appearance saved").assertDoesNotExist()
    }

    @Test
    fun `about screen renders`() {
        composeRule.setContent {
            ChatUiTheme { AboutSettingsScreen() }
        }

        composeRule.onNodeWithTag(SettingsTestTags.About).assertIsDisplayed()
        composeRule.onNodeWithText("Remote images in model output are blocked until you allow them.")
            .assertDoesNotExist()
    }

    @Composable
    private fun Defaults(state: SettingsUiState, openPicker: () -> Unit) {
        ChatDefaultsSettingsScreen(
            state = state,
            onDefaultProviderChanged = {}, onDefaultModelChanged = {},
            onDefaultSystemPromptChanged = {}, onTemperatureChanged = {}, onTopPChanged = {},
            onMaxOutputTokensChanged = {}, onStopChanged = {}, onReasoningEffortChanged = {},
            onStreamingChanged = {}, onSendKeyChanged = {}, onShowTokenUsageChanged = {},
            onLoadRemoteImagesChanged = {}, onDismissError = {}, onOpenModelPicker = openPicker,
        )
    }
}
