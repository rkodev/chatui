package dev.chatui.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.model.newId

/**
 * Stateful chat entry point.
 *
 * The ViewModel is scoped to the conversation's navigation back-stack entry.
 * Composer settings are collected from the typed settings store so a change
 * applies without recreating the ViewModel.
 */
@Composable
fun ChatRoute(
    conversationId: String,
    dependencies: ChatDependencies,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
    persistIntervalMs: Long = DEFAULT_PERSIST_INTERVAL_MS,
) {
    val viewModel: ChatViewModel = viewModel(
        key = conversationId,
        factory = ChatViewModel.factory(
            conversationId = conversationId,
            conversations = dependencies.conversationSource,
            messages = dependencies.messageStore,
            contextResolver = dependencies.contextResolver,
            adapter = dependencies.adapter,
            clock = dependencies.clock,
            newId = ::newId,
            drafts = dependencies.drafts,
            persistIntervalMs = persistIntervalMs,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by dependencies.settings.settings.collectAsStateWithLifecycle(
        initialValue = AndroidSettings(),
    )
    val online = rememberIsOnline()

    ChatScreen(
        state = state,
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::onSend,
        onStop = viewModel::onStop,
        onLoadOlder = viewModel::onLoadOlder,
        onRetryMessage = viewModel::onRetry,
        onDismissError = viewModel::onDismissError,
        onBack = onBack,
        onOpenProviders = onOpenProviders,
        sendKey = settings.sendKey,
        showTokenUsage = settings.showTokenUsage,
        chatFontSize = settings.chatFontSize,
        offline = !online,
        modifier = modifier,
        zoneId = java.time.ZoneId.systemDefault(),
    )
}
