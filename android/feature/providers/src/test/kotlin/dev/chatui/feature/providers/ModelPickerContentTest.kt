package dev.chatui.feature.providers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
    fun `shows search grouping price context modalities and free badge`() {
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
        composeRule.onNodeWithTag(ModelPickerTestTags.group("provider-1")).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-1")).assertIsDisplayed()
        composeRule.onNodeWithText("\$0.15 / M input · \$0.6 / M output").assertIsDisplayed()
        composeRule.onNodeWithText("128K context · text in · text out").assertIsDisplayed()
        composeRule.onNodeWithText("Vision").assertIsDisplayed()

        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.freeBadge("model-2")))
        composeRule.onNodeWithTag(ModelPickerTestTags.freeBadge("model-2")).assertIsDisplayed()
    }

    @Test
    fun `pin and hide actions invoke callbacks`() {
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

        composeRule.onNodeWithTag(ModelPickerTestTags.pin("model-1")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.hide("model-1")).performClick()

        assertThat(pinned).isEqualTo("model-1" to true)
        assertThat(hidden).isEqualTo("model-1" to true)
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
