package dev.chatui.feature.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.MessageRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatShortcutStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `chat screen registers stop and copy with the shell bridge`() {
        val bridge = ChatShortcutState()
        var stops = 0
        val assistant = testMessage(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            text = "Answer",
        )
        val state = ChatUiState(
            conversationId = "conversation-1",
            conversation = testConversation(),
            messages = listOf(assistant),
            streaming = createStreamingState(
                conversationId = "conversation-1",
                messageId = "assistant-1",
                startedAt = TEST_NOW,
            ),
            loading = false,
            availability = ChatAvailability(available = true),
        )

        composeRule.setContent {
            ChatUiTheme {
                CompositionLocalProvider(LocalChatShortcutState provides bridge) {
                    ChatScreen(
                        state = state,
                        onDraftChange = {},
                        onSend = {},
                        onStop = { stops += 1 },
                        onLoadOlder = {},
                        onRetryMessage = {},
                        onDismissError = {},
                        imeInsets = WindowInsets(0, 0, 0, 0),
                    )
                }
            }
        }

        composeRule.runOnIdle {
            assertThat(bridge.streaming).isTrue()
            assertThat(bridge.stop()).isTrue()
            assertThat(stops).isEqualTo(1)
            assertThat(bridge.copyLastResponse()).isTrue()
        }

        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            .isEqualTo("Answer")
    }

    @Test
    fun `bridge reports no action when no chat is composed`() {
        val bridge = ChatShortcutState()

        assertThat(bridge.stop()).isFalse()
        assertThat(bridge.copyLastResponse()).isFalse()
    }

    @Test
    fun `stop is ignored when no response is active`() {
        val bridge = ChatShortcutState()
        var stops = 0
        bridge.update(
            streaming = false,
            overlayOpen = false,
            onStop = { stops += 1 },
            onCopyLastResponse = null,
        )

        assertThat(bridge.stop()).isFalse()
        assertThat(stops).isEqualTo(0)
    }
}
