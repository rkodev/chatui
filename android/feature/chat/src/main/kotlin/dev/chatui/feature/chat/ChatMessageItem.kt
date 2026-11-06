package dev.chatui.feature.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
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
    const val Cancelled = "chat-message-cancelled"
    const val Error = "chat-message-error"
    const val Retry = "chat-message-retry"
    const val Meta = "chat-message-meta"
    const val AttachmentImage = "chat-message-attachment-image"
    const val AttachmentFile = "chat-message-attachment-file"

    fun message(id: String): String = "chat-message-$id"

    fun text(id: String): String = "chat-message-text-$id"

    fun errorDetails(id: String): String = "chat-message-error-details-$id"

    fun attachment(id: String): String = "chat-message-attachment-$id"
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
 */
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
) {
    SideEffect { onComposed?.invoke(message.id) }
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
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
    val bubbleColor = if (isUser) colors.bgUserBubble else colors.bgSurfaceRaised
    var reasoningExpanded by remember(message.id) { mutableStateOf(false) }
    var detailsExpanded by remember(message.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ChatMessageTestTags.message(message.id))
            .semantics { contentDescription = "${roleLabel(message.role)} message" },
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) {
                Arrangement.End
            } else {
                Arrangement.Start
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = roleLabel(message.role),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " · " + TIME_FORMATTER.format(message.createdAt.atZone(zoneId)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(dimens.radiusMd),
                )
                .padding(dimens.space3),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            if (reasoning.isNotEmpty()) {
                TextButton(
                    onClick = { reasoningExpanded = !reasoningExpanded },
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ChatMessageTestTags.Reasoning),
                ) {
                    Text(if (reasoningExpanded) "Hide reasoning" else "Show reasoning")
                }

                if (reasoningExpanded) {
                    Text(
                        text = reasoning.joinToString("\n"),
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(ChatMessageTestTags.ReasoningText),
                    )
                }
            }

            text.forEach { part ->
                ChatMarkdown(
                    content = part,
                    modifier = Modifier.testTag(ChatMessageTestTags.text(message.id)),
                    chatFontSize = chatFontSize,
                    loadRemoteImages = loadRemoteImages,
                )
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

            if (!hasContent && message.status == MessageStatus.STREAMING && isActiveStream) {
                Text(
                    text = "…",
                    style = bodyStyle,
                    modifier = Modifier
                        .testTag(ChatMessageTestTags.Typing)
                        .semantics { contentDescription = "Assistant is typing" },
                )
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ChatMessageTestTags.Error),
                    verticalArrangement = Arrangement.spacedBy(dimens.space1),
                ) {
                    Text(
                        text = errorCopy.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = errorCopy.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    errorCopy.details?.let { details ->
                        TextButton(
                            onClick = { detailsExpanded = !detailsExpanded },
                            modifier = Modifier.heightIn(min = dimens.touchTargetMin),
                        ) {
                            Text(if (detailsExpanded) "Hide details" else "Details")
                        }
                        if (detailsExpanded) {
                            Text(
                                text = details,
                                style = Typography.codeStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            }
        }

        if (showActions) {
            ChatMessageActions(
                message = message,
                canRegenerate = canRegenerate,
                canEditLastUser = canEditLastUser,
                onRegenerate = onRegenerate,
                onEditLastUser = onEditLastUser,
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ChatMessageTestTags.Meta),
            )
        }
    }
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
