package dev.chatui.shell

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Actions reachable from a physical keyboard.
 *
 * The map is intentionally small and matches the Android shortcut map in
 * M3-03.4. Web and Rust desktop shortcuts are separate implementations.
 */
enum class ShortcutAction {
    Search,
    NewChat,
    StopOrClose,
    CopyLastResponse,
    PreviousConversation,
    NextConversation,
}

/** Modifier state for one key event. */
data class ShortcutModifiers(
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    /** True when the platform primary modifier (Ctrl or Cmd) is held. */
    val primary: Boolean
        get() = ctrl || meta
}

/**
 * Map a key event to a shell action.
 *
 * Only explicit modifier combinations are matched. Plain character keys and
 * unmodified navigation keys return null so text fields keep their input.
 */
fun shortcutFor(key: Key, modifiers: ShortcutModifiers): ShortcutAction? = when {
    key == Key.K && modifiers.primary && !modifiers.shift && !modifiers.alt ->
        ShortcutAction.Search

    key == Key.O && modifiers.primary && modifiers.shift && !modifiers.alt ->
        ShortcutAction.NewChat

    key == Key.C && modifiers.primary && modifiers.shift && !modifiers.alt ->
        ShortcutAction.CopyLastResponse

    key == Key.Escape && !modifiers.primary && !modifiers.shift && !modifiers.alt ->
        ShortcutAction.StopOrClose

    key == Key.DirectionUp && modifiers.alt && !modifiers.primary && !modifiers.shift ->
        ShortcutAction.PreviousConversation

    key == Key.DirectionDown && modifiers.alt && !modifiers.primary && !modifiers.shift ->
        ShortcutAction.NextConversation

    else -> null
}

/** Modifier state read from a Compose key event. */
fun KeyEvent.shortcutModifiers(): ShortcutModifiers = ShortcutModifiers(
    ctrl = isCtrlPressed,
    shift = isShiftPressed,
    alt = isAltPressed,
    meta = isMetaPressed,
)

/**
 * True when a shortcut may run.
 *
 * Dialogs and sheets are separate windows and normally receive keys first. The
 * shell also refuses shortcuts while an app-owned overlay is open so a
 * shortcut can never act behind a confirmation.
 */
fun shortcutAllowed(overlayOpen: Boolean): Boolean = !overlayOpen

/** One row in the in-app shortcut help. */
data class ShortcutHelpEntry(
    val keys: String,
    val action: String,
)

/** Android hardware-keyboard shortcut map shown in the help dialog. */
val AndroidShortcutHelp: List<ShortcutHelpEntry> = listOf(
    ShortcutHelpEntry("Ctrl + K", "Focus or open conversation search"),
    ShortcutHelpEntry("Ctrl + Shift + O", "New chat"),
    ShortcutHelpEntry("Esc", "Stop streaming or close the top overlay"),
    ShortcutHelpEntry("Ctrl + Shift + C", "Copy the last assistant response"),
    ShortcutHelpEntry("Alt + Up / Down", "Previous or next conversation"),
    ShortcutHelpEntry("Ctrl + Enter", "Send when the composer uses Mod+Enter"),
)

/**
 * Callbacks used by the root key handler.
 *
 * The shell builds the production instance from its navigation controller and
 * the active chat bridge. Tests pass a recording instance to prove each
 * shortcut reaches the intended action.
 */
class ShellShortcutActions(
    val overlayOpen: () -> Boolean = { false },
    val onSearch: () -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onStopOrClose: () -> Boolean = { false },
    val onCopyLastResponse: () -> Boolean = { false },
    val onPreviousConversation: () -> Boolean = { false },
    val onNextConversation: () -> Boolean = { false },
) {
    /** Run [action] and report whether the key event was consumed. */
    fun dispatch(action: ShortcutAction): Boolean = when (action) {
        ShortcutAction.Search -> {
            onSearch()
            true
        }

        ShortcutAction.NewChat -> {
            onNewChat()
            true
        }

        ShortcutAction.StopOrClose -> onStopOrClose()
        ShortcutAction.CopyLastResponse -> onCopyLastResponse()
        ShortcutAction.PreviousConversation -> onPreviousConversation()
        ShortcutAction.NextConversation -> onNextConversation()
    }
}

/**
 * Root key handler for the shell.
 *
 * The host is focusable and takes initial focus so shortcuts work before the
 * user touches a field. When a text field gains focus it stays in the focus
 * path, so preview events still reach this handler while character keys pass
 * through untouched.
 */
@Composable
fun Modifier.shellShortcutHandler(actions: ShellShortcutActions): Modifier {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    return this
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (!shortcutAllowed(actions.overlayOpen())) return@onPreviewKeyEvent false

            val action = shortcutFor(event.key, event.shortcutModifiers())
                ?: return@onPreviewKeyEvent false

            actions.dispatch(action)
        }
}
