package dev.chatui.feature.providers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
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
        target = ModelPickerTarget.Manage("provider-1"),
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
        composeRule.onNodeWithText("128K context").assertIsDisplayed()
        composeRule.onNodeWithText("Vision").assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.check("model-1"), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.model("model-2")))
        composeRule.onNodeWithTag(ModelPickerTestTags.freeBadge("model-2"), useUnmergedTree = true)
            .assertIsDisplayed()
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
            .performScrollToNode(hasTestTag(ModelPickerTestTags.AddManual))
        composeRule.onNodeWithTag(ModelPickerTestTags.AddManual).performClick()

        assertThat(addClicks).isEqualTo(1)
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
}
