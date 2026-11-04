package dev.chatui.feature.chat

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Usage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRunnerTest {
    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `send reduces text and reasoning in order and persists the final message`() = runTest {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        val runner = ChatRunner(
            adapter = adapter,
            messages = store,
            scope = backgroundScope,
            clock = clock,
            newId = { "assistant-1" },
            persistIntervalMs = 1_000,
        )
        val conversation = testConversation()
        val user = testMessage(id = "user-1", sequence = 1, role = MessageRole.USER, text = "Hi")

        val result = async {
            runner.send(
                ChatRunInput(
                    conversation = conversation,
                    context = testChatContext().providerContext,
                    request = buildChatRequest(conversation, "openrouter/auto", listOf(user)),
                    userMessage = user,
                    assistantSequence = 2,
                ),
            )
        }

        adapter.started.await()
        adapter.events.send(StreamEvent.Start("response-1"))
        adapter.events.send(StreamEvent.ReasoningDelta("think "))
        adapter.events.send(StreamEvent.TextDelta("Hello"))
        adapter.events.send(StreamEvent.TextDelta(" world"))
        adapter.events.send(
            StreamEvent.UsageEvent(
                Usage(
                    inputTokens = 1,
                    outputTokens = 2,
                    totalTokens = 3,
                    costUsd = 0.01,
                    estimated = false,
                ),
            ),
        )
        adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()

        val completed = result.await()

        assertThat(completed).isInstanceOf(ChatRunResult.Success::class.java)
        val success = completed as ChatRunResult.Success
        assertThat(success.state.text).isEqualTo("Hello world")
        assertThat(success.state.reasoning).isEqualTo("think ")
        assertThat(success.message.status).isEqualTo(MessageStatus.COMPLETE)
        assertThat(success.message.finishReason).isEqualTo(FinishReason.STOP)
        assertThat(success.message.usage?.totalTokens).isEqualTo(3)
        assertThat(success.message.parts.map { it::class.simpleName })
            .containsExactly("Reasoning", "Text")
            .inOrder()
        assertThat(store.all(conversation.id).map { it.id })
            .containsExactly("user-1", "assistant-1")
            .inOrder()
    }

    @Test
    fun `duplicate send returns busy`() = runTest {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        val runner = ChatRunner(
            adapter = adapter,
            messages = store,
            scope = backgroundScope,
            clock = clock,
            newId = { "assistant-1" },
        )
        val conversation = testConversation()
        val input = ChatRunInput(
            conversation = conversation,
            context = testChatContext().providerContext,
            request = buildChatRequest(conversation, "openrouter/auto", emptyList()),
            assistantSequence = 1,
        )

        val first = async { runner.send(input) }
        adapter.started.await()
        val second = runner.send(input)

        assertThat(second).isInstanceOf(ChatRunResult.Failure::class.java)
        assertThat((second as ChatRunResult.Failure).reason)
            .isEqualTo(ChatRunFailureReason.BUSY)

        adapter.events.close()
        first.await()
    }

    @Test
    fun `cancel persists partial text and marks the message cancelled`() = runTest {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        val runner = ChatRunner(
            adapter = adapter,
            messages = store,
            scope = backgroundScope,
            clock = clock,
            newId = { "assistant-1" },
        )
        val conversation = testConversation()
        val input = ChatRunInput(
            conversation = conversation,
            context = testChatContext().providerContext,
            request = buildChatRequest(conversation, "openrouter/auto", emptyList()),
            assistantSequence = 1,
        )

        val result = async { runner.send(input) }
        adapter.started.await()
        adapter.events.send(StreamEvent.TextDelta("Partial answer"))
        runCurrent()

        val cancelled = runner.cancel(conversation.id)

        assertThat(adapter.cancelled).isTrue()
        assertThat(cancelled?.status).isEqualTo(MessageStatus.CANCELLED)
        assertThat(cancelled?.finishReason).isEqualTo(FinishReason.CANCELLED)
        assertThat(cancelled?.parts).containsExactly(ContentPart.Text("Partial answer"))
        assertThat(store.all(conversation.id).single().status)
            .isEqualTo(MessageStatus.CANCELLED)
        assertThat(result.await()).isInstanceOf(ChatRunResult.Success::class.java)
    }

    @Test
    fun `error preserves partial content`() = runTest {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        val runner = ChatRunner(
            adapter = adapter,
            messages = store,
            scope = backgroundScope,
            clock = clock,
            newId = { "assistant-1" },
        )
        val conversation = testConversation()
        val input = ChatRunInput(
            conversation = conversation,
            context = testChatContext().providerContext,
            request = buildChatRequest(conversation, "openrouter/auto", emptyList()),
            assistantSequence = 1,
        )

        val result = async { runner.send(input) }
        adapter.started.await()
        adapter.events.send(StreamEvent.TextDelta("Partial"))
        adapter.events.send(
            StreamEvent.ErrorEvent(
                ChatError(
                    code = ChatErrorCode.PROVIDER,
                    message = "Provider failed",
                    providerId = "provider-1",
                    status = 500,
                    retryable = true,
                    retryAfterSeconds = null,
                    requestId = null,
                    details = null,
                    at = now,
                ),
            ),
        )
        adapter.events.close()

        val success = result.await() as ChatRunResult.Success
        assertThat(success.message.status).isEqualTo(MessageStatus.ERROR)
        assertThat(success.message.parts).containsExactly(ContentPart.Text("Partial"))
        assertThat(success.message.error?.code).isEqualTo(ChatErrorCode.PROVIDER)
    }

    @Test
    fun `persistence is not per token`() = runTest {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        val runner = ChatRunner(
            adapter = adapter,
            messages = store,
            scope = backgroundScope,
            clock = clock,
            newId = { "assistant-1" },
            persistIntervalMs = 1_000,
        )
        val conversation = testConversation()
        val input = ChatRunInput(
            conversation = conversation,
            context = testChatContext().providerContext,
            request = buildChatRequest(conversation, "openrouter/auto", emptyList()),
            assistantSequence = 1,
        )

        val result = async { runner.send(input) }
        adapter.started.await()
        repeat(20) { index ->
            adapter.events.send(StreamEvent.TextDelta("$index"))
        }
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()
        result.await()

        assertThat(store.writes.size).isLessThan(20)
    }

    @Test
    fun `writes never overlap`() = runTest {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore().apply { writeDelayMs = 10 }
        val runner = ChatRunner(
            adapter = adapter,
            messages = store,
            scope = backgroundScope,
            clock = clock,
            newId = { "assistant-1" },
            persistIntervalMs = 1_000,
        )
        val conversation = testConversation()
        val input = ChatRunInput(
            conversation = conversation,
            context = testChatContext().providerContext,
            request = buildChatRequest(conversation, "openrouter/auto", emptyList()),
            assistantSequence = 1,
        )

        val result = async { runner.send(input) }
        adapter.started.await()
        repeat(5) { index -> adapter.events.send(StreamEvent.TextDelta("$index")) }
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()
        result.await()

        assertThat(store.maxConcurrentWrites).isEqualTo(1)
    }

    @Test
    fun `persistNow writes the current partial text`() = runTest {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        val runner = ChatRunner(
            adapter = adapter,
            messages = store,
            scope = backgroundScope,
            clock = clock,
            newId = { "assistant-1" },
            persistIntervalMs = 10_000,
        )
        val conversation = testConversation()
        val input = ChatRunInput(
            conversation = conversation,
            context = testChatContext().providerContext,
            request = buildChatRequest(conversation, "openrouter/auto", emptyList()),
            assistantSequence = 1,
        )

        val result = async { runner.send(input) }
        adapter.started.await()
        adapter.events.send(StreamEvent.TextDelta("Partial"))
        runCurrent()

        runner.persistNow(conversation.id)

        assertThat(store.all(conversation.id).single().parts)
            .containsExactly(ContentPart.Text("Partial"))

        adapter.events.close()
        result.await()
    }

    @Test
    fun `regenerate reuses the target id and sequence without branching`() = runTest {
        val adapter = FakeProviderAdapter()
        val target = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            text = "Old answer",
            status = MessageStatus.ERROR,
        )
        val user = testMessage(id = "user-1", sequence = 1, role = MessageRole.USER, text = "Hi")
        val store = FakeChatMessageStore(listOf(user, target))
        val runner = ChatRunner(
            adapter = adapter,
            messages = store,
            scope = backgroundScope,
            clock = clock,
            newId = { "unused" },
        )
        val conversation = testConversation()
        val request = buildChatRequest(conversation, "openrouter/auto", listOf(user))

        val result = async {
            runner.regenerate(
                RegenerateInput(
                    conversation = conversation,
                    context = testChatContext().providerContext,
                    request = request,
                    target = target,
                ),
            )
        }
        adapter.started.await()
        adapter.events.send(StreamEvent.TextDelta("New answer"))
        adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()

        val success = result.await() as ChatRunResult.Success
        assertThat(success.message.id).isEqualTo("assistant-1")
        assertThat(success.message.sequence).isEqualTo(2)
        assertThat(store.all(conversation.id).map { it.id })
            .containsExactly("user-1", "assistant-1")
            .inOrder()
    }

    @Test
    fun `retry rejects a complete message`() = runTest {
        val runner = ChatRunner(
            adapter = FakeProviderAdapter(),
            messages = FakeChatMessageStore(),
            scope = backgroundScope,
            clock = clock,
        )
        val conversation = testConversation()
        val target = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.COMPLETE,
        )

        val result = runner.retry(
            RegenerateInput(
                conversation = conversation,
                context = testChatContext().providerContext,
                request = buildChatRequest(conversation, "openrouter/auto", emptyList()),
                target = target,
            ),
        )

        assertThat(result).isInstanceOf(ChatRunResult.Failure::class.java)
        assertThat((result as ChatRunResult.Failure).reason)
            .isEqualTo(ChatRunFailureReason.INVALID_TARGET)
    }

    @Test
    fun `edit and resend truncates later messages and reuses the user message`() = runTest {
        val adapter = FakeProviderAdapter()
        val user = testMessage(id = "user-1", sequence = 1, role = MessageRole.USER, text = "Old")
        val oldAssistant = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            text = "Old answer",
        )
        val store = FakeChatMessageStore(listOf(user, oldAssistant))
        val runner = ChatRunner(
            adapter = adapter,
            messages = store,
            scope = backgroundScope,
            clock = clock,
            newId = { "assistant-2" },
        )
        val conversation = testConversation()
        val updatedUser = user.copy(parts = listOf(ContentPart.Text("New")))
        val request = buildChatRequest(conversation, "openrouter/auto", listOf(updatedUser))

        val result = async {
            runner.editAndResend(
                EditAndResendInput(
                    conversation = conversation,
                    context = testChatContext().providerContext,
                    request = request,
                    target = updatedUser,
                ),
            )
        }
        adapter.started.await()
        adapter.events.send(StreamEvent.TextDelta("New answer"))
        adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()

        val success = result.await() as ChatRunResult.Success
        assertThat(success.message.sequence).isEqualTo(2)
        assertThat(store.all(conversation.id).map { it.id })
            .containsExactly("user-1", "assistant-2")
            .inOrder()
        assertThat(store.all(conversation.id).first().parts)
            .containsExactly(ContentPart.Text("New"))
    }
}
