package dev.chatui.feature.providers

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun state() = ProviderListUiState(
        providers = listOf(
            testProvider(
                metadata = buildJsonObject {
                    put(
                        "key",
                        buildJsonObject {
                            put("label", "Work key")
                            put("usageUsd", 1.5)
                            put("limitUsd", 10.0)
                            put("isFreeTier", false)
                        },
                    )
                },
            ),
        ),
        options = listOf(
            ProviderAvailability("openrouter", "OpenRouter", true, true, null),
            ProviderAvailability("openai", "OpenAI", true, false, null),
            ProviderAvailability("anthropic", "Anthropic", true, false, null),
            ProviderAvailability("google", "Google Gemini", false, false, "Coming later"),
        ),
        loading = false,
    )

    @Test
    fun `shows provider metadata and CORS`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.provider("provider-1"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Key label: Work key").assertIsDisplayed()
        composeRule.onNodeWithText("CORS: Supported").assertIsDisplayed()
        composeRule.onNodeWithText("Free tier: No").assertIsDisplayed()
    }

    @Test
    fun `shows enabled presets and coming-later presets`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.providerOption("openai")))
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("openai"))
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Available").assertCountEquals(2)

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.providerOption("google")))
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("google"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Coming later").assertIsDisplayed()
    }

    @Test
    fun `shows enabled preset options and the preset form`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.PresetForm))
        composeRule.onNodeWithTag(ProviderListTestTags.PresetForm).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.presetOption("openai"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.presetOption("anthropic"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.presetOption("google"))
            .assertDoesNotExist()
    }

    @Test
    fun `preset form forwards selection and creation`() {
        var selected: String? = null
        var created = false

        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state().copy(
                        presetForm = PresetProviderFormState(presetId = "openai"),
                    ),
                    onOpenProvider = {},
                    onRetry = {},
                    onPresetSelected = { selected = it },
                    onCreatePresetProvider = { created = true },
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.PresetForm))
        composeRule.onNodeWithTag(ProviderListTestTags.presetOption("openai"))
            .performClick()
        composeRule.onNodeWithTag(ProviderListTestTags.PresetApiKey).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.PresetCreate))
        composeRule.onNodeWithTag(ProviderListTestTags.PresetCreate).performClick()

        assertThat(selected).isEqualTo("openai")
        assertThat(created).isTrue()
    }

    @Test
    fun `shows the custom provider form`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.CustomForm))
        composeRule.onNodeWithTag(ProviderListTestTags.CustomForm).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.CustomBaseUrl).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.CustomCreate))
        composeRule.onNodeWithTag(ProviderListTestTags.CustomCreate).assertIsDisplayed()
    }

    @Test
    fun `custom form forwards the create action`() {
        var created = false

        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                    onCreateCustomProvider = { created = true },
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.CustomCreate))
        composeRule.onNodeWithTag(ProviderListTestTags.CustomCreate).performClick()

        assertThat(created).isTrue()
    }

    @Test
    fun `shows custom form errors and notices`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state().copy(
                        customForm = CustomProviderFormState(error = "Enter a base URL."),
                        notice = "Custom provider added.",
                    ),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.CustomError))
        composeRule.onNodeWithTag(ProviderListTestTags.CustomError).assertIsDisplayed()
        composeRule.onNodeWithText("Enter a base URL.").assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.CustomNotice))
        composeRule.onNodeWithTag(ProviderListTestTags.CustomNotice).assertIsDisplayed()
    }

    @Test
    fun `shows the local cleartext warning and refusal copy`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state().copy(
                        customForm = CustomProviderFormState(
                            baseUrl = "http://10.0.2.2:11434/v1",
                            cleartextWarning = dev.chatui.core.providers.CLEARTEXT_LOCAL_WARNING,
                        ),
                    ),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.CustomCleartextWarning))
        composeRule.onNodeWithTag(ProviderListTestTags.CustomCleartextWarning)
            .assertIsDisplayed()
        composeRule.onNodeWithText(dev.chatui.core.providers.CLEARTEXT_LOCAL_WARNING)
            .assertIsDisplayed()
    }

    @Test
    fun `shows the refused cleartext copy`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state().copy(
                        customForm = CustomProviderFormState(
                            baseUrl = "http://api.example.com/v1",
                            cleartextError = dev.chatui.core.providers.CLEARTEXT_REFUSED_ERROR,
                        ),
                    ),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.CustomCleartextError))
        composeRule.onNodeWithTag(ProviderListTestTags.CustomCleartextError)
            .assertIsDisplayed()
        composeRule.onNodeWithText(dev.chatui.core.providers.CLEARTEXT_REFUSED_ERROR)
            .assertIsDisplayed()
    }

    @Test
    fun `manage action opens the provider`() {
        var opened: String? = null

        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = { opened = it },
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Manage").performClick()

        assertThat(opened).isEqualTo("provider-1")
    }

    @Test
    fun `shows an empty state`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = ProviderListUiState(loading = false),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Empty).assertIsDisplayed()
    }
}
