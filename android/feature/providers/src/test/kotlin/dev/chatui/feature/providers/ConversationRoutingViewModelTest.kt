package dev.chatui.feature.providers

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRoutingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads inactive routing by default`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ConversationRoutingViewModel(service, "conversation-1")

        advanceUntilIdle()

        assertThat(viewModel.state.value.preferences.enabled).isFalse()
        assertThat(viewModel.state.value.summary).isNull()
    }

    @Test
    fun `persists routing changes and shows a summary`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ConversationRoutingViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onEnabledChanged(true)
        advanceUntilIdle()
        viewModel.onSortChanged(RoutingSort.PRICE)
        advanceUntilIdle()
        viewModel.onDenyDataCollectionChanged(true)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.preferences.enabled).isTrue()
        assertThat(state.preferences.sort).isEqualTo(RoutingSort.PRICE)
        assertThat(state.preferences.denyDataCollection).isTrue()
        assertThat(state.summary)
            .isEqualTo("Fallbacks on, cheapest provider, data collection denied")
        assertThat(service.lastRouting?.denyDataCollection).isTrue()
    }

    @Test
    fun `reports a missing conversation`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ConversationRoutingViewModel(service, "missing")

        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Conversation not found.")
    }

    @Test
    fun `retry persists a failed routing edit after an unrelated snapshot refresh`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ConversationRoutingViewModel(service, "conversation-1")
        advanceUntilIdle()
        service.error = IllegalStateException("Try again")

        viewModel.onEnabledChanged(true)
        advanceUntilIdle()
        val original = service.conversations.getValue("conversation-1")
        val updated = original.copy(params = original.params.copy(temperature = 0.4))
        service.conversations["conversation-1"] = updated
        viewModel.onConversationChanged(updated)
        assertThat(viewModel.state.value.preferences.enabled).isTrue()
        assertThat(viewModel.state.value.error).isEqualTo("Try again")
        assertThat(service.lastRouting).isNull()

        service.error = null
        viewModel.onRetry()
        advanceUntilIdle()

        assertThat(service.lastRouting?.enabled).isTrue()
        assertThat(viewModel.state.value.error).isNull()
        assertThat(service.conversations.getValue("conversation-1").params.temperature).isEqualTo(0.4)
    }

    @Test
    fun `a later routing choice also retries the earlier unsaved choice`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ConversationRoutingViewModel(service, "conversation-1")
        advanceUntilIdle()
        service.error = IllegalStateException("Try again")
        viewModel.onEnabledChanged(true)
        advanceUntilIdle()

        service.error = null
        viewModel.onSortChanged(RoutingSort.PRICE)
        advanceUntilIdle()

        assertThat(service.lastRouting?.enabled).isTrue()
        assertThat(service.lastRouting?.sort).isEqualTo(RoutingSort.PRICE)
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `a field change preserves newer routing fields not yet shown by this editor`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ConversationRoutingViewModel(service, "conversation-1")
        advanceUntilIdle()
        service.routing["conversation-1"] = RoutingPreferences(
            enabled = true,
            allowFallbacks = false,
            sort = RoutingSort.LATENCY,
        )

        viewModel.onDenyDataCollectionChanged(true)
        advanceUntilIdle()

        assertThat(service.lastRouting).isEqualTo(
            RoutingPreferences(
                enabled = true,
                allowFallbacks = false,
                sort = RoutingSort.LATENCY,
                denyDataCollection = true,
            ),
        )
    }
}
