package dev.chatui.feature.conversations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationListBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val zone = ZoneOffset.UTC

    @Test
    fun `back dismisses the delete dialog without confirming`() {
        val conversation = testConversation(id = "conversation-1", title = "First chat")
        var dismissed = false
        var confirmed = false
        var state by mutableStateOf(
            ConversationListUiState(
                groups = listOf(
                    ConversationGroup(
                        key = ConversationGroupKey.TODAY,
                        label = "Today",
                        conversations = listOf(conversation),
                    ),
                ),
                loading = false,
                pendingDelete = conversation,
            ),
        )

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = state,
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
                    onDeleteRequested = {},
                    onDeleteDismissed = {
                        dismissed = true
                        state = state.copy(pendingDelete = null)
                    },
                    onDeleteConfirmed = { confirmed = true },
                    onRetry = {},
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.DeleteDialog).assertIsDisplayed()
        val dialog = ShadowDialog.getLatestDialog()

        composeRule.runOnIdle { dialog.onBackPressed() }

        composeRule.onNodeWithTag(ConversationListTestTags.DeleteDialog).assertDoesNotExist()
        assertThat(dismissed).isTrue()
        assertThat(confirmed).isFalse()
    }

    @Test
    fun `back dismisses the rename dialog without saving`() {
        val conversation = testConversation(id = "conversation-1", title = "First chat")
        var renamed = false

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(
                        groups = listOf(
                            ConversationGroup(
                                key = ConversationGroupKey.TODAY,
                                label = "Today",
                                conversations = listOf(conversation),
                            ),
                        ),
                        loading = false,
                    ),
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> renamed = true },
                    onDeleteRequested = {},
                    onDeleteDismissed = {},
                    onDeleteConfirmed = {},
                    onRetry = {},
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.actions("conversation-1"))
            .performClick()
        composeRule.onNodeWithTag(ConversationListTestTags.rename("conversation-1"))
            .performClick()
        composeRule.onNodeWithTag(ConversationListTestTags.RenameDialog).assertIsDisplayed()
        val dialog = ShadowDialog.getLatestDialog()

        composeRule.runOnIdle { dialog.onBackPressed() }

        composeRule.onNodeWithTag(ConversationListTestTags.RenameDialog).assertDoesNotExist()
        assertThat(renamed).isFalse()
    }
}
