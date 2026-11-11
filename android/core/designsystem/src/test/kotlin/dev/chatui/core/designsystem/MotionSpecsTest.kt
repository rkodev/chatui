package dev.chatui.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
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
        assertThat(MotionSpecs.springSnappy.stiffness).isEqualTo(500f)
        assertThat(MotionSpecs.springSnappy.dampingRatio).isEqualTo(0.85f)
        assertThat(MotionSpecs.springGentle.stiffness).isEqualTo(300f)
        assertThat(MotionSpecs.springGentle.dampingRatio).isEqualTo(0.9f)
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
}
