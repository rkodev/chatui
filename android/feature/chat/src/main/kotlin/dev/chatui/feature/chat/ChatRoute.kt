package dev.chatui.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.model.newId
import java.io.File

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
    onOpenConversationSettings: () -> Unit = {},
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
            attachments = dependencies.attachmentStore,
            persistIntervalMs = persistIntervalMs,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by dependencies.settings.settings.collectAsStateWithLifecycle(
        initialValue = AndroidSettings(),
    )
    val online = rememberIsOnline()
    val context = LocalContext.current

    val attachmentFile: (String) -> File? = { id ->
        val attachment = state.attachmentsByMessage[id]
            ?: state.pendingAttachments.firstOrNull { it.id == id }
        val root = dependencies.attachmentRoot
        if (attachment != null && root != null) {
            File(root, attachment.storageKey)
        } else {
            null
        }
    }

    ChatScreen(
        state = state,
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::onSend,
        onStop = viewModel::onStop,
        onLoadOlder = viewModel::onLoadOlder,
        onRetryMessage = viewModel::onRetry,
        onDismissError = viewModel::onDismissError,
        onRegenerateMessage = viewModel::onRegenerate,
        onEditLastUser = viewModel::onEditAndResend,
        onBack = onBack,
        onOpenProviders = onOpenProviders,
        onOpenConversationSettings = onOpenConversationSettings,
        onShareConversation = { format ->
            val text = ConversationShareFormatter.format(
                title = state.conversation?.title ?: "Conversation",
                messages = state.messages,
                format = format,
            )
            context.startActivity(ConversationShareIntent.createChooserIntent(text))
        },
        onAddAttachments = viewModel::onAddAttachments,
        onRemoveAttachment = viewModel::onRemoveAttachment,
        onConfirmSend = viewModel::onConfirmSend,
        onCancelSendConfirmation = viewModel::onCancelSendConfirmation,
        onDismissAttachmentError = viewModel::onDismissAttachmentError,
        attachmentFile = attachmentFile,
        sendKey = settings.sendKey,
        showTokenUsage = settings.showTokenUsage,
        loadRemoteImages = settings.loadRemoteImages,
        chatFontSize = settings.chatFontSize,
        offline = !online,
        modifier = modifier,
        zoneId = java.time.ZoneId.systemDefault(),
    )
}
