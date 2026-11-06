package dev.chatui.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMessageListTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")

    @Test
    fun `token updates recompose only the streaming row`() {
        val user = testMessage(
            id = "user-1",
            sequence = 1,
            role = MessageRole.USER,
            text = "Hello",
        )
        val historyMessages = listOf(user)
        val historyCompositions = AtomicInteger(0)
        val streamingCompositions = AtomicInteger(0)
        var streaming by mutableStateOf(
            createStreamingState(
                conversationId = "conversation-1",
                messageId = "assistant-1",
                startedAt = now,
            ).copy(text = "First"),
        )

        composeRule.setContent {
            ChatUiTheme {
                val onComposed = remember {
                    { id: String ->
                        if (id == "assistant-1") {
                            streamingCompositions.incrementAndGet()
                        } else {
                            historyCompositions.incrementAndGet()
                        }
                        Unit
                    }
                }

                ChatMessageList(
                    messages = historyMessages,
                    streaming = streaming,
                    conversation = testConversation(),
                    onMessageComposed = onComposed,
                    zoneId = java.time.ZoneOffset.UTC,
                )
            }
        }
        composeRule.waitForIdle()

        val historyBefore = historyCompositions.get()
        val streamingBefore = streamingCompositions.get()

        streaming = streaming.copy(text = "First and second")
        composeRule.waitForIdle()

        assertThat(historyCompositions.get()).isEqualTo(historyBefore)
        assertThat(streamingCompositions.get()).isGreaterThan(streamingBefore)
        composeRule.onNodeWithText("First and second", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `ten thousand message history composes only visible rows`() {
        val messages = (0 until 10_000).map { index ->
            testMessage(
                id = "message-$index",
                sequence = index.toLong(),
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                text = "Message $index",
            )
        }
        val composed = AtomicInteger(0)

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageList(
                    messages = messages,
                    streaming = null,
                    zoneId = java.time.ZoneOffset.UTC,
                    onMessageComposed = { composed.incrementAndGet() },
                )
            }
        }
        composeRule.waitForIdle()

        assertThat(composed.get()).isLessThan(100)
        composeRule.onNodeWithText("Message 9999", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `history rows keep stable identity when a message is updated in place`() {
        val user = testMessage(
            id = "user-1",
            sequence = 1,
            role = MessageRole.USER,
            text = "Hello",
        )
        val assistant = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            text = "Old",
            status = MessageStatus.STREAMING,
        )
        var messages by mutableStateOf(listOf(user, assistant))

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageList(
                    messages = messages,
                    streaming = null,
                    zoneId = java.time.ZoneOffset.UTC,
                )
            }
        }

        composeRule.onNodeWithTag(ChatMessageTestTags.message("assistant-1"))
            .assertExists()

        messages = listOf(user, assistant.copy(status = MessageStatus.COMPLETE, parts = assistant.parts))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatMessageTestTags.message("assistant-1"))
            .assertExists()
    }
}
