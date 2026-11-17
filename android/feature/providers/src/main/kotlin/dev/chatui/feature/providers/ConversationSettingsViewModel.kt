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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

private val EMPTY_CHAT_PARAMS = ChatParams(
    temperature = null,
    topP = null,
    maxOutputTokens = null,
    stop = emptyList(),
    reasoningEffort = null,
    streaming = true,
    extra = JsonObject(emptyMap()),
)

data class ConversationSettingsUiState(
    val conversation: Conversation? = null,
    val provider: Provider? = null,
    val model: Model? = null,
    val models: List<Model> = emptyList(),
    val providers: List<Provider> = emptyList(),
    val systemPrompt: String = "",
    val params: ChatParams = EMPTY_CHAT_PARAMS,
    val temperatureText: String = params.temperature?.toString().orEmpty(),
    val topPText: String = params.topP?.toString().orEmpty(),
    val maxOutputText: String = params.maxOutputTokens?.toString().orEmpty(),
    val parameterErrors: Map<CoreParameterKey, String> = emptyMap(),
    val stopText: String = "",
    val extrasText: String = "",
    val extrasError: String? = null,
    val unsupportedNote: String? = null,
    val routingSummary: String? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val hasPendingChanges: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false,
) {
    val presetId: String get() = provider?.presetId ?: "openrouter"
    val hasSupportedParameters: Boolean get() = model?.supportedParameters?.isNotEmpty() == true

    val generationSummary: String
        get() = listOfNotNull(
            params.temperature?.let { "Temperature $it" },
            params.topP?.let { "Top P $it" },
            params.maxOutputTokens?.let { "Max output $it" },
            params.stop.takeIf { it.isNotEmpty() }?.let { "${it.size} stop sequence(s)" },
            params.reasoningEffort?.let { "Reasoning ${it.name.lowercase()}" },
            "Streaming off".takeUnless { params.streaming },
        ).joinToString(" · ").ifEmpty { "Model defaults · Streaming on" }

    val advancedSummary: String
        get() {
            val extras = readProviderExtras(params, extraNamespaceForPreset(presetId))
            return listOfNotNull(
                if (extras.isEmpty()) "No provider extras" else "${extras.size} extra JSON field(s)",
                routingSummary?.let { "Routing: $it" },
                "JSON needs correction".takeIf { extrasError != null },
            ).joinToString(" · ")
        }
}

/**
 * One write coordinator for generation, raw extras, routing, and model return.
 * Field patches are rebased onto persistence, never a sibling editor's snapshot.
 */
class ConversationSettingsViewModel(
    private val service: ProviderManagementService,
    private val conversationId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationSettingsUiState())
    val state: StateFlow<ConversationSettingsUiState> = _state.asStateFlow()
    private val writes = Mutex()
    private class Edit(
        val systemPrompt: String? = null,
        val params: ((ChatParams) -> ChatParams)? = null,
    ) {
        fun apply(conversation: Conversation): Conversation = conversation.copy(
            systemPrompt = systemPrompt ?: conversation.systemPrompt,
            params = params?.invoke(conversation.params) ?: conversation.params,
        )
    }
    private val pending = mutableListOf<Edit>()
    private val invalidExtras = mutableMapOf<String, Pair<String, String>>()
    private var editorNamespace: String? = null

    init {
        refresh()
    }

    fun onSystemPromptChanged(value: String) {
        if (!canEdit()) return
        _state.update { it.copy(systemPrompt = value, error = null) }
        enqueue(Edit(systemPrompt = value))
    }

    fun onTemperatureChanged(value: String) {
        if (!canEdit()) return
        _state.update { it.copy(temperatureText = value) }
        val parsed = parseNumber(value, 0.0, 2.0, CoreParameterKey.TEMPERATURE) ?: return
        updateParams { it.copy(temperature = parsed.value) }
    }

    fun onTopPChanged(value: String) {
        if (!canEdit()) return
        _state.update { it.copy(topPText = value) }
        val parsed = parseNumber(value, 0.0, 1.0, CoreParameterKey.TOP_P) ?: return
        updateParams { it.copy(topP = parsed.value) }
    }

    fun onMaxOutputTokensChanged(value: String) {
        if (!canEdit()) return
        _state.update { it.copy(maxOutputText = value) }
        val parsed = value.trim().toIntOrNull()
        val valid = value.isBlank() || (parsed != null && parsed >= 1)
        parameterError(
            CoreParameterKey.MAX_OUTPUT_TOKENS,
            if (valid) null else "Enter a whole number of at least 1, or leave empty for model default.",
        )
        if (valid) updateParams { it.copy(maxOutputTokens = parsed) }
    }

    fun onStopChanged(value: String) {
        if (!canEdit()) return
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
        if (!canEdit()) return
        val namespace = extraNamespaceForPreset(_state.value.presetId)
        val parsed = parseProviderExtras(value)
        _state.update { it.copy(extrasText = value, extrasError = parsed.message) }
        if (!parsed.ok) {
            invalidExtras[namespace] = value to requireNotNull(parsed.message)
            return
        }
        invalidExtras.remove(namespace)
        val extras = parsed.value ?: JsonObject(emptyMap())
        updateParams { withProviderExtras(it, namespace, extras) }
    }

    fun prepareForModelPicker(onReady: () -> Unit) {
        if (!canEdit()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                writes.withLock {
                    if (flushEdits()) onReady()
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun onModelSelected(modelId: String) {
        if (!canEdit()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                writes.withLock {
                    if (flushEdits()) refreshContext(service.setConversationModel(conversationId, modelId))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = providerErrorMessage(error)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    suspend fun persistRouting(transform: (RoutingPreferences) -> RoutingPreferences): Conversation = writes.withLock {
        check(flushEdits()) { "Retry the unsaved settings before changing routing." }
        val latest = requireConversation()
        check(service.getProvider(latest.providerId)?.presetId == "openrouter") {
            "OpenRouter routing is unavailable for this conversation's current provider."
        }
        service.setConversationRouting(conversationId, transform(readRoutingPreferences(latest.params)))
            .also(::acceptPersisted)
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                writes.withLock {
                    if (flushEdits()) refreshContext(requireConversation())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = providerErrorMessage(error)) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun onRetry() {
        refresh()
    }

    fun onDelete() {
        if (!canEdit()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                writes.withLock {
                    service.deleteConversation(conversationId)
                    pending.clear()
                    _state.update { it.copy(deleted = true, hasPendingChanges = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = providerErrorMessage(error)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun onDismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun canEdit(): Boolean =
        _state.value.conversation != null && !_state.value.loading &&
            !_state.value.busy && !_state.value.deleted

    private fun updateParams(transform: (ChatParams) -> ChatParams) {
        if (!canEdit()) return
        _state.update {
            val next = transform(it.params)
            it.copy(
                params = next,
                error = null,
                unsupportedNote = unsupportedParameterNote(next, it.model?.supportedParameters.orEmpty()),
                routingSummary = routingSummaryFor(next, it.presetId),
            )
        }
        enqueue(Edit(params = transform))
    }

    private fun enqueue(edit: Edit) {
        pending += edit
        _state.update { it.copy(hasPendingChanges = true) }
        viewModelScope.launch { writes.withLock { flushEdits() } }
    }

    private suspend fun flushEdits(): Boolean {
        if (pending.isEmpty()) return true
        _state.update { it.copy(saving = true) }
        try {
            while (pending.isNotEmpty()) {
                val edit = pending.first()
                val latest = requireConversation()
                val updated = service.setConversationSettings(
                    conversationId,
                    systemPrompt = edit.systemPrompt,
                    params = edit.params?.invoke(latest.params),
                )
                pending.removeAt(0)
                acceptPersisted(updated)
            }
            _state.update { it.copy(error = null) }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.update { it.copy(error = providerErrorMessage(error)) }
            return false
        } finally {
            _state.update { it.copy(saving = false, hasPendingChanges = pending.isNotEmpty()) }
        }
    }

    private suspend fun requireConversation(): Conversation =
        service.getConversation(conversationId) ?: throw ProviderManagementException(
            ProviderManagementErrorCode.NOT_FOUND,
            "Conversation not found.",
        )

    private suspend fun refreshContext(conversation: Conversation) {
        val providers = service.listProviders()
        val models = service.listModels(conversation.providerId)
        _state.update {
            it.copy(
                providers = providers,
                models = models,
                provider = providers.firstOrNull { provider -> provider.id == conversation.providerId },
                model = models.firstOrNull { model -> model.id == conversation.modelId },
                error = null,
            )
        }
        acceptPersisted(conversation)
    }

    private fun acceptPersisted(conversation: Conversation) {
        val displayed = pending.fold(conversation) { current, edit -> edit.apply(current) }
        _state.update { current ->
            val namespace = extraNamespaceForPreset(current.presetId)
            val changedNamespace = editorNamespace != namespace
            val draft = invalidExtras[namespace]
            val extrasText = when {
                draft != null -> draft.first
                !changedNamespace && parseProviderExtras(current.extrasText).value ==
                    readProviderExtras(displayed.params, namespace) -> current.extrasText
                else -> stringifyProviderExtras(displayed.params, namespace)
            }
            editorNamespace = namespace
            current.copy(
                conversation = conversation,
                systemPrompt = displayed.systemPrompt,
                params = displayed.params,
                temperatureText = numericText(
                    current.temperatureText, displayed.params.temperature,
                    current.parameterErrors[CoreParameterKey.TEMPERATURE],
                ),
                topPText = numericText(
                    current.topPText, displayed.params.topP,
                    current.parameterErrors[CoreParameterKey.TOP_P],
                ),
                maxOutputText = if (CoreParameterKey.MAX_OUTPUT_TOKENS in current.parameterErrors ||
                    current.maxOutputText.trim().toIntOrNull() == displayed.params.maxOutputTokens
                ) current.maxOutputText else displayed.params.maxOutputTokens?.toString().orEmpty(),
                stopText = if (parseStopSequences(current.stopText) == displayed.params.stop) current.stopText
                    else displayed.params.stop.joinToString("\n"),
                extrasText = extrasText,
                extrasError = draft?.second,
                unsupportedNote = unsupportedParameterNote(
                    displayed.params, current.model?.supportedParameters.orEmpty(),
                ),
                routingSummary = routingSummaryFor(displayed.params, current.presetId),
                hasPendingChanges = pending.isNotEmpty(),
            )
        }
    }

    private data class ParsedNumber(val value: Double?)

    private fun parseNumber(value: String, min: Double, max: Double, key: CoreParameterKey): ParsedNumber? {
        val parsed = value.trim().toDoubleOrNull()
        val valid = value.isBlank() || (parsed != null && parsed.isFinite() && parsed in min..max)
        parameterError(key, if (valid) null else "Enter a number from $min to $max, or leave empty for model default.")
        return if (valid) ParsedNumber(parsed) else null
    }

    private fun parameterError(key: CoreParameterKey, message: String?) {
        _state.update {
            it.copy(parameterErrors = if (message == null) it.parameterErrors - key
                else it.parameterErrors + (key to message))
        }
    }

    companion object {
        fun factory(service: ProviderManagementService, conversationId: String): ViewModelProvider.Factory =
            viewModelFactory { initializer { ConversationSettingsViewModel(service, conversationId) } }
    }
}

private fun routingSummaryFor(params: ChatParams, presetId: String): String? =
    if (presetId == "openrouter") routingSummary(readRoutingPreferences(params)) else null

private fun numericText(text: String, value: Double?, error: String?): String =
    if (error != null || text.trim().toDoubleOrNull() == value) text else value?.toString().orEmpty()

private fun parseStopSequences(value: String): List<String> =
    value.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
