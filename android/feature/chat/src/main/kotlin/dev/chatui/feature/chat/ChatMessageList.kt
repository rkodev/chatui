package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.launch

/** Test tags exposed by the chat message list. */
object ChatMessageListTestTags {
    const val List = "chat-message-list"
    const val Older = "chat-message-list-older"
    const val Streaming = "chat-message-list-streaming"
    const val ScrollToBottom = "chat-message-list-scroll-to-bottom"
    const val NewMessages = "chat-message-list-new-messages"
}

/**
 * Conversation history with older-message paging and conditional auto-scroll.
 *
 * History rows use stable message ids as keys and the role as content type. The
 * active stream is rendered by a separate row; its parameters change on every
 * token, while history items keep the same [Message] instance and key so
 * Compose skips them.
 *
 * Consecutive messages from the same role form a group: only the first row
 * shows the avatar and only the last row shows the timestamp. The list also
 * exposes a scroll-to-bottom affordance and a new-message pill when content
 * arrives while the user is reading history.
 */
@Composable
fun ChatMessageList(
    messages: List<Message>,
    streaming: StreamingState?,
    modifier: Modifier = Modifier,
    conversation: Conversation? = null,
    hasOlder: Boolean = false,
    loadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {},
    onRetry: ((Message) -> Unit)? = null,
    onRegenerate: ((Message) -> Unit)? = null,
    onEditLastUser: ((Message, String) -> Unit)? = null,
    actionsEnabled: Boolean = true,
    showTokenUsage: Boolean = false,
    loadRemoteImages: Boolean = false,
    chatFontSize: ChatFontSize = ChatFontSize.MD,
    zoneId: ZoneId = ZoneId.systemDefault(),
    listState: LazyListState = rememberLazyListState(),
    onMessageComposed: ((String) -> Unit)? = null,
    attachments: Map<String, Attachment> = emptyMap(),
    attachmentFile: (String) -> File? = { null },
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val scope = rememberCoroutineScope()
    val isStreaming = streaming?.done == false
    val visibleMessages = remember(messages, isStreaming, streaming?.messageId) {
        if (isStreaming) {
            messages.filterNot { it.id == streaming.messageId }
        } else {
            messages
        }
    }
    val activeStream = streaming?.takeIf { stream ->
        !stream.done || messages.none { it.id == stream.messageId }
    }
    val lastAssistantId = remember(visibleMessages) {
        visibleMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
    }
    val lastUserId = remember(visibleMessages) {
        visibleMessages.lastOrNull { it.role == MessageRole.USER }?.id
    }

    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
        }
    }
    var initialScrollDone by remember { mutableStateOf(false) }
    var newMessagesWhileAway by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.collect { total ->
            if (total > 0 && !initialScrollDone) {
                // Request the first position during layout; later programmatic
                // scrolls animate so the list never jumps under the user.
                listState.requestScrollToItem(total - 1)
                initialScrollDone = true
            }
        }
    }

    LaunchedEffect(messages.size, streaming?.text, streaming?.reasoning, activeStream != null) {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0 || !initialScrollDone) return@LaunchedEffect
        if (isNearBottom) {
            listState.animateScrollToItem(total - 1)
        } else {
            newMessagesWhileAway = true
        }
    }

    LaunchedEffect(isNearBottom) {
        if (isNearBottom) newMessagesWhileAway = false
    }

    val showScrollToBottom = !isNearBottom && (isStreaming || newMessagesWhileAway)

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(ChatMessageListTestTags.List),
            contentPadding = PaddingValues(dimens.padding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (hasOlder || loadingOlder) {
                item(key = "older", contentType = "older") {
                    TextButton(
                        onClick = onLoadOlder,
                        enabled = !loadingOlder,
                        modifier = Modifier.testTag(ChatMessageListTestTags.Older),
                    ) {
                        Text(
                            if (loadingOlder) {
                                "Loading older messages…"
                            } else {
                                "Load older messages"
                            },
                        )
                    }
                }
            }

            itemsIndexed(
                items = visibleMessages,
                key = { _, message -> message.id },
                contentType = { _, message -> message.role.name },
            ) { index, message ->
                val previous = visibleMessages.getOrNull(index - 1)
                val next = visibleMessages.getOrNull(index + 1)
                val isGroupStart = previous == null || previous.role != message.role
                val isGroupEnd = next == null || next.role != message.role
                ChatMessageItem(
                    message = message,
                    chatFontSize = chatFontSize,
                    showTokenUsage = showTokenUsage,
                    loadRemoteImages = loadRemoteImages,
                    onRetry = onRetry,
                    onRegenerate = onRegenerate,
                    onEditLastUser = onEditLastUser,
                    canRegenerate = actionsEnabled && message.id == lastAssistantId,
                    canEditLastUser = actionsEnabled && message.id == lastUserId,
                    zoneId = zoneId,
                    onComposed = onMessageComposed,
                    attachments = attachments,
                    attachmentFile = attachmentFile,
                    isGroupStart = isGroupStart,
                    isGroupEnd = isGroupEnd,
                    showTimestamp = isGroupEnd,
                )
            }

            activeStream?.let { stream ->
                val lastVisibleRole = visibleMessages.lastOrNull()?.role
                item(key = "streaming-${stream.messageId}", contentType = "streaming") {
                    ChatMessageItem(
                        message = stream.toMessage(conversation),
                        chatFontSize = chatFontSize,
                        showTokenUsage = showTokenUsage,
                        loadRemoteImages = loadRemoteImages,
                        isActiveStream = !stream.done,
                        showActions = false,
                        onRetry = onRetry?.takeIf { stream.done },
                        zoneId = zoneId,
                        onComposed = onMessageComposed,
                        isGroupStart = lastVisibleRole != MessageRole.ASSISTANT,
                        isGroupEnd = true,
                        showTimestamp = true,
                        modifier = Modifier.testTag(ChatMessageListTestTags.Streaming),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(dimens.padding),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            if (newMessagesWhileAway) {
                Surface(
                    onClick = {
                        scope.launch {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) listState.animateScrollToItem(total - 1)
                        }
                    },
                    shape = CircleShape,
                    color = colors.accent,
                    contentColor = colors.fgOnAccent,
                    modifier = Modifier.testTag(ChatMessageListTestTags.NewMessages),
                ) {
                    Text(
                        text = "New messages",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = dimens.space3, vertical = dimens.space2),
                    )
                }
            }
            if (showScrollToBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) listState.animateScrollToItem(total - 1)
                        }
                    },
                    containerColor = colors.bgSurfaceRaised,
                    contentColor = colors.fgPrimary,
                    modifier = Modifier
                        .size(dimens.touchTargetMin)
                        .testTag(ChatMessageListTestTags.ScrollToBottom),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "Scroll to latest messages",
                    )
                }
            }
        }
    }
}

/** Transient message used to render the active stream with the normal item. */
internal fun StreamingState.toMessage(conversation: Conversation?): Message {
    val parts = buildList {
        if (reasoning.isNotEmpty()) add(ContentPart.Reasoning(reasoning))
        if (text.isNotEmpty()) add(ContentPart.Text(text))
    }

    return Message(
        id = messageId,
        conversationId = conversationId,
        sequence = 0,
        role = MessageRole.ASSISTANT,
        parts = parts,
        status = status,
        providerId = conversation?.providerId,
        modelId = conversation?.modelId,
        modelLabel = conversation?.modelLabel,
        usage = usage,
        finishReason = finishReason,
        error = error,
        latencyMs = completedAt?.let { it.toEpochMilli() - startedAt.toEpochMilli() },
        firstTokenMs = firstTokenAt?.let { it.toEpochMilli() - startedAt.toEpochMilli() },
        createdAt = startedAt,
        updatedAt = updatedAt,
        deletedAt = null,
    )
}
