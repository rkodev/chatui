package dev.chatui.feature.settings

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
class DataSettingsViewModelTest {
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
    fun `initial load exposes usage and stored keys`() = runTest(dispatcher) {
        val service = FakeDataControls()
        val viewModel = DataSettingsViewModel(service)
        advanceUntilIdle()

        assertThat(viewModel.state.value.loading).isFalse()
        assertThat(viewModel.state.value.usage).isEqualTo(service.usage)
        assertThat(viewModel.state.value.keys).isEqualTo(service.keys)
    }

    @Test
    fun `clear all stays disabled until the confirmation phrase matches`() = runTest(dispatcher) {
        val service = FakeDataControls()
        val viewModel = DataSettingsViewModel(service)
        advanceUntilIdle()

        viewModel.onConfirmationPhraseChanged("delete all data")
        advanceUntilIdle()
        viewModel.onClearAll()
        advanceUntilIdle()

        assertThat(viewModel.state.value.canClear).isFalse()
        assertThat(service.clearCalls).isEqualTo(0)

        viewModel.onConfirmationPhraseChanged(CLEAR_ALL_CONFIRMATION)
        advanceUntilIdle()
        viewModel.onClearAll()
        advanceUntilIdle()

        assertThat(service.clearCalls).isEqualTo(1)
        assertThat(viewModel.state.value.confirmationPhrase).isEmpty()
        assertThat(viewModel.state.value.status).contains("Removed 1 conversation")
    }

    @Test
    fun `per-key deletion requires a second confirmation`() = runTest(dispatcher) {
        val service = FakeDataControls()
        val viewModel = DataSettingsViewModel(service)
        advanceUntilIdle()

        viewModel.onRequestRemoveKey("provider-1")
        advanceUntilIdle()

        assertThat(viewModel.state.value.confirmKeyId).isEqualTo("provider-1")
        assertThat(service.removedKeys).isEmpty()

        viewModel.onConfirmRemoveKey("provider-1")
        advanceUntilIdle()

        assertThat(service.removedKeys).containsExactly("provider-1")
        assertThat(viewModel.state.value.confirmKeyId).isNull()
        assertThat(viewModel.state.value.status).contains("Key removed")
    }

    @Test
    fun `load failure is surfaced`() = runTest(dispatcher) {
        val service = FakeDataControls().apply {
            error = IllegalStateException("nope")
        }
        val viewModel = DataSettingsViewModel(service)
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("nope")
        assertThat(viewModel.state.value.loading).isFalse()
    }
}

private class FakeDataControls : DataControls {
    var usage: StorageUsage = StorageUsage(
        conversations = StorageUsageEntry(1, 10),
        messages = StorageUsageEntry(2, 20),
        attachments = StorageUsageEntry(3, 30),
        secrets = StorageUsageEntry(1, 40),
        providers = StorageUsageEntry(1, 50),
        models = StorageUsageEntry(1, 60),
    )
    var keys: List<ProviderKeySummary> = listOf(
        ProviderKeySummary(
            id = "provider-1",
            label = "Work",
            presetId = "custom",
            hasKey = true,
            keyLabel = "Team key",
        ),
    )
    var clearCalls: Int = 0
    val removedKeys: MutableList<String> = mutableListOf()
    var error: Throwable? = null

    override suspend fun getUsage(): StorageUsage {
        error?.let { throw it }
        return usage
    }

    override suspend fun clearAll(): ClearAllResult {
        error?.let { throw it }
        clearCalls += 1
        usage = StorageUsage(
            conversations = StorageUsageEntry(0, 0),
            messages = StorageUsageEntry(0, 0),
            attachments = StorageUsageEntry(0, 0),
            secrets = StorageUsageEntry(0, 0),
            providers = StorageUsageEntry(0, 0),
            models = StorageUsageEntry(0, 0),
        )
        keys = emptyList()
        return ClearAllResult(
            conversations = 1,
            messages = 2,
            attachments = 3,
            secrets = 1,
            providers = 1,
            models = 1,
        )
    }

    override suspend fun listProviderKeys(): List<ProviderKeySummary> {
        error?.let { throw it }
        return keys
    }

    override suspend fun removeProviderKey(providerId: String) {
        error?.let { throw it }
        removedKeys += providerId
        keys = keys.map { key ->
            if (key.id == providerId) key.copy(hasKey = false, keyLabel = null) else key
        }
    }
}
