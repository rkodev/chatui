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
    fun `lists providers and exposes M2 preset availability`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderListViewModel(service)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.providers.map { it.id }).containsExactly("provider-1")
        assertThat(state.options.first { it.id == "openrouter" }.enabled).isTrue()
        assertThat(state.options.first { it.id == "openai" }.enabled).isTrue()
        assertThat(state.loading).isFalse()
    }

    @Test
    fun `preset form creates an enabled preset provider`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderListViewModel(service)
        advanceUntilIdle()

        viewModel.onPresetSelected("openai")
        viewModel.onPresetLabelChanged("Work OpenAI")
        viewModel.onPresetApiKeyChanged("sk-openai")
        viewModel.onCreatePresetProvider()
        advanceUntilIdle()

        val input = service.lastPresetInput
        assertThat(input).isNotNull()
        assertThat(input!!.presetId).isEqualTo("openai")
        assertThat(input.apiKey).isEqualTo("sk-openai")
        assertThat(input.label).isEqualTo("Work OpenAI")

        val state = viewModel.state.value
        assertThat(state.creatingPreset).isFalse()
        assertThat(state.presetForm.presetId).isNull()
        assertThat(state.notice).isEqualTo("OpenAI added.")
    }

    @Test
    fun `preset form requires a preset and a key`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderListViewModel(service)
        advanceUntilIdle()

        viewModel.onCreatePresetProvider()
        assertThat(viewModel.state.value.presetForm.error)
            .isEqualTo("Choose a provider preset.")

        viewModel.onPresetSelected("openai")
        viewModel.onCreatePresetProvider()
        assertThat(viewModel.state.value.presetForm.error)
            .isEqualTo("Enter an API key for the selected provider.")
        assertThat(service.lastPresetInput).isNull()
    }

    @Test
    fun `custom form creates a provider and resets`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderListViewModel(service)
        advanceUntilIdle()

        viewModel.onCustomLabelChanged("My server")
        viewModel.onCustomBaseUrlChanged("https://api.example.com/v1")
        viewModel.onCustomApiKeyChanged("sk-custom")
        viewModel.onAddCustomHeader()
        viewModel.onCustomHeaderNameChanged(0, "X-Org")
        viewModel.onCustomHeaderValueChanged(0, "team-a")
        viewModel.onCreateCustomProvider()
        advanceUntilIdle()

        val input = service.lastCustomInput
        assertThat(input).isNotNull()
        assertThat(input!!.baseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(input.apiKey).isEqualTo("sk-custom")
        assertThat(input.label).isEqualTo("My server")
        assertThat(input.headers).containsExactly("X-Org", "team-a")

        val state = viewModel.state.value
        assertThat(state.creatingCustom).isFalse()
        assertThat(state.customForm.baseUrl).isEmpty()
        assertThat(state.notice).isEqualTo("Custom provider added.")
    }

    @Test
    fun `custom form shows service errors`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        service.createError = dev.chatui.core.providers.ProviderServiceException(
            dev.chatui.core.providers.ProviderServiceErrorCode.INVALID_INPUT,
            "Enter a base URL.",
        )
        val viewModel = ProviderListViewModel(service)
        advanceUntilIdle()

        viewModel.onCreateCustomProvider()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.creatingCustom).isFalse()
        assertThat(state.customForm.error).isEqualTo("Enter a base URL.")
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
