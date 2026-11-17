package dev.chatui.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.Density
import dev.chatui.core.model.ReasoningEffort
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Theme
import dev.chatui.feature.providers.ModelDefaults
import dev.chatui.feature.providers.ProviderManagementService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: TestScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsDataStore
    private lateinit var service: ProviderManagementService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = TestScope(UnconfinedTestDispatcher())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(temporaryFolder.newFolder(), "settings.preferences_pb") },
        )
        store = SettingsDataStore(dataStore)
        service = mockk(relaxed = true)
        coEvery { service.listProviders() } returns emptyList()
        coEvery { service.getDefaults() } returns ModelDefaults(null, null)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `appearance changes persist`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()

        viewModel.onThemeChanged(Theme.DARK)
        advanceUntilIdle()
        viewModel.onDensityChanged(Density.COMPACT)
        advanceUntilIdle()
        viewModel.onChatFontSizeChanged(ChatFontSize.LG)
        advanceUntilIdle()

        val stored = store.settings.first()
        assertThat(stored.theme).isEqualTo(Theme.DARK)
        assertThat(stored.density).isEqualTo(Density.COMPACT)
        assertThat(stored.chatFontSize).isEqualTo(ChatFontSize.LG)
    }

    @Test
    fun `behavior flags persist`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()

        viewModel.onSendKeyChanged(SendKey.MOD_ENTER)
        advanceUntilIdle()
        viewModel.onShowTokenUsageChanged(false)
        advanceUntilIdle()
        viewModel.onLoadRemoteImagesChanged(false)
        advanceUntilIdle()

        val stored = store.settings.first()
        assertThat(stored.sendKey).isEqualTo(SendKey.MOD_ENTER)
        assertThat(stored.showTokenUsage).isFalse()
        assertThat(stored.loadRemoteImages).isFalse()
    }

    @Test
    fun `haptic and dynamic color flags persist`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()

        viewModel.onHapticFeedbackChanged(false)
        advanceUntilIdle()
        viewModel.onDynamicColorChanged(true)
        advanceUntilIdle()

        val stored = store.settings.first()
        assertThat(stored.hapticFeedback).isFalse()
        assertThat(stored.dynamicColor).isTrue()
    }

    @Test
    fun `default prompt and parameters persist`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()

        viewModel.onDefaultSystemPromptChanged("Be brief")
        advanceUntilIdle()
        viewModel.onTemperatureChanged("0.3")
        advanceUntilIdle()
        viewModel.onTopPChanged("0.8")
        advanceUntilIdle()
        viewModel.onMaxOutputTokensChanged("512")
        advanceUntilIdle()
        viewModel.onStopChanged("END")
        advanceUntilIdle()
        viewModel.onReasoningEffortChanged(ReasoningEffort.LOW)
        advanceUntilIdle()
        viewModel.onStreamingChanged(false)
        advanceUntilIdle()

        val stored = store.settings.first()
        assertThat(stored.defaultSystemPrompt).isEqualTo("Be brief")
        assertThat(stored.defaultParams.temperature).isEqualTo(0.3)
        assertThat(stored.defaultParams.topP).isEqualTo(0.8)
        assertThat(stored.defaultParams.maxOutputTokens).isEqualTo(512)
        assertThat(stored.defaultParams.stop).containsExactly("END")
        assertThat(stored.defaultParams.reasoningEffort).isEqualTo(ReasoningEffort.LOW)
        assertThat(stored.defaultParams.streaming).isFalse()
    }

    @Test
    fun `default provider change persists through the service`() = runTest(dispatcher) {
        coEvery { service.setDefaults("provider-1", null) } returns
            ModelDefaults("provider-1", null)
        coEvery { service.listModels("provider-1") } returns emptyList()
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()

        viewModel.onDefaultProviderChanged("provider-1")
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `default model change persists through the service`() = runTest(dispatcher) {
        coEvery { service.setDefaults(null, "model-1") } returns
            ModelDefaults(null, "model-1")
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()

        viewModel.onDefaultModelChanged("model-1")
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `service failure is surfaced`() = runTest(dispatcher) {
        coEvery { service.setDefaults(any(), any()) } throws IllegalStateException("nope")
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()

        viewModel.onDefaultProviderChanged("provider-1")
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("nope")
    }

    @Test
    fun `initial and unrelated emissions are quiet while every rapid edit is persisted`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()
        store.setLastActiveConversation("another-chat")
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedRevision).isEqualTo(0)

        viewModel.onThemeChanged(Theme.DARK)
        viewModel.onDensityChanged(Density.COMPACT)
        viewModel.onDefaultSystemPromptChanged("one")
        viewModel.onDefaultSystemPromptChanged("two")
        advanceUntilIdle()

        assertThat(viewModel.state.value.savedRevision).isEqualTo(4)
        assertThat(viewModel.state.value.pendingSaves).isEqualTo(0)
        assertThat(store.current().defaultSystemPrompt).isEqualTo("two")
        assertThat(store.current().theme).isEqualTo(Theme.DARK)
        assertThat(SettingsViewModel(store, service).state.value.savedRevision).isEqualTo(0)
    }

    @Test
    fun `rapid parameter patches preserve each other and external extras`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()
        viewModel.onTemperatureChanged("0.25")
        viewModel.onTopPChanged("0.8")
        viewModel.onMaxOutputTokensChanged("512")
        viewModel.onStopChanged("END\nDONE")
        viewModel.onReasoningEffortChanged(ReasoningEffort.HIGH)
        viewModel.onStreamingChanged(false)
        advanceUntilIdle()

        val params = store.current().defaultParams
        assertThat(params.temperature).isEqualTo(0.25)
        assertThat(params.topP).isEqualTo(0.8)
        assertThat(params.maxOutputTokens).isEqualTo(512)
        assertThat(params.stop).containsExactly("END", "DONE").inOrder()
        assertThat(params.reasoningEffort).isEqualTo(ReasoningEffort.HIGH)
        assertThat(params.streaming).isFalse()
    }

    @Test
    fun `invalid numeric input never clears clamps or writes zero and blank resets to default`() = runTest(dispatcher) {
        store.setDefaultParams(AndroidSettings.DEFAULT_CHAT_PARAMS.copy(temperature = 0.4, maxOutputTokens = 512))
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()
        listOf("garbage", "-1", "3", "NaN", "Infinity").forEach { viewModel.onTemperatureChanged(it) }
        listOf("0", "-1", "1.5", "2147483648").forEach { viewModel.onMaxOutputTokensChanged(it) }
        viewModel.onTopPChanged("2")
        advanceUntilIdle()
        assertThat(store.current().defaultParams.temperature).isEqualTo(0.4)
        assertThat(store.current().defaultParams.maxOutputTokens).isEqualTo(512)
        assertThat(store.current().defaultParams.topP).isNull()
        assertThat(viewModel.state.value.savedRevision).isEqualTo(0)
        assertThat(viewModel.state.value.fieldErrors).hasSize(3)

        viewModel.onTemperatureChanged("")
        viewModel.onTopPChanged("0")
        viewModel.onMaxOutputTokensChanged(" ")
        advanceUntilIdle()
        assertThat(store.current().defaultParams.temperature).isNull()
        assertThat(store.current().defaultParams.topP).isEqualTo(0.0)
        assertThat(store.current().defaultParams.maxOutputTokens).isNull()
        assertThat(viewModel.state.value.fieldErrors).isEmpty()
    }

    @Test
    fun `failed save remains actionable after an unrelated success until retry succeeds`() = runTest(dispatcher) {
        val failingStore = mockk<SettingsDataStore>()
        every { failingStore.settings } returns MutableStateFlow(AndroidSettings())
        var failed = false
        coEvery { failingStore.setTheme(Theme.DARK) } coAnswers {
            if (!failed) {
                failed = true
                throw IllegalStateException("Storage is full")
            }
        }
        coEvery { failingStore.setDensity(Density.COMPACT) } returns Unit
        val viewModel = SettingsViewModel(failingStore, service)
        advanceUntilIdle()

        viewModel.onThemeChanged(Theme.DARK)
        viewModel.onDensityChanged(Density.COMPACT)
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo("Storage is full")
        viewModel.onPickerReturned()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo("Storage is full")
        viewModel.onRetry()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
        coVerify(exactly = 2) { failingStore.setTheme(Theme.DARK) }
        coVerify(exactly = 1) { failingStore.setDensity(Density.COMPACT) }
    }

    @Test
    fun `picker return refreshes persisted defaults and warnings without announcing a local save`() = runTest(dispatcher) {
        coEvery { service.getDefaults() } coAnswers {
            val settings = store.current()
            ModelDefaults(settings.defaultProviderId, settings.defaultModelId)
        }
        coEvery { service.listModels(any()) } returns emptyList()
        coEvery { service.getDefaultModelWarning() } returns "Selected model is hidden."
        val viewModel = SettingsViewModel(store, service)
        advanceUntilIdle()

        store.setDefaultProvider("provider-2", "model-2")
        viewModel.onPickerReturned()
        advanceUntilIdle()

        assertThat(viewModel.state.value.settings.defaultProviderId).isEqualTo("provider-2")
        assertThat(viewModel.state.value.settings.defaultModelId).isEqualTo("model-2")
        assertThat(viewModel.state.value.warning).isEqualTo("Selected model is hidden.")
        assertThat(viewModel.state.value.savedRevision).isEqualTo(0)
    }
}
