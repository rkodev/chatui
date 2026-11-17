package dev.chatui.feature.providers

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale

    @After
    fun restoreFontScale() {
        RuntimeEnvironment.setFontScale(originalFontScale)
    }

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

    private fun openConnectionDetails() {
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.ConnectionDetails))
        composeRule.onNodeWithTag(ProviderDetailTestTags.ConnectionDetails).performClick()
    }

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
        composeRule.onNodeWithTag(ProviderDetailTestTags.Cors).assertDoesNotExist()
        openConnectionDetails()
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.RateLimit))
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

        openConnectionDetails()
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.Preset))
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
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.ChangeKey))
        composeRule.onNodeWithTag(ProviderDetailTestTags.NewKeyField).assertDoesNotExist()
        composeRule.onNodeWithTag(ProviderDetailTestTags.ChangeKey).performClick()
        composeRule.onNodeWithTag(ProviderDetailTestTags.RotateButton).performClick()
        composeRule.onNodeWithTag(ProviderDetailTestTags.CancelKeyEditor).performClick()
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
    fun `documentation action passes the contract url to the launcher`() {
        val opened = mutableListOf<String>()
        val launcher = DocumentationLauncher { _, url ->
            opened += url
            DocumentationOpenResult.OpenedInCustomTab
        }

        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailScreen(
                    state = ProviderDetailUiState(
                        provider = providerWithMetadata(),
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
                    documentationLauncher = launcher,
                )
            }
        }

        openConnectionDetails()
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.DocsLink))
        composeRule.onNodeWithTag(ProviderDetailTestTags.DocsLink).performClick()

        assertThat(opened).containsExactly("https://openrouter.ai/docs")
    }

    @Test
    fun `documentation failure shows a safe message`() {
        val launcher = DocumentationLauncher { _, _ ->
            DocumentationOpenResult.Failed("No browser is available to open this link.")
        }

        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailScreen(
                    state = ProviderDetailUiState(
                        provider = providerWithMetadata(),
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
                    documentationLauncher = launcher,
                )
            }
        }

        openConnectionDetails()
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.DocsLink))
        composeRule.onNodeWithTag(ProviderDetailTestTags.DocsLink).performClick()

        composeRule.onNodeWithTag(ProviderDetailTestTags.DocsError)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("No browser is available to open this link.")
            .assertExists()
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

    @Test
    fun `unknown usage stays unknown and secondary actions run only on request`() {
        var state by mutableStateOf(ProviderDetailUiState(provider = testProvider(), loading = false))
        var managed = 0
        var refreshed = 0
        var validated = 0
        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailScreen(
                    state, onNewKeyChanged = {}, onRotateKey = {}, onRemoveKey = {}, onCancelRemove = {},
                    onToggleEnabled = {}, onValidate = { validated++ }, onRefreshModels = { refreshed++ },
                    onRetry = {}, onOpenModels = { managed++ },
                )
            }
        }
        composeRule.onNodeWithTag(ProviderDetailTestTags.Usage).assertTextContains("Usage: Not reported")
        composeRule.onNodeWithTag(ProviderDetailTestTags.Limit).assertTextContains("Limit: Not reported")
        composeRule.runOnIdle { state = state.copy(notice = "Presentation changed") }
        composeRule.runOnIdle {
            assertThat(managed + refreshed + validated).isEqualTo(0)
        }
        listOf(
            ProviderDetailTestTags.ManageModels,
            ProviderDetailTestTags.RefreshModels,
            ProviderDetailTestTags.ValidateButton,
        ).forEach { tag ->
            composeRule.onNodeWithTag(ProviderDetailTestTags.Screen).performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).performClick()
        }
        assertThat(managed).isEqualTo(1)
        assertThat(refreshed).isEqualTo(1)
        assertThat(validated).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `large font overview diagnostics and secure replacement editor remain reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        var cleared = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChatUiTheme(reducedMotion = true) {
                    ProviderDetailScreen(
                        ProviderDetailUiState(
                            provider = testProvider(label = "A provider with a long descriptive label"),
                            loading = false, newKey = "fake-replacement",
                        ),
                        onNewKeyChanged = { cleared = it.isEmpty() },
                        onRotateKey = {}, onRemoveKey = {}, onCancelRemove = {},
                        onToggleEnabled = {}, onValidate = {}, onRefreshModels = {}, onRetry = {},
                    )
                }
            }
        }
        listOf(ProviderDetailTestTags.EnabledToggle, ProviderDetailTestTags.ManageModels)
            .forEach { tag ->
                composeRule.onNodeWithTag(ProviderDetailTestTags.Screen).performScrollToNode(hasTestTag(tag))
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
                val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertThat(bounds.left).isAtLeast(0f)
                assertThat(bounds.right).isAtMost(320f)
            }
        openConnectionDetails()
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.Cors))
        composeRule.onNodeWithTag(ProviderDetailTestTags.Cors).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen)
            .performScrollToNode(hasTestTag(ProviderDetailTestTags.ChangeKey))
        composeRule.onNodeWithTag(ProviderDetailTestTags.ChangeKey).performClick()
        composeRule.onNodeWithTag(ProviderDetailTestTags.NewKeyField).performScrollTo().assertIsDisplayed()
        val window = ShadowDialog.getShownDialogs().last { it.isShowing }.window!!
        assertThat(window.isSecure()).isTrue()
        fun renderedText(): String {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithTag(ProviderDetailTestTags.NewKeyField)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.text.text
        }
        assertThat(renderedText()).doesNotContain("fake-replacement")
        composeRule.onNodeWithTag(ProviderDetailTestTags.ShowKey).performClick()
        assertThat(renderedText()).isEqualTo("fake-replacement")
        assertThat(window.isSecure()).isTrue()
        composeRule.onNodeWithTag(ProviderDetailTestTags.RotateButton).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderDetailTestTags.CancelKeyEditor).performScrollTo().performClick()
        composeRule.onNodeWithTag(ProviderDetailTestTags.KeyEditor).assertDoesNotExist()
        composeRule.onNodeWithTag(ProviderDetailTestTags.ChangeKey).assertIsFocused()
        assertThat(cleared).isTrue()
    }
}

private fun Window.isSecure(): Boolean =
    attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No activity in context chain")
}
