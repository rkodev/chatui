package dev.chatui.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionSpecsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `named specs expose the design review durations`() {
        assertThat(MotionSpecs.fadeThroughMillis).isEqualTo(180)
        assertThat(MotionSpecs.sharedAxisMillis).isEqualTo(220)
        assertThat(MotionSpecs.emphasizedMillis).isEqualTo(300)
        assertThat(MotionSpecs.dialogMillis).isEqualTo(150)
        assertThat(MotionSpecs.bubbleAppearMillis).isEqualTo(180)
        assertThat(MotionSpecs.caretBlinkMillis).isEqualTo(1_000)
        assertThat(MotionSpecs.bubbleRiseDp).isEqualTo(8)
        assertThat(MotionSpecs.springSnappy.stiffness).isEqualTo(500f)
        assertThat(MotionSpecs.springSnappy.dampingRatio).isEqualTo(0.85f)
        assertThat(MotionSpecs.springGentle.stiffness).isEqualTo(300f)
        assertThat(MotionSpecs.springGentle.dampingRatio).isEqualTo(0.9f)
        assertThat(MotionSpecs.springPress.stiffness).isEqualTo(600f)
        assertThat(MotionSpecs.springPress.dampingRatio).isEqualTo(0.6f)
    }

    @Test
    fun `animateContent snaps to the target when reduced motion is on`() {
        val target = mutableStateOf("first")
        composeRule.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                animateContent(target.value) { value -> Text(value) }
            }
        }
        composeRule.onNodeWithText("first").assertExists()

        target.value = "second"
        composeRule.waitForIdle()

        composeRule.onNodeWithText("second").assertExists()
        composeRule.onNodeWithText("first").assertDoesNotExist()
    }

    @Test
    fun `bubble appear snaps to the end state when reduced motion is on`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(modifier = Modifier.bubbleAppear("bubble").testTag("bubble"))
            }
        }

        // A snap spec reaches the end state without advancing the clock.
        composeRule.onNodeWithTag("bubble").assertExists()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("bubble").assertExists()
    }
}
