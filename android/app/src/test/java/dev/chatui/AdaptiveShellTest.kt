package dev.chatui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import dev.chatui.feature.conversations.ConversationListTestTags
import androidx.navigation.compose.rememberNavController
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.shell.AdaptiveShell
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
        setShell(start = ShellDestination.Chat.route)

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.Drawer).assertExists()
        composeRule.onNodeWithTag(ConversationListTestTags.Screen).assertDoesNotExist()

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).performClick()

        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w839dp-h1280dp")
    fun `839 dp stays on the compact path`() {
        setShell()

        composeRule.onNodeWithTag(ConversationListTestTags.Screen).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.ConversationsPlaceholder).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.Drawer).assertDoesNotExist()
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

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun `isolated layout hides the permanent sidebar`() {
        composeRule.setContent {
            ChatUiTheme {
                AdaptiveShell(
                    expanded = true,
                    sidebarVisible = false,
                    sidebar = { Text("Navigation") },
                    mainContent = { Text("Onboarding") },
                )
            }
        }

        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.MainContent).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `isolated layout hides the modal drawer`() {
        composeRule.setContent {
            ChatUiTheme {
                AdaptiveShell(
                    expanded = false,
                    sidebarVisible = false,
                    sidebar = { Text("Navigation") },
                    mainContent = { Text("Onboarding") },
                )
            }
        }

        composeRule.onNodeWithTag(ShellTestTags.Drawer).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.MainContent).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun `compact chats is real and has one history collector and one new chat`() {
        val service = FakeConversationService(
            conversations = listOf(fakeConversation(title = "Invented history")),
        )
        setShell(service = service)

        composeRule.onNodeWithText("Invented history").assertIsDisplayed()
        composeRule.onAllNodesWithTag(ConversationListTestTags.NewChat).assertCountEquals(1)
        composeRule.onNodeWithTag(ShellTestTags.ConversationsPlaceholder).assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(service.activeHistoryCollectors).isEqualTo(1)
            assertThat(service.maximumHistoryCollectors).isEqualTo(1)
        }
    }

    private fun setShell(
        start: String = ShellDestination.Conversations.route,
        service: FakeConversationService = FakeConversationService(),
    ) {
        composeRule.setContent {
            ChatUiTheme {
                ShellScaffold(
                    navController = rememberNavController(),
                    startDestination = start,
                    conversationService = service,
                )
            }
        }
    }
}
