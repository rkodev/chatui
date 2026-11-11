package dev.chatui.feature.providers

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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

    private fun openAddSheet() {
        composeRule.onNodeWithTag(ProviderListTestTags.AddProvider).performClick()
        composeRule.onNodeWithTag(ProviderListTestTags.AddSheet).assertIsDisplayed()
    }

    private fun openCustomForm() {
        openAddSheet()
        composeRule.onNodeWithTag(ProviderListTestTags.AddSheet)
            .performScrollToNode(hasTestTag(ProviderListTestTags.CustomOption))
        composeRule.onNodeWithTag(ProviderListTestTags.CustomOption).performClick()
        composeRule.onNodeWithTag(ProviderListTestTags.CustomForm).assertIsDisplayed()
    }

    @Test
    fun `shows grouped provider rows with key summary and no manage button`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.SectionConnected).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.provider("provider-1"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Work key · Usage \$1.5").assertIsDisplayed()
        composeRule.onAllNodesWithText("Manage").assertCountEquals(0)
    }

    @Test
    fun `groups providers without a key into the available section`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state().copy(
                        providers = listOf(
                            testProvider(id = "connected", label = "Connected"),
                            testProvider(
                                id = "available",
                                label = "Available",
                                secretId = null,
                            ),
                        ),
                    ),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.SectionConnected).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.SectionAvailable).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.provider("connected"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.provider("available"))
            .assertIsDisplayed()
    }

    @Test
    fun `add sheet shows enabled presets and coming-later presets`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        openAddSheet()

        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("openai"))
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Available").assertCountEquals(2)

        composeRule.onNodeWithTag(ProviderListTestTags.AddSheet)
            .performScrollToNode(hasTestTag(ProviderListTestTags.providerOption("google")))
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("google"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Coming later").assertIsDisplayed()
    }

    @Test
    fun `preset option opens the preset form with enabled presets`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        openAddSheet()
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("openai"))
            .performClick()

        composeRule.onNodeWithTag(ProviderListTestTags.PresetForm).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.presetOption("openai"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.presetOption("anthropic"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.presetOption("google"))
            .assertDoesNotExist()
    }

    @Test
    fun `preset chips meet the 48 dp touch target`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        openAddSheet()
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("openai"))
            .performClick()

        val preset = composeRule.onNodeWithTag(ProviderListTestTags.presetOption("openai"))
            .getUnclippedBoundsInRoot()
        assertThat((preset.bottom - preset.top).value).isAtLeast(48f)
    }

    @Test
    fun `auth chips meet the 48 dp touch target`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        openCustomForm()

        val auth = composeRule.onNodeWithTag(
            ProviderListTestTags.authOption("BEARER"),
        ).getUnclippedBoundsInRoot()
        assertThat((auth.bottom - auth.top).value).isAtLeast(48f)
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

        openAddSheet()
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("openai"))
            .performClick()
        composeRule.onNodeWithTag(ProviderListTestTags.PresetApiKey)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.PresetCreate).performClick()

        assertThat(selected).isEqualTo("openai")
        assertThat(created).isTrue()
    }

    @Test
    fun `custom option opens the custom provider form`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        openCustomForm()

        composeRule.onNodeWithTag(ProviderListTestTags.CustomBaseUrl).assertIsDisplayed()
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

        openCustomForm()
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

        openCustomForm()

        composeRule.onNodeWithTag(ProviderListTestTags.CustomError)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Enter a base URL.").assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.CustomNotice)
            .performScrollTo()
            .assertIsDisplayed()
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

        openCustomForm()

        composeRule.onNodeWithTag(ProviderListTestTags.CustomCleartextWarning)
            .performScrollTo()
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

        openCustomForm()

        composeRule.onNodeWithTag(ProviderListTestTags.CustomCleartextError)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(dev.chatui.core.providers.CLEARTEXT_REFUSED_ERROR)
            .assertIsDisplayed()
    }

    @Test
    fun `provider row opens the provider`() {
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

        composeRule.onNodeWithTag(ProviderListTestTags.provider("provider-1"))
            .performClick()

        assertThat(opened).isEqualTo("provider-1")
    }

    @Test
    fun `pull to refresh invokes retry`() {
        var retried = false

        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertThat(retried).isTrue()
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
