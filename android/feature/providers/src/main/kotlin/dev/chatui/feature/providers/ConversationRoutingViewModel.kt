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
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationRoutingUiState())
    val state: StateFlow<ConversationRoutingUiState> = _state.asStateFlow()

    init {
        load()
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
        load()
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
        if (_state.value.busy) {
            return
        }

        val next = transform(_state.value.preferences)
        _state.update { it.copy(preferences = next, busy = true, error = null) }

        viewModelScope.launch {
            try {
                val updated = service.setConversationRouting(conversationId, next)
                _state.update {
                    it.copy(conversation = updated, busy = false)
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
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConversationRoutingViewModel(service, conversationId) }
        }
    }
}
