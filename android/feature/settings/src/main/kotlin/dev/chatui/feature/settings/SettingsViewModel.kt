package dev.chatui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.Density
import dev.chatui.core.model.Model
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ReasoningEffort
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Theme
import dev.chatui.feature.providers.ProviderManagementService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One immutable state for the settings screens. */
data class SettingsUiState(
    val settings: AndroidSettings = AndroidSettings(),
    val providers: List<Provider> = emptyList(),
    val models: List<Model> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val warning: String? = null,
    val pendingSaves: Int = 0,
    val savedRevision: Long = 0,
    val fieldErrors: Map<String, String> = emptyMap(),
)

/**
 * State holder for appearance and chat-default settings.
 *
 * Appearance and behavior flags persist through [SettingsDataStore]. Default
 * provider/model pairs persist through [ProviderManagementService] so a model
 * can never be stored without its provider.
 */
class SettingsViewModel(
    private val settingsStore: SettingsDataStore,
    private val service: ProviderManagementService,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private val writes = Mutex()
    private data class FailedWrite(val action: suspend () -> Unit, val message: String)
    private val failedWrites = linkedMapOf<String, FailedWrite>()
    private var loadError: String? = null

    init {
        observeSettings()
        viewModelScope.launch {
            settingsStore.settings
                .map { it.defaultProviderId to it.defaultModelId }
                .distinctUntilChanged()
                .collectLatest { reloadDefaults() }
        }
    }

    fun onThemeChanged(theme: Theme) = persist("theme") { settingsStore.setTheme(theme) }

    fun onDensityChanged(density: Density) = persist("density") { settingsStore.setDensity(density) }

    fun onChatFontSizeChanged(size: ChatFontSize) =
        persist("font") { settingsStore.setChatFontSize(size) }

    fun onSendKeyChanged(sendKey: SendKey) = persist("send-key") { settingsStore.setSendKey(sendKey) }

    fun onShowTokenUsageChanged(show: Boolean) =
        persist("usage") { settingsStore.setShowTokenUsage(show) }

    fun onLoadRemoteImagesChanged(load: Boolean) =
        persist("images") { settingsStore.setLoadRemoteImages(load) }

    fun onHapticFeedbackChanged(enabled: Boolean) =
        persist("haptics") { settingsStore.setHapticFeedback(enabled) }

    fun onDynamicColorChanged(enabled: Boolean) =
        persist("dynamic-color") { settingsStore.setDynamicColor(enabled) }

    fun onDefaultSystemPromptChanged(prompt: String) = persist("prompt") {
        settingsStore.setDefaultSystemPrompt(prompt)
    }

    fun onDefaultProviderChanged(providerId: String?) = persist("provider") {
        service.setDefaults(providerId, null)
        reloadDefaults()
    }

    fun onDefaultModelChanged(modelId: String?) {
        val providerId = _state.value.settings.defaultProviderId
        persist("model") {
            service.setDefaults(providerId, modelId)
            reloadDefaults()
        }
    }

    fun onTemperatureChanged(value: String) = numberChanged("temperature", value, 0.0, 2.0) { number ->
        updateParams("temperature") { it.copy(temperature = number) }
    }

    fun onTopPChanged(value: String) = numberChanged("top-p", value, 0.0, 1.0) { number ->
        updateParams("top-p") { it.copy(topP = number) }
    }

    fun onMaxOutputTokensChanged(value: String) {
        val parsed = value.trim().toIntOrNull()
        if (value.isNotBlank() && (parsed == null || parsed < 1)) {
            fieldError("max-output", "Enter a whole number of at least 1, or leave empty.")
            return
        }
        fieldError("max-output", null)
        updateParams("max-output") { it.copy(maxOutputTokens = parsed) }
    }

    fun onStopChanged(value: String) = updateParams("stop") {
        it.copy(stop = parseStopSequences(value))
    }

    fun onReasoningEffortChanged(effort: ReasoningEffort?) = updateParams("reasoning") {
        it.copy(reasoningEffort = effort)
    }

    fun onStreamingChanged(enabled: Boolean) = updateParams("streaming") {
        it.copy(streaming = enabled)
    }

    fun onPickerReturned() {
        viewModelScope.launch { reloadDefaults() }
    }

    fun onRetry() {
        if (failedWrites.isEmpty()) {
            onPickerReturned()
        } else {
            failedWrites.toMap().forEach { (key, failure) -> persist(key, failure.action) }
        }
    }

    fun onDismissError() {
        if (failedWrites.isEmpty()) {
            loadError = null
            _state.update { it.copy(error = null) }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _state.update { it.copy(settings = settings, loading = false) }
            }
        }
    }

    private suspend fun reloadDefaults() {
        try {
            val providers = service.listProviders()
            val defaults = service.getDefaults()
            val models = defaults.providerId?.let { service.listModels(it) } ?: emptyList()
            val warning = service.getDefaultModelWarning()?.takeIf { it.isNotBlank() }
            loadError = null
            _state.update {
                it.copy(
                    providers = providers,
                    models = models,
                    warning = warning,
                    loading = false,
                    error = failedWrites.values.lastOrNull()?.message,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            loadError = error.message ?: "Could not load settings."
            _state.update {
                it.copy(loading = false, error = failedWrites.values.lastOrNull()?.message ?: loadError)
            }
        }
    }

    private fun numberChanged(
        key: String,
        value: String,
        min: Double,
        max: Double,
        onValid: (Double?) -> Unit,
    ) {
        val parsed = value.trim().toDoubleOrNull()
        if (value.isNotBlank() && (parsed == null || !parsed.isFinite() || parsed !in min..max)) {
            fieldError(key, "Enter a number from $min to $max, or leave empty.")
            return
        }
        fieldError(key, null)
        onValid(parsed)
    }

    private fun fieldError(key: String, message: String?) {
        _state.update {
            it.copy(fieldErrors = if (message == null) it.fieldErrors - key else it.fieldErrors + (key to message))
        }
    }

    private fun updateParams(key: String, transform: (ChatParams) -> ChatParams) = persist(key) {
        settingsStore.update { it.copy(defaultParams = transform(it.defaultParams)) }
    }

    private fun persist(key: String, block: suspend () -> Unit) {
        _state.update { it.copy(pendingSaves = it.pendingSaves + 1) }
        viewModelScope.launch {
            writes.withLock {
                try {
                    block()
                    failedWrites.remove(key)
                    _state.update {
                        it.copy(
                            savedRevision = it.savedRevision + 1,
                            error = failedWrites.values.lastOrNull()?.message ?: loadError,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val message = error.message ?: "Could not save settings."
                    failedWrites[key] = FailedWrite(block, message)
                    _state.update { it.copy(error = message) }
                } finally {
                    _state.update { it.copy(pendingSaves = it.pendingSaves - 1) }
                }
            }
        }
    }

    companion object {
        fun factory(
            settingsStore: SettingsDataStore,
            service: ProviderManagementService,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(settingsStore, service) }
        }
    }
}

private fun parseStopSequences(value: String): List<String> = value
    .split('\n')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
