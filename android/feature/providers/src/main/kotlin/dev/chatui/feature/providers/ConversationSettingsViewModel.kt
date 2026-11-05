package dev.chatui.feature.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Model
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ReasoningEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Empty parameters used before the conversation loads. */
private val EMPTY_CHAT_PARAMS: ChatParams = ChatParams(
    temperature = null,
    topP = null,
    maxOutputTokens = null,
    stop = emptyList(),
    reasoningEffort = null,
    streaming = true,
    extra = JsonObject(emptyMap()),
)

/** One immutable state for the conversation settings sheet. */
data class ConversationSettingsUiState(
    val conversation: Conversation? = null,
    val provider: Provider? = null,
    val model: Model? = null,
    val models: List<Model> = emptyList(),
    val providers: List<Provider> = emptyList(),
    val systemPrompt: String = "",
    val params: ChatParams = EMPTY_CHAT_PARAMS,
    val stopText: String = "",
    val extrasText: String = "",
    val extrasError: String? = null,
    val unsupportedNote: String? = null,
    val routingSummary: String? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false,
) {
    /** Provider preset id used for the extras namespace. */
    val presetId: String
        get() = provider?.presetId ?: "openrouter"

    /** True when the selected model reports at least one supported parameter. */
    val hasSupportedParameters: Boolean
        get() = model?.supportedParameters?.isNotEmpty() == true
}

/**
 * State holder for the full-height conversation settings sheet.
 *
 * Every field persists through the conversation record as it changes. Model
 * switches use the provider-management service so stale provider extras are
 * pruned, while core parameter values are preserved for the next send.
 */
class ConversationSettingsViewModel(
    private val service: ProviderManagementService,
    private val conversationId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationSettingsUiState())
    val state: StateFlow<ConversationSettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onSystemPromptChanged(value: String) {
        _state.update { it.copy(systemPrompt = value, error = null) }
        persist(systemPrompt = value)
    }

    fun onTemperatureChanged(value: String) {
        updateParams { it.copy(temperature = nullableDouble(value, 0.0, 2.0)) }
    }

    fun onTopPChanged(value: String) {
        updateParams { it.copy(topP = nullableDouble(value, 0.0, 1.0)) }
    }

    fun onMaxOutputTokensChanged(value: String) {
        updateParams { it.copy(maxOutputTokens = nullableInt(value, 1)) }
    }

    fun onStopChanged(value: String) {
        _state.update { it.copy(stopText = value) }
        updateParams { it.copy(stop = parseStopSequences(value)) }
    }

    fun onReasoningEffortChanged(effort: ReasoningEffort?) {
        updateParams { it.copy(reasoningEffort = effort) }
    }

    fun onStreamingChanged(enabled: Boolean) {
        updateParams { it.copy(streaming = enabled) }
    }

    fun onExtrasChanged(value: String) {
        _state.update { it.copy(extrasText = value) }

        val parsed = parseProviderExtras(value)
        if (!parsed.ok) {
            _state.update { it.copy(extrasError = parsed.message) }
            return
        }

        _state.update { it.copy(extrasError = null) }
        val extras = parsed.value ?: JsonObject(emptyMap())
        val namespace = extraNamespaceForPreset(_state.value.presetId)
        updateParams { withProviderExtras(it, namespace, extras) }
    }

    fun onModelSelected(modelId: String) {
        if (_state.value.busy) return

        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            try {
                val updated = service.setConversationModel(conversationId, modelId)
                val providers = service.listProviders()
                val models = service.listModels(updated.providerId)
                val provider = providers.firstOrNull { it.id == updated.providerId }
                val model = models.firstOrNull { it.id == updated.modelId }

                _state.update { current ->
                    current.copy(
                        conversation = updated,
                        providers = providers,
                        models = models,
                        provider = provider,
                        model = model,
                        params = updated.params,
                        stopText = updated.params.stop.joinToString("\n"),
                        extrasText = stringifyProviderExtras(
                            updated.params,
                            extraNamespaceForPreset(provider?.presetId ?: "openrouter"),
                        ),
                        extrasError = null,
                        unsupportedNote = unsupportedParameterNote(
                            updated.params,
                            model?.supportedParameters ?: emptyList(),
                        ),
                        routingSummary = routingSummary(
                            readRoutingPreferences(updated.params),
                        ),
                        busy = false,
                    )
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

    fun onDelete() {
        if (_state.value.busy) return

        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            try {
                service.deleteConversation(conversationId)
                _state.update { it.copy(busy = false, deleted = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(busy = false, error = providerErrorMessage(error))
                }
            }
        }
    }

    fun onDismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                val conversation = service.getConversation(conversationId)
                if (conversation == null) {
                    _state.update {
                        it.copy(loading = false, error = "Conversation not found.")
                    }
                    return@launch
                }

                val providers = service.listProviders()
                val models = service.listModels(conversation.providerId)
                val provider = providers.firstOrNull { it.id == conversation.providerId }
                val model = models.firstOrNull { it.id == conversation.modelId }

                _state.update {
                    it.copy(
                        conversation = conversation,
                        providers = providers,
                        models = models,
                        provider = provider,
                        model = model,
                        systemPrompt = conversation.systemPrompt,
                        params = conversation.params,
                        stopText = conversation.params.stop.joinToString("\n"),
                        extrasText = stringifyProviderExtras(
                            conversation.params,
                            extraNamespaceForPreset(provider?.presetId ?: "openrouter"),
                        ),
                        unsupportedNote = unsupportedParameterNote(
                            conversation.params,
                            model?.supportedParameters ?: emptyList(),
                        ),
                        routingSummary = routingSummary(
                            readRoutingPreferences(conversation.params),
                        ),
                        loading = false,
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

    private fun updateParams(transform: (ChatParams) -> ChatParams) {
        val next = transform(_state.value.params)
        _state.update {
            it.copy(
                params = next,
                unsupportedNote = unsupportedParameterNote(
                    next,
                    it.model?.supportedParameters ?: emptyList(),
                ),
            )
        }
        persist(params = next)
    }

    private fun persist(systemPrompt: String? = null, params: ChatParams? = null) {
        viewModelScope.launch {
            try {
                val updated = service.setConversationSettings(
                    conversationId = conversationId,
                    systemPrompt = systemPrompt,
                    params = params,
                )
                _state.update { it.copy(conversation = updated) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = providerErrorMessage(error)) }
            }
        }
    }

    companion object {
        fun factory(
            service: ProviderManagementService,
            conversationId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConversationSettingsViewModel(service, conversationId) }
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
