package dev.chatui.feature.chat

import dev.chatui.core.model.Attachment
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.Message
import dev.chatui.core.model.SendKey

/** Why the conversation cannot currently send and what to show the user. */
data class ChatAvailability(
    val available: Boolean,
    val reason: ChatUnavailableReason? = null,
    val message: String? = null,
)

/**
 * One immutable state for the chat screen.
 *
 * The ViewModel owns this state and exposes it as a `StateFlow`. Composables
 * only render it and forward intents; no UI logic lives in the state.
 */
data class ChatUiState(
    val conversationId: String? = null,
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val streaming: StreamingState? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    val hasOlder: Boolean = false,
    val availability: ChatAvailability? = null,
    val sendKey: SendKey = SendKey.ENTER,
    val showTokenUsage: Boolean = false,
    val announcement: String = "",
    val error: ChatError? = null,
    val notice: String? = null,
    val pendingAttachments: List<Attachment> = emptyList(),
    val attachmentsByMessage: Map<String, Attachment> = emptyMap(),
    val attachmentError: String? = null,
    val attachmentWarning: String? = null,
    val modelInputModalities: List<InputModality> = listOf(InputModality.TEXT),
    val confirmSendWithUnsupportedAttachments: Boolean = false,
) {
    /** True while the active stream has not reached a terminal state. */
    val isStreaming: Boolean
        get() = streaming?.done == false

    /** True when the user can stop the active response. */
    val canStop: Boolean
        get() = sending || isStreaming

    /** True when the composer may start a new response. */
    val canSend: Boolean
        get() = !sending &&
            !isStreaming &&
            (draft.isNotBlank() || pendingAttachments.isNotEmpty()) &&
            availability?.available != false

    /** Capability comparison for the pending attachments. */
    val attachmentSupport: AttachmentSupport
        get() = attachmentSupport(pendingAttachments, modelInputModalities)
}
