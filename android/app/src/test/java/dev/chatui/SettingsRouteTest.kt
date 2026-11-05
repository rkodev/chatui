package dev.chatui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.shell.SettingsRoute
import dev.chatui.shell.SettingsTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings exposes the provider management entry point`() {
        var opened = 0

        composeRule.setContent {
            ChatUiTheme {
                SettingsRoute(onOpenProviders = { opened += 1 })
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.ManageProviders).performClick()

        assertThat(opened).isEqualTo(1)
    }
}
