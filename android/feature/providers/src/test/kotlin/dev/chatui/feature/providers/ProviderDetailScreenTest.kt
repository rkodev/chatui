package dev.chatui.feature.providers

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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
class ProviderDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun providerWithMetadata() = testProvider(
        metadata = buildJsonObject {
            put(
                "key",
                buildJsonObject {
                    put("label", "Work key")
                    put("usageUsd", 1.5)
                    put("limitUsd", 10.0)
                    put("isFreeTier", false)
                    put(
                        "rateLimit",
                        buildJsonObject {
                            put("requests", 20)
                            put("interval", "1m")
                        },
                    )
                },
            )
        },
    )

    @Test
    fun `shows key metadata and CORS without the secret`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailScreen(
                    state = ProviderDetailUiState(
                        provider = providerWithMetadata(),
                        modelCount = 3,
                        docsUrl = "https://openrouter.ai/docs",
                        loading = false,
                    ),
                    onNewKeyChanged = {},
                    onRotateKey = {},
                    onRemoveKey = {},
                    onCancelRemove = {},
                    onToggleEnabled = {},
                    onValidate = {},
                    onRefreshModels = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderDetailTestTags.KeyLabel)
            .assertTextContains("Key label: Work key")
        composeRule.onNodeWithTag(ProviderDetailTestTags.Usage)
            .assertTextContains("Usage: \$1.5")
        composeRule.onNodeWithTag(ProviderDetailTestTags.Limit)
            .assertTextContains("Limit: \$10")
        composeRule.onNodeWithTag(ProviderDetailTestTags.FreeTier)
            .assertTextContains("Free tier: No")
        composeRule.onNodeWithTag(ProviderDetailTestTags.RateLimit)
            .assertTextContains("Rate limit: 20 requests per 1m")
        composeRule.onNodeWithTag(ProviderDetailTestTags.Cors)
            .assertTextContains("CORS: Supported")
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.DocsLink))
        composeRule.onNodeWithTag(ProviderDetailTestTags.DocsLink).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.ModelCount))
        composeRule.onNodeWithTag(ProviderDetailTestTags.ModelCount)
            .assertTextContains("3 models are cached for this provider.")
    }

    @Test
    fun `provider detail sets flag secure`() {
        lateinit var window: Window

        composeRule.setContent {
            window = LocalView.current.context.findActivity().window
            ChatUiTheme {
                ProviderDetailScreen(
                    state = ProviderDetailUiState(
                        provider = providerWithMetadata(),
                        loading = false,
                    ),
                    onNewKeyChanged = {},
                    onRotateKey = {},
                    onRemoveKey = {},
                    onCancelRemove = {},
                    onToggleEnabled = {},
                    onValidate = {},
                    onRefreshModels = {},
                    onRetry = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertThat(window.isSecure()).isTrue()
    }

    @Test
    fun `shows preset, base URL, and auth for a custom provider`() {
        val custom = testProvider(
            label = "My server",
            presetId = "custom",
        ).copy(baseUrl = "https://api.example.com/v1")

        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailScreen(
                    state = ProviderDetailUiState(
                        provider = custom,
                        loading = false,
                    ),
                    onNewKeyChanged = {},
                    onRotateKey = {},
                    onRemoveKey = {},
                    onCancelRemove = {},
                    onToggleEnabled = {},
                    onValidate = {},
                    onRefreshModels = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderDetailTestTags.Preset)
            .assertTextContains("Preset: custom")
        composeRule.onNodeWithTag(ProviderDetailTestTags.BaseUrl)
            .assertTextContains("Base URL: https://api.example.com/v1")
        composeRule.onNodeWithTag(ProviderDetailTestTags.AuthType)
            .assertTextContains("Auth: bearer")
    }

    @Test
    fun `shows the local cleartext warning for a custom provider`() {
        val custom = testProvider(
            label = "My server",
            presetId = "custom",
        ).copy(baseUrl = "http://10.0.2.2:11434/v1")

        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailScreen(
                    state = ProviderDetailUiState(
                        provider = custom,
                        loading = false,
                        cleartextWarning = dev.chatui.core.providers.CLEARTEXT_LOCAL_WARNING,
                    ),
                    onNewKeyChanged = {},
                    onRotateKey = {},
                    onRemoveKey = {},
                    onCancelRemove = {},
                    onToggleEnabled = {},
                    onValidate = {},
                    onRefreshModels = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.CleartextWarning))
        composeRule.onNodeWithTag(ProviderDetailTestTags.CleartextWarning)
            .assertIsDisplayed()
        composeRule.onNodeWithText(dev.chatui.core.providers.CLEARTEXT_LOCAL_WARNING)
            .assertIsDisplayed()
    }

    @Test
    fun `rotate and remove actions invoke callbacks`() {
        var rotateClicks = 0
        var removeClicks = 0

        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailScreen(
                    state = ProviderDetailUiState(
                        provider = providerWithMetadata(),
                        loading = false,
                        newKey = "sk-new",
                    ),
                    onNewKeyChanged = {},
                    onRotateKey = { rotateClicks += 1 },
                    onRemoveKey = { removeClicks += 1 },
                    onCancelRemove = {},
                    onToggleEnabled = {},
                    onValidate = {},
                    onRefreshModels = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.RotateButton))
        composeRule.onNodeWithTag(ProviderDetailTestTags.RotateButton).performClick()
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.RemoveButton))
        composeRule.onNodeWithTag(ProviderDetailTestTags.RemoveButton).performClick()

        assertThat(rotateClicks).isEqualTo(1)
        assertThat(removeClicks).isEqualTo(1)
    }

    @Test
    fun `validate is disabled without a stored key`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailScreen(
                    state = ProviderDetailUiState(
                        provider = testProvider(secretId = null),
                        loading = false,
                    ),
                    onNewKeyChanged = {},
                    onRotateKey = {},
                    onRemoveKey = {},
                    onCancelRemove = {},
                    onToggleEnabled = {},
                    onValidate = {},
                    onRefreshModels = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.ValidateButton))
        composeRule.onNodeWithTag(ProviderDetailTestTags.ValidateButton).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.RemoveButton))
        composeRule.onNodeWithTag(ProviderDetailTestTags.RemoveButton).assertIsNotEnabled()
    }

    @Test
    fun `shows an error and retry action`() {
        var retries = 0

        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailScreen(
                    state = ProviderDetailUiState(
                        loading = false,
                        error = "Provider not found.",
                    ),
                    onNewKeyChanged = {},
                    onRotateKey = {},
                    onRemoveKey = {},
                    onCancelRemove = {},
                    onToggleEnabled = {},
                    onValidate = {},
                    onRefreshModels = {},
                    onRetry = { retries += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(ProviderDetailTestTags.Error).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertThat(retries).isEqualTo(1)
    }
}

private fun Window.isSecure(): Boolean =
    attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No activity in context chain")
}
