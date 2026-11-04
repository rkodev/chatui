package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import java.time.ZoneId

/** Test tags exposed by the chat message list. */
object ChatMessageListTestTags {
    const val List = "chat-message-list"
    const val Older = "chat-message-list-older"
    const val Streaming = "chat-message-list-streaming"
}

/**
 * Conversation history with older-message paging and conditional auto-scroll.
 *
 * History rows use stable message ids as keys and the role as content type. The
 * active stream is rendered by a separate row; its parameters change on every
 * token, while history items keep the same [Message] instance and key so
 * Compose skips them.
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
    chatFontSize: ChatFontSize = ChatFontSize.MD,
    zoneId: ZoneId = ZoneId.systemDefault(),
    listState: LazyListState = rememberLazyListState(),
    onMessageComposed: ((String) -> Unit)? = null,
) {
    val dimens = LocalDimens.current
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

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.collect { total ->
            if (total > 0 && !initialScrollDone) {
                listState.scrollToItem(total - 1)
                initialScrollDone = true
            }
        }
    }

    LaunchedEffect(messages.size, streaming?.text, streaming?.reasoning, activeStream != null) {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0 || !initialScrollDone) return@LaunchedEffect
        if (isNearBottom) {
            listState.scrollToItem(total - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag(ChatMessageListTestTags.List),
        contentPadding = PaddingValues(dimens.padding),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
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

        items(
            items = visibleMessages,
            key = { message -> message.id },
            contentType = { message -> message.role.name },
        ) { message ->
            ChatMessageItem(
                message = message,
                chatFontSize = chatFontSize,
                showTokenUsage = showTokenUsage,
                onRetry = onRetry,
                onRegenerate = onRegenerate,
                onEditLastUser = onEditLastUser,
                canRegenerate = actionsEnabled && message.id == lastAssistantId,
                canEditLastUser = actionsEnabled && message.id == lastUserId,
                zoneId = zoneId,
                onComposed = onMessageComposed,
            )
        }

        activeStream?.let { stream ->
            item(key = "streaming-${stream.messageId}", contentType = "streaming") {
                ChatMessageItem(
                    message = stream.toMessage(conversation),
                    chatFontSize = chatFontSize,
                    showTokenUsage = showTokenUsage,
                    isActiveStream = !stream.done,
                    showActions = false,
                    onRetry = onRetry?.takeIf { stream.done },
                    zoneId = zoneId,
                    onComposed = onMessageComposed,
                    modifier = Modifier.testTag(ChatMessageListTestTags.Streaming),
                )
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

