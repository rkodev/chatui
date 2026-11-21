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
class ProviderDetailViewModelTest {
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
    fun `loads provider and model count`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderDetailViewModel(service, "provider-1")

        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.provider?.id).isEqualTo("provider-1")
        assertThat(state.modelCount).isEqualTo(1)
        assertThat(state.docsUrl).isEqualTo("https://openrouter.ai/docs")
        assertThat(state.loading).isFalse()
    }

    @Test
    fun `rotates and validates the key`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderDetailViewModel(service, "provider-1")
        advanceUntilIdle()

        viewModel.onNewKeyChanged("sk-new")
        viewModel.onRotateKey()
        advanceUntilIdle()

        assertThat(service.lastRotatedKey).isEqualTo("sk-new")
        assertThat(viewModel.state.value.newKey).isEmpty()
        assertThat(viewModel.state.value.notice).isEqualTo("Key rotated and validated.")
    }

    @Test
    fun `removal requires confirmation`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderDetailViewModel(service, "provider-1")
        advanceUntilIdle()

        viewModel.onRemoveKey()
        advanceUntilIdle()
        assertThat(viewModel.state.value.confirmRemove).isTrue()
        assertThat(viewModel.state.value.provider?.secretId).isEqualTo("secret-1")

        viewModel.onRemoveKey()
        advanceUntilIdle()
        assertThat(viewModel.state.value.provider?.secretId).isNull()
        assertThat(viewModel.state.value.confirmRemove).isFalse()
    }

    @Test
    fun `toggles the enabled state`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderDetailViewModel(service, "provider-1")
        advanceUntilIdle()

        viewModel.onToggleEnabled()
        advanceUntilIdle()

        assertThat(viewModel.state.value.provider?.enabled).isFalse()
    }

    @Test
    fun `refreshes models`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderDetailViewModel(service, "provider-1")
        advanceUntilIdle()

        viewModel.onRefreshModels()
        advanceUntilIdle()

        assertThat(service.refreshCalls).isEqualTo(1)
        assertThat(viewModel.state.value.notice).isEqualTo("Refreshed 1 models.")
    }

    @Test
    fun `shows an error when the provider is missing`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderDetailViewModel(service, "missing")

        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Provider not found.")
    }

    @Test
    fun `failed rotation retains the replacement for correction`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderDetailViewModel(service, "provider-1")
        advanceUntilIdle()
        viewModel.onNewKeyChanged("fake-replacement")
        service.error = IllegalStateException("Try again")

        viewModel.onRotateKey()
        advanceUntilIdle()

        assertThat(viewModel.state.value.newKey).isEqualTo("fake-replacement")
        assertThat(viewModel.state.value.error).isEqualTo("Try again")
        assertThat(viewModel.state.value.keyChangeVersion).isEqualTo(0)
        service.error = null
        viewModel.onRotateKey()
        advanceUntilIdle()
        assertThat(viewModel.state.value.newKey).isEmpty()
        assertThat(viewModel.state.value.keyChangeVersion).isEqualTo(1)
    }

    @Test
    fun `cancel and confirm key removal retain provider models and conversations`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ProviderDetailViewModel(service, "provider-1")
        advanceUntilIdle()

        viewModel.onRemoveKey()
        viewModel.onCancelRemove()
        assertThat(viewModel.state.value.confirmRemove).isFalse()
        assertThat(service.providersFlow.value.single().secretId).isNotNull()
        viewModel.onRemoveKey()
        viewModel.onRemoveKey()
        advanceUntilIdle()

        assertThat(service.providersFlow.value.single().secretId).isNull()
        assertThat(service.models).hasSize(1)
        assertThat(service.conversations).containsKey("conversation-1")
    }
}
