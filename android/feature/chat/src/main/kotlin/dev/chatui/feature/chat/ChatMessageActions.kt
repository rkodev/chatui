package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Message
import kotlinx.coroutines.delay

/** Test tags exposed by the message action row and edit dialog. */
object ChatMessageActionsTestTags {
    const val Row = "chat-message-actions"
    const val Copy = "chat-message-actions-copy"
    const val Regenerate = "chat-message-actions-regenerate"
    const val Edit = "chat-message-actions-edit"
    const val Overflow = "chat-message-actions-overflow"
    const val Menu = "chat-message-actions-menu"
    const val EditDialog = "chat-message-actions-edit-dialog"
    const val EditInput = "chat-message-actions-edit-input"
    const val EditConfirm = "chat-message-actions-edit-confirm"
    const val EditCancel = "chat-message-actions-edit-cancel"
}

/**
 * Copy, regenerate, and edit-last-user actions for one message.
 *
 * Copy is local. Regenerate and edit are only offered when the parent marks
 * the message as the last assistant or last user message. Editing always opens
 * a confirmation dialog because it truncates every later message in the
 * conversation; no branch is created.
 *
 * When [inline] is true the actions render as a compact row (used for the last
 * assistant and last user messages). Otherwise they live behind a trailing
 * overflow menu. [menuInitiallyOpen] lets a long-press on the bubble open the
 * same menu without duplicating action tags.
 */
@Composable
fun ChatMessageActions(
    message: Message,
    modifier: Modifier = Modifier,
    canRegenerate: Boolean = false,
    canEditLastUser: Boolean = false,
    onRegenerate: ((Message) -> Unit)? = null,
    onEditLastUser: ((Message, String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    inline: Boolean = true,
    menuInitiallyOpen: Boolean = false,
    onMenuDismiss: (() -> Unit)? = null,
) {
    val dimens = LocalDimens.current
    var copied by remember(message.id) { mutableStateOf(false) }
    var editing by remember(message.id) { mutableStateOf(false) }
    var draft by remember(message.id) { mutableStateOf("") }
    var menuOpen by remember(message.id) { mutableStateOf(menuInitiallyOpen) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(menuInitiallyOpen) {
        if (menuInitiallyOpen) menuOpen = true
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_RESET_MS)
            copied = false
        }
    }

    val copyAction: () -> Unit = {
        val text = message.copyableText()
        if (onCopy != null) {
            onCopy(text)
        } else {
            clipboard.setText(AnnotatedString(text))
        }
        copied = true
    }
    val regenerateAction: () -> Unit = {
        onRegenerate?.invoke(message)
    }
    val editAction: () -> Unit = {
        draft = message.copyableText()
        editing = true
    }

    if (inline) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .testTag(ChatMessageActionsTestTags.Row),
            horizontalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            TextButton(
                onClick = copyAction,
                modifier = Modifier
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ChatMessageActionsTestTags.Copy)
                    .semantics { contentDescription = "Copy message" },
            ) {
                Text(if (copied) "Copied" else "Copy")
            }

            if (canRegenerate && onRegenerate != null) {
                TextButton(
                    onClick = regenerateAction,
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ChatMessageActionsTestTags.Regenerate)
                        .semantics { contentDescription = "Regenerate response" },
                ) {
                    Text("Regenerate")
                }
            }

            if (canEditLastUser && onEditLastUser != null) {
                TextButton(
                    onClick = editAction,
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ChatMessageActionsTestTags.Edit)
                        .semantics { contentDescription = "Edit last message" },
                ) {
                    Text("Edit")
                }
            }
        }
    } else {
        Box(modifier = modifier) {
            if (!menuInitiallyOpen) {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ChatMessageActionsTestTags.Overflow)
                        .semantics { contentDescription = "Message actions" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = null,
                    )
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = {
                    menuOpen = false
                    onMenuDismiss?.invoke()
                },
                modifier = Modifier.testTag(ChatMessageActionsTestTags.Menu),
            ) {
                DropdownMenuItem(
                    text = { Text(if (copied) "Copied" else "Copy") },
                    onClick = {
                        copyAction()
                        menuOpen = false
                        onMenuDismiss?.invoke()
                    },
                    modifier = Modifier.testTag(ChatMessageActionsTestTags.Copy),
                )
                if (canRegenerate && onRegenerate != null) {
                    DropdownMenuItem(
                        text = { Text("Regenerate") },
                        onClick = {
                            regenerateAction()
                            menuOpen = false
                            onMenuDismiss?.invoke()
                        },
                        modifier = Modifier.testTag(ChatMessageActionsTestTags.Regenerate),
                    )
                }
                if (canEditLastUser && onEditLastUser != null) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            editAction()
                            menuOpen = false
                            onMenuDismiss?.invoke()
                        },
                        modifier = Modifier.testTag(ChatMessageActionsTestTags.Edit),
                    )
                }
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            modifier = Modifier.testTag(ChatMessageActionsTestTags.EditDialog),
            title = { Text("Edit last message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ChatMessageActionsTestTags.EditInput),
                        label = { Text("Message text") },
                        minLines = 2,
                        maxLines = 8,
                    )
                    Text(
                        text = "Editing this message deletes every later message in this " +
                            "conversation. This cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            editing = false
                            onEditLastUser?.invoke(message, text)
                        }
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.testTag(ChatMessageActionsTestTags.EditConfirm),
                ) {
                    Text("Edit and resend")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { editing = false },
                    modifier = Modifier.testTag(ChatMessageActionsTestTags.EditCancel),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

private const val COPIED_RESET_MS = 1_500L
