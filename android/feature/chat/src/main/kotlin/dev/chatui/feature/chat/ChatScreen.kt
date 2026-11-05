package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.Message
import dev.chatui.core.model.SendKey
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
    sendKey: SendKey = SendKey.ENTER,
    showTokenUsage: Boolean = false,
    loadRemoteImages: Boolean = false,
    chatFontSize: ChatFontSize = ChatFontSize.MD,
    offline: Boolean = false,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val dimens = LocalDimens.current

    Surface(
        modifier = modifier
            .fillMaxSize()
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
    sendKey: SendKey,
    showTokenUsage: Boolean,
    loadRemoteImages: Boolean,
    chatFontSize: ChatFontSize,
    offline: Boolean,
    zoneId: ZoneId,
    dimensPadding: androidx.compose.ui.unit.Dp,
) {
    val dimens = LocalDimens.current
    val modelLabel = state.conversation?.modelLabel
        ?: state.conversation?.modelId
        ?: "No model selected"
    val availability = state.availability
    val unavailable = availability?.takeIf { !it.available }
    val isEmpty = state.messages.isEmpty() && state.streaming == null

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
                )
            }
        }

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
        )

        ChatAnnouncement(state.announcement)
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
