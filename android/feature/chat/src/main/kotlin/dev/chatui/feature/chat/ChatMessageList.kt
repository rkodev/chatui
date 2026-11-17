package dev.chatui.feature.chat

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.LocalReducedMotion
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
    const val Latest = "chat-message-list-latest"
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
 * shows the role label and only the last row shows the timestamp.
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
    val reducedMotion = LocalReducedMotion.current
    val followThreshold = with(LocalDensity.current) { 96.dp.roundToPx() }
    val scope = rememberCoroutineScope()
    val conversationKey = conversation?.id ?: messages.firstOrNull()?.conversationId ?: streaming?.conversationId
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

    val isNearBottom by remember(listState, followThreshold) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (
                last != null && last.index >= info.totalItemsCount - 2 &&
                    last.offset + last.size <= info.viewportEndOffset + followThreshold
                )
        }
    }
    var initialScrollDone by rememberSaveable(conversationKey) { mutableStateOf(false) }
    var following by rememberSaveable(conversationKey) {
        mutableStateOf(listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
    }
    var newMessagesWhileAway by rememberSaveable(conversationKey) { mutableStateOf(false) }
    var jumpingToLatest by remember(listState) { mutableStateOf(false) }
    val latestContent by rememberUpdatedState(visibleMessages.lastOrNull() to activeStream)

    LaunchedEffect(listState, conversationKey) {
        var previousContent = latestContent
        var previousSnapshot: FollowSnapshot? = null
        var tailRequested = false
        snapshotFlow {
            val info = listState.layoutInfo
            FollowSnapshot(
                content = latestContent,
                total = info.totalItemsCount,
                firstKey = info.visibleItemsInfo.firstOrNull()?.key,
                firstOffset = listState.firstVisibleItemScrollOffset,
                lastItemBottom = info.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size },
                nearBottom = isNearBottom,
                canScrollForward = listState.canScrollForward,
                scrolling = listState.isScrollInProgress,
            )
        }.collect { snapshot ->
            val contentChanged = previousContent != snapshot.content
            previousContent = snapshot.content
            val previous = previousSnapshot
            previousSnapshot = snapshot
            if (snapshot.total == 0) return@collect
            if (!initialScrollDone) {
                initialScrollDone = true
                if (following) {
                    tailRequested = true
                    listState.requestScrollToItem(snapshot.total - 1)
                }
            } else {
                val movedWithoutNewContent = previous != null &&
                    previous.total == snapshot.total && !contentChanged &&
                    (previous.firstKey != snapshot.firstKey || previous.firstOffset != snapshot.firstOffset)
                if (!jumpingToLatest && (snapshot.scrolling || (movedWithoutNewContent && !tailRequested))) {
                    following = snapshot.nearBottom
                }
                if (!snapshot.canScrollForward) tailRequested = false
                if (contentChanged && !following) newMessagesWhileAway = true
                if (following) {
                    newMessagesWhileAway = false
                    // Snap to the measured tail; token growth never restarts an animation.
                    if (!snapshot.scrolling && snapshot.canScrollForward) {
                        tailRequested = true
                        listState.requestScrollToItem(snapshot.total - 1)
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(ChatMessageListTestTags.List),
            contentPadding = PaddingValues(dimens.padding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                    modifier = if (reducedMotion) Modifier else Modifier.animateItem(
                        fadeInSpec = tween(150),
                        placementSpec = tween(160),
                        fadeOutSpec = tween(150),
                    ),
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
            item(key = Long.MIN_VALUE, contentType = "end") {
                Spacer(Modifier.height(1.dp))
            }
        }

        if (!isNearBottom) {
            Surface(
                onClick = {
                    scope.launch {
                        jumpingToLatest = true
                        following = true
                        newMessagesWhileAway = false
                        try {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) {
                                if (reducedMotion) {
                                    listState.scrollToItem(total - 1)
                                } else {
                                    listState.animateScrollToItem(total - 1)
                                }
                            }
                        } finally {
                            jumpingToLatest = false
                            following = isNearBottom
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(dimens.padding)
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ChatMessageListTestTags.Latest)
                    .semantics {
                        contentDescription = "Latest messages"
                        stateDescription = if (newMessagesWhileAway) "New content available" else "Earlier messages"
                    },
                shape = RoundedCornerShape(24.dp),
                color = colors.bgSurfaceRaised,
                contentColor = colors.fgPrimary,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = dimens.space3, vertical = dimens.space2),
                    horizontalArrangement = Arrangement.spacedBy(dimens.space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = null,
                    )
                    Column {
                        Text("Latest messages", style = MaterialTheme.typography.labelLarge)
                        if (newMessagesWhileAway) {
                            Text("New content available", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private data class FollowSnapshot(
    val content: Pair<Message?, StreamingState?>,
    val total: Int,
    val firstKey: Any?,
    val firstOffset: Int,
    val lastItemBottom: Int?,
    val nearBottom: Boolean,
    val canScrollForward: Boolean,
    val scrolling: Boolean,
)

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
