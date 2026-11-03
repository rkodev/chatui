package dev.chatui.feature.conversations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Clock
import java.time.ZoneId

/**
 * Stateful conversation list entry point.
 *
 * The ViewModel is scoped to the current navigation back-stack entry. New-chat
 * and select intents emit one navigation event so the shell can open the chat
 * destination without re-navigating on recomposition.
 */
@Composable
fun ConversationRoute(
    service: ConversationService,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemUTC(),
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val viewModel: ConversationListViewModel = viewModel(
        factory = ConversationListViewModel.factory(service, clock, zoneId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationListEvent.OpenConversation ->
                    onOpenConversation(event.conversationId)
            }
        }
    }

    ConversationListScreen(
        state = state,
        onNewChat = viewModel::onNewChat,
        onSelect = viewModel::onSelect,
        onRename = viewModel::onRename,
        onDeleteRequested = viewModel::onDeleteRequested,
        onDeleteDismissed = viewModel::onDeleteDismissed,
        onDeleteConfirmed = viewModel::onDeleteConfirmed,
        onRetry = viewModel::onRetry,
        modifier = modifier,
        now = clock.instant(),
        zoneId = zoneId,
    )
}
