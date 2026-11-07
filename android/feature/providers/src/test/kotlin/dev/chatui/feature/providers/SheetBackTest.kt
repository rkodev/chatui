package dev.chatui.feature.providers

import androidx.activity.ComponentDialog
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `back dismisses the conversation settings sheet`() {
        var dismissed = false

        composeRule.setContent {
            ChatUiTheme {
                ConversationSettingsSheet(
                    state = settingsState(),
                    onDismiss = { dismissed = true },
                    onSystemPromptChanged = {},
                    onTemperatureChanged = {},
                    onTopPChanged = {},
                    onMaxOutputTokensChanged = {},
                    onStopChanged = {},
                    onReasoningEffortChanged = {},
                    onStreamingChanged = {},
                    onExtrasChanged = {},
                    onModelSelected = {},
                    onDelete = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(ConversationSettingsTestTags.Sheet).assertIsDisplayed()
        val sheet = showingDialog()

        composeRule.runOnIdle { sheet.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertThat(dismissed).isTrue()
    }

    @Test
    fun `back closes the delete confirmation before the settings sheet`() {
        var dismissed = false
        var deleted = false

        composeRule.setContent {
            ChatUiTheme {
                ConversationSettingsSheet(
                    state = settingsState(),
                    onDismiss = { dismissed = true },
                    onSystemPromptChanged = {},
                    onTemperatureChanged = {},
                    onTopPChanged = {},
                    onMaxOutputTokensChanged = {},
                    onStopChanged = {},
                    onReasoningEffortChanged = {},
                    onStreamingChanged = {},
                    onExtrasChanged = {},
                    onModelSelected = {},
                    onDelete = { deleted = true },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(ConversationSettingsTestTags.Delete)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.ConfirmDelete)
            .assertIsDisplayed()
        val confirm = showingDialog()

        composeRule.runOnIdle { confirm.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ConversationSettingsTestTags.ConfirmDelete)
            .assertDoesNotExist()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Sheet).assertIsDisplayed()
        assertThat(deleted).isFalse()
        assertThat(dismissed).isFalse()

        val sheet = showingDialog()
        composeRule.runOnIdle { sheet.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertThat(dismissed).isTrue()
    }

    @Test
    fun `back dismisses the model picker sheet`() {
        var dismissed = false

        composeRule.setContent {
            ChatUiTheme {
                ModelPickerSheet(
                    state = ModelPickerUiState(
                        target = ModelPickerTarget.Manage("provider-1"),
                        models = listOf(testModel()),
                        providers = listOf(testProvider()),
                        loading = false,
                    ),
                    onDismiss = { dismissed = true },
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

        composeRule.onNodeWithTag(ModelPickerTestTags.Sheet).assertIsDisplayed()
        val sheet = showingDialog()

        composeRule.runOnIdle { sheet.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertThat(dismissed).isTrue()
    }

    private fun settingsState() = ConversationSettingsUiState(
        conversation = testConversation(),
        provider = testProvider(),
        model = testModel(),
        models = listOf(testModel()),
        providers = listOf(testProvider()),
        loading = false,
    )

    private fun showingDialog(): ComponentDialog =
        ShadowDialog.getShownDialogs().last { it.isShowing } as ComponentDialog
}
