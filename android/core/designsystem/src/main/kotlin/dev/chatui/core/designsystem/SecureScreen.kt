package dev.chatui.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Marks the hosting window as secure while [content] is composed.
 *
 * `FLAG_SECURE` keeps the window out of the recents thumbnail and blocks
 * screenshots and screen recording. The flag is added when the composable
 * enters the composition and removed on dispose only when this composable was
 * the one that added it, so a caller that already set the flag keeps it.
 *
 * Use this around screens that display or accept secret material, such as API
 * key entry and provider key rotation. It is a no-op when no activity window is
 * available, for example in previews.
 */
@Composable
fun SecureScreen(content: @Composable () -> Unit) {
    val view = LocalView.current

    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val alreadySecure = window != null &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

        if (window != null && !alreadySecure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        onDispose {
            if (window != null && !alreadySecure) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    content()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
