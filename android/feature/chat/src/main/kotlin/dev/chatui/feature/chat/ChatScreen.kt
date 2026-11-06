package dev.chatui.feature.chat

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chatui.core.database.attachment.AttachmentInput
import dev.chatui.core.database.attachment.MAX_IMAGE_BYTES
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.SendKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.ZoneId

/** Test tags exposed by the chat screen. */
object ChatScreenTestTags {
    const val Screen = "chat-screen"
    const val Header = "chat-screen-header"
    const val ModelChip = "chat-screen-model-chip"
    const val Loading = "chat-screen-loading"
    const val NotFound = "chat-screen-not-found"
    const val Empty = "chat-screen-empty"
    const val NoProvider = "chat-screen-no-provider"
    const val ModelUnavailable = "chat-screen-model-unavailable"
    const val NoKey = "chat-screen-no-key"
    const val Error = "chat-screen-error"
    const val Announcement = "chat-screen-announcement"
    const val Back = "chat-screen-back"
    const val OpenProviders = "chat-screen-open-providers"
    const val ConversationSettings = "chat-screen-conversation-settings"
    const val Share = "chat-screen-share"
    const val ShareMenu = "chat-screen-share-menu"
    const val ShareText = "chat-screen-share-text"
    const val ShareMarkdown = "chat-screen-share-markdown"
    const val AttachmentWarning = "chat-screen-attachment-warning"
    const val SendConfirmation = "chat-screen-send-confirmation"
    const val SendAnyway = "chat-screen-send-anyway"
    const val CancelSend = "chat-screen-cancel-send"
}

/**
 * Stateless chat screen.
 *
 * The screen renders history, availability, streaming presentation, and the
 * composer from one [ChatUiState]. It forwards every intent to the ViewModel.
 * Streaming text is rendered by [ChatMessageList]'s dedicated streaming row.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onLoadOlder: () -> Unit,
    onRetryMessage: (Message) -> Unit,
    onDismissError: () -> Unit,
    onRegenerateMessage: (Message) -> Unit = {},
    onEditLastUser: (Message, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
    onOpenConversationSettings: () -> Unit = {},
    onShareConversation: ((ConversationShareFormat) -> Unit)? = null,
    onAddAttachments: (List<AttachmentInput>) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onConfirmSend: () -> Unit = {},
    onCancelSendConfirmation: () -> Unit = {},
    onDismissAttachmentError: () -> Unit = {},
    attachmentFile: (String) -> File? = { null },
    sendKey: SendKey = SendKey.ENTER,
    showTokenUsage: Boolean = false,
    loadRemoteImages: Boolean = false,
    chatFontSize: ChatFontSize = ChatFontSize.MD,
    offline: Boolean = false,
    zoneId: ZoneId = ZoneId.systemDefault(),
    imeInsets: WindowInsets = WindowInsets.ime,
) {
    val dimens = LocalDimens.current
    val shortcutState = LocalChatShortcutState.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val lastAssistant = state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val copyLastResponse: (() -> Unit)? = lastAssistant?.let { message ->
        { clipboard.setText(AnnotatedString(message.copyableText())) }
    }

    // Register the existing stop and copy actions with the shell's root key
    // handler. The screen keeps ownership of streaming and message state; the
    // shell only invokes these callbacks.
    SideEffect {
        shortcutState?.update(
            streaming = state.canStop,
            overlayOpen = state.confirmSendWithUnsupportedAttachments,
            onStop = onStop,
            onCopyLastResponse = copyLastResponse,
        )
    }
    DisposableEffect(shortcutState) {
        onDispose { shortcutState?.clear() }
    }

    // The confirmation dialog is a separate window and normally consumes back
    // itself. This handler is the fallback for hosts that do not route back to
    // the dialog window; it uses the same close function as onDismissRequest so
    // the dialog can never both close and pop navigation.
    BackHandler(enabled = state.confirmSendWithUnsupportedAttachments) {
        onCancelSendConfirmation()
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            // Keep the composer above the keyboard. The shell consumes the
            // navigation-bar inset first, so this only adds the remaining IME
            // height instead of double-padding the bottom edge.
            .windowInsetsPadding(imeInsets)
            .testTag(ChatScreenTestTags.Screen),
        color = MaterialTheme.colorScheme.background,
    ) {
        when {
            state.loading -> LoadingState()
            state.conversation == null && state.conversationId != null -> NotFoundState(onBack)
            else -> ChatContent(
                state = state,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                onLoadOlder = onLoadOlder,
                onRetryMessage = onRetryMessage,
                onDismissError = onDismissError,
                onRegenerateMessage = onRegenerateMessage,
                onEditLastUser = onEditLastUser,
                onOpenProviders = onOpenProviders,
                onOpenConversationSettings = onOpenConversationSettings,
                onShareConversation = onShareConversation,
                onAddAttachments = onAddAttachments,
                onRemoveAttachment = onRemoveAttachment,
                onConfirmSend = onConfirmSend,
                onCancelSendConfirmation = onCancelSendConfirmation,
                onDismissAttachmentError = onDismissAttachmentError,
                attachmentFile = attachmentFile,
                sendKey = sendKey,
                showTokenUsage = showTokenUsage,
                loadRemoteImages = loadRemoteImages,
                chatFontSize = chatFontSize,
                offline = offline,
                zoneId = zoneId,
                dimensPadding = dimens.padding,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ChatScreenTestTags.Loading),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Loading conversation…",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun NotFoundState(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(ChatScreenTestTags.NotFound),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Conversation not found", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "This conversation may have been deleted.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag(ChatScreenTestTags.Back),
        ) {
            Text("Back to chats")
        }
    }
}

@Composable
private fun ChatContent(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onLoadOlder: () -> Unit,
    onRetryMessage: (Message) -> Unit,
    onDismissError: () -> Unit,
    onRegenerateMessage: (Message) -> Unit,
    onEditLastUser: (Message, String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenConversationSettings: () -> Unit,
    onShareConversation: ((ConversationShareFormat) -> Unit)?,
    onAddAttachments: (List<AttachmentInput>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onConfirmSend: () -> Unit,
    onCancelSendConfirmation: () -> Unit,
    onDismissAttachmentError: () -> Unit,
    attachmentFile: (String) -> File?,
    sendKey: SendKey,
    showTokenUsage: Boolean,
    loadRemoteImages: Boolean,
    chatFontSize: ChatFontSize,
    offline: Boolean,
    zoneId: ZoneId,
    dimensPadding: androidx.compose.ui.unit.Dp,
) {
    val dimens = LocalDimens.current
    val context = LocalContext.current
    var shareMenuExpanded by remember { mutableStateOf(false) }
    val modelLabel = state.conversation?.modelLabel
        ?: state.conversation?.modelId
        ?: "No model selected"
    val availability = state.availability
    val unavailable = availability?.takeIf { !it.available }
    val isEmpty = state.messages.isEmpty() && state.streaming == null

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { selected ->
            readAttachmentInput(context, selected)?.let { input ->
                onAddAttachments(listOf(input))
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val inputs = uris.mapNotNull { uri -> readAttachmentInput(context, uri) }
        if (inputs.isNotEmpty()) onAddAttachments(inputs)
    }

    val pasteImage = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val inputs = if (clip == null) {
            emptyList()
        } else {
            (0 until clip.itemCount).mapNotNull { index ->
                clip.getItemAt(index).uri?.let { uri -> readAttachmentInput(context, uri) }
            }
        }
        if (inputs.isNotEmpty()) onAddAttachments(inputs)
    }

    val dropTarget = remember(context, onAddAttachments) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val clip = event.toAndroidDragEvent().clipData ?: return false
                val inputs = (0 until clip.itemCount).mapNotNull { index ->
                    clip.getItemAt(index).uri?.let { uri -> readAttachmentInput(context, uri) }
                }
                if (inputs.isEmpty()) return false
                onAddAttachments(inputs)
                return true
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimensPadding, vertical = dimens.space2)
                .testTag(ChatScreenTestTags.Header),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.conversation?.title ?: "Chat",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (state.conversation != null) {
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.conversation != null && onShareConversation != null) {
                Box {
                    TextButton(
                        onClick = { shareMenuExpanded = true },
                        modifier = Modifier
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ChatScreenTestTags.Share)
                            .semantics { contentDescription = "Share conversation" },
                    ) {
                        Text("Share")
                    }
                    DropdownMenu(
                        expanded = shareMenuExpanded,
                        onDismissRequest = { shareMenuExpanded = false },
                        modifier = Modifier.testTag(ChatScreenTestTags.ShareMenu),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Share as text") },
                            onClick = {
                                shareMenuExpanded = false
                                onShareConversation(ConversationShareFormat.TEXT)
                            },
                            modifier = Modifier.testTag(ChatScreenTestTags.ShareText),
                        )
                        DropdownMenuItem(
                            text = { Text("Share as Markdown") },
                            onClick = {
                                shareMenuExpanded = false
                                onShareConversation(ConversationShareFormat.MARKDOWN)
                            },
                            modifier = Modifier.testTag(ChatScreenTestTags.ShareMarkdown),
                        )
                    }
                }
            }
            TextButton(
                onClick = onOpenConversationSettings,
                modifier = Modifier
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ChatScreenTestTags.ConversationSettings),
            ) {
                Text("Settings")
            }
            AssistChip(
                onClick = onOpenProviders,
                label = { Text(modelLabel) },
                modifier = Modifier
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ChatScreenTestTags.ModelChip),
            )
        }

        HorizontalDivider()

        unavailable?.let { availabilityState ->
            AvailabilityBanner(
                availability = availabilityState,
                onOpenProviders = onOpenProviders,
            )
        }

        state.error?.let { error ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensPadding, vertical = dimens.space2)
                    .testTag(ChatScreenTestTags.Error),
                verticalArrangement = Arrangement.spacedBy(dimens.space1),
            ) {
                Text(
                    text = error.message,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = onDismissError,
                    modifier = Modifier.heightIn(min = dimens.touchTargetMin),
                ) {
                    Text("Dismiss")
                }
            }
        }

        state.attachmentWarning?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensPadding, vertical = dimens.space1)
                    .testTag(ChatScreenTestTags.AttachmentWarning),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (isEmpty) {
                EmptyState(
                    modelLabel = modelLabel,
                    enabled = availability?.available != false,
                )
            } else {
                ChatMessageList(
                    messages = state.messages,
                    streaming = state.streaming,
                    conversation = state.conversation,
                    hasOlder = state.hasOlder,
                    loadingOlder = state.loadingOlder,
                    onLoadOlder = onLoadOlder,
                    onRetry = onRetryMessage,
                    onRegenerate = onRegenerateMessage,
                    onEditLastUser = onEditLastUser,
                    actionsEnabled = !state.isStreaming && !state.sending,
                    showTokenUsage = showTokenUsage,
                    loadRemoteImages = loadRemoteImages,
                    chatFontSize = chatFontSize,
                    zoneId = zoneId,
                    attachments = state.attachmentsByMessage,
                    attachmentFile = attachmentFile,
                )
            }
        }

        Box(
            modifier = Modifier.dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.toAndroidDragEvent().clipData != null
                },
                target = dropTarget,
            ),
        ) {
            ChatComposer(
                draft = state.draft,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                sendKey = sendKey,
                enabled = availability?.available != false,
                streaming = state.isStreaming,
                sending = state.sending,
                offline = offline,
                attachments = state.pendingAttachments,
                attachmentWarning = state.attachmentWarning,
                attachmentError = state.attachmentError,
                attachmentFile = attachmentFile,
                onAddPhoto = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onAddFile = {
                    documentPicker.launch(
                        arrayOf(
                            "text/*",
                            "application/json",
                            "application/xml",
                            "application/javascript",
                        ),
                    )
                },
                onPasteImage = pasteImage,
                onRemoveAttachment = onRemoveAttachment,
                onDismissAttachmentError = onDismissAttachmentError,
            )
        }

        ChatAnnouncement(state.announcement)
    }

    if (state.confirmSendWithUnsupportedAttachments) {
        AlertDialog(
            onDismissRequest = onCancelSendConfirmation,
            modifier = Modifier.testTag(ChatScreenTestTags.SendConfirmation),
            title = { Text("Send unsupported attachments?") },
            text = {
                Text(
                    state.attachmentWarning
                        ?: "The selected model cannot receive every attachment.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmSend,
                    modifier = Modifier.testTag(ChatScreenTestTags.SendAnyway),
                ) {
                    Text("Send anyway")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onCancelSendConfirmation,
                    modifier = Modifier.testTag(ChatScreenTestTags.CancelSend),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun AvailabilityBanner(
    availability: ChatAvailability,
    onOpenProviders: () -> Unit,
) {
    val dimens = LocalDimens.current
    val (tag, title, action) = when (availability.reason) {
        ChatUnavailableReason.NO_PROVIDER -> Triple(
            ChatScreenTestTags.NoProvider,
            "No provider is configured. Add a provider before starting a chat.",
            "Add provider",
        )

        ChatUnavailableReason.MODEL_UNAVAILABLE -> Triple(
            ChatScreenTestTags.ModelUnavailable,
            availability.message ?: "The selected model is unavailable. Choose another model.",
            "Choose another model",
        )

        ChatUnavailableReason.NO_KEY -> Triple(
            ChatScreenTestTags.NoKey,
            availability.message ?: "The API key for this provider is unavailable.",
            "Open providers",
        )

        null -> Triple(ChatScreenTestTags.Error, availability.message.orEmpty(), "Open providers")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.padding, vertical = dimens.space2)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(
            onClick = onOpenProviders,
            modifier = Modifier
                .heightIn(min = dimens.touchTargetMin)
                .testTag(ChatScreenTestTags.OpenProviders),
        ) {
            Text(action)
        }
    }
}

@Composable
private fun EmptyState(modelLabel: String, enabled: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(ChatScreenTestTags.Empty),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Start the conversation", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = if (enabled) {
                "Send a message to $modelLabel."
            } else {
                "Fix the provider or model setup before sending a message."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ChatAnnouncement(message: String) {
    Box(
        modifier = Modifier
            .size(1.dp)
            .testTag(ChatScreenTestTags.Announcement)
            .semantics {
                contentDescription = message
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * Read a content URI into an attachment input.
 *
 * The read is capped just above the image limit so a hostile provider cannot
 * exhaust memory before validation rejects the file.
 */
private fun readAttachmentInput(context: Context, uri: Uri): AttachmentInput? {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri).orEmpty()
    var name = uri.lastPathSegment ?: "attachment"
    var declaredSize = -1L

    try {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declaredSize = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (_: Exception) {
        // Fall back to the URI segment and the actual byte count.
    }

    val bytes = try {
        resolver.openInputStream(uri)?.use { readAtMost(it, MAX_IMAGE_BYTES + 1) }
    } catch (_: Exception) {
        null
    } ?: return null

    return AttachmentInput(
        name = name,
        mimeType = mimeType,
        size = if (declaredSize > 0) declaredSize else bytes.size.toLong(),
        bytes = bytes,
    )
}

private fun readAtMost(input: InputStream, limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L

    while (total < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
        if (read < 0) break
        output.write(buffer, 0, read)
        total += read
    }

    return output.toByteArray()
}
