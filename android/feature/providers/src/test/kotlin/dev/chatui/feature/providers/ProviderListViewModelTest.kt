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
class ProviderListViewModelTest {
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
    fun `lists providers and keeps other presets unavailable`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderListViewModel(service)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.providers.map { it.id }).containsExactly("provider-1")
        assertThat(state.options.first { it.id == "openrouter" }.enabled).isTrue()
        assertThat(state.options.first { it.id == "openai" }.enabled).isFalse()
        assertThat(state.options.first { it.id == "openai" }.statusLabel)
            .isEqualTo("Coming later")
        assertThat(state.loading).isFalse()
    }

    @Test
    fun `shows an error when the provider flow fails`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        service.providersError = IllegalStateException("boom")
        val viewModel = ProviderListViewModel(service)

        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("boom")
    }
}
