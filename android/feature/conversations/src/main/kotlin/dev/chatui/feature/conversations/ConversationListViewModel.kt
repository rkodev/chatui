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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which conversation list the user is viewing. */
enum class ConversationListView {
    ACTIVE,
    ARCHIVED,
}

/** One immutable state for the conversation list. */
data class ConversationListUiState(
    val groups: List<ConversationGroup> = emptyList(),
    val archivedGroups: List<ConversationGroup> = emptyList(),
    val view: ConversationListView = ConversationListView.ACTIVE,
    val activeConversationId: String? = null,
    val loading: Boolean = true,
    val archivedLoading: Boolean = false,
    val error: String? = null,
    val archivedError: String? = null,
    val pendingDelete: Conversation? = null,
    val pendingUndo: Conversation? = null,
    val searchQuery: String = "",
    val searchResults: List<ConversationSearchResult> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
)

/** One-shot navigation or feedback event from the conversation list. */
sealed interface ConversationListEvent {
    data class OpenConversation(val conversationId: String) : ConversationListEvent
}

/**
 * Conversation list state holder.
 *
 * The ViewModel owns grouping, new-chat creation, rename, pin/archive, delete
 * confirmation, buffered delete undo, and last-active tracking. Composables
 * only render state and forward intents.
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
    private var archivedJob: Job? = null
    private var activeJob: Job? = null
    private var searchJob: Job? = null
    private var searchGeneration: Int = 0
    private var pendingDeleteJob: Job? = null
    private var pendingUndo: Conversation? = null
    private var pendingUndoWasActive: Boolean = false
    private var activeConversations: List<Conversation> = emptyList()
    private var archivedConversations: List<Conversation> = emptyList()

    /**
     * Scope used only to finish a buffered delete when the ViewModel is cleared.
     * `viewModelScope` is already cancelled by the time `onCleared` runs.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        observeConversations()
        observeArchivedConversations()
        observeActiveConversation()
    }

    fun onRetry() {
        if (_state.value.view == ConversationListView.ARCHIVED) {
            observeArchivedConversations()
        } else {
            observeConversations()
        }
    }

    /** Switch between the active history and the archive view. */
    fun onViewChanged(view: ConversationListView) {
        _state.update { it.copy(view = view) }
    }

    /**
     * Update the search query and schedule a debounced search.
     *
     * Every keystroke cancels the previous job and increments a generation
     * counter. A result is applied only when its generation is still current,
     * so a slow query can never overwrite a newer one.
     */
    fun onSearchQueryChanged(query: String) {
        _state.update {
            it.copy(
                searchQuery = query,
                searchError = null,
            )
        }

        searchJob?.cancel()
        val trimmed = query.trim()

        if (trimmed.isEmpty()) {
            searchGeneration += 1
            _state.update {
                it.copy(
                    searchResults = emptyList(),
                    searchLoading = false,
                    searchError = null,
                )
            }
            return
        }

        val generation = ++searchGeneration
        _state.update { it.copy(searchLoading = true) }

        searchJob = viewModelScope.launch {
            try {
                delay(SEARCH_DEBOUNCE_MS)
                val results = service.searchConversations(trimmed)
                if (generation == searchGeneration) {
                    _state.update {
                        it.copy(
                            searchResults = results,
                            searchLoading = false,
                            searchError = null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == searchGeneration) {
                    _state.update {
                        it.copy(
                            searchLoading = false,
                            searchError = error.message
                                ?: "Could not search conversations.",
                        )
                    }
                }
            }
        }
    }

    /** Retry the current search query after a failure. */
    fun onSearchRetry() {
        onSearchQueryChanged(_state.value.searchQuery)
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

    /** Pin or unpin a conversation. Pinned rows sort first. */
    fun onTogglePin(conversation: Conversation) {
        viewModelScope.launch {
            try {
                service.setPinned(conversation.id, !conversation.pinned)
                _state.update { it.copy(error = null, archivedError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                setError(error.message ?: "Could not update the pin.")
            }
        }
    }

    /** Archive or unarchive a conversation. */
    fun onToggleArchive(conversation: Conversation) {
        viewModelScope.launch {
            try {
                service.setArchived(conversation.id, !conversation.archived)
                _state.update { it.copy(error = null, archivedError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                setError(error.message ?: "Could not update the archive.")
            }
        }
    }

    fun onDeleteRequested(conversation: Conversation) {
        _state.update { it.copy(pendingDelete = conversation) }
    }

    fun onDeleteDismissed() {
        _state.update { it.copy(pendingDelete = null) }
    }

    /**
     * Confirm a delete and buffer it for the undo window.
     *
     * The row disappears immediately, but storage is only mutated when the
     * window expires, another delete is confirmed, or the ViewModel is cleared.
     */
    fun onDeleteConfirmed() {
        val target = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }

        val previous = pendingUndo
        if (previous != null) {
            pendingDeleteJob?.cancel()
            pendingDeleteJob = null
            viewModelScope.launch { commitDelete(previous) }
        }

        pendingUndo = target
        pendingUndoWasActive = _state.value.activeConversationId == target.id
        _state.update { it.copy(pendingUndo = target) }
        applyActive()
        applyArchived()

        if (pendingUndoWasActive) {
            viewModelScope.launch {
                try {
                    service.setLastActiveConversation(null)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // The pointer is a highlight only; the delete still proceeds.
                }
            }
        }

        pendingDeleteJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            pendingDeleteJob = null
            commitDelete(target)
        }
    }

    /** Cancel the buffered delete and restore the row without touching storage. */
    fun onUndoDelete() {
        val target = pendingUndo ?: return
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingUndo = null
        _state.update { it.copy(pendingUndo = null) }
        applyActive()
        applyArchived()

        if (pendingUndoWasActive) {
            pendingUndoWasActive = false
            viewModelScope.launch {
                try {
                    service.setLastActiveConversation(target.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // The pointer is a highlight only; the row is already restored.
                }
            }
        }
    }

    private suspend fun commitDelete(target: Conversation) {
        if (pendingUndo?.id == target.id) {
            pendingUndo = null
            pendingUndoWasActive = false
            _state.update { it.copy(pendingUndo = null) }
            applyActive()
            applyArchived()
        }

        try {
            service.deleteConversation(target.id)
            _state.update { it.copy(error = null) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Storage still has the row, so clearing the pending marker restores it.
            applyActive()
            applyArchived()
            setError(error.message ?: "Could not delete the conversation.")
        }
    }

    private fun setError(message: String) {
        _state.update {
            if (it.view == ConversationListView.ARCHIVED) {
                it.copy(archivedError = message)
            } else {
                it.copy(error = message)
            }
        }
    }

    private fun observeConversations() {
        conversationsJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }

        conversationsJob = viewModelScope.launch {
            try {
                service.observeConversations().collect { conversations ->
                    activeConversations = conversations
                    _state.update { it.copy(error = null) }
                    applyActive()
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

    private fun observeArchivedConversations() {
        archivedJob?.cancel()
        _state.update { it.copy(archivedLoading = true, archivedError = null) }

        archivedJob = viewModelScope.launch {
            try {
                service.observeArchivedConversations().collect { conversations ->
                    archivedConversations = conversations
                    _state.update { it.copy(archivedError = null) }
                    applyArchived()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        archivedLoading = false,
                        archivedError = error.message
                            ?: "Could not load archived conversations.",
                    )
                }
            }
        }
    }

    private fun applyActive() {
        val visible = activeConversations.filterNot { it.id == pendingUndo?.id }
        _state.update {
            it.copy(
                groups = groupConversationsByDate(visible, clock.instant(), zoneId),
                loading = false,
            )
        }
    }

    private fun applyArchived() {
        val visible = archivedConversations.filterNot { it.id == pendingUndo?.id }
        _state.update {
            it.copy(
                archivedGroups = groupConversationsByDate(visible, clock.instant(), zoneId),
                archivedLoading = false,
            )
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

    override fun onCleared() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null

        val target = pendingUndo
        pendingUndo = null

        if (target != null) {
            cleanupScope.launch {
                try {
                    service.deleteConversation(target.id)
                } catch (_: Throwable) {
                    // Best effort: the ViewModel is already gone.
                } finally {
                    cleanupScope.cancel()
                }
            }
        } else {
            cleanupScope.cancel()
        }

        super.onCleared()
    }

    companion object {
        /** Debounce window before a search query reaches the service. */
        const val SEARCH_DEBOUNCE_MS: Long = 300L

        /** Buffered delete window required by spec 3.2. */
        const val UNDO_WINDOW_MS: Long = 5_000L

        fun factory(
            service: ConversationService,
            clock: Clock = Clock.systemUTC(),
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConversationListViewModel(service, clock, zoneId) }
        }
    }
}
