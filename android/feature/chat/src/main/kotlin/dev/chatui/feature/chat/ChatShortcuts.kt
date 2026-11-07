package dev.chatui.feature.chat

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bridge between the shell's root key handler and the active chat screen.
 *
 * The shell owns the key handler but the chat screen owns streaming and message
 * state. The screen registers its existing callbacks here so shortcuts route
 * through the same actions as the on-screen controls instead of duplicating
 * them.
 */
class ChatShortcutState {
    /** True while the active response can be stopped. */
    var streaming: Boolean by mutableStateOf(false)
        private set

    /** True while an app-owned chat overlay (for example a dialog) is open. */
    var overlayOpen: Boolean by mutableStateOf(false)
        private set

    private var stopHandler: (() -> Unit)? = null
    private var copyHandler: (() -> Unit)? = null

    /** Replace the callbacks for the currently composed chat screen. */
    fun update(
        streaming: Boolean,
        overlayOpen: Boolean,
        onStop: (() -> Unit)?,
        onCopyLastResponse: (() -> Unit)?,
    ) {
        this.streaming = streaming
        this.overlayOpen = overlayOpen
        stopHandler = onStop
        copyHandler = onCopyLastResponse
    }

    /** Drop callbacks when the chat screen leaves the composition. */
    fun clear() {
        streaming = false
        overlayOpen = false
        stopHandler = null
        copyHandler = null
    }

    /** Stop the active response. Returns false when no response is active. */
    fun stop(): Boolean {
        if (!streaming) return false
        val handler = stopHandler ?: return false
        handler()
        return true
    }

    /** Copy the last assistant response. Returns false when none is available. */
    fun copyLastResponse(): Boolean {
        val handler = copyHandler ?: return false
        handler()
        return true
    }
}

/** The shell-provided chat shortcut bridge, or null outside the shell. */
val LocalChatShortcutState = compositionLocalOf<ChatShortcutState?> { null }
