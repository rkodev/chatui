package dev.chatui.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.model.Theme
import dev.chatui.core.network.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * First-run onboarding state holder.
 *
 * The public catalog starts loading when the ViewModel is created, before any
 * key is entered. Connecting is the only path that validates a key, and a
 * failure leaves the typed key and label in state.
 */
class OnboardingViewModel(
    private val service: OnboardingService,
) : ViewModel() {
    private val initialProviders = runCatching { service.providerOptions() }
        .getOrDefault(emptyList())

    private val _state = MutableStateFlow(
        OnboardingUiState(
            providers = initialProviders,
            selectedProviderId = initialProviders
                .firstOrNull { it.recommended }
                ?.id
                ?: initialProviders.firstOrNull { it.enabled }?.id
                ?: "openrouter",
        ),
    )

    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<OnboardingEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<OnboardingEvent> = _events.asSharedFlow()
    private var catalogLoading = false

    init {
        loadCatalog()
        loadTheme()
    }

    fun onThemeSelected(theme: Theme) {
        _state.update { it.copy(theme = theme) }
        viewModelScope.launch {
            try {
                service.setTheme(theme)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // The selected theme is already applied in the UI. Persisting
                // is best-effort so a storage failure never blocks onboarding.
            }
        }
    }

    fun onContinue() {
        _state.update { state ->
            if (state.step == OnboardingStep.WELCOME) {
                state.copy(step = OnboardingStep.KEY_ENTRY, error = null)
            } else {
                state
            }
        }
    }

    fun onBack() {
        _state.update { state ->
            if (state.step == OnboardingStep.KEY_ENTRY && !state.testing && !state.connected) {
                state.copy(step = OnboardingStep.WELCOME, error = null)
            } else {
                state
            }
        }
    }

    fun onProviderSelected(providerId: String) {
        _state.update { state ->
            val option = state.providers.firstOrNull { it.id == providerId }
            if (state.step == OnboardingStep.WELCOME && option?.enabled == true) {
                state.copy(selectedProviderId = providerId)
            } else {
                state
            }
        }
    }

    fun onApiKeyChanged(value: String) {
        _state.update { state ->
            if (state.step == OnboardingStep.KEY_ENTRY && !state.testing && !state.connected) {
                state.copy(apiKey = value, error = null)
            } else {
                state
            }
        }
    }

    fun onLabelChanged(value: String) {
        _state.update { state ->
            if (state.step == OnboardingStep.KEY_ENTRY && !state.testing && !state.connected) {
                state.copy(label = value, error = null)
            } else {
                state
            }
        }
    }

    fun onRetryCatalog() {
        loadCatalog()
    }

    fun onConnect() {
        val current = _state.value
        if (current.step != OnboardingStep.KEY_ENTRY || current.testing || current.connected) {
            return
        }

        val key = current.apiKey.trim()
        if (key.isEmpty()) {
            _state.update {
                it.copy(
                    error = OnboardingErrorCopy(
                        title = "Enter an API key",
                        message = "Paste your OpenRouter API key to continue.",
                        details = null,
                    ),
                )
            }
            return
        }

        _state.update { it.copy(testing = true, error = null, notice = null) }

        viewModelScope.launch {
            try {
                val result = service.connect(
                    apiKey = key,
                    label = current.label.trim().takeIf { it.isNotEmpty() },
                )
                _state.update {
                    it.copy(
                        testing = false,
                        connected = true,
                        notice = result.notice,
                        step = OnboardingStep.BACKUP,
                    )
                }
                _events.tryEmit(OnboardingEvent.Connected)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        testing = false,
                        error = describeOnboardingError(error, key),
                    )
                }
            }
        }
    }

    private fun loadTheme() {
        viewModelScope.launch {
            try {
                val theme = service.currentTheme()
                _state.update { it.copy(theme = theme) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Keep the system default when the persisted preference is
                // unavailable.
            }
        }
    }

    private fun loadCatalog() {
        if (catalogLoading) return
        catalogLoading = true
        _state.update { it.copy(catalog = CatalogUiState.Loading) }

        viewModelScope.launch {
            try {
                val models = service.loadCatalog()
                _state.update {
                    val catalog = models.map { model -> model.toCatalogModel() }
                    it.copy(catalog = CatalogUiState.Ready(catalog), cachedCatalog = catalog)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(catalog = CatalogUiState.Error(describeCatalogError(error)))
                }
            } finally {
                catalogLoading = false
            }
        }
    }

    companion object {
        fun factory(service: OnboardingService): ViewModelProvider.Factory = viewModelFactory {
            initializer { OnboardingViewModel(service) }
        }
    }
}

private fun ModelInfo.toCatalogModel(): CatalogModel = CatalogModel(
    remoteId = remoteId,
    label = label,
    contextWindow = contextWindow,
    isFree = isFree,
)
