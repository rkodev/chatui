package dev.chatui.feature.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.model.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One immutable state for the provider list. */
data class ProviderListUiState(
    val providers: List<Provider> = emptyList(),
    val options: List<ProviderAvailability> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Provider list state holder.
 *
 * Only OpenRouter is enabled in M1. Other presets are shown as "Coming later"
 * and there is no custom-provider creation path.
 */
class ProviderListViewModel(
    private val service: ProviderManagementService,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ProviderListUiState(options = service.providerOptions()),
    )
    val state: StateFlow<ProviderListUiState> = _state.asStateFlow()

    init {
        observeProviders()
    }

    fun onRetry() {
        observeProviders()
    }

    private fun observeProviders() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                service.observeProviders().collect { providers ->
                    _state.update {
                        it.copy(
                            providers = providers,
                            loading = false,
                            error = null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = providerErrorMessage(error),
                    )
                }
            }
        }
    }

    companion object {
        fun factory(service: ProviderManagementService): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ProviderListViewModel(service) }
            }
    }
}
