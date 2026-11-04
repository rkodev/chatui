package dev.chatui.feature.chat

import dev.chatui.core.model.ChatError
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message

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
    val error: ChatError? = null,
    val notice: String? = null,
) {
    /** True while the active stream has not reached a terminal state. */
    val isStreaming: Boolean
        get() = streaming?.done == false

    /** True when the composer may start a new response. */
    val canSend: Boolean
        get() = !sending && !isStreaming && draft.isNotBlank()
}
