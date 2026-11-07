package dev.chatui.feature.conversations

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
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
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
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
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.Empty).assertIsDisplayed()
    }

    @Test
    fun `conversation actions button has an accessible name and 48 dp target`() {
        val conversation = testConversation(id = "conversation-1", title = "First chat")

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
                    onRename = { _, _ -> },
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

        val actions = composeRule.onNodeWithTag(
            ConversationListTestTags.actions("conversation-1"),
        )
        actions.assertContentDescriptionEquals("Conversation actions")
        val bounds = actions.getUnclippedBoundsInRoot()
        assertThat((bounds.bottom - bounds.top).value).isAtLeast(48f)
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
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
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
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
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

    @Test
    fun `search field forwards query changes`() {
        var query = ""

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
                    onSearchQueryChanged = { query = it },
                    onSearchRetry = {},
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.SearchField)
            .performTextInput("kotlin")

        assertThat(query).isEqualTo("kotlin")
    }

    @Test
    fun `search loading state is shown`() {
        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(
                        loading = false,
                        searchQuery = "kotlin",
                        searchLoading = true,
                    ),
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
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

        composeRule.onNodeWithTag(ConversationListTestTags.SearchLoading).assertIsDisplayed()
    }

    @Test
    fun `search empty state is shown`() {
        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(
                        loading = false,
                        searchQuery = "kotlin",
                    ),
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
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

        composeRule.onNodeWithTag(ConversationListTestTags.SearchEmpty).assertIsDisplayed()
    }

    @Test
    fun `search error state is shown and retry invokes the intent`() {
        var retried = false

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(
                        loading = false,
                        searchQuery = "kotlin",
                        searchError = "search failed",
                    ),
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
                    onDeleteRequested = {},
                    onDeleteDismissed = {},
                    onDeleteConfirmed = {},
                    onRetry = {},
                    onSearchQueryChanged = {},
                    onSearchRetry = { retried = true },
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.SearchError).assertIsDisplayed()
        composeRule.onNodeWithText("search failed").assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.SearchRetry).performClick()

        assertThat(retried).isTrue()
    }

    @Test
    fun `search results are grouped and show title and message context`() {
        val conversation = testConversation(id = "conversation-1", title = "Kotlin notes")
        val result = testSearchResult(
            conversation = conversation,
            titleMatched = true,
            messageMatches = listOf(
                testSearchMessageMatch(
                    messageId = "message-1",
                    snippet = "I love Kotlin",
                ),
            ),
        )

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(
                        loading = false,
                        searchQuery = "kotlin",
                        searchResults = listOf(result),
                    ),
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
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

        composeRule.onNodeWithTag(
            ConversationListTestTags.searchResult("conversation-1"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Kotlin notes").assertIsDisplayed()
        composeRule.onNodeWithText("Title match").assertIsDisplayed()
        composeRule.onNodeWithTag(
            ConversationListTestTags.searchResultMessage("conversation-1", "message-1"),
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText("You: I love Kotlin", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `selecting a search result opens the conversation`() {
        val conversation = testConversation(id = "conversation-1", title = "Kotlin notes")
        var selected: Conversation? = null

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(
                        loading = false,
                        searchQuery = "kotlin",
                        searchResults = listOf(testSearchResult(conversation = conversation)),
                    ),
                    onNewChat = {},
                    onSelect = { selected = it },
                    onRename = { _, _ -> },
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

        composeRule.onNodeWithTag(
            ConversationListTestTags.searchResult("conversation-1"),
        ).performClick()

        assertThat(selected).isEqualTo(conversation)
    }

    @Test
    fun `archive toggle switches the view`() {
        var view: ConversationListView? = null

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
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
                    onViewChanged = { view = it },
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.ArchivedToggle).performClick()

        assertThat(view).isEqualTo(ConversationListView.ARCHIVED)
    }

    @Test
    fun `archive view renders archived conversations and its empty state`() {
        val archived = testConversation(
            id = "archived-1",
            title = "Archived chat",
            archived = true,
        )

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(
                        view = ConversationListView.ARCHIVED,
                        archivedGroups = listOf(
                            ConversationGroup(
                                key = ConversationGroupKey.TODAY,
                                label = "Today",
                                conversations = listOf(archived),
                            ),
                        ),
                        loading = false,
                    ),
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
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

        composeRule.onNodeWithText("Archived chat").assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.group(ConversationGroupKey.TODAY))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.SearchField).assertDoesNotExist()
    }

    @Test
    fun `pin and archive actions invoke their intents`() {
        val conversation = testConversation(id = "conversation-1", title = "First chat")
        var pinned: Conversation? = null
        var archived: Conversation? = null

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
                    onRename = { _, _ -> },
                    onDeleteRequested = {},
                    onDeleteDismissed = {},
                    onDeleteConfirmed = {},
                    onRetry = {},
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
                    onTogglePin = { pinned = it },
                    onToggleArchive = { archived = it },
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.actions("conversation-1"))
            .performClick()
        composeRule.onNodeWithTag(ConversationListTestTags.pin("conversation-1"))
            .performClick()
        assertThat(pinned).isEqualTo(conversation)

        composeRule.onNodeWithTag(ConversationListTestTags.actions("conversation-1"))
            .performClick()
        composeRule.onNodeWithTag(ConversationListTestTags.archive("conversation-1"))
            .performClick()
        assertThat(archived).isEqualTo(conversation)
    }

    @Test
    fun `undo bar restores a buffered delete`() {
        val conversation = testConversation(id = "conversation-1", title = "First chat")
        var undone = false

        composeRule.setContent {
            ChatUiTheme {
                ConversationListScreen(
                    state = ConversationListUiState(
                        loading = false,
                        pendingUndo = conversation,
                    ),
                    onNewChat = {},
                    onSelect = {},
                    onRename = { _, _ -> },
                    onDeleteRequested = {},
                    onDeleteDismissed = {},
                    onDeleteConfirmed = {},
                    onRetry = {},
                    onSearchQueryChanged = {},
                    onSearchRetry = {},
                    onUndoDelete = { undone = true },
                    now = now,
                    zoneId = zone,
                )
            }
        }

        composeRule.onNodeWithTag(ConversationListTestTags.UndoBar).assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.UndoButton).performClick()

        assertThat(undone).isTrue()
    }
}
