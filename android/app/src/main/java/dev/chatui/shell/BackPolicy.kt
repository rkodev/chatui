package dev.chatui.shell

/**
 * Ordered back layers for the shell.
 *
 * Back always closes the topmost layer before moving to the next one. The
 * order is fixed and matches the platform window stack: dialogs and sheets are
 * separate windows, the drawer lives in the shell, the IME is owned by the
 * system, and navigation is the final fallback.
 */
enum class BackLayer {
    Dialog,
    Sheet,
    Drawer,
    Keyboard,
    Navigation,
}

/** Snapshot of the layers that can currently consume a back gesture. */
data class BackState(
    val dialogOpen: Boolean = false,
    val sheetOpen: Boolean = false,
    val drawerOpen: Boolean = false,
    val keyboardOpen: Boolean = false,
)

/** The layer that must receive back first. */
fun topmostBackLayer(state: BackState): BackLayer = when {
    state.dialogOpen -> BackLayer.Dialog
    state.sheetOpen -> BackLayer.Sheet
    state.drawerOpen -> BackLayer.Drawer
    state.keyboardOpen -> BackLayer.Keyboard
    else -> BackLayer.Navigation
}

/**
 * True when the app must handle back itself.
 *
 * The IME and navigation are owned by the platform, so the app must not
 * intercept them. Dialogs, sheets, and the drawer are app-owned layers and
 * must close before navigation runs.
 */
fun appHandlesBack(state: BackState): Boolean = when (topmostBackLayer(state)) {
    BackLayer.Dialog, BackLayer.Sheet, BackLayer.Drawer -> true
    BackLayer.Keyboard, BackLayer.Navigation -> false
}
