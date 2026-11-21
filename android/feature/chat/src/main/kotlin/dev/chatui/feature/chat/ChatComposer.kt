package dev.chatui.feature.chat

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.chatui.core.database.attachment.formatAttachmentSize
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.LocalReducedMotion
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
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
    const val Processing = "chat-composer-processing"

    fun attachmentChip(id: String): String = "chat-composer-attachment-$id"

    fun removeAttachment(id: String): String = "chat-composer-remove-$id"
}

/**
 * One composer surface with a bounded input viewport and accessory strip.
 *
 * Text and attachments remain owned by the caller. Send and Stop share an
 * immediate action target; attachments never block editing or cancellation.
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
    processingAttachments: Boolean = false,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val haptics = rememberHaptics()
    val canStop = streaming || sending
    val canSend = enabled && !offline && !canStop && !processingAttachments &&
        (draft.isNotBlank() || attachments.isNotEmpty())
    var sheetOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val attachFocus = remember { FocusRequester() }
    var restoreAttachFocus by remember { mutableStateOf(false) }
    val closeSheet = {
        sheetOpen = false
        restoreAttachFocus = true
    }
    LaunchedEffect(sheetOpen, restoreAttachFocus) {
        if (!sheetOpen && restoreAttachFocus) {
            attachFocus.requestFocus()
            restoreAttachFocus = false
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val barShape = RoundedCornerShape(dimens.composerRadius)

    BoxWithConstraints(
        modifier = modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding, vertical = dimens.densitySpace1)
            .testTag(ChatComposerTestTags.Composer),
    ) {
        val lineHeight = with(LocalDensity.current) { Typography.body.lineHeight.toDp() }
        val inputPadding = dimens.densitySpace1.coerceAtMost(
            ((maxHeight - lineHeight) / 2).coerceAtLeast(0.dp),
        )
        val inputMinHeight = maxOf(dimens.touchTargetMin, lineHeight + inputPadding * 2)
        val rowPadding = dimens.space1.coerceAtMost(
            ((maxHeight - inputMinHeight) / 2).coerceAtLeast(0.dp),
        )
        val inputMaxHeight = (maxHeight * 0.4f).coerceAtLeast(inputMinHeight)
        val attachmentWidth = maxWidth.coerceAtMost(360.dp)
        val hasAccessories = offline || attachments.isNotEmpty() || attachmentWarning != null ||
            attachmentError != null || processingAttachments
        val accessoryMinHeight = if (attachments.isNotEmpty() || attachmentWarning != null ||
            attachmentError != null
        ) dimens.touchTargetMin * 2 else dimens.touchTargetMin
        val accessoryMaxHeight = (maxHeight * 0.3f).coerceAtLeast(accessoryMinHeight)
        val collapsedAccessories = maxHeight <
            inputMinHeight + rowPadding * 2 + dimens.space2 + accessoryMinHeight
        val accessories: @Composable () -> Unit = {
            ComposerAccessories(
                attachments = attachments,
                attachmentWarning = attachmentWarning,
                attachmentError = attachmentError,
                attachmentFile = attachmentFile,
                onRemoveAttachment = onRemoveAttachment,
                onChooseAgain = onDismissAttachmentError?.let { dismiss ->
                    {
                        dismiss()
                        sheetOpen = true
                    }
                },
                processing = processingAttachments,
                offline = offline,
                maxWidth = attachmentWidth,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            if (hasAccessories && !collapsedAccessories) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = accessoryMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    accessories()
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (focused) colors.accent else colors.borderSubtle,
                        shape = barShape,
                    ),
                shape = barShape,
                color = colors.bgInput,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = dimens.space2, vertical = rowPadding),
                    horizontalArrangement = Arrangement.spacedBy(dimens.space1),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = { sheetOpen = true },
                        enabled = enabled || (collapsedAccessories && hasAccessories),
                        modifier = Modifier
                            .sizeIn(minWidth = dimens.touchTargetMin, minHeight = dimens.touchTargetMin)
                            .focusRequester(attachFocus)
                            .testTag(ChatComposerTestTags.Attach)
                            .semantics {
                                contentDescription = if (collapsedAccessories && hasAccessories) {
                                    "Attachments"
                                } else {
                                    "Add attachment"
                                }
                                if (collapsedAccessories && hasAccessories && !sheetOpen) {
                                    stateDescription = listOfNotNull(
                                        attachments.takeIf { it.isNotEmpty() }?.let { "${it.size} attachments" },
                                        "Adding attachments".takeIf { processingAttachments },
                                        attachmentError,
                                        attachmentWarning,
                                        "Offline. Sending is disabled.".takeIf { offline },
                                    ).joinToString(". ")
                                    liveRegion = LiveRegionMode.Polite
                                }
                            },
                    ) {
                        if (collapsedAccessories && hasAccessories && !sheetOpen) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = colors.bgSurfaceRaised,
                                        contentColor = colors.fgPrimary,
                                        modifier = if (processingAttachments) {
                                            Modifier.testTag(ChatComposerTestTags.Processing)
                                        } else {
                                            Modifier
                                        },
                                    ) {
                                        Text(
                                            when {
                                                attachmentError != null || attachmentWarning != null -> "!"
                                                processingAttachments -> "\u2026"
                                                attachments.isNotEmpty() -> attachments.size.toString()
                                                else -> "!"
                                            },
                                        )
                                    }
                                },
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.fgSecondary)
                            }
                        } else {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.fgSecondary)
                        }
                    }

                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = inputMinHeight, max = inputMaxHeight)
                            .onFocusChanged { focused = it.isFocused }
                            .testTag(ChatComposerTestTags.Input)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || event.key != Key.Enter ||
                                    event.isShiftPressed
                                ) {
                                    return@onPreviewKeyEvent false
                                }
                                val shouldSend = sendKey == SendKey.ENTER ||
                                    event.isCtrlPressed || event.isMetaPressed
                                if (shouldSend && canSend) {
                                    onSend()
                                    true
                                } else {
                                    false
                                }
                            },
                        enabled = enabled,
                        textStyle = Typography.body.copy(color = colors.fgPrimary),
                        minLines = 1,
                        maxLines = 8,
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (sendKey == SendKey.ENTER) ImeAction.Send else ImeAction.Default,
                        ),
                        keyboardActions = if (sendKey == SendKey.ENTER) {
                            KeyboardActions(onSend = { if (canSend) onSend() })
                        } else {
                            KeyboardActions()
                        },
                        decorationBox = { innerTextField ->
                            Box(Modifier.padding(vertical = inputPadding)) {
                                if (draft.isEmpty()) {
                                    Text("Message…", style = Typography.body, color = colors.fgMuted)
                                }
                                innerTextField()
                            }
                        },
                    )

                    IconButton(
                        onClick = {
                            if (canStop) {
                                onStop()
                            } else {
                                haptics.confirm()
                                onSend()
                            }
                        },
                        enabled = canStop || canSend,
                        modifier = Modifier
                            .sizeIn(minWidth = dimens.touchTargetMin, minHeight = dimens.touchTargetMin)
                            .testTag(if (canStop) ChatComposerTestTags.Stop else ChatComposerTestTags.Send)
                            .semantics { contentDescription = if (canStop) "Stop response" else "Send message" },
                    ) {
                        if (canStop) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, tint = colors.fgPrimary)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = null,
                                tint = if (canSend) colors.fgOnAccent else colors.fgMuted,
                                modifier = Modifier.size(28.dp).clip(CircleShape)
                                    .background(if (canSend) colors.accent else colors.bgSurfaceRaised)
                                    .padding(5.dp),
                            )
                        }
                    }
                }
            }
        }
        if (sheetOpen) {
            ModalBottomSheet(onDismissRequest = closeSheet, sheetState = sheetState) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = dimens.space5),
                ) {
                    if (collapsedAccessories && hasAccessories) {
                        Column(
                            modifier = Modifier.padding(dimens.screenPadding),
                            verticalArrangement = Arrangement.spacedBy(dimens.space2),
                        ) {
                            Text("Attachments and status", style = Typography.title3)
                            accessories()
                        }
                    }
                    if (onAddPhoto != null) {
                        SheetAction(Icons.Rounded.Image, "Photo", ChatComposerTestTags.AttachPhoto, enabled) {
                            closeSheet()
                            onAddPhoto()
                        }
                    }
                    if (onAddFile != null) {
                        SheetAction(Icons.Rounded.AttachFile, "File", ChatComposerTestTags.AttachFile, enabled) {
                            closeSheet()
                            onAddFile()
                        }
                    }
                    if (onPasteImage != null) {
                        SheetAction(Icons.Rounded.ContentPaste, "Paste", ChatComposerTestTags.Paste, enabled) {
                            closeSheet()
                            onPasteImage()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerAccessories(
    attachments: List<Attachment>,
    attachmentWarning: String?,
    attachmentError: String?,
    attachmentFile: (String) -> File?,
    onRemoveAttachment: ((String) -> Unit)?,
    onChooseAgain: (() -> Unit)?,
    processing: Boolean,
    offline: Boolean,
    maxWidth: Dp,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val reducedMotion = LocalReducedMotion.current
    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        if (processing) {
            Text(
                text = "Adding attachments...",
                style = Typography.footnote,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ChatComposerTestTags.Processing)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        attachmentError?.let { error ->
            InlineBanner(
                severity = BannerSeverity.Error,
                title = "Attachment failed",
                body = error,
                modifier = Modifier.testTag(ChatComposerTestTags.AttachmentError),
                action = onChooseAgain?.let { chooseAgain ->
                    { TextButton(onClick = chooseAgain) { Text("Choose again") } }
                },
            )
        }
        if (offline) {
            Text(
                text = "You are offline. Sending is disabled.",
                style = Typography.footnote,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ChatComposerTestTags.Offline),
            )
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
                        onRemove = onRemoveAttachment?.let { remove -> { remove(attachment.id) } },
                        maxWidth = maxWidth,
                        modifier = if (reducedMotion) Modifier else Modifier.animateItem(
                            placementSpec = tween(160),
                        ),
                    )
                }
            }
        }
        attachmentWarning?.let { warning ->
            InlineBanner(
                severity = BannerSeverity.Warning,
                title = "Attachment warning",
                body = warning,
                modifier = Modifier.testTag(ChatComposerTestTags.AttachmentWarning),
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = dimens.touchTargetMin).testTag(tag),
    ) {
        Icon(icon, contentDescription = null, tint = colors.fgSecondary, modifier = Modifier.size(20.dp))
        Text(label, style = Typography.body, modifier = Modifier.padding(start = dimens.space3))
    }
}

@Composable
private fun AttachmentChip(
    attachment: Attachment,
    file: File?,
    onRemove: (() -> Unit)?,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val shape = RoundedCornerShape(dimens.radiusMd)
    Row(
        modifier = modifier
            .widthIn(min = maxWidth.coerceAtMost(240.dp), max = maxWidth)
            .clip(shape)
            .background(colors.bgSurfaceRaised)
            .padding(dimens.space2)
            .testTag(ChatComposerTestTags.attachmentChip(attachment.id)),
        horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (attachment.kind == AttachmentKind.IMAGE && file != null) {
            AsyncImage(
                model = rememberChatImageRequest(file, maxWidth = 48.dp, maxHeight = 48.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(shape),
            )
        } else {
            Icon(
                Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = colors.fgSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(attachment.name, style = Typography.caption, color = colors.fgSecondary)
            Text(formatAttachmentSize(attachment.size), style = Typography.caption, color = colors.fgMuted)
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .sizeIn(minWidth = dimens.touchTargetMin, minHeight = dimens.touchTargetMin)
                    .testTag(ChatComposerTestTags.removeAttachment(attachment.id))
                    .semantics { contentDescription = "Remove ${attachment.name}" },
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.fgSecondary)
            }
        }
    }
}
