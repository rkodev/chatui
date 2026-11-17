package dev.chatui.feature.providers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.OutputModality
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelPickerContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun state(
        models: List<dev.chatui.core.model.Model> = listOf(
            testModel(
                id = "model-1",
                label = "Auto",
                remoteId = "openrouter/auto",
                pricing = ModelPricing(inputPerMTok = 0.15, outputPerMTok = 0.6),
                capabilities = listOf(ModelCapability.TEXT, ModelCapability.VISION),
            ),
            testModel(
                id = "model-2",
                label = "Free model",
                remoteId = "author/free:free",
                pricing = ModelPricing(inputPerMTok = 0.0, outputPerMTok = 0.0),
            ),
        ),
        selectedModelId: String? = "model-1",
    ) = ModelPickerUiState(
        target = ModelPickerTarget.Conversation("conversation-1"),
        conversation = testConversation(),
        models = models,
        providers = listOf(testProvider()),
        selectedModelId = selectedModelId,
        loading = false,
    )

    @Test
    fun `shows search grouping price context and free badge`() {
        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.Search).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.Filters).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.group("provider-1")).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-1")).assertIsDisplayed()
        composeRule.onNodeWithText("\$0.15 / M input · \$0.6 / M output").assertIsDisplayed()
        composeRule.onNodeWithText("128K context · Vision").assertIsDisplayed()
        composeRule.onNodeWithText("text in · text out").assertExists()
        composeRule.onNodeWithTag(ModelPickerTestTags.check("model-1"), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.model("model-2")))
        composeRule.onNodeWithTag(ModelPickerTestTags.freeBadge("model-2"), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `filter chips meet the 48 dp touch target`() {
        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = {},
                )
            }
        }

        val bounds = composeRule.onNodeWithTag(ModelPickerTestTags.filter("all"))
            .getUnclippedBoundsInRoot()
        assertThat((bounds.bottom - bounds.top).value).isAtLeast(48f)
    }

    @Test
    fun `pin and hide actions invoke callbacks from the trailing menu`() {
        var pinned: Pair<String, Boolean>? = null
        var hidden: Pair<String, Boolean>? = null

        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { model, value -> pinned = model.id to value },
                    onHide = { model, value -> hidden = model.id to value },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.menu("model-1")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.pin("model-1")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.menu("model-1")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.hide("model-1")).performClick()

        assertThat(pinned).isEqualTo("model-1" to true)
        assertThat(hidden).isEqualTo("model-1" to true)
    }

    @Test
    fun `selecting a model forwards the chosen model`() {
        var selected: String? = null

        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(),
                    onQueryChanged = {},
                    onSelect = { selected = it.id },
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.model("model-2")))
        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-2"))
            .performSemanticsAction(SemanticsActions.OnClick)

        assertThat(selected).isEqualTo("model-2")
    }

    @Test
    fun `free filter hides paid models`() {
        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.filter("free")).performClick()

        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-2")).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-1")).assertDoesNotExist()
    }

    @Test
    fun `pinned filter shows only pinned models`() {
        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(
                        models = listOf(
                            testModel(id = "pinned", label = "Pinned", pinned = true),
                            testModel(id = "regular", label = "Regular"),
                        ),
                        selectedModelId = null,
                    ),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.filter("pinned")).performClick()

        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.model("pinned")))
        composeRule.onNodeWithTag(ModelPickerTestTags.model("pinned")).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("regular")).assertDoesNotExist()
    }

    @Test
    fun `details disclosure expands from the trailing menu`() {
        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.menu("model-1")).performClick()
        composeRule.onNodeWithText("Details").performClick()

        composeRule.onNodeWithTag(ModelPickerTestTags.details("model-1"), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `shows an unavailable warning`() {
        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(
                        models = listOf(
                            testModel(
                                id = "image",
                                outputModalities = listOf(OutputModality.IMAGE),
                            ),
                        ),
                        selectedModelId = "image",
                    ),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.Warning).assertIsDisplayed()
    }

    @Test
    fun `manual add invokes the callback`() {
        var addClicks = 0

        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = { addClicks += 1 },
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.ManualToggle))
        composeRule.onNodeWithTag(ModelPickerTestTags.ManualId).assertDoesNotExist()
        composeRule.onNodeWithTag(ModelPickerTestTags.ManualToggle).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.AddManual))
        composeRule.onNodeWithTag(ModelPickerTestTags.AddManual).performClick()

        assertThat(addClicks).isEqualTo(1)
    }

    @Test
    fun `error state shows retry and invokes refresh`() {
        var refreshed = false

        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state().copy(error = "Catalog unavailable"),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = { refreshed = true },
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.Error).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(refreshed).isTrue()
    }

    @Test
    fun `pull to refresh invokes refresh`() {
        var refreshed = false

        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = { refreshed = true },
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertThat(refreshed).isTrue()
    }

    @Test
    fun `shows an empty state when no model matches`() {
        composeRule.setContent {
            ChatUiTheme {
                ModelPickerContent(
                    state = state(models = emptyList(), selectedModelId = null),
                    onQueryChanged = {},
                    onSelect = {},
                    onPin = { _, _ -> },
                    onHide = { _, _ -> },
                    onManualRemoteIdChanged = {},
                    onManualLabelChanged = {},
                    onAddManual = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(ModelPickerTestTags.Empty).assertIsDisplayed()
    }

    @Test
    fun `search combines with free and pinned filters`() {
        var current by mutableStateOf(
            state(
                models = listOf(
                    testModel(id = "free", remoteId = "author/alpha:free", label = "Alpha free", pinned = true),
                    testModel(id = "paid", remoteId = "author/alpha", label = "Alpha paid"),
                    testModel(id = "other", label = "Other", pinned = true),
                ),
            ),
        )
        composeRule.setContent {
            ChatUiTheme {
                PickerFixture(current, onQueryChanged = { current = current.copy(query = it) })
            }
        }
        composeRule.onNodeWithTag(ModelPickerTestTags.Search).performTextReplacement("alpha")
        composeRule.onNodeWithTag(ModelPickerTestTags.filter("free")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.model("free")))
        composeRule.onNodeWithTag(ModelPickerTestTags.model("free")).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("paid")).assertDoesNotExist()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("other")).assertDoesNotExist()
        composeRule.onNodeWithTag(ModelPickerTestTags.filter("pinned")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("free")).assertExists()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("paid")).assertDoesNotExist()
    }

    @Test
    fun `target label and failure do not masquerade as loading or an empty catalog`() {
        var current by mutableStateOf(
            state(models = emptyList()).copy(target = ModelPickerTarget.Defaults, loading = true),
        )
        var refreshes = 0
        composeRule.setContent {
            ChatUiTheme { PickerFixture(current, onRefresh = { refreshes++ }) }
        }
        composeRule.onNodeWithText("Changes apply to defaults for new chats.").assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.Loading).assertExists()
        composeRule.onNodeWithTag(ModelPickerTestTags.Warning).assertDoesNotExist()
        composeRule.onNodeWithTag(ModelPickerTestTags.Empty).assertDoesNotExist()

        composeRule.runOnIdle { current = current.copy(loading = false, error = "Offline") }
        composeRule.onNodeWithTag(ModelPickerTestTags.Error).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.Loading).assertDoesNotExist()
        composeRule.onNodeWithTag(ModelPickerTestTags.Empty).assertDoesNotExist()
        composeRule.runOnIdle { assertThat(refreshes).isEqualTo(0) }
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun `price and wrapping filters remain reachable at 200 percent with a keyboard inset`() {
        val model = testModel(
            label = "A long model label that must remain readable",
            capabilities = listOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.REASONING),
            pricing = ModelPricing(inputPerMTok = 123.45, outputPerMTok = 678.9, perRequest = 0.02),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChatUiTheme {
                    Box(Modifier.fillMaxSize().padding(bottom = 224.dp)) {
                        PickerFixture(state(models = listOf(model)))
                    }
                }
            }
        }
        composeRule.onNodeWithTag(ModelPickerTestTags.Search).assertIsDisplayed()
        listOf("all", "free", "pinned").forEach {
            val bounds = composeRule.onNodeWithTag(ModelPickerTestTags.filter(it)).getUnclippedBoundsInRoot()
            assertThat(bounds.left.value).isAtLeast(0f)
            assertThat(bounds.right.value).isAtMost(320f)
            assertThat((bounds.bottom - bounds.top).value).isAtLeast(48f)
        }
        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.model(model.id)))
        composeRule.onNodeWithText(formatModelPrice(model), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        val bounds = composeRule.onNodeWithText(formatModelPrice(model), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertThat(bounds.right.value).isAtMost(320f)
    }

    @Composable
    private fun PickerFixture(
        state: ModelPickerUiState,
        onQueryChanged: (String) -> Unit = {},
        onRefresh: () -> Unit = {},
    ) {
        ModelPickerContent(
            state = state,
            onQueryChanged = onQueryChanged,
            onSelect = {},
            onPin = { _, _ -> },
            onHide = { _, _ -> },
            onManualRemoteIdChanged = {},
            onManualLabelChanged = {},
            onAddManual = {},
            onRefresh = onRefresh,
        )
    }
}
