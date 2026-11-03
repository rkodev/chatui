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
class ModelPickerViewModelTest {
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
    fun `conversation picker switches the model immediately`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        service.models += testModel(
            id = "model-2",
            remoteId = "author/second",
            label = "Second",
        )
        val viewModel = ModelPickerViewModel(
            service = service,
            target = ModelPickerTarget.Conversation("conversation-1"),
        )

        advanceUntilIdle()
        assertThat(viewModel.state.value.selectedModelId).isEqualTo("model-1")

        viewModel.onSelect(service.models.first { it.id == "model-2" })
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(service.lastSelectedModelId).isEqualTo("model-2")
        assertThat(state.conversation?.modelId).isEqualTo("model-2")
        assertThat(state.selectedModelId).isEqualTo("model-2")
        assertThat(state.notice).isEqualTo("Model switched to Second.")
    }

    @Test
    fun `defaults picker persists the selected model`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        service.models += testModel(id = "model-2", remoteId = "author/second")
        val viewModel = ModelPickerViewModel(
            service = service,
            target = ModelPickerTarget.Defaults,
        )

        advanceUntilIdle()
        viewModel.onSelect(service.models.first { it.id == "model-2" })
        advanceUntilIdle()

        assertThat(service.defaults.modelId).isEqualTo("model-2")
        assertThat(viewModel.state.value.selectedModelId).isEqualTo("model-2")
    }

    @Test
    fun `manage picker pins hides and adds manual models`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ModelPickerViewModel(
            service = service,
            target = ModelPickerTarget.Manage("provider-1"),
            includeHidden = true,
        )

        advanceUntilIdle()
        val model = service.models.first()

        viewModel.onPin(model, true)
        advanceUntilIdle()
        assertThat(service.models.first().pinned).isTrue()

        viewModel.onHide(model, true)
        advanceUntilIdle()
        assertThat(service.models.first().hidden).isTrue()

        viewModel.onManualRemoteIdChanged("author/manual")
        viewModel.onManualLabelChanged("Manual")
        viewModel.onAddManual()
        advanceUntilIdle()

        assertThat(service.models.map { it.remoteId }).contains("author/manual")
        assertThat(viewModel.state.value.manualRemoteId).isEmpty()
    }

    @Test
    fun `search filters the picker groups`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        service.models += testModel(
            id = "model-2",
            remoteId = "author/second",
            label = "Second",
        )
        val viewModel = ModelPickerViewModel(
            service = service,
            target = ModelPickerTarget.Manage("provider-1"),
        )
        advanceUntilIdle()

        viewModel.onQueryChanged("second")

        val groups = viewModel.state.value.groups
        assertThat(groups.flatMap { it.models }.map { it.id }).containsExactly("model-2")
    }

    @Test
    fun `refresh reloads the catalog`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ModelPickerViewModel(
            service = service,
            target = ModelPickerTarget.Manage("provider-1"),
        )
        advanceUntilIdle()

        viewModel.onRefresh()
        advanceUntilIdle()

        assertThat(service.refreshCalls).isEqualTo(1)
    }

    @Test
    fun `manual add requires a model id`() = runTest(dispatcher) {
        val service = FakeProviderManagementService()
        val viewModel = ModelPickerViewModel(
            service = service,
            target = ModelPickerTarget.Manage("provider-1"),
        )
        advanceUntilIdle()

        viewModel.onAddManual()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error)
            .isEqualTo("Enter the model id, for example author/model.")
    }
}
