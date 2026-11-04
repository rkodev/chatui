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
}
