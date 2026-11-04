package dev.chatui.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.newId
import dev.chatui.core.network.ProviderAdapter
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Number of messages loaded per page. */
const val CHAT_MESSAGE_PAGE_SIZE: Int = 50

/**
 * Chat state holder.
 *
 * The ViewModel resolves the conversation, provider, model, and key, builds the
 * normalized request, and delegates the stream lifecycle to [ChatRunner]. It
 * exposes one immutable [ChatUiState] and intent methods; composables only
 * render state and forward intents.
 */
class ChatViewModel(
    private val conversationId: String,
    private val conversations: ChatConversationSource,
    private val messages: ChatMessageStore,
    private val contextResolver: ChatContextResolver,
    adapter: ProviderAdapter,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = ::newId,
    private val drafts: ChatDrafts = InMemoryChatDrafts(),
    persistIntervalMs: Long = DEFAULT_PERSIST_INTERVAL_MS,
) : ViewModel() {
    private val runner = ChatRunner(
        adapter = adapter,
        messages = messages,
        scope = viewModelScope,
        clock = clock,
        newId = newId,
        persistIntervalMs = persistIntervalMs,
    )

    private val _state = MutableStateFlow(
        ChatUiState(
            conversationId = conversationId,
            draft = drafts.draftFor(conversationId),
        ),
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var oldestLoadedSequence: Long? = null

    init {
        observeConversation()
        observeMessages()
        observeStreaming()
    }

    fun onDraftChange(text: String) {
        setDraft(text)
    }

    fun onLoadOlder() {
        val current = _state.value
        if (current.loadingOlder || !current.hasOlder) return

        val cursor = current.messages.firstOrNull()?.sequence ?: oldestLoadedSequence ?: return
        _state.update { it.copy(loadingOlder = true, error = null) }

        viewModelScope.launch {
            try {
                val older = messages.older(conversationId, cursor, CHAT_MESSAGE_PAGE_SIZE)
                if (older.isNotEmpty()) {
                    oldestLoadedSequence = older.first().sequence
                }

                _state.update { state ->
                    val merged = (older + state.messages)
                        .distinctBy { it.id }
                        .sortedBy { it.sequence }
                    state.copy(
                        messages = merged,
                        loadingOlder = false,
                        hasOlder = older.size >= CHAT_MESSAGE_PAGE_SIZE,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loadingOlder = false,
                        error = storageError(
                            error.message ?: "Could not load older messages.",
                        ),
                    )
                }
            }
        }
    }

    fun onSend() {
        val current = _state.value
        if (current.sending || current.isStreaming) return
        if (current.availability?.available == false) return

        val text = current.draft.trim()
        if (text.isEmpty()) return

        _state.update { it.copy(sending = true, error = null, notice = null) }

        viewModelScope.launch {
            try {
                val conversation = loadConversation() ?: return@launch
                val context = resolveContext(conversation) ?: return@launch
                val history = messages.all(conversationId)
                val nextSequence = (history.maxOfOrNull { it.sequence } ?: 0L) + 1
                val userMessage = newUserMessage(
                    conversation = conversation,
                    text = text,
                    sequence = nextSequence,
                    now = clock.instant(),
                    id = newId(),
                )
                val request = buildChatRequest(
                    conversation = conversation,
                    modelRemoteId = context.modelRemoteId,
                    messages = history + userMessage,
                )
                val result = runner.send(
                    ChatRunInput(
                        conversation = conversation,
                        context = context.providerContext,
                        request = request,
                        userMessage = userMessage,
                        assistantSequence = nextSequence + 1,
                    ),
                )
                applyResult(result, draftOnFailure = text)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        sending = false,
                        error = storageError(error.message ?: "Could not send the message."),
                    )
                }
            }
        }
    }

    fun onStop() {
        viewModelScope.launch {
            runner.cancel(conversationId)
        }
    }

    fun onRegenerate(target: Message) {
        if (_state.value.sending || _state.value.isStreaming) return
        _state.update { it.copy(sending = true, error = null, notice = null) }

        viewModelScope.launch {
            try {
                val conversation = loadConversation() ?: return@launch
                val context = resolveContext(conversation) ?: return@launch
                val history = messages.all(conversationId)
                val beforeTarget = history.filter { it.sequence < target.sequence }
                val request = buildChatRequest(
                    conversation = conversation,
                    modelRemoteId = context.modelRemoteId,
                    messages = beforeTarget,
                )
                val result = runner.regenerate(
                    RegenerateInput(
                        conversation = conversation,
                        context = context.providerContext,
                        request = request,
                        target = target,
                    ),
                )
                applyResult(result, draftOnFailure = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        sending = false,
                        error = storageError(
                            error.message ?: "Could not regenerate the message.",
                        ),
                    )
                }
            }
        }
    }

    fun onRetry(target: Message) {
        if (_state.value.sending || _state.value.isStreaming) return
        _state.update { it.copy(sending = true, error = null, notice = null) }

        viewModelScope.launch {
            try {
                val conversation = loadConversation() ?: return@launch
                val context = resolveContext(conversation) ?: return@launch
                val history = messages.all(conversationId)
                val beforeTarget = history.filter { it.sequence < target.sequence }
                val request = buildChatRequest(
                    conversation = conversation,
                    modelRemoteId = context.modelRemoteId,
                    messages = beforeTarget,
                )
                val result = runner.retry(
                    RegenerateInput(
                        conversation = conversation,
                        context = context.providerContext,
                        request = request,
                        target = target,
                    ),
                )
                applyResult(result, draftOnFailure = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        sending = false,
                        error = storageError(error.message ?: "Could not retry the message."),
                    )
                }
            }
        }
    }

    fun onEditAndResend(target: Message, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_state.value.sending || _state.value.isStreaming) return

        _state.update { it.copy(sending = true, error = null, notice = null) }

        viewModelScope.launch {
            try {
                val conversation = loadConversation() ?: return@launch
                val context = resolveContext(conversation) ?: return@launch
                val history = messages.all(conversationId)
                val lastUser = history.lastOrNull { it.role == MessageRole.USER }

                if (lastUser?.id != target.id) {
                    _state.update {
                        it.copy(
                            sending = false,
                            error = invalidTargetError(
                                "Only the last user message can be edited.",
                            ),
                        )
                    }
                    return@launch
                }

                val updatedTarget = target.copy(
                    parts = listOf(ContentPart.Text(trimmed)),
                    status = MessageStatus.COMPLETE,
                    usage = null,
                    finishReason = null,
                    error = null,
                    updatedAt = clock.instant(),
                )
                val beforeTarget = history.filter { it.sequence < target.sequence }
                val request = buildChatRequest(
                    conversation = conversation,
                    modelRemoteId = context.modelRemoteId,
                    messages = beforeTarget + updatedTarget,
                )
                val result = runner.editAndResend(
                    EditAndResendInput(
                        conversation = conversation,
                        context = context.providerContext,
                        request = request,
                        target = updatedTarget,
                    ),
                )
                applyResult(result, draftOnFailure = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        sending = false,
                        error = storageError(error.message ?: "Could not edit the message."),
                    )
                }
            }
        }
    }

    /** Persist the current partial text when the screen pauses. */
    fun onPause() {
        viewModelScope.launch {
            runner.persistNow(conversationId)
        }
    }

    fun onDismissError() {
        _state.update { it.copy(error = null, notice = null) }
    }

    override fun onCleared() {
        runner.dispose()
        super.onCleared()
    }

    private fun observeConversation() {
        viewModelScope.launch {
            try {
                val conversation = conversations.getConversation(conversationId)
                _state.update {
                    it.copy(
                        conversation = conversation,
                        loading = false,
                        error = if (conversation == null) {
                            invalidTargetError("Conversation $conversationId was not found.")
                        } else {
                            it.error
                        },
                    )
                }

                if (conversation != null) {
                    refreshAvailability(conversation)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = storageError(
                            error.message ?: "Could not load the conversation.",
                        ),
                    )
                }
            }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            try {
                val page = messages.latest(conversationId, CHAT_MESSAGE_PAGE_SIZE)
                oldestLoadedSequence = page.firstOrNull()?.sequence
                _state.update {
                    it.copy(
                        messages = page,
                        loading = false,
                        hasOlder = page.size >= CHAT_MESSAGE_PAGE_SIZE,
                    )
                }

                messages.observeConversation(conversationId).collect { all ->
                    val cursor = oldestLoadedSequence
                    val window = if (cursor == null) {
                        all
                    } else {
                        all.filter { message -> message.sequence >= cursor }
                    }
                    _state.update {
                        it.copy(
                            messages = window,
                            loading = false,
                            hasOlder = cursor != null &&
                                all.any { message -> message.sequence < cursor },
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = storageError(error.message ?: "Could not load messages."),
                    )
                }
            }
        }
    }

    private fun observeStreaming() {
        viewModelScope.launch {
            runner.streams
                .map { streams -> streams[conversationId] }
                .collect { streaming ->
                    val previous = _state.value.streaming
                    val announcement = announcementFor(previous, streaming)
                    _state.update {
                        it.copy(
                            streaming = streaming,
                            announcement = announcement ?: it.announcement,
                        )
                    }
                }
        }
    }

    private suspend fun loadConversation(): Conversation? {
        val conversation = conversations.getConversation(conversationId)

        if (conversation == null) {
            _state.update {
                it.copy(
                    sending = false,
                    error = invalidTargetError("Conversation $conversationId was not found."),
                )
            }
            return null
        }

        _state.update { it.copy(conversation = conversation) }
        return conversation
    }

    private suspend fun refreshAvailability(conversation: Conversation) {
        when (val result = contextResolver.resolve(conversation)) {
            is ChatContextResult.Ready -> {
                _state.update { it.copy(availability = ChatAvailability(available = true)) }
            }

            is ChatContextResult.Unavailable -> {
                _state.update {
                    it.copy(availability = result.toAvailability())
                }
            }
        }
    }

    private suspend fun resolveContext(conversation: Conversation): ChatContext? =
        when (val result = contextResolver.resolve(conversation)) {
            is ChatContextResult.Ready -> {
                _state.update { it.copy(availability = ChatAvailability(available = true)) }
                result.context
            }

            is ChatContextResult.Unavailable -> {
                _state.update {
                    it.copy(
                        sending = false,
                        availability = result.toAvailability(),
                        error = result.error,
                        notice = result.message,
                    )
                }
                null
            }
        }

    private fun ChatContextResult.Unavailable.toAvailability(): ChatAvailability =
        ChatAvailability(
            available = false,
            reason = reason,
            message = message,
        )

    private fun applyResult(result: ChatRunResult, draftOnFailure: String?) {
        when (result) {
            is ChatRunResult.Success -> {
                _state.update {
                    it.copy(
                        sending = false,
                        error = result.state.error,
                        notice = result.state.error?.message,
                    )
                }
                setDraft("")
            }

            is ChatRunResult.Failure -> {
                val nextDraft = when {
                    result.userMessagePersisted -> ""
                    draftOnFailure != null -> draftOnFailure
                    else -> _state.value.draft
                }
                _state.update {
                    it.copy(
                        sending = false,
                        error = result.error ?: storageError(result.message),
                        notice = result.message,
                    )
                }
                setDraft(nextDraft)
            }
        }
    }

    private fun setDraft(text: String) {
        if (text.isEmpty()) {
            drafts.clearDraft(conversationId)
        } else {
            drafts.setDraft(conversationId, text)
        }
        _state.update { it.copy(draft = text) }
    }

    private fun announcementFor(
        previous: StreamingState?,
        next: StreamingState?,
    ): String? {
        if (next == null) return null
        if (previous?.status == next.status) return null

        return when (next.status) {
            MessageStatus.STREAMING -> "Response started"
            MessageStatus.COMPLETE -> "Response complete"
            MessageStatus.CANCELLED -> "Response cancelled"
            MessageStatus.ERROR -> next.error?.let {
                "Response failed. ${describeChatError(it).message}"
            } ?: "Response failed."
            MessageStatus.PENDING -> null
        }
    }

    private fun newUserMessage(
        conversation: Conversation,
        text: String,
        sequence: Long,
        now: Instant,
        id: String,
    ): Message = Message(
        id = id,
        conversationId = conversation.id,
        sequence = sequence,
        role = MessageRole.USER,
        parts = listOf(ContentPart.Text(text)),
        status = MessageStatus.COMPLETE,
        providerId = conversation.providerId,
        modelId = conversation.modelId,
        modelLabel = conversation.modelLabel,
        usage = null,
        finishReason = null,
        error = null,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = now,
        updatedAt = now,
        deletedAt = null,
    )

    private fun storageError(message: String): ChatError = ChatError(
        code = ChatErrorCode.STORAGE,
        message = message,
        providerId = null,
        status = null,
        retryable = false,
        retryAfterSeconds = null,
        requestId = null,
        details = null,
        at = clock.instant(),
    )

    private fun invalidTargetError(message: String): ChatError = ChatError(
        code = ChatErrorCode.INVALID_REQUEST,
        message = message,
        providerId = null,
        status = null,
        retryable = false,
        retryAfterSeconds = null,
        requestId = null,
        details = null,
        at = clock.instant(),
    )

    companion object {
        fun factory(
            conversationId: String,
            conversations: ChatConversationSource,
            messages: ChatMessageStore,
            contextResolver: ChatContextResolver,
            adapter: ProviderAdapter,
            clock: Clock = Clock.systemUTC(),
            newId: () -> String = ::newId,
            drafts: ChatDrafts = InMemoryChatDrafts(),
            persistIntervalMs: Long = DEFAULT_PERSIST_INTERVAL_MS,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    conversationId = conversationId,
                    conversations = conversations,
                    messages = messages,
                    contextResolver = contextResolver,
                    adapter = adapter,
                    clock = clock,
                    newId = newId,
                    drafts = drafts,
                    persistIntervalMs = persistIntervalMs,
                )
            }
        }
    }
}
