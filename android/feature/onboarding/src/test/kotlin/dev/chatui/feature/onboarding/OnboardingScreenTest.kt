package dev.chatui.feature.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
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

        composeRule.onNodeWithTag(OnboardingTestTags.ConnectButton).performClick()

        assertThat(connectClicks).isEqualTo(1)
    }

    @Test
    fun `catalog models are visible before validation and completion is hidden`() {
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
            .performScrollToNode(hasTestTag(OnboardingTestTags.TestingStatus))
        composeRule.onNodeWithTag(OnboardingTestTags.TestingStatus).assertIsDisplayed()
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
    fun `connected state enables start chatting`() {
        composeRule.setContent {
            ChatUiTheme {
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

        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.ConnectedStatus))
        composeRule.onNodeWithTag(OnboardingTestTags.ConnectedStatus).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.StartChattingButton))
        composeRule.onNodeWithTag(OnboardingTestTags.StartChattingButton).assertIsEnabled()
    }

    @Test
    fun `step indicator is visible on both steps`() {
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

        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.CatalogLoading))
        composeRule.onNodeWithTag(OnboardingTestTags.CatalogLoading).assertIsDisplayed()
    }

    @Test
    fun `catalog collapses by default and expands with the disclosure`() {
        val models = (1..5).map { index ->
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
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.CatalogDisclosure))
        composeRule.onNodeWithTag(OnboardingTestTags.CatalogDisclosure).performClick()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasText("Model 4"))
        composeRule.onNodeWithText("Model 4").assertIsDisplayed()
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
    fun `completion state starts chatting`() {
        var startClicks = 0

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
                    onStartChatting = { startClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.Completion))
        composeRule.onNodeWithTag(OnboardingTestTags.Completion).assertIsDisplayed()
        composeRule.onNodeWithText("You're all set").assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.StartChattingButton))
        composeRule.onNodeWithTag(OnboardingTestTags.StartChattingButton).performClick()

        assertThat(startClicks).isEqualTo(1)
    }
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
