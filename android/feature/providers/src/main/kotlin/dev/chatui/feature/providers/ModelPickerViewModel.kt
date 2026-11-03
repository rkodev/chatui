package dev.chatui.feature.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Model
import dev.chatui.core.model.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a model selection is persisted. */
sealed interface ModelPickerTarget {
    data class Conversation(val conversationId: String) : ModelPickerTarget

    data object Defaults : ModelPickerTarget

    data class Manage(val providerId: String) : ModelPickerTarget
}

/** One immutable state for the model picker and manage-models surfaces. */
data class ModelPickerUiState(
    val target: ModelPickerTarget,
    val models: List<Model> = emptyList(),
    val providers: List<Provider> = emptyList(),
    val conversation: Conversation? = null,
    val defaults: ModelDefaults? = null,
    val warning: String? = null,
    val query: String = "",
    val selectedModelId: String? = null,
    val includeHidden: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val manualRemoteId: String = "",
    val manualLabel: String = "",
) {
    val groups: List<ModelPickerGroup>
        get() = buildModelPickerGroups(models, providers, query, includeHidden)

    val selectedModel: Model?
        get() = models.firstOrNull { it.id == selectedModelId }

    val availability: ModelAvailability
        get() = modelAvailability(selectedModel, models)

    val providerId: String?
        get() = when (target) {
            is ModelPickerTarget.Conversation -> conversation?.providerId
            ModelPickerTarget.Defaults -> defaults?.providerId
            is ModelPickerTarget.Manage -> target.providerId
        }
}

/**
 * Model picker state holder.
 *
 * The same state powers the per-conversation picker, the global defaults
 * screen, and the manage-models screen. Selection is persisted immediately
 * through [ProviderManagementService]; pin/hide/manual-add/refresh go through
 * the model repository.
 */
class ModelPickerViewModel(
    private val service: ProviderManagementService,
    private val target: ModelPickerTarget,
    private val includeHidden: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ModelPickerUiState(
            target = target,
            includeHidden = includeHidden,
        ),
    )
    val state: StateFlow<ModelPickerUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onQueryChanged(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun onManualRemoteIdChanged(value: String) {
        _state.update { it.copy(manualRemoteId = value, error = null) }
    }

    fun onManualLabelChanged(value: String) {
        _state.update { it.copy(manualLabel = value, error = null) }
    }

    fun onDismissError() {
        _state.update { it.copy(error = null) }
    }

    fun onSelect(model: Model) {
        if (_state.value.busy) {
            return
        }

        _state.update { it.copy(busy = true, error = null, notice = null) }

        viewModelScope.launch {
            try {
                when (val currentTarget = _state.value.target) {
                    is ModelPickerTarget.Conversation -> {
                        val updated = service.setConversationModel(
                            currentTarget.conversationId,
                            model.id,
                        )
                        val models = service.listModels(updated.providerId)
                        _state.update {
                            it.copy(
                                conversation = updated,
                                models = models,
                                selectedModelId = updated.modelId,
                                busy = false,
                                notice = "Model switched to ${updated.modelLabel}.",
                            )
                        }
                    }

                    ModelPickerTarget.Defaults -> {
                        val defaults = service.setDefaults(model.providerId, model.id)
                        val warning = service.getDefaultModelWarning()
                        _state.update {
                            it.copy(
                                defaults = defaults,
                                selectedModelId = defaults.modelId,
                                warning = warning,
                                busy = false,
                                notice = "Default model set to ${model.label}.",
                            )
                        }
                    }

                    is ModelPickerTarget.Manage -> {
                        _state.update {
                            it.copy(selectedModelId = model.id, busy = false)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(busy = false, error = providerErrorMessage(error))
                }
            }
        }
    }

    fun onPin(model: Model, pinned: Boolean) {
        runAction {
            service.setModelPinned(model.id, pinned)
            reloadModels()
        }
    }

    fun onHide(model: Model, hidden: Boolean) {
        runAction {
            service.setModelHidden(model.id, hidden)
            reloadModels()
        }
    }

    fun onAddManual() {
        val current = _state.value
        val providerId = current.providerId
        if (providerId == null) {
            _state.update { it.copy(error = "Choose a provider before adding a model.") }
            return
        }

        val remoteId = current.manualRemoteId.trim()
        if (remoteId.isEmpty()) {
            _state.update {
                it.copy(error = "Enter the model id, for example author/model.")
            }
            return
        }

        runAction {
            service.addManualModel(
                providerId = providerId,
                input = ManualModelInput(
                    remoteId = remoteId,
                    label = current.manualLabel.trim().takeIf { it.isNotEmpty() },
                ),
            )
            _state.update { it.copy(manualRemoteId = "", manualLabel = "") }
            reloadModels()
        }
    }

    fun onRefresh() {
        val providerId = _state.value.providerId
        if (providerId == null) {
            _state.update { it.copy(error = "Choose a provider before refreshing models.") }
            return
        }

        runAction {
            service.refreshModels(providerId)
            reloadModels()
        }
    }

    fun onRetry() {
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                when (val currentTarget = _state.value.target) {
                    is ModelPickerTarget.Conversation -> {
                        val conversation = service.getConversation(currentTarget.conversationId)
                        if (conversation == null) {
                            _state.update {
                                it.copy(loading = false, error = "Conversation not found.")
                            }
                            return@launch
                        }
                        val models = service.listModels(conversation.providerId)
                        val providers = service.listProviders()
                        _state.update {
                            it.copy(
                                conversation = conversation,
                                models = models,
                                providers = providers,
                                selectedModelId = conversation.modelId,
                                loading = false,
                            )
                        }
                    }

                    ModelPickerTarget.Defaults -> {
                        val defaults = service.getDefaults()
                        val models = defaults.providerId
                            ?.let { providerId -> service.listModels(providerId) }
                            ?: emptyList()
                        val providers = service.listProviders()
                        val warning = service.getDefaultModelWarning()
                        _state.update {
                            it.copy(
                                defaults = defaults,
                                models = models,
                                providers = providers,
                                selectedModelId = defaults.modelId,
                                warning = warning,
                                loading = false,
                            )
                        }
                    }

                    is ModelPickerTarget.Manage -> {
                        val models = service.listModels(currentTarget.providerId)
                        val providers = service.listProviders()
                        _state.update {
                            it.copy(
                                models = models,
                                providers = providers,
                                loading = false,
                            )
                        }
                    }
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

    private fun runAction(block: suspend () -> Unit) {
        if (_state.value.busy) {
            return
        }

        _state.update { it.copy(busy = true, error = null, notice = null) }

        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(busy = false, error = providerErrorMessage(error))
                }
            }
        }
    }

    private suspend fun reloadModels() {
        val providerId = _state.value.providerId ?: return
        val models = service.listModels(providerId)
        _state.update { it.copy(models = models) }
    }

    companion object {
        fun factory(
            service: ProviderManagementService,
            target: ModelPickerTarget,
            includeHidden: Boolean = false,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ModelPickerViewModel(service, target, includeHidden) }
        }
    }
}
