package dev.chatui.feature.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.model.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One immutable state for conversation routing controls. */
data class ConversationRoutingUiState(
    val conversation: Conversation? = null,
    val preferences: RoutingPreferences = DEFAULT_ROUTING_PREFERENCES,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val summary: String?
        get() = routingSummary(preferences)
}

/**
 * Curated OpenRouter routing controls for one conversation.
 *
 * Values are stored in `conversation.params.extra.openrouter.provider` and
 * persisted immediately. The summary is shown only when routing is active.
 */
class ConversationRoutingViewModel(
    private val service: ProviderManagementService,
    private val conversationId: String,
    initialConversation: Conversation? = null,
    private val persistPreferences: suspend ((RoutingPreferences) -> RoutingPreferences) -> Conversation = { transform ->
        service.setConversationRouting(conversationId, transform(service.getConversationRouting(conversationId)))
    },
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationRoutingUiState())
    val state: StateFlow<ConversationRoutingUiState> = _state.asStateFlow()
    private var pendingChange: ((RoutingPreferences) -> RoutingPreferences)? = null

    init {
        if (initialConversation?.id == conversationId) onConversationChanged(initialConversation) else load()
    }

    fun onConversationChanged(conversation: Conversation) {
        val current = _state.value
        if (conversation.id != conversationId || current.busy || conversation == current.conversation) return
        val preferences = readRoutingPreferences(conversation.params)
        val routingChanged = preferences != current.conversation?.params?.let(::readRoutingPreferences)
        if (routingChanged) pendingChange = null
        _state.update {
            it.copy(
                conversation = conversation,
                preferences = if (pendingChange != null) it.preferences else preferences,
                loading = false,
                error = if (routingChanged) null else it.error,
            )
        }
    }

    fun onEnabledChanged(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
    }

    fun onAllowFallbacksChanged(allowFallbacks: Boolean) {
        update { it.copy(allowFallbacks = allowFallbacks) }
    }

    fun onSortChanged(sort: RoutingSort?) {
        update { it.copy(sort = sort) }
    }

    fun onDenyDataCollectionChanged(deny: Boolean) {
        update { it.copy(denyDataCollection = deny) }
    }

    fun onRetry() {
        if (_state.value.busy || _state.value.loading) return
        val change = pendingChange
        if (change == null) load() else persist(change, _state.value.preferences)
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
                val preferences = service.getConversationRouting(conversationId)
                _state.update {
                    it.copy(
                        conversation = conversation,
                        preferences = preferences,
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

    private fun update(transform: (RoutingPreferences) -> RoutingPreferences) {
        val current = _state.value
        if (current.busy || current.loading || current.conversation == null) return

        val previous = pendingChange
        val change: (RoutingPreferences) -> RoutingPreferences =
            if (previous == null) transform else { preferences -> transform(previous(preferences)) }
        persist(change, transform(current.preferences))
    }

    private fun persist(transform: (RoutingPreferences) -> RoutingPreferences, next: RoutingPreferences) {
        pendingChange = transform
        _state.update { it.copy(preferences = next, busy = true, error = null) }

        viewModelScope.launch {
            try {
                val updated = persistPreferences(transform)
                pendingChange = null
                _state.update {
                    it.copy(
                        conversation = updated,
                        preferences = readRoutingPreferences(updated.params),
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

    companion object {
        fun factory(
            service: ProviderManagementService,
            conversationId: String,
            initialConversation: Conversation? = null,
            persistPreferences: suspend ((RoutingPreferences) -> RoutingPreferences) -> Conversation = { transform ->
                service.setConversationRouting(conversationId, transform(service.getConversationRouting(conversationId)))
            },
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ConversationRoutingViewModel(service, conversationId, initialConversation, persistPreferences)
            }
        }
    }
}
