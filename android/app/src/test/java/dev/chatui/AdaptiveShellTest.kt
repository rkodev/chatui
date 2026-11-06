package dev.chatui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
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
@Config(sdk = [34])
class AdaptiveShellTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `compact width uses a modal drawer`() {
        setShell()

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.Drawer).assertExists()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsNotDisplayed()

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).performClick()

        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w839dp-h1280dp")
    fun `839 dp stays on the compact path`() {
        setShell()

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.Drawer).assertExists()
        composeRule.onNodeWithTag(ShellTestTags.ExpandedLayout).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun `840 dp shows the permanent sidebar`() {
        setShell()

        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.ExpandedLayout).assertExists()
        composeRule.onNodeWithTag(ShellTestTags.MenuButton).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.Drawer).assertDoesNotExist()
    }

    private fun setShell() {
        composeRule.setContent {
            ChatUiTheme {
                ShellScaffold(
                    navController = rememberNavController(),
                    startDestination = ShellDestination.Conversations.route,
                    conversationService = FakeConversationService(),
                )
            }
        }
    }
}
