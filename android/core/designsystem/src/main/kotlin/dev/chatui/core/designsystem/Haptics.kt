package dev.chatui.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Small semantic wrapper around [HapticFeedback].
 *
 * Every method is a no-op when the persisted haptic setting is off, so callers
 * never need to branch on the setting. The class is stateless and cheap to
 * remember.
 */
@Stable
class Haptics internal constructor(
    private val feedback: HapticFeedback,
    private val enabled: Boolean,
) {
    /** Light tick for selection and toggles. */
    fun tick() = perform(HapticFeedbackType.SegmentTick)

    /** Confirmation for send, copy, and save. */
    fun confirm() = perform(HapticFeedbackType.Confirm)

    /** Rejection for validation failures. */
    fun reject() = perform(HapticFeedbackType.Reject)

    /** Long-press acknowledgement for message actions. */
    fun longPress() = perform(HapticFeedbackType.LongPress)

    private fun perform(type: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(type)
    }
}

/** Returns a [Haptics] helper gated by [LocalHaptics]. */
@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHaptics.current
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}
