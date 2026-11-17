package dev.chatui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsFocused
import androidx.navigation.compose.rememberNavController
import dev.chatui.core.designsystem.ChatUiScaffold
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.feature.chat.ChatTopBarState
import dev.chatui.feature.chat.ChatScreenTestTags
import dev.chatui.feature.chat.ConversationShareFormat
import dev.chatui.shell.ShellScaffold
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.ShellTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ShellTopBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `large title collapses and hides the subtitle on scroll`() {
        val listState = LazyListState()
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ChatUiScaffold(
                    title = "Chats",
                    subtitle = "3 conversations",
                    lazyListState = listState,
                ) { padding ->
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .padding(padding)
                            .testTag("list"),
                    ) {
                        items(100) { index ->
                            Text("Row $index")
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("3 conversations").assertIsDisplayed()

        composeRule.onNodeWithTag("list").performScrollToIndex(10)

        composeRule.onNodeWithText("3 conversations").assertDoesNotExist()
        composeRule.onNodeWithText("Chats").assertIsDisplayed()
    }

    @Test
    fun `chat overflow exposes direct share and settings and restores trigger focus`() {
        var format: ConversationShareFormat? = null
        var settings = 0
        val header = ChatTopBarState().apply {
            update("Invented chat", "Model One", { format = it }, { settings++ }, {})
        }
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ShellScaffold(
                    navController = rememberNavController(),
                    startDestination = ShellDestination.Chat.route,
                    conversationService = FakeConversationService(),
                    chatTopBarState = header,
                )
            }
        }

        composeRule.onNodeWithTag(ChatScreenTestTags.ConversationSettings).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.MoreButton).performClick()
        composeRule.onNodeWithText("Conversation settings").assertIsDisplayed()
        composeRule.onNodeWithText("Share as text").performClick()
        composeRule.onNodeWithTag(ChatScreenTestTags.MoreMenu).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.MoreButton).assertIsFocused().performClick()
        composeRule.onNodeWithText("Share as Markdown").performClick()
        composeRule.runOnIdle { assertThat(format).isEqualTo(ConversationShareFormat.MARKDOWN) }
        composeRule.onNodeWithTag(ShellTestTags.MoreButton).performClick()
        composeRule.onNodeWithText("Conversation settings").performClick()
        composeRule.runOnIdle { assertThat(settings).isEqualTo(1) }
    }

    @Test
    fun `chats root overflow keeps providers settings and keyboard help discoverable`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ShellScaffold(
                    navController = rememberNavController(),
                    conversationService = FakeConversationService(),
                )
            }
        }

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.MoreButton).performClick()
        composeRule.onNodeWithText("Providers").assertIsDisplayed()
        composeRule.onNodeWithText("App settings").assertIsDisplayed()
        composeRule.onNodeWithText("Keyboard shortcuts").assertIsDisplayed()
    }
}
