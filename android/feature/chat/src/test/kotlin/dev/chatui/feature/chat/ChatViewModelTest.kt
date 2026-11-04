package dev.chatui.feature.chat

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.StreamEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state is exposed as a StateFlow and send streams into it`() = runTest(dispatcher) {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        var nextId = 0
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = store,
            contextResolver = FakeChatContextResolver(),
            adapter = adapter,
            clock = clock,
            newId = { "id-${nextId++}" },
        )

        viewModel.state.test {
            val initial = awaitItem()
            assertThat(initial.conversationId).isEqualTo("conversation-1")
            assertThat(initial.loading).isTrue()

            viewModel.onDraftChange("Hello")
            assertThat(awaitItem().draft).isEqualTo("Hello")

            viewModel.onSend()
            runCurrent()
            adapter.started.await()
            adapter.events.send(StreamEvent.TextDelta("Hi"))
            adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
            adapter.events.close()
            runCurrent()

            val final = expectMostRecentItem()
            assertThat(final.draft).isEmpty()
            assertThat(final.streaming?.text).isEqualTo("Hi")
            assertThat(final.streaming?.done).isTrue()
            assertThat(final.messages.any { it.role == MessageRole.ASSISTANT }).isTrue()
        }
    }

    @Test
    fun `failure preserves the draft`() = runTest(dispatcher) {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = store,
            contextResolver = FakeChatContextResolver(
                result = ChatContextResult.Unavailable(
                    reason = ChatUnavailableReason.NO_KEY,
                    message = "No key",
                    error = ChatError(
                        code = ChatErrorCode.AUTH,
                        message = "No key",
                        providerId = null,
                        status = null,
                        retryable = false,
                        retryAfterSeconds = null,
                        requestId = null,
                        details = null,
                        at = now,
                    ),
                ),
            ),
            adapter = adapter,
            clock = clock,
        )

        viewModel.onDraftChange("Hello")
        viewModel.onSend()
        runCurrent()

        assertThat(viewModel.state.value.draft).isEqualTo("Hello")
        assertThat(viewModel.state.value.error?.code).isEqualTo(ChatErrorCode.AUTH)
    }

    @Test
    fun `stop cancels the active stream`() = runTest(dispatcher) {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        var nextId = 0
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = store,
            contextResolver = FakeChatContextResolver(),
            adapter = adapter,
            clock = clock,
            newId = { "id-${nextId++}" },
        )

        viewModel.onDraftChange("Hello")
        viewModel.onSend()
        runCurrent()
        adapter.started.await()
        adapter.events.send(StreamEvent.TextDelta("Partial"))
        runCurrent()

        viewModel.onStop()
        runCurrent()

        assertThat(adapter.cancelled).isTrue()
        assertThat(viewModel.state.value.streaming?.status)
            .isEqualTo(MessageStatus.CANCELLED)
        assertThat(store.all("conversation-1").last().status)
            .isEqualTo(MessageStatus.CANCELLED)
    }

    @Test
    fun `regenerate reuses the assistant message`() = runTest(dispatcher) {
        val adapter = FakeProviderAdapter()
        val target = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            text = "Old",
            status = MessageStatus.ERROR,
        )
        val store = FakeChatMessageStore(
            listOf(
                testMessage(id = "user-1", sequence = 1, role = MessageRole.USER, text = "Hi"),
                target,
            ),
        )
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = store,
            contextResolver = FakeChatContextResolver(),
            adapter = adapter,
            clock = clock,
            newId = { "unused" },
        )

        viewModel.onRegenerate(target)
        runCurrent()
        adapter.started.await()
        adapter.events.send(StreamEvent.TextDelta("New"))
        adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()
        runCurrent()

        assertThat(store.all("conversation-1").map { it.id })
            .containsExactly("user-1", "assistant-1")
            .inOrder()
        assertThat(viewModel.state.value.streaming?.text).isEqualTo("New")
    }
}
