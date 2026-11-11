package dev.chatui.core.designsystem

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HapticsTest {
    @Test
    fun `all effects are no-ops when disabled`() {
        val feedback = RecordingHapticFeedback()
        val haptics = Haptics(feedback, enabled = false)

        haptics.tick()
        haptics.confirm()
        haptics.reject()
        haptics.longPress()

        assertThat(feedback.types).isEmpty()
    }

    @Test
    fun `each effect maps to a haptic type when enabled`() {
        val feedback = RecordingHapticFeedback()
        val haptics = Haptics(feedback, enabled = true)

        haptics.tick()
        haptics.confirm()
        haptics.reject()
        haptics.longPress()

        assertThat(feedback.types).containsExactly(
            HapticFeedbackType.SegmentTick,
            HapticFeedbackType.Confirm,
            HapticFeedbackType.Reject,
            HapticFeedbackType.LongPress,
        ).inOrder()
    }

    private class RecordingHapticFeedback : HapticFeedback {
        val types = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            types += hapticFeedbackType
        }
    }
}
