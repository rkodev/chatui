package dev.chatui.feature.chat

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Chat-owned app-bar content published to the shell.
 *
 * The shell owns the top bar, but the chat screen owns the conversation title,
 * model label, and share/settings actions. The screen writes this state and the
 * shell reads it to render the bar. When no state is provided (standalone
 * previews and tests), the screen renders its own compact header instead.
 *
 * Callbacks are expected to be stable (see `rememberUpdatedState` in
 * [ChatScreen]) so updating the state does not trigger a recomposition loop.
 */
class ChatTopBarState {
    var title by mutableStateOf<String?>(null)
        private set
    var subtitle by mutableStateOf<String?>(null)
        private set
    var onShare: ((ConversationShareFormat) -> Unit)? by mutableStateOf(null)
        private set
    var onOpenSettings: (() -> Unit)? by mutableStateOf(null)
        private set
    var onOpenModelPicker: (() -> Unit)? by mutableStateOf(null)
        private set

    /** Updates the published bar content, skipping no-op writes. */
    fun update(
        title: String?,
        subtitle: String?,
        onShare: ((ConversationShareFormat) -> Unit)?,
        onOpenSettings: (() -> Unit)?,
        onOpenModelPicker: (() -> Unit)?,
    ) {
        if (this.title != title) this.title = title
        if (this.subtitle != subtitle) this.subtitle = subtitle
        if (this.onShare !== onShare) this.onShare = onShare
        if (this.onOpenSettings !== onOpenSettings) this.onOpenSettings = onOpenSettings
        if (this.onOpenModelPicker !== onOpenModelPicker) {
            this.onOpenModelPicker = onOpenModelPicker
        }
    }

    /** Clears the published content when the chat screen leaves composition. */
    fun clear() {
        title = null
        subtitle = null
        onShare = null
        onOpenSettings = null
        onOpenModelPicker = null
    }
}

/** Provides the shell-owned [ChatTopBarState] to the chat screen. */
val LocalChatTopBarState = compositionLocalOf<ChatTopBarState?> { null }
