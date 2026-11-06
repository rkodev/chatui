package dev.chatui.feature.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.MessageRole
import java.time.Instant
import java.time.ZoneId

/** Test tags exposed by the conversation list and drawer. */
object ConversationListTestTags {
    const val Screen = "conversation-list-screen"
    const val Drawer = "conversation-drawer"
    const val NewChat = "conversation-list-new-chat"
    const val Loading = "conversation-list-loading"
    const val Empty = "conversation-list-empty"
    const val Error = "conversation-list-error"
    const val RenameDialog = "conversation-rename-dialog"
    const val RenameField = "conversation-rename-field"
    const val RenameSave = "conversation-rename-save"
    const val DeleteDialog = "conversation-delete-dialog"
    const val DeleteConfirm = "conversation-delete-confirm"
    const val SearchField = "conversation-search-field"
    const val SearchLoading = "conversation-search-loading"
    const val SearchEmpty = "conversation-search-empty"
    const val SearchError = "conversation-search-error"
    const val SearchRetry = "conversation-search-retry"
    const val ArchivedToggle = "conversation-archived-toggle"
    const val UndoBar = "conversation-undo-bar"
    const val UndoButton = "conversation-undo-button"

    fun group(key: ConversationGroupKey): String =
        "conversation-group-${key.name.lowercase()}"

    fun item(id: String): String = "conversation-item-$id"

    fun actions(id: String): String = "conversation-actions-$id"

    fun pin(id: String): String = "conversation-pin-$id"

    fun archive(id: String): String = "conversation-archive-$id"

    fun rename(id: String): String = "conversation-rename-$id"

    fun delete(id: String): String = "conversation-delete-$id"

    fun searchResult(id: String): String = "conversation-search-result-$id"

    fun searchResultMessage(id: String, messageId: String): String =
        "conversation-search-message-$id-$messageId"
}

/**
 * Conversation history list.
 *
 * The list renders the required date groups, a New chat action, and per-item
 * rename/delete actions. Delete always opens a confirmation dialog. The
 * composable is presentational; storage and navigation live in the ViewModel
 * and route.
 */
@Composable
fun ConversationListScreen(
    state: ConversationListUiState,
    onNewChat: () -> Unit,
    onSelect: (Conversation) -> Unit,
    onRename: (Conversation, String) -> Unit,
    onDeleteRequested: (Conversation) -> Unit,
    onDeleteDismissed: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onRetry: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchRetry: () -> Unit,
    onTogglePin: (Conversation) -> Unit = {},
    onToggleArchive: (Conversation) -> Unit = {},
    onViewChanged: (ConversationListView) -> Unit = {},
    onUndoDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val dimens = LocalDimens.current
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    // Dialogs are separate windows and normally consume back themselves. These
    // handlers are fallbacks that share the same close functions as the
    // dialogs' onDismissRequest callbacks.
    BackHandler(enabled = renameTarget != null) { renameTarget = null }
    BackHandler(enabled = state.pendingDelete != null) { onDeleteDismissed() }

    val archived = state.view == ConversationListView.ARCHIVED
    val groups = if (archived) state.archivedGroups else state.groups
    val loading = if (archived) state.archivedLoading else state.loading
    val error = if (archived) state.archivedError else state.error
    val emptyText = if (archived) "No archived conversations." else "No conversations yet."
    val loadingText = if (archived) {
        "Loading archived conversations…"
    } else {
        "Loading conversations…"
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(ConversationListTestTags.Screen),
        contentPadding = PaddingValues(dimens.padding),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (archived) "Archived" else "Chats",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onViewChanged(
                            if (archived) {
                                ConversationListView.ACTIVE
                            } else {
                                ConversationListView.ARCHIVED
                            },
                        )
                    },
                    modifier = Modifier.testTag(ConversationListTestTags.ArchivedToggle),
                ) {
                    Text(if (archived) "Chats" else "Archived")
                }
                Button(
                    onClick = onNewChat,
                    modifier = Modifier.testTag(ConversationListTestTags.NewChat),
                ) {
                    Text("New chat")
                }
            }
        }

        state.pendingUndo?.let { target ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ConversationListTestTags.UndoBar),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "“${target.title}” deleted",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onUndoDelete,
                        modifier = Modifier.testTag(ConversationListTestTags.UndoButton),
                    ) {
                        Text("Undo")
                    }
                }
            }
        }

        if (!archived) {
            item {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChanged,
                    label = { Text("Search conversations") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ConversationListTestTags.SearchField),
                )
            }
        }

        error?.let { message ->
            item {
                Column(
                    modifier = Modifier.testTag(ConversationListTestTags.Error),
                    verticalArrangement = Arrangement.spacedBy(dimens.space1),
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }

        if (!archived && state.searchQuery.isNotBlank()) {
            val searchError = state.searchError

            when {
                searchError != null -> item {
                    Column(
                        modifier = Modifier.testTag(ConversationListTestTags.SearchError),
                        verticalArrangement = Arrangement.spacedBy(dimens.space1),
                    ) {
                        Text(
                            text = searchError,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = onSearchRetry,
                            modifier = Modifier.testTag(
                                ConversationListTestTags.SearchRetry,
                            ),
                        ) {
                            Text("Retry")
                        }
                    }
                }

                state.searchLoading -> item {
                    Text(
                        text = "Searching…",
                        modifier = Modifier.testTag(ConversationListTestTags.SearchLoading),
                    )
                }

                state.searchResults.isEmpty() -> item {
                    Text(
                        text = "No matches.",
                        modifier = Modifier.testTag(ConversationListTestTags.SearchEmpty),
                    )
                }

                else -> items(
                    state.searchResults,
                    key = { "search-${it.conversation.id}" },
                ) { result ->
                    SearchResultRow(
                        result = result,
                        onSelect = { onSelect(result.conversation) },
                    )
                }
            }
        } else {
            when {
                loading && groups.isEmpty() -> item {
                    Text(
                        text = loadingText,
                        modifier = Modifier.testTag(ConversationListTestTags.Loading),
                    )
                }

                groups.isEmpty() -> item {
                    Text(
                        text = emptyText,
                        modifier = Modifier.testTag(ConversationListTestTags.Empty),
                    )
                }

                else -> groups.forEach { group ->
                    item(key = "group-${group.key.name}") {
                        Text(
                            text = group.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(
                                ConversationListTestTags.group(group.key),
                            ),
                        )
                    }

                    items(group.conversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            active = conversation.id == state.activeConversationId,
                            now = now,
                            zoneId = zoneId,
                            onSelect = { onSelect(conversation) },
                            onTogglePin = { onTogglePin(conversation) },
                            onToggleArchive = { onToggleArchive(conversation) },
                            onRename = {
                                renameTarget = conversation
                                renameValue = conversation.title
                                renameError = null
                            },
                            onDelete = { onDeleteRequested(conversation) },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            value = renameValue,
            error = renameError,
            onValueChange = {
                renameValue = it
                renameError = null
            },
            onDismiss = { renameTarget = null },
            onSave = {
                val trimmed = renameValue.trim()
                if (trimmed.isEmpty()) {
                    renameError = "Enter a title."
                } else {
                    onRename(target, trimmed)
                    renameTarget = null
                }
            },
        )
    }

    state.pendingDelete?.let { target ->
        DeleteDialog(
            conversation = target,
            onDismiss = onDeleteDismissed,
            onConfirm = onDeleteConfirmed,
        )
    }
}

/**
 * Modal drawer wrapper for the conversation list.
 *
 * The chat screen uses this in M1-09; the Conversations destination renders
 * [ConversationListScreen] directly.
 */
@Composable
fun ConversationDrawer(
    drawerState: DrawerState,
    state: ConversationListUiState,
    onNewChat: () -> Unit,
    onSelect: (Conversation) -> Unit,
    onRename: (Conversation, String) -> Unit,
    onDeleteRequested: (Conversation) -> Unit,
    onDeleteDismissed: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onRetry: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchRetry: () -> Unit,
    onTogglePin: (Conversation) -> Unit = {},
    onToggleArchive: (Conversation) -> Unit = {},
    onViewChanged: (ConversationListView) -> Unit = {},
    onUndoDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.testTag(ConversationListTestTags.Drawer),
            ) {
                ConversationListScreen(
                    state = state,
                    onNewChat = onNewChat,
                    onSelect = onSelect,
                    onRename = onRename,
                    onDeleteRequested = onDeleteRequested,
                    onDeleteDismissed = onDeleteDismissed,
                    onDeleteConfirmed = onDeleteConfirmed,
                    onRetry = onRetry,
                    onSearchQueryChanged = onSearchQueryChanged,
                    onSearchRetry = onSearchRetry,
                    onTogglePin = onTogglePin,
                    onToggleArchive = onToggleArchive,
                    onViewChanged = onViewChanged,
                    onUndoDelete = onUndoDelete,
                    now = now,
                    zoneId = zoneId,
                )
            }
        },
        content = content,
    )
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    active: Boolean,
    now: Instant,
    zoneId: ZoneId,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val dimens = LocalDimens.current
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ConversationListTestTags.item(conversation.id)),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {}
                    .clickable(onClick = onSelect),
            ) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    text = formatConversationTimestamp(
                        instant = conversation.lastMessageAt,
                        now = now,
                        zoneId = zoneId,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(dimens.touchTargetMin)
                        .testTag(
                            ConversationListTestTags.actions(conversation.id),
                        )
                        .semantics {
                            contentDescription = "Conversation actions"
                        },
                ) {
                    Text("⋯", modifier = Modifier.clearAndSetSemantics {})
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (conversation.pinned) "Unpin" else "Pin") },
                        onClick = {
                            menuExpanded = false
                            onTogglePin()
                        },
                        modifier = Modifier.testTag(
                            ConversationListTestTags.pin(conversation.id),
                        ),
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (conversation.archived) "Unarchive" else "Archive")
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleArchive()
                        },
                        modifier = Modifier.testTag(
                            ConversationListTestTags.archive(conversation.id),
                        ),
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        modifier = Modifier.testTag(
                            ConversationListTestTags.rename(conversation.id),
                        ),
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        modifier = Modifier.testTag(
                            ConversationListTestTags.delete(conversation.id),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: ConversationSearchResult,
    onSelect: () -> Unit,
) {
    val dimens = LocalDimens.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ConversationListTestTags.searchResult(result.conversation.id)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .clickable(onClick = onSelect)
                .padding(dimens.space3),
            verticalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            Text(
                text = result.conversation.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )

            if (result.titleMatched) {
                Text(
                    text = "Title match",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            result.messageMatches.take(3).forEach { match ->
                Text(
                    text = "${roleLabel(match.role)}: ${match.snippet}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.testTag(
                        ConversationListTestTags.searchResultMessage(
                            result.conversation.id,
                            match.messageId,
                        ),
                    ),
                )
            }
        }
    }
}

private fun roleLabel(role: MessageRole): String = when (role) {
    MessageRole.USER -> "You"
    MessageRole.ASSISTANT -> "Assistant"
    MessageRole.SYSTEM -> "System"
    MessageRole.TOOL -> "Tool"
}

@Composable
private fun RenameDialog(
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ConversationListTestTags.RenameDialog),
        title = { Text("Rename conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("Title") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.testTag(ConversationListTestTags.RenameField),
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = value.trim().isNotEmpty(),
                modifier = Modifier.testTag(ConversationListTestTags.RenameSave),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeleteDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ConversationListTestTags.DeleteDialog),
        title = { Text("Delete conversation?") },
        text = {
            Text("“${conversation.title}” and its messages will be deleted.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(ConversationListTestTags.DeleteConfirm),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
