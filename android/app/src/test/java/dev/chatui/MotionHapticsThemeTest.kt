package dev.chatui

import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.designsystem.LocalHaptics
import dev.chatui.core.designsystem.LocalReducedMotion
import dev.chatui.core.designsystem.LocalIsDarkTheme
import dev.chatui.core.designsystem.LocalDynamicColorApplied
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LightColors
import dev.chatui.core.model.Theme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    @Test
    @Config(qualifiers = "notnight")
    fun `explicit dark app theme is exposed on a light system and can change`() {
        var theme by mutableStateOf(Theme.DARK)
        var dark = false
        composeRule.setContent {
            ChatUiTheme(themeMode = theme) { dark = LocalIsDarkTheme.current }
        }
        composeRule.runOnIdle {
            assertThat(dark).isTrue()
            theme = Theme.LIGHT
        }
        composeRule.runOnIdle { assertThat(dark).isFalse() }
    }

    @Test
    @Config(qualifiers = "night")
    fun `explicit light app theme is exposed on a dark system`() {
        var dark = true
        composeRule.setContent {
            ChatUiTheme(themeMode = Theme.LIGHT) { dark = LocalIsDarkTheme.current }
        }
        composeRule.runOnIdle { assertThat(dark).isFalse() }
    }

    @Test
    @Config(sdk = [28], qualifiers = "notnight")
    fun `requested dynamic color falls back without changing the app theme or haptics`() {
        var dynamic = true
        var palette = LightColors
        var haptics = true
        composeRule.setContent {
            ChatUiTheme(themeMode = Theme.LIGHT, dynamicColor = true, hapticsEnabled = false) {
                dynamic = LocalDynamicColorApplied.current
                palette = LocalChatUiColors.current
                haptics = LocalHaptics.current
            }
        }
        composeRule.runOnIdle {
            assertThat(dynamic).isFalse()
            assertThat(palette).isEqualTo(LightColors)
            assertThat(haptics).isFalse()
        }
    }
}
