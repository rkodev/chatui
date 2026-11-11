package dev.chatui.core.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HapticsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rememberHaptics is a no-op when the setting is off`() {
        val feedback = RecordingHapticFeedback()
        var haptics: Haptics? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalHaptics provides false,
                LocalHapticFeedback provides feedback,
            ) {
                haptics = rememberHaptics()
            }
        }

        composeRule.runOnIdle {
            haptics?.tick()
            haptics?.confirm()
            haptics?.reject()
            haptics?.longPress()
        }

        assertThat(feedback.types).isEmpty()
    }

    @Test
    fun `rememberHaptics fires when the setting is on`() {
        val feedback = RecordingHapticFeedback()
        var haptics: Haptics? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalHaptics provides true,
                LocalHapticFeedback provides feedback,
            ) {
                haptics = rememberHaptics()
            }
        }

        composeRule.runOnIdle { haptics?.confirm() }

        assertThat(feedback.types).containsExactly(HapticFeedbackType.Confirm)
    }

    private class RecordingHapticFeedback : HapticFeedback {
        val types = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            types += hapticFeedbackType
        }
    }
}
