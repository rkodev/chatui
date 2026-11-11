package dev.chatui.feature.chat

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.attachment.AttachmentInput
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.InputModality
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
        assertThat(viewModel.state.value.availability?.available).isFalse()
        assertThat(viewModel.state.value.availability?.reason)
            .isEqualTo(ChatUnavailableReason.NO_KEY)
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

    @Test
    fun `loads the newest page and pages older messages on demand`() = runTest(dispatcher) {
        val allMessages = (1L..60L).map { sequence ->
            testMessage(
                id = "message-$sequence",
                sequence = sequence,
                role = if (sequence % 2L == 0L) MessageRole.ASSISTANT else MessageRole.USER,
                text = "Message $sequence",
            )
        }
        val store = FakeChatMessageStore(allMessages)
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = store,
            contextResolver = FakeChatContextResolver(),
            adapter = FakeProviderAdapter(),
            clock = clock,
        )
        runCurrent()

        assertThat(viewModel.state.value.messages).hasSize(CHAT_MESSAGE_PAGE_SIZE)
        assertThat(viewModel.state.value.messages.first().sequence).isEqualTo(11L)
        assertThat(viewModel.state.value.hasOlder).isTrue()

        viewModel.onLoadOlder()
        runCurrent()

        assertThat(viewModel.state.value.messages).hasSize(60)
        assertThat(viewModel.state.value.messages.first().sequence).isEqualTo(1L)
        assertThat(viewModel.state.value.hasOlder).isFalse()
    }

    @Test
    fun `announcements change on start and completion but not on tokens`() = runTest(dispatcher) {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = store,
            contextResolver = FakeChatContextResolver(),
            adapter = adapter,
            clock = clock,
            newId = { "id-1" },
        )

        viewModel.onDraftChange("Hello")
        viewModel.onSend()
        runCurrent()
        adapter.started.await()
        runCurrent()

        assertThat(viewModel.state.value.announcement).isEqualTo("Response started")

        adapter.events.send(StreamEvent.TextDelta("Hi"))
        runCurrent()
        assertThat(viewModel.state.value.announcement).isEqualTo("Response started")

        adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()
        runCurrent()
        assertThat(viewModel.state.value.announcement).isEqualTo("Response complete")
    }

    @Test
    fun `announcements include a terminal error but not token updates`() = runTest(dispatcher) {
        val adapter = FakeProviderAdapter()
        val store = FakeChatMessageStore()
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = store,
            contextResolver = FakeChatContextResolver(),
            adapter = adapter,
            clock = clock,
            newId = { "id-1" },
        )

        viewModel.onDraftChange("Hello")
        viewModel.onSend()
        runCurrent()
        adapter.started.await()
        runCurrent()

        adapter.events.send(StreamEvent.TextDelta("Hi"))
        runCurrent()
        assertThat(viewModel.state.value.announcement).isEqualTo("Response started")

        adapter.events.send(
            StreamEvent.ErrorEvent(
                ChatError(
                    code = ChatErrorCode.NETWORK,
                    message = "Network unavailable",
                    providerId = null,
                    status = null,
                    retryable = true,
                    retryAfterSeconds = null,
                    requestId = null,
                    details = null,
                    at = now,
                ),
            ),
        )
        adapter.events.close()
        runCurrent()

        assertThat(viewModel.state.value.announcement).startsWith("Response failed.")
    }

    @Test
    fun `per-conversation drafts are restored and cleared after a successful send`() =
        runTest(dispatcher) {
            val adapter = FakeProviderAdapter()
            val store = FakeChatMessageStore()
            val drafts = InMemoryChatDrafts().apply {
                setDraft("conversation-1", "Saved draft")
            }
            val viewModel = ChatViewModel(
                conversationId = "conversation-1",
                conversations = FakeChatConversationSource(),
                messages = store,
                contextResolver = FakeChatContextResolver(),
                adapter = adapter,
                clock = clock,
                newId = { "id-1" },
                drafts = drafts,
            )
            runCurrent()

            assertThat(viewModel.state.value.draft).isEqualTo("Saved draft")
            assertThat(drafts.draftFor("conversation-1")).isEqualTo("Saved draft")

            viewModel.onSend()
            runCurrent()
            adapter.started.await()
            adapter.events.send(StreamEvent.TextDelta("Hi"))
            adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
            adapter.events.close()
            runCurrent()

            assertThat(viewModel.state.value.draft).isEmpty()
            assertThat(drafts.draftFor("conversation-1")).isEmpty()
        }

    @Test
    fun `adding and removing attachments updates pending state`() = runTest(dispatcher) {
        val store = FakeChatAttachmentStore()
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = FakeChatMessageStore(),
            contextResolver = FakeChatContextResolver(),
            adapter = FakeProviderAdapter(),
            clock = clock,
            attachments = store,
        )
        runCurrent()

        viewModel.onAddAttachments(
            listOf(AttachmentInput("notes.txt", "text/plain", 5, "hello".toByteArray())),
        )
        runCurrent()

        val added = viewModel.state.value.pendingAttachments.single()
        assertThat(added.name).isEqualTo("notes.txt")

        viewModel.onRemoveAttachment(added.id)
        runCurrent()

        assertThat(viewModel.state.value.pendingAttachments).isEmpty()
        assertThat(store.attachments).isEmpty()
    }

    @Test
    fun `sending an image includes parts and resolved bytes`() = runTest(dispatcher) {
        val adapter = FakeProviderAdapter()
        val store = FakeChatAttachmentStore()
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = FakeChatMessageStore(),
            contextResolver = FakeChatContextResolver(
                result = ChatContextResult.Ready(
                    testChatContext(
                        inputModalities = listOf(InputModality.TEXT, InputModality.IMAGE),
                    ),
                ),
            ),
            adapter = adapter,
            clock = clock,
            attachments = store,
        )
        runCurrent()

        viewModel.onAddAttachments(
            listOf(AttachmentInput("photo.png", "image/png", 3, byteArrayOf(1, 2, 3))),
        )
        runCurrent()
        val attachment = viewModel.state.value.pendingAttachments.single()

        viewModel.onDraftChange("Look")
        viewModel.onSend()
        runCurrent()
        adapter.started.await()

        val request = adapter.lastRequest!!
        assertThat(request.messages.last().parts).contains(
            ContentPart.Image(attachment.id, "image/png", "photo.png"),
        )
        assertThat(request.attachments).containsKey(attachment.id)

        adapter.events.send(StreamEvent.TextDelta("Hi"))
        adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()
        runCurrent()

        assertThat(viewModel.state.value.pendingAttachments).isEmpty()
        assertThat(store.attachments[attachment.id]?.messageId).isNotNull()
    }

    @Test
    fun `unsupported attachments require confirmation before sending`() = runTest(dispatcher) {
        val adapter = FakeProviderAdapter()
        val store = FakeChatAttachmentStore()
        val viewModel = ChatViewModel(
            conversationId = "conversation-1",
            conversations = FakeChatConversationSource(),
            messages = FakeChatMessageStore(),
            contextResolver = FakeChatContextResolver(
                result = ChatContextResult.Ready(
                    testChatContext(inputModalities = listOf(InputModality.TEXT)),
                ),
            ),
            adapter = adapter,
            clock = clock,
            attachments = store,
        )
        runCurrent()

        viewModel.onAddAttachments(
            listOf(AttachmentInput("photo.png", "image/png", 3, byteArrayOf(1, 2, 3))),
        )
        runCurrent()
        viewModel.onDraftChange("Look")
        viewModel.onSend()
        runCurrent()

        assertThat(viewModel.state.value.confirmSendWithUnsupportedAttachments).isTrue()
        assertThat(adapter.lastRequest).isNull()

        viewModel.onConfirmSend()
        runCurrent()
        adapter.started.await()

        assertThat(adapter.lastRequest).isNotNull()
        assertThat(viewModel.state.value.confirmSendWithUnsupportedAttachments).isFalse()

        adapter.events.send(StreamEvent.Finish(FinishReason.STOP))
        adapter.events.close()
        runCurrent()
    }
}
