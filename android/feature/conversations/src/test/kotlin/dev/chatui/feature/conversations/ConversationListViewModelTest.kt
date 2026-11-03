package dev.chatui.feature.conversations

import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock: Clock = Clock.fixed(
        Instant.parse("2026-11-03T12:00:00Z"),
        ZoneOffset.UTC,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `groups conversations by date`() = runTest(dispatcher) {
        val service = FakeConversationService()
        service.conversationsFlow.value = listOf(
            testConversation(id = "today", lastMessageAt = Instant.parse("2026-11-03T08:00:00Z")),
            testConversation(
                id = "yesterday",
                lastMessageAt = Instant.parse("2026-11-02T08:00:00Z"),
            ),
            testConversation(
                id = "older",
                lastMessageAt = Instant.parse("2026-09-01T08:00:00Z"),
            ),
        )

        val viewModel = ConversationListViewModel(service, clock, ZoneOffset.UTC)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.groups.map { it.key }).containsExactly(
            ConversationGroupKey.TODAY,
            ConversationGroupKey.YESTERDAY,
            ConversationGroupKey.OLDER,
        ).inOrder()
        assertThat(state.groups.flatMap { group -> group.conversations.map { it.id } })
            .containsExactly("today", "yesterday", "older")
    }

    @Test
    fun `new chat emits an open event and records last active`() = runTest(dispatcher) {
        val service = FakeConversationService()
        val created = testConversation(id = "new-conversation")
        service.createResult = CreateConversationResult.Created(created)
        val viewModel = ConversationListViewModel(service, clock, ZoneOffset.UTC)
        advanceUntilIdle()

        val event = async { viewModel.events.first() }
        viewModel.onNewChat()
        advanceUntilIdle()

        assertThat(event.await())
            .isEqualTo(ConversationListEvent.OpenConversation("new-conversation"))
        assertThat(service.activeFlow.value).isEqualTo("new-conversation")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `new chat without defaults shows an actionable error`() = runTest(dispatcher) {
        val service = FakeConversationService()
        service.createResult = CreateConversationResult.NotConfigured(
            "No provider is configured. Add a provider before starting a chat.",
        )
        val viewModel = ConversationListViewModel(service, clock, ZoneOffset.UTC)
        advanceUntilIdle()

        viewModel.onNewChat()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error)
            .isEqualTo("No provider is configured. Add a provider before starting a chat.")
    }

    @Test
    fun `select records last active and emits an open event`() = runTest(dispatcher) {
        val service = FakeConversationService()
        val conversation = testConversation(id = "conversation-1")
        service.conversationsFlow.value = listOf(conversation)
        val viewModel = ConversationListViewModel(service, clock, ZoneOffset.UTC)
        advanceUntilIdle()

        val event = async { viewModel.events.first() }
        viewModel.onSelect(conversation)
        advanceUntilIdle()

        assertThat(service.lastActiveSet).isEqualTo("conversation-1")
        assertThat(event.await())
            .isEqualTo(ConversationListEvent.OpenConversation("conversation-1"))
    }

    @Test
    fun `rename forwards the trimmed title to the service`() = runTest(dispatcher) {
        val service = FakeConversationService()
        val conversation = testConversation(id = "conversation-1")
        service.conversationsFlow.value = listOf(conversation)
        val viewModel = ConversationListViewModel(service, clock, ZoneOffset.UTC)
        advanceUntilIdle()

        viewModel.onRename(conversation, "Renamed")
        advanceUntilIdle()

        assertThat(service.lastRenamed).isEqualTo("conversation-1" to "Renamed")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `delete requires confirmation before calling the service`() = runTest(dispatcher) {
        val service = FakeConversationService()
        val conversation = testConversation(id = "conversation-1")
        service.conversationsFlow.value = listOf(conversation)
        val viewModel = ConversationListViewModel(service, clock, ZoneOffset.UTC)
        advanceUntilIdle()

        viewModel.onDeleteRequested(conversation)
        assertThat(viewModel.state.value.pendingDelete).isEqualTo(conversation)
        assertThat(service.lastDeleted).isNull()

        viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        assertThat(service.lastDeleted).isEqualTo("conversation-1")
        assertThat(viewModel.state.value.pendingDelete).isNull()
    }

    @Test
    fun `dismissing the delete dialog keeps the conversation`() = runTest(dispatcher) {
        val service = FakeConversationService()
        val conversation = testConversation(id = "conversation-1")
        service.conversationsFlow.value = listOf(conversation)
        val viewModel = ConversationListViewModel(service, clock, ZoneOffset.UTC)
        advanceUntilIdle()

        viewModel.onDeleteRequested(conversation)
        viewModel.onDeleteDismissed()
        advanceUntilIdle()

        assertThat(viewModel.state.value.pendingDelete).isNull()
        assertThat(service.lastDeleted).isNull()
    }

    @Test
    fun `delete failure surfaces an error`() = runTest(dispatcher) {
        val service = FakeConversationService()
        val conversation = testConversation(id = "conversation-1")
        service.conversationsFlow.value = listOf(conversation)
        service.deleteError = IllegalStateException("delete failed")
        val viewModel = ConversationListViewModel(service, clock, ZoneOffset.UTC)
        advanceUntilIdle()

        viewModel.onDeleteRequested(conversation)
        viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("delete failed")
    }

    @Test
    fun `last active id is restored into the state`() = runTest(dispatcher) {
        val service = FakeConversationService()
        service.activeFlow.value = "conversation-1"
        val viewModel = ConversationListViewModel(service, clock, ZoneOffset.UTC)
        advanceUntilIdle()

        assertThat(viewModel.state.value.activeConversationId).isEqualTo("conversation-1")
    }
}
