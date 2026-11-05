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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One immutable state for the settings screens. */
data class SettingsUiState(
    val settings: AndroidSettings = AndroidSettings(),
    val providers: List<Provider> = emptyList(),
    val models: List<Model> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
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

    init {
        observeSettings()
        loadProviders()
    }

    fun onThemeChanged(theme: Theme) = persist { it.setTheme(theme) }

    fun onDensityChanged(density: Density) = persist { it.setDensity(density) }

    fun onChatFontSizeChanged(size: ChatFontSize) = persist { it.setChatFontSize(size) }

    fun onSendKeyChanged(sendKey: SendKey) = persist { it.setSendKey(sendKey) }

    fun onShowTokenUsageChanged(show: Boolean) = persist { it.setShowTokenUsage(show) }

    fun onLoadRemoteImagesChanged(load: Boolean) = persist { it.setLoadRemoteImages(load) }

    fun onDefaultSystemPromptChanged(prompt: String) = persist {
        it.setDefaultSystemPrompt(prompt)
    }

    fun onDefaultProviderChanged(providerId: String?) {
        viewModelScope.launch {
            try {
                service.setDefaults(providerId, null)
                val models = providerId?.let { service.listModels(it) } ?: emptyList()
                _state.update { it.copy(models = models, error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: "Could not save defaults.") }
            }
        }
    }

    fun onDefaultModelChanged(modelId: String?) {
        val providerId = _state.value.settings.defaultProviderId
        viewModelScope.launch {
            try {
                service.setDefaults(providerId, modelId)
                _state.update { it.copy(error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: "Could not save defaults.") }
            }
        }
    }

    fun onTemperatureChanged(value: String) = updateParams {
        it.copy(temperature = nullableDouble(value, 0.0, 2.0))
    }

    fun onTopPChanged(value: String) = updateParams {
        it.copy(topP = nullableDouble(value, 0.0, 1.0))
    }

    fun onMaxOutputTokensChanged(value: String) = updateParams {
        it.copy(maxOutputTokens = nullableInt(value, 1))
    }

    fun onStopChanged(value: String) = updateParams {
        it.copy(stop = parseStopSequences(value))
    }

    fun onReasoningEffortChanged(effort: ReasoningEffort?) = updateParams {
        it.copy(reasoningEffort = effort)
    }

    fun onStreamingChanged(enabled: Boolean) = updateParams {
        it.copy(streaming = enabled)
    }

    fun onDismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _state.update { it.copy(settings = settings, loading = false) }
            }
        }
    }

    private fun loadProviders() {
        viewModelScope.launch {
            try {
                val providers = service.listProviders()
                val defaults = service.getDefaults()
                val models = defaults.providerId?.let { service.listModels(it) } ?: emptyList()
                _state.update {
                    it.copy(providers = providers, models = models, loading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Could not load settings.",
                    )
                }
            }
        }
    }

    private fun updateParams(transform: (ChatParams) -> ChatParams) {
        val next = transform(_state.value.settings.defaultParams)
        _state.update {
            it.copy(settings = it.settings.copy(defaultParams = next))
        }
        persist { it.setDefaultParams(next) }
    }

    private fun persist(block: suspend (SettingsDataStore) -> Unit) {
        viewModelScope.launch {
            try {
                block(settingsStore)
                _state.update { it.copy(error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(error = error.message ?: "Could not save settings.")
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

private fun nullableDouble(value: String, min: Double, max: Double): Double? {
    if (value.trim().isEmpty()) return null
    val parsed = value.trim().toDoubleOrNull() ?: return null
    return parsed.coerceIn(min, max)
}

private fun nullableInt(value: String, min: Int): Int? {
    if (value.trim().isEmpty()) return null
    val parsed = value.trim().toIntOrNull() ?: return null
    return parsed.coerceAtLeast(min)
}

private fun parseStopSequences(value: String): List<String> = value
    .split('\n')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
