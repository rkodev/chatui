package dev.chatui.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Named motion specs shared by every screen.
 *
 * Durations mirror the design review section 12. Callers should prefer these
 * over ad-hoc `tween` values so the motion language stays consistent.
 */
object MotionSpecs {
    val springSnappy: SpringSpec<Float> = spring(stiffness = 500f, dampingRatio = 0.85f)
    val springGentle: SpringSpec<Float> = spring(stiffness = 300f, dampingRatio = 0.9f)

    /** Gentle spring for size changes such as `animateContentSize`. */
    val springGentleSize: SpringSpec<IntSize> = spring(stiffness = 300f, dampingRatio = 0.9f)

    /** Spring used for press feedback on selection and toggle controls. */
    val springPress: SpringSpec<Float> = spring(stiffness = 600f, dampingRatio = 0.6f)

    const val fadeThroughMillis: Int = 180
    const val sharedAxisMillis: Int = 220
    const val emphasizedMillis: Int = 300
    const val dialogMillis: Int = 150
    const val bubbleAppearMillis: Int = 180
    const val snackbarMillis: Int = 200

    /** Full blink period for the streaming caret. */
    const val caretBlinkMillis: Int = 1_000

    /** Distance a new bubble rises while fading in. */
    const val bubbleRiseDp: Int = 8

    fun <T> fadeThrough(): TweenSpec<T> =
        tween(durationMillis = fadeThroughMillis, easing = FastOutSlowInEasing)

    fun <T> sharedAxis(): TweenSpec<T> =
        tween(durationMillis = sharedAxisMillis, easing = FastOutSlowInEasing)

    fun <T> emphasized(): TweenSpec<T> =
        tween(durationMillis = emphasizedMillis, easing = FastOutSlowInEasing)

    fun <T> dialog(): TweenSpec<T> =
        tween(durationMillis = dialogMillis, easing = FastOutSlowInEasing)

    fun <T> bubbleAppear(): TweenSpec<T> =
        tween(durationMillis = bubbleAppearMillis, easing = FastOutSlowInEasing)
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

/**
 * Spring press feedback for selection and toggle controls.
 *
 * The control scales to [pressedScale] while pressed and springs back on
 * release. Under reduced motion the scale snaps so the end state is immediate.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.96f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (reduced) snap() else MotionSpecs.springPress,
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Fade and rise a newly composed bubble.
 *
 * [key] scopes the animation to one item so scrolling a list does not replay
 * it. Under reduced motion the bubble is composed at its end state.
 */
@Composable
fun Modifier.bubbleAppear(key: Any?): Modifier {
    val reduced = LocalReducedMotion.current
    var visible by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(key) { visible = true }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (reduced) snap() else MotionSpecs.bubbleAppear(),
        label = "bubbleAppear",
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * MotionSpecs.bubbleRiseDp.dp.toPx()
    }
}
