package dev.chatui.core.designsystem

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Shared snackbar host state.
 *
 * The shell provides one state for the whole app so transient results from any
 * screen land in the same host. Screens fall back to a local state when they
 * are rendered outside the shell (tests, previews, screenshots).
 */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }

/** Consistent snackbar host with action support. */
@Composable
fun ChatUiSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatUiColors.current
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = colors.bgSurfaceRaised,
            contentColor = colors.fgPrimary,
            actionColor = colors.accent,
            dismissActionContentColor = colors.fgSecondary,
        )
    }
}
