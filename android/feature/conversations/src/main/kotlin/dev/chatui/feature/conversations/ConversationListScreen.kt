package dev.chatui.feature.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.EmptyState
import dev.chatui.core.designsystem.ErrorState
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.LocalReducedMotion
import dev.chatui.core.designsystem.LocalSnackbarHostState
import dev.chatui.core.designsystem.PrimaryButton
import dev.chatui.core.designsystem.SegmentedControl
import dev.chatui.core.designsystem.SkeletonList
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.MessageRole
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Test tags exposed by the conversation list and drawer. */
object ConversationListTestTags {
    const val Screen = "conversation-list-screen"
    const val Drawer = "conversation-drawer"
    const val NewChat = "conversation-list-new-chat"
    const val EmptyStartChat = "conversation-list-empty-start-chat"
    const val Loading = "conversation-list-loading"
    const val Empty = "conversation-list-empty"
    const val Error = "conversation-list-error"
    const val RenameDialog = "conversation-rename-dialog"
    const val RenameField = "conversation-rename-field"
    const val RenameSave = "conversation-rename-save"
    const val DeleteDialog = "conversation-delete-dialog"
    const val DeleteConfirm = "conversation-delete-confirm"
    const val SearchField = "conversation-search-field"
    const val SearchClear = "conversation-search-clear"
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    snackbarHostState: SnackbarHostState? = LocalSnackbarHostState.current,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val haptics = rememberHaptics()
    val reducedMotion = LocalReducedMotion.current
    val host = snackbarHostState ?: remember { SnackbarHostState() }
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

    LaunchedEffect(state.pendingUndo?.id) {
        if (state.pendingUndo == null) {
            host.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        val result = host.showSnackbar(
            message = "Conversation deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            onUndoDelete()
        }
    }

    // The snackbar mirrors the ViewModel's buffered-delete window. The delay
    // runs off the composition's test dispatcher so the snackbar is not
    // auto-dismissed by test-clock advancement before it can be asserted.
    LaunchedEffect(state.pendingUndo?.id) {
        if (state.pendingUndo != null) {
            withContext(Dispatchers.Default) {
                delay(ConversationListViewModel.UNDO_WINDOW_MS)
            }
            host.currentSnackbarData?.dismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ConversationListTestTags.Screen),
            contentPadding = PaddingValues(
                horizontal = dimens.screenPadding,
                vertical = dimens.space3,
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SegmentedControl(
                        options = listOf("Chats", "Archived"),
                        selectedIndex = if (archived) 1 else 0,
                        onSelected = { index ->
                            onViewChanged(
                                if (index == 0) {
                                    ConversationListView.ACTIVE
                                } else {
                                    ConversationListView.ARCHIVED
                                },
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(ConversationListTestTags.ArchivedToggle),
                    )
                    Button(
                        onClick = onNewChat,
                        modifier = Modifier.testTag(ConversationListTestTags.NewChat),
                    ) {
                        Text("New chat")
                    }
                }
            }

            if (!archived) {
                item {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchQueryChanged,
                        placeholder = { Text("Search conversations") },
                        singleLine = true,
                        trailingIcon = {
                            AnimatedVisibility(
                                visible = state.searchQuery.isNotEmpty(),
                                enter = if (reducedMotion) {
                                    EnterTransition.None
                                } else {
                                    fadeIn() + expandVertically()
                                },
                                exit = if (reducedMotion) {
                                    ExitTransition.None
                                } else {
                                    fadeOut() + shrinkVertically()
                                },
                            ) {
                                IconButton(
                                    onClick = { onSearchQueryChanged("") },
                                    modifier = Modifier
                                        .testTag(ConversationListTestTags.SearchClear)
                                        .semantics {
                                            contentDescription = "Clear search"
                                        },
                                ) {
                                    Text("×", modifier = Modifier.clearAndSetSemantics {})
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ConversationListTestTags.SearchField),
                    )
                }
            }

            error?.let { message ->
                item {
                    ErrorState(
                        title = "Could not load conversations",
                        body = message,
                        onRetry = onRetry,
                        modifier = Modifier.testTag(ConversationListTestTags.Error),
                    )
                }
            }

            if (!archived && state.searchQuery.isNotBlank()) {
                val searchError = state.searchError

                when {
                    searchError != null -> item {
                        InlineBanner(
                            severity = BannerSeverity.Error,
                            title = "Search failed",
                            body = searchError,
                            modifier = Modifier.testTag(
                                ConversationListTestTags.SearchError,
                            ),
                            action = {
                                TextButton(
                                    onClick = onSearchRetry,
                                    modifier = Modifier.testTag(
                                        ConversationListTestTags.SearchRetry,
                                    ),
                                ) {
                                    Text("Retry")
                                }
                            },
                        )
                    }

                    state.searchLoading -> item {
                        SkeletonList(
                            modifier = Modifier.testTag(
                                ConversationListTestTags.SearchLoading,
                            ),
                            count = 3,
                        )
                    }

                    state.searchResults.isEmpty() -> item {
                        EmptyState(
                            title = "No matches",
                            body = "Try a different search term.",
                            modifier = Modifier.testTag(
                                ConversationListTestTags.SearchEmpty,
                            ),
                        )
                    }

                    else -> items(
                        state.searchResults,
                        key = { "search-${it.conversation.id}" },
                    ) { result ->
                        SearchResultRow(
                            result = result,
                            query = state.searchQuery,
                            onSelect = { onSelect(result.conversation) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            } else {
                when {
                    loading && groups.isEmpty() -> item {
                        SkeletonList(
                            modifier = Modifier.testTag(ConversationListTestTags.Loading),
                            count = 5,
                        )
                    }

                    groups.isEmpty() -> item {
                        ConversationEmptyState(
                            archived = archived,
                            onNewChat = onNewChat,
                        )
                    }

                    else -> groups.forEach { group ->
                        stickyHeader(key = "group-${group.key.name}") {
                            Text(
                                text = group.label.uppercase(),
                                style = Typography.caption,
                                color = colors.fgMuted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.bgGrouped)
                                    .padding(
                                        horizontal = dimens.cardPadding,
                                        vertical = dimens.space2,
                                    )
                                    .testTag(
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
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }

        if (snackbarHostState == null) {
            SnackbarHost(
                hostState = host,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(dimens.padding),
            ) { data ->
                UndoSnackbar(data = data)
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
                    haptics.reject()
                    renameError = "Enter a title."
                } else {
                    haptics.confirm()
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
            onConfirm = {
                haptics.confirm()
                onDeleteConfirmed()
            },
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

@OptIn(ExperimentalMaterial3Api::class)
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
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val haptics = rememberHaptics()
    var menuExpanded by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptics.confirm()
                    onTogglePin()
                    false
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    haptics.confirm()
                    onToggleArchive()
                    false
                }

                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { distance -> distance * SWIPE_THRESHOLD_FRACTION },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ConversationListTestTags.item(conversation.id)),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val background = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> colors.accent
                SwipeToDismissBoxValue.EndToStart -> colors.warning
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            val label = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> "Pin"
                SwipeToDismissBoxValue.EndToStart -> "Archive"
                SwipeToDismissBoxValue.Settled -> ""
            }
            val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(horizontal = dimens.cardPadding),
                contentAlignment = alignment,
            ) {
                if (label.isNotEmpty()) {
                    Text(
                        text = label,
                        style = Typography.subhead,
                        color = colors.fgOnAccent,
                    )
                }
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.rowMinHeight)
                .background(if (active) colors.accentSoft else Color.Transparent)
                .clickable {
                    haptics.tick()
                    onSelect()
                }
                .padding(
                    horizontal = dimens.cardPadding,
                    vertical = dimens.space2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.title,
                        style = Typography.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.fgPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.pinned) {
                        Spacer(Modifier.width(dimens.space1))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(colors.accent, CircleShape)
                                .semantics { contentDescription = "Pinned" },
                        )
                    }
                }
                Text(
                    text = conversationPreview(conversation),
                    style = Typography.footnote,
                    color = colors.fgSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(dimens.space2))
            Text(
                text = formatConversationTimestamp(
                    instant = conversation.lastMessageAt,
                    now = now,
                    zoneId = zoneId,
                ),
                style = Typography.caption,
                color = colors.fgMuted,
            )
            Spacer(Modifier.width(dimens.space1))
            Text(
                text = "›",
                style = Typography.title3,
                color = colors.fgMuted,
                modifier = Modifier.clearAndSetSemantics {},
            )

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
private fun ConversationEmptyState(
    archived: Boolean,
    onNewChat: () -> Unit,
) {
    EmptyState(
        title = if (archived) "No archived conversations" else "No conversations yet",
        body = if (archived) {
            "Archived conversations appear here."
        } else {
            "Start a chat to see it in your history."
        },
        modifier = Modifier.testTag(ConversationListTestTags.Empty),
        action = if (archived) {
            null
        } else {
            {
                PrimaryButton(
                    text = "Start a chat",
                    onClick = onNewChat,
                    modifier = Modifier.testTag(ConversationListTestTags.EmptyStartChat),
                )
            }
        },
    )
}

@Composable
private fun SearchResultRow(
    result: ConversationSearchResult,
    query: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val highlight = SpanStyle(
        background = colors.accentSoft,
        color = colors.fgPrimary,
        fontWeight = FontWeight.SemiBold,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ConversationListTestTags.searchResult(result.conversation.id))
            .clickable(onClick = onSelect)
            .padding(dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Text(
            text = highlightMatches(result.conversation.title, query, highlight),
            style = Typography.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.fgPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (result.titleMatched) {
            Text(
                text = "Title match",
                style = Typography.caption,
                color = colors.accent,
            )
        }

        result.messageMatches.take(3).forEach { match ->
            Text(
                text = highlightMatches(
                    text = "${roleLabel(match.role)}: ${match.snippet}",
                    query = query,
                    style = highlight,
                ),
                style = Typography.footnote,
                color = colors.fgSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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

@Composable
private fun UndoSnackbar(data: SnackbarData) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val progress = remember { Animatable(1f) }

    LaunchedEffect(data) {
        progress.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = ConversationListViewModel.UNDO_WINDOW_MS.toInt(),
                easing = LinearEasing,
            ),
        )
    }

    Snackbar(
        modifier = Modifier.testTag(ConversationListTestTags.UndoBar),
        action = {
            data.visuals.actionLabel?.let { label ->
                TextButton(
                    onClick = { data.performAction() },
                    modifier = Modifier.testTag(ConversationListTestTags.UndoButton),
                ) {
                    Text(label)
                }
            }
        },
    ) {
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = colors.accent,
        )
        Spacer(Modifier.width(dimens.space2))
        Text(data.visuals.message)
    }
}

private fun conversationPreview(conversation: Conversation): String = when {
    conversation.messageCount == 0 -> "No messages yet"
    conversation.modelLabel.isNotBlank() -> conversation.modelLabel
    else -> "${conversation.messageCount} messages"
}

private fun highlightMatches(
    text: String,
    query: String,
    style: SpanStyle,
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)

    val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
    val ranges = mutableListOf<IntRange>()

    tokens.forEach { token ->
        var index = text.indexOf(token, ignoreCase = true)
        while (index >= 0) {
            ranges += index until index + token.length
            index = text.indexOf(token, index + token.length, ignoreCase = true)
        }
    }

    if (ranges.isEmpty()) return AnnotatedString(text)

    val merged = mutableListOf<IntRange>()
    ranges.sortedBy { it.first }.forEach { range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }

    return buildAnnotatedString {
        var cursor = 0
        merged.forEach { range ->
            if (range.first > cursor) {
                append(text.substring(cursor, range.first))
            }
            withStyle(style) {
                append(text.substring(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
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

/** Fraction of the row width a swipe must cross before its action fires. */
private const val SWIPE_THRESHOLD_FRACTION = 0.4f
