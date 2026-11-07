package dev.chatui.feature.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.providers.BaseUrlSecurity
import dev.chatui.core.providers.CreateCustomProviderInput
import dev.chatui.core.providers.CreatePresetProviderInput
import dev.chatui.core.providers.assessBaseUrlSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One editable non-secret header row in the Custom provider form. */
data class CustomHeaderField(
    val name: String = "",
    val value: String = "",
)

/** Editable state for the Custom provider creation form. */
data class CustomProviderFormState(
    val label: String = "",
    val baseUrl: String = "",
    val authType: ProviderAuthType = ProviderAuthType.BEARER,
    val apiKey: String = "",
    val headers: List<CustomHeaderField> = emptyList(),
    val error: String? = null,
    val cleartextWarning: String? = null,
    val cleartextError: String? = null,
)

/** Editable state for creating a provider from an enabled preset. */
data class PresetProviderFormState(
    val presetId: String? = null,
    val label: String = "",
    val apiKey: String = "",
    val error: String? = null,
)

/** One immutable state for the provider list. */
data class ProviderListUiState(
    val providers: List<Provider> = emptyList(),
    val options: List<ProviderAvailability> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val presetForm: PresetProviderFormState = PresetProviderFormState(),
    val creatingPreset: Boolean = false,
    val customForm: CustomProviderFormState = CustomProviderFormState(),
    val creatingCustom: Boolean = false,
    val notice: String? = null,
)

/**
 * Provider list state holder.
 *
 * M2 enables every OpenAI-compatible preset and Custom provider creation.
 * Native-adapter presets remain visible as "Coming later".
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

    fun onPresetSelected(presetId: String) {
        _state.update {
            it.copy(
                presetForm = it.presetForm.copy(presetId = presetId, error = null),
            )
        }
    }

    fun onPresetLabelChanged(value: String) {
        _state.update {
            it.copy(presetForm = it.presetForm.copy(label = value, error = null))
        }
    }

    fun onPresetApiKeyChanged(value: String) {
        _state.update {
            it.copy(presetForm = it.presetForm.copy(apiKey = value, error = null))
        }
    }

    fun onCreatePresetProvider() {
        val current = _state.value
        if (current.creatingPreset) {
            return
        }

        val form = current.presetForm
        val presetId = form.presetId
        if (presetId == null) {
            _state.update {
                it.copy(presetForm = form.copy(error = "Choose a provider preset."))
            }
            return
        }

        val option = current.options.firstOrNull { it.id == presetId }
        if (option == null || !option.enabled) {
            _state.update {
                it.copy(
                    presetForm = form.copy(
                        error = "This provider preset is not available yet.",
                    ),
                )
            }
            return
        }

        val apiKey = form.apiKey.trim()
        if (option.requiresKey && apiKey.isEmpty()) {
            _state.update {
                it.copy(
                    presetForm = form.copy(
                        error = "Enter an API key for the selected provider.",
                    ),
                )
            }
            return
        }

        _state.update {
            it.copy(
                creatingPreset = true,
                notice = null,
                presetForm = form.copy(error = null),
            )
        }

        viewModelScope.launch {
            try {
                service.createPresetProvider(
                    CreatePresetProviderInput(
                        presetId = presetId,
                        apiKey = apiKey.takeIf { option.requiresKey },
                        label = form.label.takeIf { it.isNotBlank() },
                    ),
                )
                _state.update {
                    it.copy(
                        creatingPreset = false,
                        presetForm = PresetProviderFormState(),
                        notice = "${option.label} added.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        creatingPreset = false,
                        presetForm = it.presetForm.copy(
                            error = providerErrorMessage(error),
                        ),
                    )
                }
            }
        }
    }

    fun onCustomLabelChanged(value: String) {
        updateCustomForm { it.copy(label = value, error = null) }
    }

    fun onCustomBaseUrlChanged(value: String) {
        val trimmed = value.trim()
        val assessment = assessBaseUrlSecurity(trimmed)
        val isHttp = trimmed.startsWith("http://", ignoreCase = true)

        updateCustomForm {
            it.copy(
                baseUrl = value,
                error = null,
                cleartextWarning = (assessment as? BaseUrlSecurity.AllowedLocalCleartext)
                    ?.warning,
                cleartextError = if (isHttp) {
                    (assessment as? BaseUrlSecurity.RefusedCleartext)?.error
                } else {
                    null
                },
            )
        }
    }

    fun onCustomAuthTypeChanged(value: ProviderAuthType) {
        updateCustomForm { it.copy(authType = value, error = null) }
    }

    fun onCustomApiKeyChanged(value: String) {
        updateCustomForm { it.copy(apiKey = value, error = null) }
    }

    fun onAddCustomHeader() {
        updateCustomForm { it.copy(headers = it.headers + CustomHeaderField()) }
    }

    fun onCustomHeaderNameChanged(index: Int, value: String) {
        updateCustomHeader(index) { it.copy(name = value) }
    }

    fun onCustomHeaderValueChanged(index: Int, value: String) {
        updateCustomHeader(index) { it.copy(value = value) }
    }

    fun onRemoveCustomHeader(index: Int) {
        updateCustomForm { form ->
            if (index !in form.headers.indices) {
                form
            } else {
                form.copy(headers = form.headers.filterIndexed { i, _ -> i != index })
            }
        }
    }

    fun onCreateCustomProvider() {
        val current = _state.value
        if (current.creatingCustom) {
            return
        }

        val form = current.customForm
        _state.update {
            it.copy(
                creatingCustom = true,
                notice = null,
                customForm = form.copy(error = null),
            )
        }

        viewModelScope.launch {
            try {
                service.createCustomProvider(
                    CreateCustomProviderInput(
                        baseUrl = form.baseUrl,
                        authType = form.authType,
                        apiKey = form.apiKey.takeIf { it.isNotBlank() },
                        label = form.label.takeIf { it.isNotBlank() },
                        headers = form.headers
                            .filter { it.name.isNotBlank() || it.value.isNotBlank() }
                            .associate { it.name.trim() to it.value.trim() },
                    ),
                )
                _state.update {
                    it.copy(
                        creatingCustom = false,
                        customForm = CustomProviderFormState(),
                        notice = "Custom provider added.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        creatingCustom = false,
                        customForm = it.customForm.copy(
                            error = providerErrorMessage(error),
                        ),
                    )
                }
            }
        }
    }

    private fun updateCustomForm(transform: (CustomProviderFormState) -> CustomProviderFormState) {
        _state.update { it.copy(customForm = transform(it.customForm)) }
    }

    private fun updateCustomHeader(
        index: Int,
        transform: (CustomHeaderField) -> CustomHeaderField,
    ) {
        updateCustomForm { form ->
            if (index !in form.headers.indices) {
                form
            } else {
                form.copy(
                    headers = form.headers.mapIndexed { i, header ->
                        if (i == index) transform(header) else header
                    },
                    error = null,
                )
            }
        }
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
