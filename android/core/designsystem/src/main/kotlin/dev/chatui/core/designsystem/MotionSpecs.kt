package dev.chatui.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Named motion specs shared by every screen.
 *
 * Durations mirror the design review section 4.5. Callers should prefer these
 * over ad-hoc `tween` values so the motion language stays consistent.
 */
object MotionSpecs {
    val springSnappy: SpringSpec<Float> = spring(stiffness = 500f, dampingRatio = 0.85f)
    val springGentle: SpringSpec<Float> = spring(stiffness = 300f, dampingRatio = 0.9f)

    const val fadeThroughMillis: Int = 180
    const val sharedAxisMillis: Int = 220
    const val emphasizedMillis: Int = 300

    fun <T> fadeThrough(): TweenSpec<T> =
        tween(durationMillis = fadeThroughMillis, easing = FastOutSlowInEasing)

    fun <T> sharedAxis(): TweenSpec<T> =
        tween(durationMillis = sharedAxisMillis, easing = FastOutSlowInEasing)

    fun <T> emphasized(): TweenSpec<T> =
        tween(durationMillis = emphasizedMillis, easing = FastOutSlowInEasing)
}

/**
 * Crossfades between [targetValue] states.
 *
 * When [LocalReducedMotion] is true the target content is composed directly,
 * so the end state is visible immediately and motion never gates content.
 */
@Composable
fun <T> animateContent(
    targetValue: T,
    modifier: Modifier = Modifier,
    label: String = "animateContent",
    content: @Composable (T) -> Unit,
) {
    if (LocalReducedMotion.current) {
        content(targetValue)
    } else {
        AnimatedContent(
            targetState = targetValue,
            modifier = modifier,
            transitionSpec = {
                fadeIn(animationSpec = tween(MotionSpecs.fadeThroughMillis)) togetherWith
                    fadeOut(animationSpec = tween(MotionSpecs.fadeThroughMillis))
            },
            label = label,
        ) { value -> content(value) }
    }
}
