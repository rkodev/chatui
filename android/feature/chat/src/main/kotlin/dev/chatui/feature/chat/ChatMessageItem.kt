package dev.chatui.feature.chat

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import dev.chatui.core.database.attachment.formatAttachmentSize
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.BrandMark
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.LocalReducedMotion
import dev.chatui.core.designsystem.MotionSpecs
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.bubbleAppear
import dev.chatui.core.designsystem.rememberHaptics
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Usage
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Test tags exposed by one message row. */
object ChatMessageTestTags {
    const val Reasoning = "chat-message-reasoning"
    const val ReasoningText = "chat-message-reasoning-text"
    const val Typing = "chat-message-typing"
    const val Caret = "chat-message-caret"
    const val Cancelled = "chat-message-cancelled"
    const val Error = "chat-message-error"
    const val Retry = "chat-message-retry"
    const val Meta = "chat-message-meta"
    const val Timestamp = "chat-message-timestamp"
    const val AttachmentImage = "chat-message-attachment-image"
    const val AttachmentFile = "chat-message-attachment-file"

    fun message(id: String): String = "chat-message-$id"

    fun text(id: String): String = "chat-message-text-$id"

    fun errorDetails(id: String): String = "chat-message-error-details-$id"

    fun attachment(id: String): String = "chat-message-attachment-$id"

    fun avatar(id: String): String = "chat-message-avatar-$id"
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun roleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> "System"
    MessageRole.USER -> "You"
    MessageRole.ASSISTANT -> "Assistant"
    MessageRole.TOOL -> "Tool"
}

private fun Message.textParts(): List<String> = parts
    .filterIsInstance<ContentPart.Text>()
    .map { it.text }
    .filter { it.isNotEmpty() }

private fun Message.reasoningParts(): List<String> = parts
    .filterIsInstance<ContentPart.Reasoning>()
    .map { it.text }
    .filter { it.isNotEmpty() }

private fun formatUsage(usage: Usage): String {
    val parts = buildList {
        usage.inputTokens?.let { add("$it in") }
        usage.outputTokens?.let { add("$it out") }
        usage.totalTokens?.let { add("$it total") }
        usage.costUsd?.let { add("$" + "%.4f".format(it)) }
    }
    return parts.joinToString(" · ")
}

/**
 * One chat message.
 *
 * Text is rendered through the safe Markdown renderer, and persisted messages
 * expose copy/regenerate/edit actions. The item also renders cancelled,
 * interrupted, and inline error states.
 *
 * Grouping is driven by the caller: [isGroupStart] shows the avatar and adds
 * the larger inter-group gap, while [isGroupEnd] shows the timestamp and the
 * tail corner on the sender side.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: Message,
    modifier: Modifier = Modifier,
    chatFontSize: ChatFontSize = ChatFontSize.MD,
    showTokenUsage: Boolean = false,
    loadRemoteImages: Boolean = false,
    isActiveStream: Boolean = false,
    showActions: Boolean = true,
    canRegenerate: Boolean = false,
    canEditLastUser: Boolean = false,
    onRetry: ((Message) -> Unit)? = null,
    onRegenerate: ((Message) -> Unit)? = null,
    onEditLastUser: ((Message, String) -> Unit)? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
    onComposed: ((String) -> Unit)? = null,
    attachments: Map<String, Attachment> = emptyMap(),
    attachmentFile: (String) -> File? = { null },
    isGroupStart: Boolean = true,
    isGroupEnd: Boolean = true,
    showTimestamp: Boolean = false,
) {
    SideEffect { onComposed?.invoke(message.id) }
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val haptics = rememberHaptics()
    val reducedMotion = LocalReducedMotion.current
    val isUser = message.role == MessageRole.USER
    val text = message.textParts()
    val reasoning = message.reasoningParts()
    val attachmentParts = message.parts.filter { part ->
        part is ContentPart.Image || part is ContentPart.File
    }
    val hasText = text.any { it.isNotBlank() }
    val hasContent = hasText || attachmentParts.isNotEmpty()
    val errorCopy = message.error?.let(::describeChatError)
    val bodyStyle: TextStyle = Typography.chatStyle(chatFontSize)
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) colors.bgUserBubble else colors.bgGrouped
    var reasoningExpanded by remember(message.id) { mutableStateOf(false) }
    var detailsExpanded by remember(message.id) { mutableStateOf(false) }
    var timestampVisible by remember(message.id) { mutableStateOf(false) }
    var contextMenuOpen by remember(message.id) { mutableStateOf(false) }

    val waitingForFirstToken = isActiveStream && !hasContent
    val bubbleShape = RoundedCornerShape(
        topStart = dimens.bubbleRadius,
        topEnd = dimens.bubbleRadius,
        bottomStart = if (!isUser && isGroupEnd) dimens.bubbleTailRadius else dimens.bubbleRadius,
        bottomEnd = if (isUser && isGroupEnd) dimens.bubbleTailRadius else dimens.bubbleRadius,
    )
    val bubbleBrush = if (waitingForFirstToken) {
        rememberShimmerBrush(bubbleColor)
    } else {
        SolidColor(bubbleColor)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (isGroupStart) 10.dp else 0.dp)
            .testTag(ChatMessageTestTags.message(message.id))
            .semantics { contentDescription = "${roleLabel(message.role)} message" },
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxBubbleWidth = maxWidth * 0.78f
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (!isUser && isGroupStart) {
                    MessageAvatar(message = message)
                    Box(modifier = Modifier.width(dimens.space2))
                }

                Box {
                    Column(
                        modifier = Modifier
                            .widthIn(max = maxBubbleWidth)
                            .bubbleAppear(message.id)
                            .clip(bubbleShape)
                            .background(brush = bubbleBrush, shape = bubbleShape)
                            .pointerInput(message.id) {
                                detectTapGestures(
                                    onTap = { timestampVisible = !timestampVisible },
                                    onLongPress = {
                                        haptics.longPress()
                                        contextMenuOpen = true
                                    },
                                )
                            }
                            .padding(
                                horizontal = dimens.bubblePaddingH,
                                vertical = dimens.bubblePaddingV,
                            )
                            .animateContentSize(
                                animationSpec = if (reducedMotion) {
                                    snap()
                                } else {
                                    MotionSpecs.springGentleSize
                                },
                            ),
                        verticalArrangement = Arrangement.spacedBy(dimens.space2),
                    ) {
                        if (reasoning.isNotEmpty()) {
                            ReasoningDisclosure(
                                expanded = reasoningExpanded,
                                onToggle = { reasoningExpanded = !reasoningExpanded },
                                reasoning = reasoning.joinToString("\n"),
                                bodyStyle = bodyStyle,
                            )
                        }

                        text.forEach { part ->
                            ChatMarkdown(
                                content = part,
                                modifier = Modifier.testTag(ChatMessageTestTags.text(message.id)),
                                chatFontSize = chatFontSize,
                                loadRemoteImages = loadRemoteImages,
                            )
                        }

                        if (isActiveStream && hasText) {
                            StreamingCaret()
                        }

                        attachmentParts.forEach { part ->
                            when (part) {
                                is ContentPart.Image -> MessageImageAttachment(
                                    part = part,
                                    attachment = attachments[part.attachmentId],
                                    file = attachmentFile(part.attachmentId),
                                )

                                is ContentPart.File -> MessageFileAttachment(
                                    part = part,
                                    attachment = attachments[part.attachmentId],
                                )

                                else -> Unit
                            }
                        }

                        if (waitingForFirstToken) {
                            TypingIndicator()
                        }

                        if (!hasContent && message.status == MessageStatus.COMPLETE) {
                            Text(
                                text = "No content.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (message.status == MessageStatus.CANCELLED &&
                            message.interruptionExplanation() == null
                        ) {
                            Text(
                                text = "Response cancelled. Partial content is preserved.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(ChatMessageTestTags.Cancelled),
                            )
                        }

                        message.interruptionExplanation()?.let { explanation ->
                            Text(
                                text = explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(ChatMessageTestTags.Cancelled),
                            )
                        }

                        if (errorCopy != null) {
                            ErrorCard(
                                message = message,
                                errorCopy = errorCopy,
                                detailsExpanded = detailsExpanded,
                                onToggleDetails = { detailsExpanded = !detailsExpanded },
                                onRetry = onRetry,
                            )
                        }
                    }

                    if (contextMenuOpen) {
                        ChatMessageActions(
                            message = message,
                            canRegenerate = canRegenerate,
                            canEditLastUser = canEditLastUser,
                            onRegenerate = onRegenerate,
                            onEditLastUser = onEditLastUser,
                            inline = false,
                            menuInitiallyOpen = true,
                            onMenuDismiss = { contextMenuOpen = false },
                        )
                    }
                }

                if (isUser && isGroupStart) {
                    Box(modifier = Modifier.width(dimens.space2))
                    MessageAvatar(message = message)
                }
            }
        }

        if (showActions && !contextMenuOpen) {
            ChatMessageActions(
                message = message,
                canRegenerate = canRegenerate,
                canEditLastUser = canEditLastUser,
                onRegenerate = onRegenerate,
                onEditLastUser = onEditLastUser,
                inline = canRegenerate,
            )
        }

        if (showTimestamp || timestampVisible) {
            Text(
                text = TIME_FORMATTER.format(message.createdAt.atZone(zoneId)),
                style = Typography.caption,
                color = colors.fgMuted,
                modifier = Modifier.testTag(ChatMessageTestTags.Timestamp),
            )
        }

        val usage = message.usage?.let(::formatUsage).orEmpty()
        val meta = buildList {
            if (message.role == MessageRole.ASSISTANT) {
                (message.modelLabel ?: message.modelId)?.let { add(it) }
                message.providerId?.let { add(it) }
            }
            if (showTokenUsage && usage.isNotEmpty()) {
                add(usage)
            }
        }

        if (meta.isNotEmpty()) {
            Text(
                text = meta.joinToString(" · "),
                style = Typography.caption,
                color = colors.fgMuted,
                modifier = Modifier.testTag(ChatMessageTestTags.Meta),
            )
        }
    }
}

@Composable
private fun MessageAvatar(message: Message) {
    val colors = LocalChatUiColors.current
    if (message.role == MessageRole.ASSISTANT) {
        BrandMark(
            modifier = Modifier
                .size(28.dp)
                .testTag(ChatMessageTestTags.avatar(message.id)),
            size = 28.dp,
        )
    } else {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.accentSoft)
                .testTag(ChatMessageTestTags.avatar(message.id)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Y",
                style = Typography.caption,
                color = colors.onAccentSoft,
            )
        }
    }
}

@Composable
private fun ReasoningDisclosure(
    expanded: Boolean,
    onToggle: () -> Unit,
    reasoning: String,
    bodyStyle: TextStyle,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val reduced = LocalReducedMotion.current
    Column(
        modifier = Modifier.animateContentSize(
            animationSpec = if (reduced) snap() else MotionSpecs.springGentleSize,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.touchTargetMin)
                .testTag(ChatMessageTestTags.Reasoning)
                .combinedClickable(onClick = onToggle, onLongClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.fgMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Reasoning",
                style = Typography.footnote,
                color = colors.fgMuted,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduced) {
                EnterTransition.None
            } else {
                fadeIn() + expandVertically()
            },
            exit = if (reduced) {
                ExitTransition.None
            } else {
                fadeOut() + shrinkVertically()
            },
        ) {
            Text(
                text = reasoning,
                style = bodyStyle,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ChatMessageTestTags.ReasoningText),
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: Message,
    errorCopy: ChatErrorCopy,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onRetry: ((Message) -> Unit)?,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    InlineBanner(
        severity = BannerSeverity.Error,
        title = errorCopy.title,
        body = errorCopy.message,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatMessageTestTags.Error),
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.space1)) {
                errorCopy.details?.let { details ->
                    TextButton(
                        onClick = onToggleDetails,
                        modifier = Modifier.heightIn(min = dimens.touchTargetMin),
                    ) {
                        Text(if (detailsExpanded) "Hide details" else "Details")
                    }
                    if (detailsExpanded) {
                        Text(
                            text = details,
                            style = Typography.codeStyle,
                            color = colors.fgSecondary,
                            modifier = Modifier.testTag(
                                ChatMessageTestTags.errorDetails(message.id),
                            ),
                        )
                    }
                }
                if (onRetry != null) {
                    TextButton(
                        onClick = { onRetry(message) },
                        modifier = Modifier
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ChatMessageTestTags.Retry),
                    ) {
                        Text("Retry")
                    }
                }
            }
        },
    )
}

@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    val colors = LocalChatUiColors.current
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier
            .testTag(ChatMessageTestTags.Typing)
            .semantics { contentDescription = "Assistant is typing" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha = if (reduced) {
                0.7f
            } else {
                val animated by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 600, delayMillis = index * 120),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "typingDot$index",
                )
                animated
            }
            Box(
                modifier = Modifier
                    .offset(y = (-3 * alpha).dp)
                    .size(6.dp)
                    .background(colors.fgMuted.copy(alpha = alpha), CircleShape),
            )
        }
    }
}

@Composable
private fun StreamingCaret(modifier: Modifier = Modifier) {
    val colors = LocalChatUiColors.current
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha = if (reduced) {
        1f
    } else {
        val animated by transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = MotionSpecs.caretBlinkMillis / 2),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "caretAlpha",
        )
        animated
    }
    Box(
        modifier = modifier
            .size(width = 2.dp, height = 16.dp)
            .background(colors.accent.copy(alpha = alpha))
            .testTag(ChatMessageTestTags.Caret),
    )
}

@Composable
private fun rememberShimmerBrush(base: androidx.compose.ui.graphics.Color): Brush {
    val colors = LocalChatUiColors.current
    val reduced = LocalReducedMotion.current
    if (reduced) return SolidColor(base)
    val transition = rememberInfiniteTransition(label = "bubbleShimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bubbleShimmerProgress",
    )
    val x = progress * 1_000f
    return Brush.linearGradient(
        colors = listOf(base, colors.skeletonHighlight, base),
        start = androidx.compose.ui.geometry.Offset(x, 0f),
        end = androidx.compose.ui.geometry.Offset(x + 400f, 400f),
    )
}

@Composable
private fun MessageImageAttachment(
    part: ContentPart.Image,
    attachment: Attachment?,
    file: File?,
) {
    val description = attachment?.name ?: part.alt
    if (file == null) {
        ImageAttachmentFallback(description)
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val request = rememberChatImageRequest(
            file = file,
            maxWidth = maxWidth,
            maxHeight = 320.dp,
        )
        SubcomposeAsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .testTag(ChatMessageTestTags.attachment(part.attachmentId))
                .semantics { contentDescription = description },
            error = { ImageAttachmentFallback(description) },
        )
    }
}

@Composable
private fun ImageAttachmentFallback(description: String) {
    Text(
        text = description.ifBlank { "Image attachment" },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .testTag(ChatMessageTestTags.AttachmentImage)
            .semantics { contentDescription = "Image attachment" },
    )
}

/**
 * Build a Coil request for a local attachment file.
 *
 * The request is bounded to the display size so Coil never decodes the full
 * source bitmap. The cache key includes the file path, modification time, and
 * target size so a resized request cannot reuse a differently sized bitmap.
 */
internal fun chatImageRequest(
    context: Context,
    file: File,
    widthPx: Int,
    heightPx: Int,
): ImageRequest {
    val boundedWidth = widthPx.coerceAtLeast(1)
    val boundedHeight = heightPx.coerceAtLeast(1)
    val cacheKey = "${file.path}:${file.lastModified()}:${boundedWidth}x$boundedHeight"
    return ImageRequest.Builder(context)
        .data(file)
        .size(boundedWidth, boundedHeight)
        .memoryCacheKey(cacheKey)
        .build()
}

@Composable
internal fun rememberChatImageRequest(
    file: File,
    maxWidth: Dp,
    maxHeight: Dp,
): ImageRequest {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.roundToPx() }
    val heightPx = with(density) { maxHeight.roundToPx() }
    return remember(file.path, widthPx, heightPx) {
        chatImageRequest(context, file, widthPx, heightPx)
    }
}

@Composable
private fun MessageFileAttachment(
    part: ContentPart.File,
    attachment: Attachment?,
) {
    val dimens = LocalDimens.current
    val name = attachment?.name ?: part.name
    val size = attachment?.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatMessageTestTags.attachment(part.attachmentId))
            .semantics { contentDescription = "File attachment $name" },
        horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (size != null) {
            Text(
                text = formatAttachmentSize(size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ChatMessageTestTags.AttachmentFile),
            )
        }
    }
}
