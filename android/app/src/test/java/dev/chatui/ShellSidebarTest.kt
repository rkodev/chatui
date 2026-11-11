package dev.chatui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.feature.conversations.ConversationListTestTags
import dev.chatui.shell.ChatDestination
import dev.chatui.shell.SettingsDestination
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.ShellSidebar
import dev.chatui.shell.ShellTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ShellSidebarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `sidebar exposes every destination`() {
        val routes = mutableListOf<String>()
        setSidebar(onNavigate = { routes += it })

        listOf(
            ShellDestination.Providers.route,
            ShellDestination.Settings.route,
            SettingsDestination.DATA,
            ShellDestination.Backup.route,
        ).forEach { route ->
            composeRule.onNodeWithTag(ShellTestTags.navigationItem(route)).performClick()
        }

        assertThat(routes).containsExactly(
            ShellDestination.Providers.route,
            ShellDestination.Settings.route,
            SettingsDestination.DATA,
            ShellDestination.Backup.route,
        ).inOrder()
    }

    @Test
    fun `sidebar opens a conversation`() {
        val routes = mutableListOf<String>()
        setSidebar(
            service = FakeConversationService(
                conversations = listOf(fakeConversation("conversation-1", "First chat")),
            ),
            onNavigate = { routes += it },
        )

        composeRule.onNodeWithText("First chat").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { routes.isNotEmpty() }

        assertThat(routes).containsExactly(ChatDestination.createRoute("conversation-1"))
    }

    @Test
    fun `sidebar new chat opens the created conversation`() {
        val routes = mutableListOf<String>()
        setSidebar(
            service = FakeConversationService(
                createdConversation = fakeConversation("new-conversation", "New chat"),
            ),
            onNavigate = { routes += it },
        )

        composeRule.onNodeWithTag(ConversationListTestTags.NewChat).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { routes.isNotEmpty() }

        assertThat(routes).containsExactly(ChatDestination.createRoute("new-conversation"))
    }

    @Test
    fun `sidebar header new chat invokes the shell action`() {
        var newChats = 0
        setSidebar(onNavigate = {}, onNewChat = { newChats += 1 })

        composeRule.onNodeWithTag(ShellTestTags.NewChat).performClick()

        assertThat(newChats).isEqualTo(1)
    }

    @Test
    fun `sidebar groups destinations into chats library and settings`() {
        setSidebar(onNavigate = {})

        composeRule.onNodeWithText("CHATS").assertExists()
        composeRule.onNodeWithText("LIBRARY").assertExists()
        composeRule.onNodeWithText("SETTINGS").assertExists()
    }

    private fun setSidebar(
        service: FakeConversationService = FakeConversationService(),
        onNavigate: (String) -> Unit,
        onNewChat: () -> Unit = {},
    ) {
        composeRule.setContent {
            ChatUiTheme {
                ShellSidebar(
                    currentRoute = ShellDestination.Conversations.route,
                    conversationService = service,
                    onNavigate = onNavigate,
                    onNewChat = onNewChat,
                )
            }
        }
    }
}
