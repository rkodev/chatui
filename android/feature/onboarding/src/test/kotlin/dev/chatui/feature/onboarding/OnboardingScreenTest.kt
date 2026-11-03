package dev.chatui.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
        composeRule.onNodeWithTag(OnboardingTestTags.providerOption("openai"))
            .assertIsNotEnabled()
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
    fun `catalog models are visible before validation and sending is disabled`() {
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
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.StartChattingButton))
        composeRule.onNodeWithTag(OnboardingTestTags.StartChattingButton).assertIsNotEnabled()
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

        composeRule.onNodeWithTag(OnboardingTestTags.TestingStatus).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.ConnectButton).assertIsNotEnabled()
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

        composeRule.onNodeWithTag(OnboardingTestTags.ConnectedStatus).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.Screen)
            .performScrollToNode(hasTestTag(OnboardingTestTags.StartChattingButton))
        composeRule.onNodeWithTag(OnboardingTestTags.StartChattingButton).assertIsEnabled()
    }
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
        statusLabel = "Coming later",
    ),
)
