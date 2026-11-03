package dev.chatui.feature.providers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
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
            ProviderAvailability("openai", "OpenAI", false, false, "Coming later"),
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
    fun `shows other presets as coming later and no custom creation`() {
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
        composeRule.onNodeWithText("Coming later").assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderListTestTags.NoCustomProvider))
        composeRule.onNodeWithTag(ProviderListTestTags.NoCustomProvider).assertIsDisplayed()
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
