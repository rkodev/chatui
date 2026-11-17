package dev.chatui.feature.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Theme
import dev.chatui.core.model.BackupConfig
import dev.chatui.core.backup.sync.SyncResult
import dev.chatui.core.backup.sync.RestoreResult
import dev.chatui.feature.backup.BackupFormValues
import dev.chatui.feature.backup.BackupSettingsServiceLike
import dev.chatui.feature.backup.BackupSettingsSnapshot
import dev.chatui.feature.backup.BackupSettingsTestTags
import dev.chatui.feature.backup.BackupStatus
import dev.chatui.feature.backup.RestoreRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `first run shows welcome and preselects OpenRouter`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.Welcome).assertIsDisplayed()
        openProviders()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.providerOption("openrouter")))
        composeRule.onNodeWithText("Recommended").assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.providerOption("openrouter"))
            .assertIsDisplayed()
            .assertTextContains("OpenRouter")
        composeRule.onNodeWithTag(OnboardingTestTags.providerOption("openai"))
            .assertIsNotEnabled()
    }

    @Test
    fun `welcome copy does not claim other providers are coming later`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithText("coming later", substring = true, ignoreCase = true)
            .assertDoesNotExist()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.ProviderPicker))
        composeRule.onNodeWithText(
            "You can add other providers from Providers after setup.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `provider picker forwards the selected provider`() {
        var selected: String? = null

        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = { selected = it },
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        openProviders()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.providerOption("openrouter")))
        composeRule.onNodeWithTag(OnboardingTestTags.providerOption("openrouter"))
            .performClick()

        assertThat(selected).isEqualTo("openrouter")
    }

    @Test
    fun `invalid key shows an actionable error and preserves the form`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        apiKey = "sk-bad",
                        label = "Work",
                        error = OnboardingErrorCopy(
                            title = "API key rejected",
                            message = "Your API key was rejected. Check the key and try again.",
                            details = "Invalid key",
                        ),
                        catalog = CatalogUiState.Ready(
                            listOf(CatalogModel("openrouter/auto", "Auto", 128_000, false)),
                        ),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.ErrorCard))
        composeRule.onNodeWithTag(OnboardingTestTags.ErrorCard).assertIsDisplayed()
        composeRule.onNodeWithText("Your API key was rejected. Check the key and try again.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.ApiKeyField).assertTextContains("sk-bad")
        composeRule.onNodeWithTag(OnboardingTestTags.LabelField).assertTextContains("Work")
    }

    @Test
    fun `connect button invokes the connect intent`() {
        var connectClicks = 0

        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        apiKey = "sk-good",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = { connectClicks += 1 },
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.ConnectButton))
        composeRule.onNodeWithTag(OnboardingTestTags.ConnectButton).performClick()

        assertThat(connectClicks).isEqualTo(1)
    }

    @Test
    fun `public catalog is disclosed before validation and completion is hidden`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(
                            listOf(CatalogModel("openrouter/auto", "Auto", 128_000, false)),
                        ),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithText("openrouter/auto").assertDoesNotExist()
        openCatalog()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasText("openrouter/auto"))
        composeRule.onNodeWithText("openrouter/auto").assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.StartChattingButton).assertDoesNotExist()
    }

    @Test
    fun `testing state disables connect and shows progress`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        apiKey = "sk-good",
                        testing = true,
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.ConnectButton))
        composeRule.onAllNodesWithTag(OnboardingTestTags.TestingStatus, useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithTag(OnboardingTestTags.TestingStatus, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.ConnectButton).assertTextContains("Connecting...")
        composeRule.onNodeWithTag(OnboardingTestTags.ConnectButton).assertIsNotEnabled()
    }

    @Test
    fun `key entry step sets flag secure`() {
        lateinit var window: Window

        composeRule.setContent {
            window = LocalView.current.context.findActivity().window
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertThat(window.isSecure()).isTrue()
    }

    @Test
    fun `welcome step does not set flag secure`() {
        lateinit var window: Window

        composeRule.setContent {
            window = LocalView.current.context.findActivity().window
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertThat(window.isSecure()).isFalse()
    }

    @Test
    fun `backup step enables start chatting`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.BACKUP,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        apiKey = "sk-good",
                        connected = true,
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.BackupStep).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.StartChattingButton).assertIsEnabled()
        composeRule.onNodeWithTag(OnboardingTestTags.BackupStep)
            .performScrollToNode(hasTestTag(OnboardingTestTags.BackupUnavailable))
        composeRule.onNodeWithTag(OnboardingTestTags.BackupUnavailable).assertIsDisplayed()
    }

    @Test
    fun `step indicator is visible across steps`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.StepIndicator).assertIsDisplayed()
        composeRule.onNodeWithText("Welcome").assertIsDisplayed()
        composeRule.onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test
    fun `provider card exposes selection semantics`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        openProviders()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.providerOption("openrouter")))
        composeRule.onNodeWithTag(OnboardingTestTags.providerOption("openrouter"))
            .assertIsSelected()
    }

    @Test
    fun `key entry exposes show hide paste and help affordances`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.ShowKeyToggle).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.PasteKey).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.HelpLink).assertIsDisplayed()
    }

    @Test
    fun `catalog shows a skeleton while loading`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Loading,
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        openCatalog()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.CatalogLoading))
        composeRule.onNodeWithTag(OnboardingTestTags.CatalogLoading).assertIsDisplayed()
    }

    @Test
    fun `catalog error shows a retry action`() {
        var retried = false

        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Error("Catalog unavailable"),
                        cachedCatalog = listOf(CatalogModel("cached/model", "Cached model", 4096, false)),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = { retried = true },
                )
            }
        }

        openCatalog()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.CatalogError))
        composeRule.onNodeWithTag(OnboardingTestTags.CatalogError).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.RetryCatalog).performClick()

        assertThat(retried).isTrue()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasText("Cached model"))
        composeRule.onNodeWithText("Cached model").assertIsDisplayed()
    }

    @Test
    fun `catalog empty state explains that models load after validation`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        openCatalog()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.CatalogEmpty))
        composeRule.onNodeWithTag(OnboardingTestTags.CatalogEmpty).assertIsDisplayed()
        composeRule.onNodeWithText("No models returned").assertIsDisplayed()
    }

    @Test
    fun `catalog is optional and expanded rows are virtualized in the outer list`() {
        val models = (1..100).map { index ->
            CatalogModel("openrouter/model-$index", "Model $index", 128_000, index == 1)
        }

        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        catalog = CatalogUiState.Ready(models),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithText("Model 4").assertDoesNotExist()
        openCatalog()
        composeRule.onNodeWithText("Model 100").assertDoesNotExist()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasText("Model 100"))
        composeRule.onNodeWithText("Model 100").assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.CatalogDisclosure))
        composeRule.onNodeWithTag(OnboardingTestTags.CatalogDisclosure).performClick()
        composeRule.onNodeWithText("Model 100").assertDoesNotExist()
    }

    @Test
    fun `connected connect button morphs to a success check`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        apiKey = "sk-good",
                        connected = true,
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.ConnectButton)
            .assertTextContains("Connected")
    }

    @Test
    fun `backup step starts chatting`() {
        var startClicks = 0

        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.BACKUP,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        apiKey = "sk-good",
                        connected = true,
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                    onStartChatting = { startClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.StartChattingButton).performClick()

        assertThat(startClicks).isEqualTo(1)
    }

    @Test
    fun `backup step can be skipped`() {
        var startClicks = 0

        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    state = OnboardingUiState(
                        step = OnboardingStep.BACKUP,
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        connected = true,
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                    onStartChatting = { startClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.BackupSkip).performClick()

        assertThat(startClicks).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `get started stays visible while scrolling a compact welcome screen`() {
        var continueClicks = 0
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    state = OnboardingUiState(providers = providers()),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = { continueClicks += 1 },
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.ContinueButton).assertIsDisplayed()
        openProviders()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.providerOption("openai")))
        composeRule.onNodeWithTag(OnboardingTestTags.ContinueButton)
            .assertIsDisplayed()
            .performClick()

        assertThat(continueClicks).isEqualTo(1)
    }

    @Test
    fun `welcome step offers light dark and system appearance choices`() {
        var selected: Theme? = null

        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        providers = providers(),
                        selectedProviderId = "openrouter",
                        theme = Theme.SYSTEM,
                        catalog = CatalogUiState.Ready(emptyList()),
                    ),
                    onApiKeyChanged = {},
                    onLabelChanged = {},
                    onProviderSelected = {},
                    onContinue = {},
                    onBack = {},
                    onConnect = {},
                    onRetryCatalog = {},
                    onThemeSelected = { selected = it },
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.ThemeSelector))
        composeRule.onNodeWithTag(OnboardingTestTags.ThemeSelector).performClick()
        composeRule.onNodeWithTag(OnboardingTestTags.themeOption(Theme.LIGHT))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.themeOption(Theme.DARK))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.themeOption(Theme.SYSTEM))
            .assertIsSelected()
            .performClick()
        composeRule.onNodeWithTag(OnboardingTestTags.themeOption(Theme.DARK)).performClick()

        assertThat(selected).isEqualTo(Theme.DARK)
    }

    @Test
    fun `paste show hide and help use the existing key and documentation targets`() {
        lateinit var clipboard: ClipboardManager
        var openedUri: String? = null
        var state by mutableStateOf(OnboardingUiState(step = OnboardingStep.KEY_ENTRY, providers = providers()))
        composeRule.setContent {
            clipboard = LocalClipboardManager.current
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { openedUri = uri }
            }) {
                ChatUiTheme {
                    OnboardingScreen(
                        state, onApiKeyChanged = { state = state.copy(apiKey = it) },
                        onLabelChanged = {}, onProviderSelected = {}, onContinue = {},
                        onBack = {}, onConnect = {}, onRetryCatalog = {},
                    )
                }
            }
        }
        composeRule.runOnIdle { clipboard.setText(AnnotatedString("fake-pasted-key")) }
        composeRule.onNodeWithTag(OnboardingTestTags.PasteKey).performClick()
        assertThat(state.apiKey).isEqualTo("fake-pasted-key")
        assertThat(composeRule.onNodeWithTag(OnboardingTestTags.ApiKeyField)
            .fetchSemanticsNode().config.contains(SemanticsProperties.Password)).isTrue()
        assertThat(keyLayoutText()).doesNotContain("fake-pasted-key")
        composeRule.onNodeWithTag(OnboardingTestTags.ShowKeyToggle).performClick()
        assertThat(keyLayoutText()).isEqualTo("fake-pasted-key")
        composeRule.onNodeWithTag(OnboardingTestTags.ShowKeyToggle).performClick()
        assertThat(keyLayoutText()).doesNotContain("fake-pasted-key")
        composeRule.onNodeWithTag(OnboardingTestTags.HelpLink).performClick()
        assertThat(openedUri).isEqualTo("https://openrouter.ai/keys")
    }

    @Test
    fun `other provider selections never relabel the existing OpenRouter validation target`() {
        composeRule.setContent {
            ChatUiTheme {
                OnboardingScreen(
                    OnboardingUiState(
                        step = OnboardingStep.KEY_ENTRY,
                        providers = providers().map { it.copy(enabled = true) },
                        selectedProviderId = "openai",
                    ),
                    onApiKeyChanged = {}, onLabelChanged = {}, onProviderSelected = {},
                    onContinue = {}, onBack = {}, onConnect = {}, onRetryCatalog = {},
                )
            }
        }
        composeRule.onNodeWithText("First-run connection currently validates an OpenRouter key.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.ApiKeyField).assertTextContains("OpenRouter API key")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun `connection and backup actions wrap with large fonts and simulated keyboard space`() {
        var state by mutableStateOf(
            OnboardingUiState(step = OnboardingStep.KEY_ENTRY, providers = providers(), apiKey = "fake-key"),
        )
        val backup = OnboardingBackupFake()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChatUiTheme(reducedMotion = true) {
                    OnboardingScreen(
                        state,
                        onApiKeyChanged = {}, onLabelChanged = {}, onProviderSelected = {},
                        onContinue = {}, onBack = {}, onConnect = {}, onRetryCatalog = {},
                        modifier = Modifier.padding(bottom = 224.dp),
                        backupService = backup,
                    )
                }
            }
        }
        listOf(OnboardingTestTags.ApiKeyField, OnboardingTestTags.HelpLink, OnboardingTestTags.ConnectButton)
            .forEach { tag ->
                composeRule.onNodeWithTag(OnboardingTestTags.Screen).performScrollToNode(hasTestTag(tag))
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
                val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertThat(bounds.left).isAtLeast(0f)
                assertThat(bounds.right).isAtMost(320f)
                assertThat(bounds.bottom).isAtMost(344f)
            }
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.LabelDisclosure))
        composeRule.onNodeWithTag(OnboardingTestTags.LabelDisclosure).performClick()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.LabelField))
        composeRule.onNodeWithTag(OnboardingTestTags.LabelField).assertIsDisplayed()
        openCatalog()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.CatalogLoading))
        composeRule.onNodeWithTag(OnboardingTestTags.CatalogLoading).assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(step = OnboardingStep.BACKUP, connected = true) }
        composeRule.onNodeWithTag(OnboardingTestTags.BackupStep)
            .performScrollToNode(hasTestTag(OnboardingTestTags.BackupSkip))
        composeRule.onNodeWithTag(OnboardingTestTags.BackupSkip).assertIsDisplayed()
        assertThat(backup.calls).isEmpty()
        composeRule.onNodeWithTag(OnboardingTestTags.BackupStep)
            .performScrollToNode(hasTestTag(OnboardingTestTags.BackupSetup))
        composeRule.onNodeWithTag(OnboardingTestTags.BackupSetup).performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.SetupCancel).performScrollTo().performClick()
        composeRule.onNodeWithTag(OnboardingTestTags.BackupSetup).assertIsFocused()
    }

    @Test
    fun `backup choice does not call the service and setup cancellation restores choice focus and security`() {
        val backup = OnboardingBackupFake()
        lateinit var activity: ComponentActivity
        var finishes = 0
        var notice by mutableStateOf<String?>(null)
        composeRule.setContent {
            activity = LocalView.current.context.findActivity() as ComponentActivity
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    OnboardingUiState(step = OnboardingStep.BACKUP, connected = true, notice = notice),
                    onApiKeyChanged = {}, onLabelChanged = {}, onProviderSelected = {},
                    onContinue = {}, onBack = {}, onConnect = {}, onRetryCatalog = {},
                    backupService = backup, onStartChatting = { finishes++ },
                )
            }
        }
        composeRule.onNodeWithTag(BackupSettingsTestTags.Root).assertDoesNotExist()
        composeRule.runOnIdle { notice = "Optional setup" }
        composeRule.runOnIdle { assertThat(backup.calls).isEmpty() }
        composeRule.onNodeWithTag(OnboardingTestTags.BackupSetup).performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Root).assertIsDisplayed()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Status).assertDoesNotExist()
        composeRule.onNodeWithTag(BackupSettingsTestTags.SetupCancel).performScrollTo().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Root).assertDoesNotExist()
        composeRule.onNodeWithTag(OnboardingTestTags.BackupSetup).assertIsFocused()
        assertThat(activity.window.isSecure()).isTrue()

        composeRule.onNodeWithTag(OnboardingTestTags.BackupSetup).performClick()
        composeRule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(OnboardingTestTags.BackupStep).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.BackupSetup).assertIsFocused()
        composeRule.onNodeWithTag(OnboardingTestTags.StartChattingButton).performClick()
        assertThat(finishes).isEqualTo(1)
        assertThat(backup.calls).containsExactly("load", "status").inOrder()
    }

    @Test
    fun `explicit backup setup tests enables and completes only after a successful first backup`() {
        val backup = OnboardingBackupFake()
        var finishes = 0
        showBackupChoice(backup) { finishes++ }
        composeRule.onNodeWithTag(OnboardingTestTags.BackupSetup).performClick()
        fillBackupForm()
        composeRule.onNodeWithTag(BackupSettingsTestTags.TestConnection).performScrollTo().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Enable).performScrollTo().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.SetupComplete)
            .performScrollTo().assertIsEnabled().performClick()
        assertThat(finishes).isEqualTo(1)
        assertThat(backup.calls).containsAtLeast("test", "enable", "backup")
        assertThat(backup.lastForm?.passphrase).isEqualTo("fake-passphrase")
    }

    @Test
    fun `backup failure retains input and allows retry without repeating provider onboarding`() {
        val backup = OnboardingBackupFake().apply { backupError = IllegalStateException("Offline; try again") }
        var finishes = 0
        showBackupChoice(backup) { finishes++ }
        composeRule.onNodeWithTag(OnboardingTestTags.BackupSetup).performClick()
        fillBackupForm()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Enable).performScrollTo().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Error).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Passphrase).assertTextContains("fake-passphrase")
        composeRule.onNodeWithTag(BackupSettingsTestTags.SetupComplete).performScrollTo().assertIsNotEnabled()
        assertThat(finishes).isEqualTo(0)
        backup.backupError = null
        composeRule.onNodeWithTag(BackupSettingsTestTags.Enable).performScrollTo().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.SetupComplete).performScrollTo().performClick()
        assertThat(finishes).isEqualTo(1)
        assertThat(backup.calls.count { it == "enable" }).isEqualTo(2)
    }

    private fun openProviders() {
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.ProviderPicker))
        composeRule.onNodeWithTag(OnboardingTestTags.ProviderPicker).performClick()
    }

    private fun keyLayoutText(): String {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(OnboardingTestTags.ApiKeyField)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.text.text
    }

    private fun openCatalog() {
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.CatalogDisclosure))
        composeRule.onNodeWithTag(OnboardingTestTags.CatalogDisclosure).performClick()
    }

    private fun showBackupChoice(service: BackupSettingsServiceLike, onFinished: () -> Unit) {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                OnboardingScreen(
                    OnboardingUiState(step = OnboardingStep.BACKUP, connected = true),
                    onApiKeyChanged = {}, onLabelChanged = {}, onProviderSelected = {},
                    onContinue = {}, onBack = {}, onConnect = {}, onRetryCatalog = {},
                    backupService = service, onStartChatting = onFinished,
                )
            }
        }
    }

    private fun fillBackupForm() {
        mapOf(
            BackupSettingsTestTags.Endpoint to "https://backup.example.test",
            BackupSettingsTestTags.Region to "region",
            BackupSettingsTestTags.Bucket to "chatui",
            BackupSettingsTestTags.AccessKey to "fake-access",
            BackupSettingsTestTags.SecretKey to "fake-secret",
            BackupSettingsTestTags.Passphrase to "fake-passphrase",
        ).forEach { (tag, text) ->
            composeRule.onNodeWithTag(tag).performScrollTo().performTextInput(text)
        }
    }
}

private class OnboardingBackupFake : BackupSettingsServiceLike {
    val calls = mutableListOf<String>()
    var backupError: Throwable? = null
    var lastForm: BackupFormValues? = null
    override suspend fun load(): BackupSettingsSnapshot {
        calls += "load"
        return BackupSettingsSnapshot(config = null, credentials = null)
    }
    override suspend fun getStatus(): BackupStatus {
        calls += "status"
        return BackupStatus()
    }
    override suspend fun setEnabled(enabled: Boolean, values: BackupFormValues): BackupConfig? {
        calls += "enable"
        lastForm = values
        return null
    }
    override suspend fun testConnection(values: BackupFormValues) { calls += "test" }
    override suspend fun backupNow(): SyncResult {
        calls += "backup"
        backupError?.let { throw it }
        return SyncResult(emptyList(), emptyList(), emptyList(), null, 0)
    }
    override suspend fun saveConfig(values: BackupFormValues, enabled: Boolean): BackupConfig =
        error("Unexpected automatic save")
    override suspend fun restore(request: RestoreRequest): RestoreResult = error("Unexpected restore")
    override suspend fun deleteRemote(confirmation: String) = error("Unexpected deletion")
}

private fun Window.isSecure(): Boolean =
    attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No activity in context chain")
}

private fun providers(): List<ProviderOption> = listOf(
    ProviderOption(
        id = "openrouter",
        label = "OpenRouter",
        enabled = true,
        recommended = true,
        statusLabel = null,
    ),
    ProviderOption(
        id = "openai",
        label = "OpenAI",
        enabled = false,
        recommended = false,
        statusLabel = "Unavailable",
    ),
)
