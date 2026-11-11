package dev.chatui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.shell.ShortcutHelpDialog
import dev.chatui.shell.ShortcutHelpTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `focus returns to the trigger after a dialog closes`() {
        var open by mutableStateOf(false)
        val trigger = FocusRequester()

        composeRule.setContent {
            ChatUiTheme {
                Box {
                    Button(
                        onClick = { open = true },
                        modifier = Modifier
                            .focusRequester(trigger)
                            .testTag("trigger"),
                    ) {
                        Text("Open")
                    }
                    if (open) {
                        ShortcutHelpDialog(onDismiss = { open = false })
                    }
                }
            }
            LaunchedEffect(Unit) { trigger.requestFocus() }
        }

        composeRule.onNodeWithTag("trigger").assertIsFocused()
        composeRule.onNodeWithTag("trigger").performClick()
        composeRule.onNodeWithTag(ShortcutHelpTestTags.Close).performClick()

        composeRule.onNodeWithTag("trigger").assertIsFocused()
    }
}
