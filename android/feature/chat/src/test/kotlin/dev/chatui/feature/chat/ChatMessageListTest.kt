package dev.chatui.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.ContentPart
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

        // Bounded threshold: fewer than 100 of the 10,000 rows may be composed.
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

    @Test
    fun `duplicate-prone ids keep stable identity and do not collide with the streaming row`() {
        // Identical text and sequence values make index- or content-based keys
        // ambiguous; only the message id can identify each row.
        val first = testMessage(
            id = "message-a",
            sequence = 1,
            role = MessageRole.USER,
            text = "Same text",
        )
        val second = testMessage(
            id = "message-b",
            sequence = 1,
            role = MessageRole.USER,
            text = "Same text",
        )
        val third = testMessage(
            id = "message-c",
            sequence = 1,
            role = MessageRole.USER,
            text = "Same text",
        )
        var messages by mutableStateOf(listOf(first, second, third))
        var streaming by mutableStateOf<StreamingState?>(null)

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageList(
                    messages = messages,
                    streaming = streaming,
                    zoneId = java.time.ZoneOffset.UTC,
                )
            }
        }
        composeRule.waitForIdle()

        listOf("message-a", "message-b", "message-c").forEach { id ->
            composeRule.onNodeWithTag(ChatMessageTestTags.message(id)).assertExists()
        }

        // Update the middle row in place. The other two rows keep their ids and
        // must not be reused for the changed content.
        messages = listOf(
            first,
            second.copy(parts = listOf(ContentPart.Text("Updated middle"))),
            third,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatMessageTestTags.message("message-a")).assertExists()
        composeRule.onNodeWithTag(ChatMessageTestTags.message("message-b")).assertExists()
        composeRule.onNodeWithTag(ChatMessageTestTags.message("message-c")).assertExists()
        composeRule.onNodeWithText("Updated middle", useUnmergedTree = true).assertExists()

        // A streaming row whose id matches a history id must replace that row
        // rather than produce a duplicate LazyColumn key.
        streaming = createStreamingState(
            conversationId = "conversation-1",
            messageId = "message-c",
            startedAt = now,
        ).copy(text = "Streaming")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatMessageListTestTags.Streaming).assertExists()
        composeRule.onNodeWithTag(ChatMessageTestTags.message("message-c")).assertDoesNotExist()
    }

    @Test
    fun `consecutive same-role messages share one role label without avatars`() {
        val first = testMessage(
            id = "user-1",
            sequence = 1,
            role = MessageRole.USER,
            text = "First",
        )
        val second = testMessage(
            id = "user-2",
            sequence = 2,
            role = MessageRole.USER,
            text = "Second",
        )
        val assistant = testMessage(
            id = "assistant-1",
            sequence = 3,
            role = MessageRole.ASSISTANT,
            text = "Answer",
        )

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageList(
                    messages = listOf(first, second, assistant),
                    streaming = null,
                    zoneId = java.time.ZoneOffset.UTC,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatMessageTestTags.role("user-1")).assertExists()
        composeRule.onNodeWithTag(ChatMessageTestTags.role("user-2")).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatMessageTestTags.role("assistant-1")).assertExists()
        composeRule.onNodeWithTag(ChatMessageTestTags.avatar("user-1")).assertDoesNotExist()
    }

    @Test
    fun `one labeled latest control appears when reading older messages`() {
        val messages = (0 until 50).map { index ->
            testMessage(
                id = "message-$index",
                sequence = index.toLong(),
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                text = "Message $index",
            )
        }
        val streaming = createStreamingState(
            conversationId = "conversation-1",
            messageId = "assistant-stream",
            startedAt = now,
        ).copy(text = "Streaming")

        composeRule.setContent {
            ChatUiTheme {
                ChatMessageList(
                    messages = messages,
                    streaming = streaming,
                    zoneId = java.time.ZoneOffset.UTC,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatMessageListTestTags.List).performScrollToIndex(0)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(ChatMessageListTestTags.Latest).assertCountEquals(1)
        composeRule.onNodeWithTag(ChatMessageListTestTags.Latest)
            .assertIsDisplayed()
    }

    @Test
    fun `off-screen updates preserve the reader and mark the latest control unread`() {
        val messages = (0 until 50).map { index ->
            testMessage(
                id = "message-$index",
                sequence = index.toLong(),
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                text = "Message $index",
            )
        }
        var streaming by mutableStateOf(
            createStreamingState(
                conversationId = "conversation-1",
                messageId = "assistant-stream",
                startedAt = now,
            ).copy(text = "First"),
        )
        lateinit var list: LazyListState

        composeRule.setContent {
            ChatUiTheme {
                list = rememberLazyListState()
                ChatMessageList(
                    messages = messages,
                    streaming = streaming,
                    zoneId = java.time.ZoneOffset.UTC,
                    listState = list,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatMessageListTestTags.List).performScrollToIndex(0)
        composeRule.waitForIdle()
        val before = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset

        streaming = streaming.copy(text = "First and second")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatMessageListTestTags.Latest)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New content available"))
        assertThat(list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset).isEqualTo(before)
        composeRule.onNodeWithTag(ChatMessageListTestTags.Latest).performClick()
        composeRule.waitForIdle()
        assertThat(list.canScrollForward).isFalse()
        composeRule.onNodeWithTag(ChatMessageListTestTags.Latest).assertDoesNotExist()
    }

    @Test
    fun `prepended history keeps the keyed anchor and does not create unread content`() {
        var messages by mutableStateOf((20..60).map { index ->
            testMessage(id = "message-$index", sequence = index.toLong(), text = "Message $index")
        })
        lateinit var list: LazyListState
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                list = rememberLazyListState()
                ChatMessageList(messages, null, hasOlder = true, listState = list)
            }
        }
        composeRule.onNodeWithTag(ChatMessageListTestTags.List).performScrollToIndex(10)
        composeRule.waitForIdle()
        val anchor = list.layoutInfo.visibleItemsInfo.first().key
        val offset = list.firstVisibleItemScrollOffset

        messages = (0..19).map { index ->
            testMessage(id = "message-$index", sequence = index.toLong(), text = "Message $index")
        } + messages
        composeRule.waitForIdle()

        assertThat(list.layoutInfo.visibleItemsInfo.first().key).isEqualTo(anchor)
        assertThat(list.firstVisibleItemScrollOffset).isEqualTo(offset)
        composeRule.onNodeWithTag(ChatMessageListTestTags.Latest)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Earlier messages"))
    }

    @Test
    fun `following remains at the tail through large deltas completion and cancellation without animation`() {
        var streaming by mutableStateOf(
            createStreamingState("conversation-1", "assistant-stream", now).copy(text = "First"),
        )
        lateinit var list: LazyListState
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                list = rememberLazyListState()
                ChatMessageList(
                    messages = listOf(testMessage()),
                    streaming = streaming,
                    listState = list,
                )
            }
        }
        composeRule.waitForIdle()
        streaming = streaming.copy(text = "A growing line of the response.\n".repeat(80))
        composeRule.waitForIdle()
        assertThat(list.canScrollForward).isFalse()
        assertThat(list.isScrollInProgress).isFalse()
        composeRule.onNodeWithTag(ChatMessageListTestTags.Latest).assertDoesNotExist()

        streaming = streaming.copy(status = MessageStatus.CANCELLED, done = true)
        composeRule.waitForIdle()
        assertThat(list.canScrollForward).isFalse()
        composeRule.onNodeWithTag(ChatMessageTestTags.Cancelled).assertExists()
        composeRule.onNodeWithTag(ChatMessageTestTags.Typing).assertDoesNotExist()
    }

    @Test
    fun `changing the current model leaves historical attribution unchanged`() {
        var conversation by mutableStateOf(testConversation())
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ChatMessageList(listOf(testMessage(role = MessageRole.ASSISTANT)), null, conversation = conversation)
            }
        }
        conversation = conversation.copy(modelLabel = "A newly selected model", modelId = "new-model")
        composeRule.onNodeWithTag(ChatMessageTestTags.Meta)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Model One \u00b7 provider-1"))))
        composeRule.onNodeWithText("A newly selected model", substring = true).assertDoesNotExist()
    }

    @Test
    fun `restoration preserves reading position and unread state instead of following a live response`() {
        val restoration = StateRestorationTester(composeRule)
        val messages = (0 until 40).map { index ->
            testMessage(id = "saved-$index", sequence = index.toLong(), text = "Message $index")
        }
        var streaming by mutableStateOf(
            createStreamingState("conversation-1", "assistant-stream", now).copy(text = "First"),
        )
        lateinit var list: LazyListState
        restoration.setContent {
            ChatUiTheme(reducedMotion = true) {
                list = rememberLazyListState()
                ChatMessageList(messages, streaming, listState = list)
            }
        }
        composeRule.onNodeWithTag(ChatMessageListTestTags.List).performScrollToIndex(8)
        streaming = streaming.copy(text = "First and second")
        composeRule.waitForIdle()
        val anchor = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertThat(list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset).isEqualTo(anchor)
        composeRule.onNodeWithTag(ChatMessageListTestTags.Latest)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New content available"))
        streaming = streaming.copy(text = "Another long response.\n".repeat(80))
        composeRule.waitForIdle()
        assertThat(list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset).isEqualTo(anchor)
    }
}
