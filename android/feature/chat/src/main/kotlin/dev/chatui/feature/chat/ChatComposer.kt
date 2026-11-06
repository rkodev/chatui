package dev.chatui.feature.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.chatui.core.database.attachment.formatAttachmentSize
import dev.chatui.core.designsystem.LocalDimens
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
 * Auto-growing chat composer.
 *
 * The component is presentational: the draft, send-key mode, streaming flag,
 * pending attachments, and availability come from the ViewModel. Drafts are
 * hoisted to [ChatDrafts] so switching conversations restores what the user
 * typed.
 */
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
    val canSend = enabled &&
        !offline &&
        !streaming &&
        !sending &&
        (draft.isNotBlank() || attachments.isNotEmpty())
    var attachMenuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ChatComposerTestTags.Composer),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.padding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            if (offline) {
                Text(
                    text = "You are offline. Sending is disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ChatComposerTestTags.Offline),
                )
            }

            if (attachments.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(dimens.space1),
                ) {
                    attachments.forEach { attachment ->
                        AttachmentChip(
                            attachment = attachment,
                            file = attachmentFile(attachment.id),
                            onRemove = onRemoveAttachment?.let { remove ->
                                { remove(attachment.id) }
                            },
                        )
                    }
                }
            }

            attachmentWarning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (onAddPhoto != null || onAddFile != null) {
                    Column {
                        TextButton(
                            onClick = { attachMenuOpen = true },
                            enabled = enabled,
                            modifier = Modifier
                                .heightIn(min = dimens.touchTargetMin)
                                .testTag(ChatComposerTestTags.Attach),
                        ) {
                            Text("Attach")
                        }
                        DropdownMenu(
                            expanded = attachMenuOpen,
                            onDismissRequest = { attachMenuOpen = false },
                        ) {
                            if (onAddPhoto != null) {
                                DropdownMenuItem(
                                    text = { Text("Photo") },
                                    onClick = {
                                        attachMenuOpen = false
                                        onAddPhoto()
                                    },
                                    modifier = Modifier.testTag(ChatComposerTestTags.AttachPhoto),
                                )
                            }
                            if (onAddFile != null) {
                                DropdownMenuItem(
                                    text = { Text("File") },
                                    onClick = {
                                        attachMenuOpen = false
                                        onAddFile()
                                    },
                                    modifier = Modifier.testTag(ChatComposerTestTags.AttachFile),
                                )
                            }
                        }
                    }
                }

                if (onPasteImage != null) {
                    TextButton(
                        onClick = onPasteImage,
                        enabled = enabled,
                        modifier = Modifier
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ChatComposerTestTags.Paste),
                    ) {
                        Text("Paste")
                    }
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
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
                    placeholder = { Text("Send a message…") },
                    enabled = enabled,
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
                )

                if (streaming || sending) {
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ChatComposerTestTags.Stop),
                    ) {
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ChatComposerTestTags.Send),
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: Attachment,
    file: File?,
    onRemove: (() -> Unit)?,
) {
    val dimens = LocalDimens.current
    val bitmap = remember(attachment.id, file?.path) {
        if (attachment.kind == AttachmentKind.IMAGE && file != null) {
            BitmapFactory.decodeFile(file.path)
        } else {
            null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatComposerTestTags.attachmentChip(attachment.id)),
        horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = formatAttachmentSize(attachment.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (onRemove != null) {
            TextButton(
                onClick = onRemove,
                modifier = Modifier
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ChatComposerTestTags.removeAttachment(attachment.id)),
            ) {
                Text("Remove")
            }
        }
    }
}
