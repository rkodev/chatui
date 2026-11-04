package dev.chatui.feature.providers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.chatui.core.designsystem.ChatUiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageModelSnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the provider and model snapshot`() {
        composeRule.setContent {
            ChatUiTheme {
                MessageModelSnapshot(
                    message = testMessage(
                        providerId = "provider-1",
                        modelId = "model-1",
                        modelLabel = "Model One",
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(MessageModelSnapshotTestTags.Snapshot).assertIsDisplayed()
        composeRule.onNodeWithTag(MessageModelSnapshotTestTags.Model)
            .assertTextContains("Model: Model One")
        composeRule.onNodeWithTag(MessageModelSnapshotTestTags.Provider)
            .assertTextContains("Provider: provider-1")
    }

    @Test
    fun `renders nothing without a snapshot`() {
        composeRule.setContent {
            ChatUiTheme {
                MessageModelSnapshot(
                    message = testMessage(providerId = null, modelId = null, modelLabel = null),
                )
            }
        }

        composeRule.onNodeWithTag(MessageModelSnapshotTestTags.Snapshot).assertDoesNotExist()
    }
}
