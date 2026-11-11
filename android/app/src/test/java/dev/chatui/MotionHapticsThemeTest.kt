package dev.chatui

import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.designsystem.LocalHaptics
import dev.chatui.core.designsystem.LocalReducedMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionHapticsThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `theme exposes reduced motion and the haptics setting`() {
        var reducedMotion: Boolean? = null
        var hapticsEnabled: Boolean? = null

        composeRule.setContent {
            ChatUiTheme(reducedMotion = true, hapticsEnabled = false) {
                reducedMotion = LocalReducedMotion.current
                hapticsEnabled = LocalHaptics.current
            }
        }

        composeRule.runOnIdle {
            assertThat(reducedMotion).isTrue()
            assertThat(hapticsEnabled).isFalse()
        }
    }
}
