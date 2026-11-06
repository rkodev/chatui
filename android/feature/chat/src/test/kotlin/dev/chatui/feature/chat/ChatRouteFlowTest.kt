package dev.chatui.feature.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.StreamEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Route-level chat flow: the real [ChatViewModel] drives the real [ChatScreen]
 * with a controllable [FakeProviderAdapter]. No live provider is contacted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRouteFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun viewModel(adapter: FakeProviderAdapter): ChatViewModel {
        var nextId = 0
        return ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = FakeChatMessageStore(),
            contextResolver = FakeChatContextResolver(),
            adapter = adapter,
            clock = clock,
            newId = { "id-${nextId++}" },
        )
    }

    private fun setRouteContent(viewModel: ChatViewModel) {
        composeRule.setContent {
            val state by viewModel.state.collectAsState()
            ChatUiTheme {
                ChatScreen(
                    state = state,
                    onDraftChange = viewModel::onDraftChange,
                    onSend = viewModel::onSend,
                    onStop = viewModel::onStop,
                    onLoadOlder = viewModel::onLoadOlder,
                    onRetryMessage = viewModel::onRetry,
                    onDismissError = viewModel::onDismissError,
                    onRegenerateMessage = viewModel::onRegenerate,
                    onEditLastUser = viewModel::onEditAndResend,
                    imeInsets = WindowInsets(0, 0, 0, 0),
                    zoneId = ZoneOffset.UTC,
                )
            }
        }
    }

    private fun sendMessage(text: String) {
        composeRule.onNodeWithTag(ChatComposerTestTags.Input)
            .performTextReplacement(text)
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).performClick()
    }

    @Test
    fun `send streams deltas into one row and completes with actions`() {
        val adapter = FakeProviderAdapter()
        val viewModel = viewModel(adapter)
        setRouteContent(viewModel)

        composeRule.onNodeWithTag(ChatScreenTestTags.Empty).assertIsDisplayed()

        sendMessage("Hello")
        composeRule.waitUntil(timeoutMillis = 5_000) { adapter.lastRequest != null }

        // The user row is persisted and rendered before the stream starts.
        composeRule.onNodeWithTag(ChatMessageTestTags.message("id-0")).assertExists()
        composeRule.onNodeWithTag(ChatMessageTestTags.text("id-0")).assertExists()

        adapter.events.trySend(StreamEvent.TextDelta("Hi"))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(ChatMessageListTestTags.Streaming).assertIsDisplayed()
        composeRule.onNodeWithText("Hi").assertExists()

        adapter.events.trySend(StreamEvent.TextDelta(" there"))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi there").fetchSemanticsNodes().isNotEmpty()
        }
        // Deltas accumulate in a single streaming row.
        composeRule.onAllNodesWithTag(ChatMessageListTestTags.Streaming)
            .assertCountEquals(1)
        composeRule.onNodeWithText("Hi there").assertExists()

        adapter.events.trySend(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            val state = viewModel.state.value
            state.streaming?.done == true &&
                state.messages.any { it.role == MessageRole.ASSISTANT } &&
                composeRule.onAllNodesWithText("Hi there").fetchSemanticsNodes().isNotEmpty()
        }

        // The completed response is persisted, the streaming row is gone, and
        // the normal message action row is available.
        composeRule.onNodeWithTag(ChatMessageListTestTags.Streaming).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatMessageTestTags.message("id-1")).assertExists()
        composeRule.onNodeWithText("Hi there").assertExists()
        assertThat(
            composeRule.onAllNodesWithTag(ChatMessageActionsTestTags.Row)
                .fetchSemanticsNodes(),
        ).isNotEmpty()
        assertThat(viewModel.state.value.announcement).isEqualTo("Response complete")
    }

    @Test
    fun `stop cancels the stream and keeps the partial response`() {
        val adapter = FakeProviderAdapter()
        val viewModel = viewModel(adapter)
        setRouteContent(viewModel)

        sendMessage("Hello")
        composeRule.waitUntil(timeoutMillis = 5_000) { adapter.lastRequest != null }

        adapter.events.trySend(StreamEvent.TextDelta("Partial"))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.streaming?.text == "Partial"
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Stop).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.streaming?.status == MessageStatus.CANCELLED &&
                composeRule.onAllNodesWithTag(ChatMessageTestTags.Cancelled)
                    .fetchSemanticsNodes().isNotEmpty()
        }

        assertThat(adapter.cancelled).isTrue()
        composeRule.onNodeWithTag(ChatMessageTestTags.Cancelled).assertExists()
        composeRule.onNodeWithText("Partial").assertExists()
        assertThat(viewModel.state.value.announcement).isEqualTo("Response cancelled")
    }
}
