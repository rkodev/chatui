package dev.chatui.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.chatui.core.database.attachment.formatAttachmentSize
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
import dev.chatui.core.designsystem.softShadow
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.SendKey
import java.io.File

/** Test tags exposed by the chat composer. */
object ChatComposerTestTags {
    const val Composer = "chat-composer"
    const val Input = "chat-composer-input"
    const val Send = "chat-composer-send"
    const val Stop = "chat-composer-stop"
    const val Offline = "chat-composer-offline"
    const val Attach = "chat-composer-attach"
    const val AttachPhoto = "chat-composer-attach-photo"
    const val AttachFile = "chat-composer-attach-file"
    const val Paste = "chat-composer-paste"
    const val AttachmentWarning = "chat-composer-attachment-warning"
    const val AttachmentError = "chat-composer-attachment-error"

    fun attachmentChip(id: String): String = "chat-composer-attachment-$id"

    fun removeAttachment(id: String): String = "chat-composer-remove-$id"
}

/**
 * Floating chat composer.
 *
 * The component is presentational: the draft, send-key mode, streaming flag,
 * pending attachments, and availability come from the ViewModel. Drafts are
 * hoisted to [ChatDrafts] so switching conversations restores what the user
 * typed.
 *
 * Attach and paste live in a bottom sheet opened by the leading `+` button.
 * The trailing circular button morphs between send and stop while streaming.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sendKey: SendKey,
    enabled: Boolean,
    streaming: Boolean,
    sending: Boolean,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
    attachments: List<Attachment> = emptyList(),
    attachmentWarning: String? = null,
    attachmentError: String? = null,
    attachmentFile: (String) -> File? = { null },
    onAddPhoto: (() -> Unit)? = null,
    onAddFile: (() -> Unit)? = null,
    onPasteImage: (() -> Unit)? = null,
    onRemoveAttachment: ((String) -> Unit)? = null,
    onDismissAttachmentError: (() -> Unit)? = null,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val haptics = rememberHaptics()
    val canSend = enabled &&
        !offline &&
        !streaming &&
        !sending &&
        (draft.isNotBlank() || attachments.isNotEmpty())
    var sheetOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val sendInteraction = remember { MutableInteractionSource() }
    val pressed by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(stiffness = 600f, dampingRatio = 0.6f),
        label = "sendScale",
    )
    val barShape = RoundedCornerShape(dimens.radiusXl)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.padding, vertical = dimens.space2)
            .testTag(ChatComposerTestTags.Composer),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        if (offline) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dimens.radiusMd))
                    .background(colors.warning.copy(alpha = 0.14f))
                    .padding(horizontal = dimens.space3, vertical = dimens.space2)
                    .testTag(ChatComposerTestTags.Offline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "You are offline. Sending is disabled.",
                    style = Typography.footnote,
                    color = colors.fgPrimary,
                )
            }
        }

        if (attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
            ) {
                items(items = attachments, key = { it.id }) { attachment ->
                    AttachmentChip(
                        attachment = attachment,
                        file = attachmentFile(attachment.id),
                        onRemove = onRemoveAttachment?.let { remove ->
                            { remove(attachment.id) }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        attachmentWarning?.let { warning ->
            Text(
                text = warning,
                style = Typography.footnote,
                color = colors.warning,
                modifier = Modifier.testTag(ChatComposerTestTags.AttachmentWarning),
            )
        }

        attachmentError?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error,
                    style = Typography.footnote,
                    color = colors.danger,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ChatComposerTestTags.AttachmentError),
                )
                if (onDismissAttachmentError != null) {
                    TextButton(onClick = onDismissAttachmentError) {
                        Text("Dismiss")
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .softShadow(dimens.elevation2, barShape)
                .border(
                    width = 1.dp,
                    color = if (focused) colors.accent else colors.borderSubtle,
                    shape = barShape,
                ),
            shape = barShape,
            color = colors.bgInput,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = dimens.space2, vertical = dimens.space1),
                horizontalArrangement = Arrangement.spacedBy(dimens.space1),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = { sheetOpen = true },
                    enabled = enabled,
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ChatComposerTestTags.Attach)
                        .semantics { contentDescription = "Add attachment" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = colors.fgSecondary,
                    )
                }

                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused }
                        .testTag(ChatComposerTestTags.Input)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key != Key.Enter) return@onPreviewKeyEvent false
                            if (event.isShiftPressed) return@onPreviewKeyEvent false

                            val modifierPressed = event.isCtrlPressed || event.isMetaPressed
                            val shouldSend = sendKey == SendKey.ENTER || modifierPressed

                            if (!shouldSend || !canSend) {
                                false
                            } else {
                                onSend()
                                true
                            }
                        },
                    enabled = enabled,
                    textStyle = Typography.body.copy(color = colors.fgPrimary),
                    minLines = 1,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (sendKey == SendKey.ENTER) {
                            ImeAction.Send
                        } else {
                            ImeAction.Default
                        },
                    ),
                    keyboardActions = if (sendKey == SendKey.ENTER) {
                        KeyboardActions(
                            onSend = {
                                if (canSend) onSend()
                            },
                        )
                    } else {
                        KeyboardActions()
                    },
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.padding(vertical = dimens.space2),
                        ) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "Message…",
                                    style = Typography.body,
                                    color = colors.fgMuted,
                                )
                            }
                            innerTextField()
                        }
                    },
                )

                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = sendScale
                        scaleY = sendScale
                    },
                ) {
                    AnimatedContent(
                        targetState = streaming || sending,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "sendStopMorph",
                    ) { isStreaming ->
                        if (isStreaming) {
                            IconButton(
                                onClick = onStop,
                                modifier = Modifier
                                    .heightIn(min = dimens.touchTargetMin)
                                    .testTag(ChatComposerTestTags.Stop)
                                    .semantics { contentDescription = "Stop response" },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    tint = colors.fgPrimary,
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    haptics.confirm()
                                    onSend()
                                },
                                enabled = canSend,
                                interactionSource = sendInteraction,
                                modifier = Modifier
                                    .heightIn(min = dimens.touchTargetMin)
                                    .testTag(ChatComposerTestTags.Send)
                                    .semantics { contentDescription = "Send message" },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowUpward,
                                    contentDescription = null,
                                    tint = if (canSend) colors.fgOnAccent else colors.fgMuted,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (canSend) colors.accent else colors.bgSurfaceRaised,
                                        )
                                        .padding(5.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.space5),
            ) {
                if (onAddPhoto != null) {
                    SheetAction(
                        icon = Icons.Rounded.Image,
                        label = "Photo",
                        tag = ChatComposerTestTags.AttachPhoto,
                    ) {
                        sheetOpen = false
                        onAddPhoto()
                    }
                }
                if (onAddFile != null) {
                    SheetAction(
                        icon = Icons.Rounded.AttachFile,
                        label = "File",
                        tag = ChatComposerTestTags.AttachFile,
                    ) {
                        sheetOpen = false
                        onAddFile()
                    }
                }
                if (onPasteImage != null) {
                    SheetAction(
                        icon = Icons.Rounded.ContentPaste,
                        label = "Paste",
                        tag = ChatComposerTestTags.Paste,
                    ) {
                        sheetOpen = false
                        onPasteImage()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.touchTargetMin)
            .testTag(tag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.fgSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = Typography.body,
            modifier = Modifier.padding(start = dimens.space3),
        )
    }
}

@Composable
private fun AttachmentChip(
    attachment: Attachment,
    file: File?,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val shape = RoundedCornerShape(dimens.radiusMd)

    Box(
        modifier = modifier
            .size(72.dp)
            .clip(shape)
            .background(colors.bgSurfaceRaised)
            .testTag(ChatComposerTestTags.attachmentChip(attachment.id)),
    ) {
        if (attachment.kind == AttachmentKind.IMAGE && file != null) {
            val request = rememberChatImageRequest(
                file = file,
                maxWidth = 72.dp,
                maxHeight = 72.dp,
            )
            AsyncImage(
                model = request,
                contentDescription = attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .size(72.dp)
                    .padding(dimens.space2),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.InsertDriveFile,
                    contentDescription = null,
                    tint = colors.fgSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = attachment.name,
                    style = Typography.caption,
                    color = colors.fgSecondary,
                    maxLines = 1,
                )
                Text(
                    text = formatAttachmentSize(attachment.size),
                    style = Typography.caption,
                    color = colors.fgMuted,
                    maxLines = 1,
                )
            }
        }

        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .testTag(ChatComposerTestTags.removeAttachment(attachment.id))
                    .semantics { contentDescription = "Remove ${attachment.name}" },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = colors.fgOnAccent,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(colors.scrim)
                        .padding(2.dp),
                )
            }
        }
    }
}
