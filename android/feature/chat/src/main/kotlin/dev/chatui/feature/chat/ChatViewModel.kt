package dev.chatui.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.database.attachment.AttachmentInput
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.newId
import dev.chatui.core.network.ProviderAdapter
import dev.chatui.core.network.ResolvedAttachment
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
    private val attachments: ChatAttachmentStore? = null,
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
        cleanupAttachments()
    }

    fun onDraftChange(text: String) {
        setDraft(text)
    }

    /** Add files from a picker, clipboard, or drag-and-drop. */
    fun onAddAttachments(inputs: List<AttachmentInput>) {
        val store = attachments ?: return
        if (inputs.isEmpty()) return

        viewModelScope.launch {
            try {
                val result = store.addFiles(inputs)
                _state.update { state ->
                    state.copy(
                        pendingAttachments = state.pendingAttachments + result.attachments,
                        attachmentError = result.errors.firstOrNull()?.message,
                        confirmSendWithUnsupportedAttachments = false,
                    )
                }
                updateAttachmentWarning()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        attachmentError = error.message ?: "Could not add the attachment.",
                    )
                }
            }
        }
    }

    /** Remove a pending attachment and its local bytes. */
    fun onRemoveAttachment(id: String) {
        val store = attachments ?: return

        viewModelScope.launch {
            try {
                store.remove(id)
                _state.update { state ->
                    state.copy(
                        pendingAttachments = state.pendingAttachments.filterNot { it.id == id },
                        confirmSendWithUnsupportedAttachments = false,
                    )
                }
                updateAttachmentWarning()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        attachmentError = error.message ?: "Could not remove the attachment.",
                    )
                }
            }
        }
    }

    fun onDismissAttachmentError() {
        _state.update { it.copy(attachmentError = null) }
    }

    /** Confirm sending even though the model cannot receive every attachment. */
    fun onConfirmSend() {
        _state.update { it.copy(confirmSendWithUnsupportedAttachments = false) }
        sendNow()
    }

    fun onCancelSendConfirmation() {
        _state.update { it.copy(confirmSendWithUnsupportedAttachments = false) }
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
        if (text.isEmpty() && current.pendingAttachments.isEmpty()) return

        if (!current.attachmentSupport.supported) {
            _state.update { it.copy(confirmSendWithUnsupportedAttachments = true) }
            return
        }

        sendNow()
    }

    private fun sendNow() {
        val current = _state.value
        if (current.sending || current.isStreaming) return
        if (current.availability?.available == false) return

        val text = current.draft.trim()
        val pending = current.pendingAttachments
        if (text.isEmpty() && pending.isEmpty()) return

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
                    attachments = pending,
                    sequence = nextSequence,
                    now = clock.instant(),
                    id = newId(),
                )
                val requestMessages = history + userMessage
                val resolved = resolveAttachments(requestMessages)
                val request = buildChatRequest(
                    conversation = conversation,
                    modelRemoteId = context.modelRemoteId,
                    messages = requestMessages,
                    attachments = resolved,
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
                applyResult(
                    result = result,
                    draftOnFailure = text,
                    userMessage = userMessage,
                    pending = pending,
                )
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
                    attachments = resolveAttachments(beforeTarget),
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
                    attachments = resolveAttachments(beforeTarget),
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
                val truncated = history
                    .filter { it.sequence > target.sequence }
                    .map { it.id } + target.id
                val request = buildChatRequest(
                    conversation = conversation,
                    modelRemoteId = context.modelRemoteId,
                    messages = beforeTarget + updatedTarget,
                    attachments = resolveAttachments(beforeTarget + updatedTarget),
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
                if (result is ChatRunResult.Success ||
                    (result is ChatRunResult.Failure && result.userMessagePersisted)
                ) {
                    attachments?.deleteForMessages(truncated)
                }
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
                conversations.observeConversation(conversationId).collect { conversation ->
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
                    refreshAttachments(window)
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
                _state.update {
                    it.copy(
                        availability = ChatAvailability(available = true),
                        modelInputModalities = result.context.inputModalities,
                    )
                }
                updateAttachmentWarning()
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
                _state.update {
                    it.copy(
                        availability = ChatAvailability(available = true),
                        modelInputModalities = result.context.inputModalities,
                    )
                }
                updateAttachmentWarning()
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

    private fun applyResult(
        result: ChatRunResult,
        draftOnFailure: String?,
        userMessage: Message? = null,
        pending: List<Attachment> = emptyList(),
    ) {
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
                bindAttachments(userMessage, pending)
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
                if (result.userMessagePersisted) {
                    bindAttachments(userMessage, pending)
                }
            }
        }
    }

    private fun bindAttachments(userMessage: Message?, pending: List<Attachment>) {
        if (userMessage == null || pending.isEmpty()) return
        val store = attachments ?: return
        val ids = pending.map { it.id }

        _state.update { it.copy(pendingAttachments = emptyList()) }
        updateAttachmentWarning()

        viewModelScope.launch {
            try {
                store.attachToMessage(ids, userMessage.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // The rows stay unbound and are removed by the next orphan cleanup.
            }
        }
    }

    private suspend fun resolveAttachments(
        messages: List<Message>,
    ): Map<String, ResolvedAttachment> {
        val store = attachments ?: return emptyMap()
        return store.resolveForMessages(messages).mapValues { (_, value) ->
            ResolvedAttachment(
                mimeType = value.mimeType,
                name = value.name,
                dataBase64 = value.dataBase64,
            )
        }
    }

    private suspend fun refreshAttachments(messages: List<Message>) {
        val store = attachments ?: return
        val ids = messages
            .flatMap { it.parts }
            .mapNotNull { part ->
                when (part) {
                    is ContentPart.Image -> part.attachmentId
                    is ContentPart.File -> part.attachmentId
                    else -> null
                }
            }
            .toSet()

        val loaded = ids.mapNotNull { id -> store.get(id)?.let { id to it } }.toMap()
        _state.update { it.copy(attachmentsByMessage = loaded) }
    }

    private fun updateAttachmentWarning() {
        _state.update { state ->
            state.copy(
                attachmentWarning = attachmentSupport(
                    state.pendingAttachments,
                    state.modelInputModalities,
                ).message,
            )
        }
    }

    private fun cleanupAttachments() {
        val store = attachments ?: return
        val startedAt = clock.instant()
        viewModelScope.launch {
            try {
                store.cleanupOrphans(startedAt)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Cleanup is best-effort; a later startup retries it.
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
        attachments: List<Attachment>,
        sequence: Long,
        now: Instant,
        id: String,
    ): Message {
        val parts = buildList {
            if (text.isNotEmpty()) add(ContentPart.Text(text))
            attachments.forEach { attachment ->
                when (attachment.kind) {
                    AttachmentKind.IMAGE -> add(
                        ContentPart.Image(
                            attachmentId = attachment.id,
                            mimeType = attachment.mimeType,
                            alt = attachment.name,
                        ),
                    )

                    AttachmentKind.FILE -> add(
                        ContentPart.File(
                            attachmentId = attachment.id,
                            mimeType = attachment.mimeType,
                            name = attachment.name,
                        ),
                    )
                }
            }
        }

        return Message(
            id = id,
            conversationId = conversation.id,
            sequence = sequence,
            role = MessageRole.USER,
            parts = parts,
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
    }

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
            attachments: ChatAttachmentStore? = null,
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
                    attachments = attachments,
                    persistIntervalMs = persistIntervalMs,
                )
            }
        }
    }
}
