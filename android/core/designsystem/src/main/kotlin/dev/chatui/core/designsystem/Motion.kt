package dev.chatui.core.designsystem

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Motion tokens from the web `--motion-*` custom properties.
 *
 * When the system animator duration scale is zero, [Reduced] sets every
 * duration to zero so callers can skip animation without branching on a
 * separate flag. Layout never depends on animation, so disabling motion cannot
 * hide content.
 */
@Immutable
data class ChatUiMotion(
    val fastMillis: Int,
    val normalMillis: Int,
    val standardEasing: Easing,
    val reducedMotion: Boolean,
) {
    companion object {
        val Standard: ChatUiMotion = ChatUiMotion(
            fastMillis = 120,
            normalMillis = 200,
            standardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
            reducedMotion = false,
        )

        val Reduced: ChatUiMotion = ChatUiMotion(
            fastMillis = 0,
            normalMillis = 0,
            standardEasing = LinearEasing,
            reducedMotion = true,
        )

        fun forReducedMotion(reducedMotion: Boolean): ChatUiMotion =
            if (reducedMotion) Reduced else Standard
    }
}

/**
 * Reads the system animator duration scale and observes changes.
 *
 * Compose 1.12 exposes [androidx.compose.ui.MotionDurationScale] as a coroutine
 * context element rather than a public composition local, so the design system
 * reads the same `Settings.Global.ANIMATOR_DURATION_SCALE` value that Compose
 * uses internally. A zero scale means the user disabled animations.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current.applicationContext
    var reducedMotion by remember(context) {
        mutableStateOf(isReducedMotion(readAnimatorDurationScale(context)))
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reducedMotion = isReducedMotion(readAnimatorDurationScale(context))
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    return reducedMotion
}

/** Pure predicate used by [rememberReducedMotion] and unit tests. */
internal fun isReducedMotion(animatorDurationScale: Float): Boolean =
    animatorDurationScale == 0f

private fun readAnimatorDurationScale(context: Context): Float =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
