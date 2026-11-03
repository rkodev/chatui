package dev.chatui.feature.conversations

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Conversation
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val zone = ZoneOffset.UTC

    @Test
    fun `renders date groups and conversation rows`() {
        val state = ConversationListUiState(
            groups = listOf(
                ConversationGroup(
                    key = ConversationGroupKey.TODAY,
                    label = "Today",
                    conversations = listOf(
                        testConversation(
                            id = "conversation-1",
                            title = "First chat",
                            lastMessageAt = Instant.parse("2026-11-03T08:00:00Z"),
                        ),
                    ),
                ),
                ConversationGroup(
                    key = ConversationGroupKey.YESTERDAY,
                    label = "Yesterday",
                    conversations = listOf(
                        testConversation(
                            id = "conversation-2",
                            title = "Second chat",
                            lastMessageAt = Instant.parse("2026-11-02T08:00:00Z"),
                        ),
                    ),
                ),
            ),
            loading = false,
        )

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = state,
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
                    onDeleteRequested = {},
                    onDeleteDismissed = {},
                    onDeleteConfirmed = {},
                    onRetry = {},
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.Screen).assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.NewChat).assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.group(ConversationGroupKey.TODAY))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.group(ConversationGroupKey.YESTERDAY))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.item("conversation-1"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("First chat").assertIsDisplayed()
        composeRule.onNodeWithText("Second chat").assertIsDisplayed()
    }

    @Test
    fun `new chat button invokes the intent`() {
        var clicks = 0

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(loading = false),
                    onNewChat = { clicks += 1 },
                    onSelect = {},
                    onRename = { _, _ -> },
                    onDeleteRequested = {},
                    onDeleteDismissed = {},
                    onDeleteConfirmed = {},
                    onRetry = {},
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.NewChat).performClick()

        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `empty state is shown when there are no conversations`() {
        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(loading = false),
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
                    onDeleteRequested = {},
                    onDeleteDismissed = {},
                    onDeleteConfirmed = {},
                    onRetry = {},
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.Empty).assertIsDisplayed()
    }

    @Test
    fun `rename action opens a dialog and saves the new title`() {
        val conversation = testConversation(id = "conversation-1", title = "First chat")
        var renamed: Pair<Conversation, String>? = null

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
                    onRename = { target, title -> renamed = target to title },
                    onDeleteRequested = {},
                    onDeleteDismissed = {},
                    onDeleteConfirmed = {},
                    onRetry = {},
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
        composeRule.onNodeWithTag(ConversationListTestTags.RenameField).performTextClearance()
        composeRule.onNodeWithTag(ConversationListTestTags.RenameField)
            .performTextInput("Renamed chat")
        composeRule.onNodeWithTag(ConversationListTestTags.RenameSave).performClick()

        assertThat(renamed).isEqualTo(conversation to "Renamed chat")
    }

    @Test
    fun `delete action requires confirmation`() {
        val conversation = testConversation(id = "conversation-1", title = "First chat")
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
            ),
        )

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = state,
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
                    onDeleteRequested = { state = state.copy(pendingDelete = it) },
                    onDeleteDismissed = { state = state.copy(pendingDelete = null) },
                    onDeleteConfirmed = {
                        confirmed = true
                        state = state.copy(pendingDelete = null)
                    },
                    onRetry = {},
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.actions("conversation-1"))
            .performClick()
        composeRule.onNodeWithTag(ConversationListTestTags.delete("conversation-1"))
            .performClick()
        composeRule.onNodeWithTag(ConversationListTestTags.DeleteDialog).assertIsDisplayed()
        assertThat(confirmed).isFalse()

        composeRule.onNodeWithTag(ConversationListTestTags.DeleteConfirm).performClick()

        assertThat(confirmed).isTrue()
    }

    @Test
    fun `drawer renders the conversation list`() {
        composeRule.setContent {
            ChatUiTheme {
                val drawerState = rememberDrawerState(DrawerValue.Open)
                ConversationDrawer(
                    drawerState = drawerState,
                    state = ConversationListUiState(loading = false),
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
                    onDeleteRequested = {},
                    onDeleteDismissed = {},
                    onDeleteConfirmed = {},
                    onRetry = {},
                    now = now,
                    zoneId = zone,
                ) {
                    Text("Chat content")
                }
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.Drawer).assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.NewChat).assertIsDisplayed()
    }
}
