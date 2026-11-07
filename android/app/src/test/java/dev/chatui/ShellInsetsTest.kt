package dev.chatui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.ShellScaffold
import dev.chatui.shell.ShellTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ShellInsetsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shell applies safe drawing insets once to the content`() {
        setShell(WindowInsets(16.dp, 24.dp, 16.dp, 48.dp))

        val main = composeRule
            .onNodeWithTag(ShellTestTags.MainContent)
            .getUnclippedBoundsInRoot()

        assertThat(main.left.value).isWithin(0.5f).of(16f)
        assertThat(main.right.value).isWithin(0.5f).of(411f - 16f)
        assertThat(main.bottom.value).isWithin(0.5f).of(891f - 48f)
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun `expanded layout applies insets to the sidebar and content`() {
        setShell(WindowInsets(16.dp, 24.dp, 16.dp, 48.dp))

        val main = composeRule
            .onNodeWithTag(ShellTestTags.MainContent)
            .getUnclippedBoundsInRoot()
        val sidebar = composeRule
            .onNodeWithTag(ShellTestTags.Sidebar)
            .getUnclippedBoundsInRoot()

        assertThat(sidebar.left.value).isWithin(0.5f).of(16f)
        assertThat(main.right.value).isWithin(0.5f).of(840f - 16f)
        assertThat(main.bottom.value).isWithin(0.5f).of(1280f - 48f)
    }

    @Test
    fun `drawer does not add a second set of insets`() {
        setShell(WindowInsets(16.dp, 24.dp, 16.dp, 48.dp))

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).performClick()

        val main = composeRule
            .onNodeWithTag(ShellTestTags.MainContent)
            .getUnclippedBoundsInRoot()
        val sidebar = composeRule
            .onNodeWithTag(ShellTestTags.Sidebar)
            .getUnclippedBoundsInRoot()

        // The drawer lives inside the already-inset content area, so its top
        // must match the content top rather than adding the status-bar inset
        // again.
        assertThat(sidebar.top.value).isWithin(0.5f).of(main.top.value)
    }

    private fun setShell(windowInsets: WindowInsets) {
        composeRule.setContent {
            ChatUiTheme {
                ShellScaffold(
                    navController = rememberNavController(),
                    startDestination = ShellDestination.Conversations.route,
                    conversationService = FakeConversationService(),
                    windowInsets = windowInsets,
                )
            }
        }
    }
}
