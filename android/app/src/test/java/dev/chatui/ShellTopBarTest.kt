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
import dev.chatui.core.designsystem.ChatUiScaffold
import dev.chatui.core.designsystem.ChatUiTheme
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
}
