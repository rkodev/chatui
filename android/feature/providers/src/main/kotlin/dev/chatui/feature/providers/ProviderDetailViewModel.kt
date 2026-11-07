package dev.chatui.feature.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.model.Provider
import dev.chatui.core.providers.BaseUrlSecurity
import dev.chatui.core.providers.assessBaseUrlSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Busy action shown by the provider detail screen. */
enum class ProviderDetailAction {
    ROTATE,
    REMOVE,
    TOGGLE,
    VALIDATE,
    REFRESH,
}

/** One immutable state for provider details. */
data class ProviderDetailUiState(
    val provider: Provider? = null,
    val modelCount: Int = 0,
    val docsUrl: String? = null,
    val loading: Boolean = true,
    val busy: ProviderDetailAction? = null,
    val error: String? = null,
    val notice: String? = null,
    val newKey: String = "",
    val confirmRemove: Boolean = false,
    val cleartextWarning: String? = null,
    val cleartextError: String? = null,
)

/**
 * Provider detail state holder.
 *
 * The key itself is never rendered. Only the non-secret `/key` metadata cached
 * on the provider record is exposed through [ProviderDetailUiState.provider].
 */
class ProviderDetailViewModel(
    private val service: ProviderManagementService,
    private val providerId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ProviderDetailUiState())
    val state: StateFlow<ProviderDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onNewKeyChanged(value: String) {
        _state.update { it.copy(newKey = value, error = null) }
    }

    fun onCancelRemove() {
        _state.update { it.copy(confirmRemove = false) }
    }

    fun onRotateKey() {
        val current = _state.value
        if (current.busy != null) {
            return
        }

        val key = current.newKey.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(error = "Enter a new API key before rotating.") }
            return
        }

        run(ProviderDetailAction.ROTATE) {
            service.rotateKey(providerId, key)
            val validated = service.validateProviderKey(providerId)
            _state.update {
                it.copy(
                    provider = validated,
                    newKey = "",
                    notice = "Key rotated and validated.",
                )
            }
            validated
        }
    }

    fun onRemoveKey() {
        val current = _state.value
        if (current.busy != null) {
            return
        }

        if (!current.confirmRemove) {
            _state.update { it.copy(confirmRemove = true) }
            return
        }

        run(ProviderDetailAction.REMOVE) {
            val updated = service.removeKey(providerId)
            _state.update {
                it.copy(
                    provider = updated,
                    confirmRemove = false,
                    notice = "Key removed. Add a new key before sending.",
                )
            }
            updated
        }
    }

    fun onToggleEnabled() {
        val current = _state.value
        val provider = current.provider ?: return
        if (current.busy != null) {
            return
        }

        run(ProviderDetailAction.TOGGLE) {
            val updated = service.setProviderEnabled(provider.id, !provider.enabled)
            _state.update { it.copy(provider = updated) }
            updated
        }
    }

    fun onValidate() {
        if (_state.value.busy != null) {
            return
        }

        run(ProviderDetailAction.VALIDATE) {
            val updated = service.validateProviderKey(providerId)
            _state.update {
                it.copy(provider = updated, notice = "Key details refreshed.")
            }
            updated
        }
    }

    fun onRefreshModels() {
        if (_state.value.busy != null) {
            return
        }

        run(ProviderDetailAction.REFRESH) {
            val result = service.refreshModels(providerId)
            _state.update {
                it.copy(
                    modelCount = result.models.size,
                    notice = "Refreshed ${result.models.size} models.",
                )
            }
            result
        }
    }

    fun onRetry() {
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                val provider = service.getProvider(providerId)
                val models = if (provider != null) service.listModels(provider.id) else emptyList()
                val security = provider?.let { assessBaseUrlSecurity(it.baseUrl) }
                _state.update {
                    it.copy(
                        provider = provider,
                        modelCount = models.size,
                        docsUrl = provider?.let { service.docsUrl(it.presetId) },
                        loading = false,
                        error = if (provider == null) "Provider not found." else null,
                        cleartextWarning = (security as? BaseUrlSecurity.AllowedLocalCleartext)
                            ?.warning,
                        cleartextError = (security as? BaseUrlSecurity.RefusedCleartext)?.error,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(loading = false, error = providerErrorMessage(error))
                }
            }
        }
    }

    private fun run(
        action: ProviderDetailAction,
        block: suspend () -> Any,
    ) {
        _state.update { it.copy(busy = action, error = null, notice = null) }

        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(busy = null, error = providerErrorMessage(error))
                }
            }
        }
    }

    companion object {
        fun factory(
            service: ProviderManagementService,
            providerId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProviderDetailViewModel(service, providerId) }
        }
    }
}
