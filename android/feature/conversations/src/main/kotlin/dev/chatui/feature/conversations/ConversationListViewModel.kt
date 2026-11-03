package dev.chatui.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.model.Conversation
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One immutable state for the conversation list. */
data class ConversationListUiState(
    val groups: List<ConversationGroup> = emptyList(),
    val activeConversationId: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val pendingDelete: Conversation? = null,
)

/** One-shot navigation or feedback event from the conversation list. */
sealed interface ConversationListEvent {
    data class OpenConversation(val conversationId: String) : ConversationListEvent
}

/**
 * Conversation list state holder.
 *
 * The ViewModel owns grouping, new-chat creation, rename, delete confirmation,
 * and last-active tracking. Composables only render state and forward intents.
 */
class ConversationListViewModel(
    private val service: ConversationService,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationListUiState())
    val state: StateFlow<ConversationListUiState> = _state.asStateFlow()

    private val eventChannel = Channel<ConversationListEvent>(Channel.BUFFERED)
    val events: Flow<ConversationListEvent> = eventChannel.receiveAsFlow()

    private var conversationsJob: Job? = null
    private var activeJob: Job? = null

    init {
        observeConversations()
        observeActiveConversation()
    }

    fun onRetry() {
        observeConversations()
    }

    fun onNewChat() {
        viewModelScope.launch {
            try {
                when (val result = service.createConversation()) {
                    is CreateConversationResult.Created -> {
                        _state.update { it.copy(error = null) }
                        eventChannel.send(
                            ConversationListEvent.OpenConversation(result.conversation.id),
                        )
                    }

                    is CreateConversationResult.NotConfigured -> {
                        _state.update { it.copy(error = result.message) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(error = error.message ?: "Could not start a new chat.")
                }
            }
        }
    }

    fun onSelect(conversation: Conversation) {
        viewModelScope.launch {
            try {
                service.setLastActiveConversation(conversation.id)
                _state.update { it.copy(error = null) }
                eventChannel.send(ConversationListEvent.OpenConversation(conversation.id))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(error = error.message ?: "Could not open the conversation.")
                }
            }
        }
    }

    fun onRename(conversation: Conversation, title: String) {
        viewModelScope.launch {
            try {
                service.renameConversation(conversation.id, title)
                _state.update { it.copy(error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(error = error.message ?: "Could not rename the conversation.")
                }
            }
        }
    }

    fun onDeleteRequested(conversation: Conversation) {
        _state.update { it.copy(pendingDelete = conversation) }
    }

    fun onDeleteDismissed() {
        _state.update { it.copy(pendingDelete = null) }
    }

    fun onDeleteConfirmed() {
        val target = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }

        viewModelScope.launch {
            try {
                service.deleteConversation(target.id)
                _state.update { it.copy(error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(error = error.message ?: "Could not delete the conversation.")
                }
            }
        }
    }

    private fun observeConversations() {
        conversationsJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }

        conversationsJob = viewModelScope.launch {
            try {
                service.observeConversations().collect { conversations ->
                    _state.update {
                        it.copy(
                            groups = groupConversationsByDate(
                                conversations = conversations,
                                now = clock.instant(),
                                zoneId = zoneId,
                            ),
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
                        error = error.message ?: "Could not load conversations.",
                    )
                }
            }
        }
    }

    private fun observeActiveConversation() {
        activeJob?.cancel()

        activeJob = viewModelScope.launch {
            try {
                service.observeLastActiveConversationId().collect { id ->
                    _state.update { it.copy(activeConversationId = id) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // The active pointer is a highlight only; a failure must not
                // hide the conversation list.
            }
        }
    }

    companion object {
        fun factory(
            service: ConversationService,
            clock: Clock = Clock.systemUTC(),
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConversationListViewModel(service, clock, zoneId) }
        }
    }
}
