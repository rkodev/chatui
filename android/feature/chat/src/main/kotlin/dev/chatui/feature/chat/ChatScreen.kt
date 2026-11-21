package dev.chatui.feature.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.chatui.core.database.attachment.AttachmentInput
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.IconAction
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.PrimaryButton
import dev.chatui.core.designsystem.Skeleton
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.SendKey
import java.io.File
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
    const val More = "chat-screen-more"
    const val MoreMenu = "chat-screen-more-menu"
    const val AttachmentWarning = "chat-screen-attachment-warning"
    const val SendConfirmation = "chat-screen-send-confirmation"
    const val SendAnyway = "chat-screen-send-anyway"
    const val CancelSend = "chat-screen-cancel-send"
    const val EmptyModelSelector = "chat-screen-empty-model-selector"

    fun emptyPrompt(index: Int): String = "chat-screen-empty-prompt-$index"
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
    onOpenModelPicker: () -> Unit = {},
    onIngestAttachment: (suspend (AttachmentInput) -> Unit)? = null,
) {
    val dimens = LocalDimens.current
    val shortcutState = LocalChatShortcutState.current
    val haptics = rememberHaptics()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val lastAssistant = state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val copyLastResponse: (() -> Unit)? = lastAssistant?.let { message ->
        {
            clipboard.setText(AnnotatedString(message.copyableText()))
            haptics.confirm()
        }
    }

    // Publish the conversation title, model label, and header actions to the
    // shell-owned app bar. The callbacks are wrapped in stable lambdas so the
    // state update does not trigger a recomposition loop.
    val topBarState = LocalChatTopBarState.current
    val currentOnShare by rememberUpdatedState(onShareConversation)
    val currentOnSettings by rememberUpdatedState(onOpenConversationSettings)
    val currentOnModelPicker by rememberUpdatedState(onOpenModelPicker)
    val shareCallback = remember {
        { format: ConversationShareFormat ->
            currentOnShare?.invoke(format)
            Unit
        }
    }
    val settingsCallback = remember { { currentOnSettings() } }
    val modelPickerCallback = remember { { currentOnModelPicker() } }
    val modelLabel = state.conversation?.modelLabel
        ?.takeIf { it.isNotBlank() }
        ?: state.conversation?.modelId
    SideEffect {
        topBarState?.update(
            title = state.conversation?.title,
            subtitle = modelLabel,
            onShare = if (state.conversation != null && onShareConversation != null) {
                shareCallback
            } else {
                null
            },
            onOpenSettings = if (state.conversation != null) settingsCallback else null,
            onOpenModelPicker = if (state.conversation != null) modelPickerCallback else null,
        )
    }
    DisposableEffect(topBarState) {
        onDispose { topBarState?.clear() }
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
                onOpenModelPicker = onOpenModelPicker,
                onOpenConversationSettings = onOpenConversationSettings,
                onShareConversation = onShareConversation,
                onAddAttachments = onAddAttachments,
                onIngestAttachment = onIngestAttachment,
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
                hostedInShell = topBarState != null,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    val dimens = LocalDimens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimens.screenPadding)
            .testTag(ChatScreenTestTags.Loading),
        verticalArrangement = Arrangement.spacedBy(dimens.space4),
    ) {
        repeat(4) { index ->
            val userBubble = index % 2 == 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (userBubble) {
                    Arrangement.End
                } else {
                    Arrangement.Start
                },
            ) {
                Skeleton(
                    modifier = Modifier
                        .fillMaxWidth(if (userBubble) 0.55f else 0.75f)
                        .height(if (userBubble) 44.dp else 72.dp),
                )
            }
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
    onOpenModelPicker: () -> Unit,
    onOpenConversationSettings: () -> Unit,
    onShareConversation: ((ConversationShareFormat) -> Unit)?,
    onAddAttachments: (List<AttachmentInput>) -> Unit,
    onIngestAttachment: (suspend (AttachmentInput) -> Unit)?,
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
    hostedInShell: Boolean,
) {
    val dimens = LocalDimens.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val ownerKey = state.conversationId ?: state.conversation?.id ?: "unpersisted"
    val ingestion = remember(ownerKey, context.applicationContext) {
        ChatAttachmentIngestion(
            scope = scope,
            read = { uri -> readAttachmentInput(context.applicationContext, uri) },
            onInput = { input ->
                if (onIngestAttachment != null) {
                    onIngestAttachment(input)
                } else {
                    onAddAttachments(listOf(input))
                }
            },
        )
    }
    DisposableEffect(ingestion) {
        onDispose { ingestion.close() }
    }
    var photoRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var documentRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val moreFocus = remember { FocusRequester() }
    val modelLabel = state.conversation?.modelLabel
        ?: state.conversation?.modelId
        ?: "No model selected"
    val availability = state.availability
    val unavailable = availability?.takeIf { !it.available }
    val isEmpty = state.messages.isEmpty() && state.streaming == null
    val globalError = state.error?.takeUnless { error ->
        state.messages.any { it.error == error } || state.streaming?.error == error ||
            (error.code != ChatErrorCode.STORAGE && unavailable?.message == error.message)
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val requestedId = photoRequestId
        photoRequestId = null
        if (requestedId == ownerKey && uri != null) ingestion.submit(listOf(uri))
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val requestedId = documentRequestId
        documentRequestId = null
        if (requestedId == ownerKey) ingestion.submit(uris)
    }

    val pasteImage = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ingestion.submit(attachmentUris(clipboard?.primaryClip))
    }

    val dropTarget = remember(ingestion, activity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val androidEvent = event.toAndroidDragEvent()
                val uris = attachmentUris(androidEvent.clipData)
                if (uris.isEmpty()) return false
                val permission = activity?.requestDragAndDropPermissions(androidEvent)
                ingestion.submit(uris, onFinished = { permission?.release() })
                return true
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bannerMaxHeight = maxHeight * 0.3f
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // When the shell hosts the screen, the conversation title, model, and
            // share/settings actions live in the app bar. The standalone fallback
            // keeps the header for previews and direct composition tests.
            if (!hostedInShell) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimensPadding, vertical = dimens.space2)
                        .testTag(ChatScreenTestTags.Header),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = dimens.touchTargetMin)
                            .clickable(onClick = onOpenModelPicker)
                            .testTag(ChatScreenTestTags.ModelChip)
                            .semantics(mergeDescendants = true) { contentDescription = "Change model" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.conversation?.title ?: "Chat",
                                style = Typography.title3,
                            )
                            if (state.conversation != null) {
                                Text(
                                    text = modelLabel,
                                    style = Typography.footnote,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(Icons.Rounded.ExpandMore, contentDescription = null)
                    }
                    Box {
                        IconAction(
                            icon = Icons.Rounded.MoreVert,
                            contentDescription = "More",
                            onClick = { moreMenuExpanded = true },
                            modifier = Modifier.focusRequester(moreFocus).testTag(ChatScreenTestTags.More),
                        )
                        val dismiss = {
                            moreMenuExpanded = false
                            moreFocus.requestFocus()
                            Unit
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = dismiss,
                            modifier = Modifier.testTag(ChatScreenTestTags.MoreMenu),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Conversation settings") },
                                onClick = { dismiss(); onOpenConversationSettings() },
                                modifier = Modifier.testTag(ChatScreenTestTags.ConversationSettings),
                            )
                            if (state.conversation != null && onShareConversation != null) {
                                DropdownMenuItem(
                                    text = { Text("Share as text") },
                                    onClick = {
                                        dismiss()
                                        onShareConversation(ConversationShareFormat.TEXT)
                                    },
                                    modifier = Modifier.testTag(ChatScreenTestTags.ShareText),
                                )
                                DropdownMenuItem(
                                    text = { Text("Share as Markdown") },
                                    onClick = {
                                        dismiss()
                                        onShareConversation(ConversationShareFormat.MARKDOWN)
                                    },
                                    modifier = Modifier.testTag(ChatScreenTestTags.ShareMarkdown),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
            }

            if (unavailable != null || globalError != null) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .heightIn(max = bannerMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    unavailable?.let { availabilityState ->
                        AvailabilityBanner(
                            availability = availabilityState,
                            onOpenProviders = onOpenProviders,
                            onOpenModelPicker = onOpenModelPicker,
                        )
                    }

                    globalError?.let { error ->
                        InlineBanner(
                            severity = BannerSeverity.Error,
                            title = "Something went wrong",
                            body = error.message,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = dimensPadding, vertical = dimens.space2)
                                .testTag(ChatScreenTestTags.Error),
                            action = {
                                TextButton(
                                    onClick = onDismissError,
                                    modifier = Modifier.heightIn(min = dimens.touchTargetMin),
                                ) {
                                    Text("Dismiss")
                                }
                            },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isEmpty) {
                    EmptyState(
                        onPromptSelected = onDraftChange,
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
                    attachmentError = ingestion.error ?: state.attachmentError,
                    attachmentFile = attachmentFile,
                    onAddPhoto = {
                        if (photoRequestId == null) {
                            photoRequestId = ownerKey
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    },
                    onAddFile = {
                        if (documentRequestId == null) {
                            documentRequestId = ownerKey
                            documentPicker.launch(
                                arrayOf(
                                    "text/*",
                                    "application/json",
                                    "application/xml",
                                    "application/javascript",
                                ),
                            )
                        }
                    },
                    onPasteImage = pasteImage,
                    onRemoveAttachment = onRemoveAttachment,
                    onDismissAttachmentError = {
                        ingestion.dismissError()
                        onDismissAttachmentError()
                    },
                    processingAttachments = ingestion.processing || state.processingAttachments,
                )
            }

            ChatAnnouncement(state.announcement)
        }
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
    onOpenModelPicker: () -> Unit,
) {
    val dimens = LocalDimens.current
    val (tag, title, body, action) = when (availability.reason) {
        ChatUnavailableReason.NO_PROVIDER -> AvailabilityCopy(
            tag = ChatScreenTestTags.NoProvider,
            title = "No provider configured",
            body = "Add a provider before starting a chat.",
            action = "Add provider",
        )

        ChatUnavailableReason.MODEL_UNAVAILABLE -> AvailabilityCopy(
            tag = ChatScreenTestTags.ModelUnavailable,
            title = "Model unavailable",
            body = availability.message
                ?: "The selected model is unavailable. Choose another model.",
            action = "Choose another model",
        )

        ChatUnavailableReason.NO_KEY -> AvailabilityCopy(
            tag = ChatScreenTestTags.NoKey,
            title = "API key needed",
            body = availability.message ?: "The API key for this provider is unavailable.",
            action = "Open providers",
        )

        null -> AvailabilityCopy(
            tag = ChatScreenTestTags.Error,
            title = "Chat unavailable",
            body = availability.message.orEmpty(),
            action = "Open providers",
        )
    }

    InlineBanner(
        severity = BannerSeverity.Warning,
        title = title,
        body = body,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.padding, vertical = dimens.space2)
            .testTag(tag),
        action = {
            PrimaryButton(
                text = action,
                onClick = if (availability.reason == ChatUnavailableReason.MODEL_UNAVAILABLE) {
                    onOpenModelPicker
                } else {
                    onOpenProviders
                },
                modifier = Modifier.testTag(ChatScreenTestTags.OpenProviders),
            )
        },
    )
}

private data class AvailabilityCopy(
    val tag: String,
    val title: String,
    val body: String,
    val action: String,
)

private val SUGGESTED_PROMPTS = listOf(
    "Summarize a document",
    "Brainstorm ideas",
    "Explain a concept",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    onPromptSelected: (String) -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ChatScreenTestTags.Empty),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Text(
                text = "How can I help?",
                style = Typography.title2,
                color = colors.fgPrimary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
                verticalArrangement = Arrangement.spacedBy(dimens.space2),
            ) {
                SUGGESTED_PROMPTS.forEachIndexed { index, prompt ->
                    AssistChip(
                        onClick = { onPromptSelected(prompt) },
                        label = { Text(prompt) },
                        modifier = Modifier
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ChatScreenTestTags.emptyPrompt(index)),
                    )
                }
            }
        }
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
