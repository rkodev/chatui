package dev.chatui.feature.providers

import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
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
import dev.chatui.core.model.ProviderAuthType
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
class ProviderListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale

    @After
    fun restoreFontScale() {
        RuntimeEnvironment.setFontScale(originalFontScale)
    }

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
    fun `shows configured provider status and reported key summary without claiming a connection`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Configured · Key stored").assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.provider("provider-1"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Work key · Usage \$1.5").assertIsDisplayed()
        composeRule.onAllNodesWithText("Manage").assertCountEquals(0)
    }

    @Test
    fun `status distinguishes missing required keys no-auth providers and disabled profiles`() {
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
                            testProvider(
                                id = "local",
                                label = "Local",
                                secretId = null,
                            ).copy(authType = ProviderAuthType.NONE),
                            testProvider(id = "disabled", enabled = false),
                        ),
                    ),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Configured · Key stored").assertIsDisplayed()
        composeRule.onNodeWithText("Needs key").assertIsDisplayed()
        composeRule.onNodeWithText("Configured · No key required").assertIsDisplayed()
        composeRule.onNodeWithText("Off · Configured · Key stored").assertIsDisplayed()
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
    fun `preset choice opens a focused form without asking for the same preset twice`() {
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
        composeRule.onNodeWithTag(ProviderListTestTags.ChangePreset)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("anthropic"))
            .assertDoesNotExist()
        composeRule.onNodeWithTag(ProviderListTestTags.ChangePreset).performClick()
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("google"))
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `preset choices meet the 48 dp touch target`() {
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
        val preset = composeRule.onNodeWithTag(ProviderListTestTags.providerOption("openai"))
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
        composeRule.onNodeWithTag(ProviderListTestTags.PresetCreate).performScrollTo().performClick()

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
        composeRule.onNodeWithTag(ProviderListTestTags.CustomCreate).performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithTag(ProviderListTestTags.CustomCreate).performScrollTo().performClick()

        assertThat(created).isTrue()
    }

    @Test
    fun `shows recoverable custom form errors without discarding entered values`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state().copy(
                        customForm = CustomProviderFormState(
                            baseUrl = "https://example.com/v1",
                            apiKey = "fake-key",
                            error = "Try again.",
                        ),
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
        composeRule.onNodeWithText("Try again.").assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.CustomBaseUrl)
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

    @Test
    fun `shell owns the title and cancelling add restores the single add entry`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state(),
                    onOpenProvider = {},
                    onRetry = {},
                    showTitle = false,
                )
            }
        }
        composeRule.onAllNodesWithText("Providers").assertCountEquals(0)
        composeRule.onAllNodesWithText("Add provider").assertCountEquals(1)
        openAddSheet()
        composeRule.onNodeWithTag(ProviderListTestTags.CancelAdd).performScrollTo().performClick()
        composeRule.onNodeWithTag(ProviderListTestTags.AddSheet).assertDoesNotExist()
        composeRule.onNodeWithTag(ProviderListTestTags.AddProvider).assertIsFocused()
    }

    @Test
    fun `cached providers remain usable when refresh fails`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListScreen(
                    state = state().copy(error = "Offline"),
                    onOpenProvider = {},
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithTag(ProviderListTestTags.Error).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.provider("provider-1")).assertIsDisplayed()
    }

    @Test
    fun `preset and custom credential editors protect their windows and show only explicitly revealed drafts`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ProviderListScreen(
                    state = state().copy(
                        presetForm = PresetProviderFormState(presetId = "openai", apiKey = "fake-preset-key"),
                        customForm = CustomProviderFormState(
                            baseUrl = "https://provider.example.test", apiKey = "fake-custom-key",
                        ),
                    ),
                    onOpenProvider = {}, onRetry = {},
                )
            }
        }
        openAddSheet()
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("openai")).performClick()
        assertEditorProtection(ProviderListTestTags.PresetApiKey, ProviderListTestTags.PresetShowKey, "fake-preset-key")
        composeRule.onNodeWithTag(ProviderListTestTags.CancelAdd).performScrollTo().performClick()
        openCustomForm()
        assertEditorProtection(ProviderListTestTags.CustomApiKey, ProviderListTestTags.CustomShowKey, "fake-custom-key")
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `large font custom fields headers warning and submit remain reachable on a short screen`() {
        RuntimeEnvironment.setFontScale(2f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChatUiTheme(reducedMotion = true) {
                    ProviderListScreen(
                        state = state().copy(customForm = CustomProviderFormState(
                            baseUrl = "http://10.0.2.2:11434/v1",
                            cleartextWarning = dev.chatui.core.providers.CLEARTEXT_LOCAL_WARNING,
                            headers = listOf(CustomHeaderField("X-Client", "example")),
                        )),
                        onOpenProvider = {}, onRetry = {},
                    )
                }
            }
        }
        openCustomForm()
        composeRule.onNodeWithTag(ProviderListTestTags.CustomCleartextWarning)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.CustomOptional).performScrollTo().assertIsDisplayed()
        listOf(
            ProviderListTestTags.CustomLabel,
            ProviderListTestTags.customHeaderName(0),
            ProviderListTestTags.customHeaderValue(0),
            ProviderListTestTags.customHeaderRemove(0),
            ProviderListTestTags.CustomCreate,
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertThat(bounds.left).isAtLeast(0f)
            assertThat(bounds.right).isAtMost(320f)
        }
    }

    private fun assertEditorProtection(fieldTag: String, toggleTag: String, fakeKey: String) {
        composeRule.onNodeWithTag(fieldTag).performScrollTo()
        val window = ShadowDialog.getShownDialogs().last { it.isShowing }.window!!
        assertThat(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)
        fun renderedText(): String {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithTag(fieldTag)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.text.text
        }
        assertThat(renderedText()).doesNotContain(fakeKey)
        composeRule.onNodeWithTag(toggleTag).performClick()
        assertThat(renderedText()).isEqualTo(fakeKey)
        composeRule.onNodeWithTag(toggleTag).performClick()
        assertThat(renderedText()).doesNotContain(fakeKey)
        assertThat(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)
    }
}
