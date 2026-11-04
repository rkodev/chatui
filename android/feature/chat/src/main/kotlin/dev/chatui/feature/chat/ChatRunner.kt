package dev.chatui.feature.chat

import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.newId
import dev.chatui.core.network.NormalizedChatRequest
import dev.chatui.core.network.OpenRouterRequestException
import dev.chatui.core.network.ProviderAdapter
import dev.chatui.core.network.ProviderContext
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Default persistence cadence from spec section 9.4. */
const val DEFAULT_PERSIST_INTERVAL_MS: Long = 1_000L

/** Why a chat run could not start or finish. */
enum class ChatRunFailureReason {
    BUSY,
    STORAGE,
    INVALID_TARGET,
    UNAVAILABLE,
}

/** Result of one chat run. */
sealed interface ChatRunResult {
    data class Success(
        val message: Message,
        val state: StreamingState,
    ) : ChatRunResult

    data class Failure(
        val reason: ChatRunFailureReason,
        val message: String,
        val error: ChatError? = null,
        val userMessagePersisted: Boolean = false,
    ) : ChatRunResult
}

/** Input for a new assistant response. */
data class ChatRunInput(
    val conversation: Conversation,
    val context: ProviderContext,
    val request: NormalizedChatRequest,
    val userMessage: Message? = null,
    val assistantSequence: Long,
    val replaceExisting: Boolean = false,
)

/** Input for regenerating or retrying an existing assistant message. */
data class RegenerateInput(
    val conversation: Conversation,
    val context: ProviderContext,
    val request: NormalizedChatRequest,
    val target: Message,
)

/** Input for editing the last user message and resending. */
data class EditAndResendInput(
    val conversation: Conversation,
    val context: ProviderContext,
    val request: NormalizedChatRequest,
    val target: Message,
)

/**
 * Owns one provider stream per conversation.
 *
 * Responsibilities:
 * - create and persist the assistant placeholder;
 * - reduce normalized events into a local streaming buffer;
 * - persist partial text about once per second and on completion, cancellation,
 *   error, and explicit pause;
 * - enforce one active stream per conversation and a send lock;
 * - cancel, regenerate, retry, and edit-and-resend without branching.
 */
class ChatRunner(
    private val adapter: ProviderAdapter,
    private val messages: ChatMessageStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = ::newId,
    private val persistIntervalMs: Long = DEFAULT_PERSIST_INTERVAL_MS,
) {
    private val _streams = MutableStateFlow<Map<String, StreamingState>>(emptyMap())

    /** Latest streaming snapshot per conversation. */
    val streams: StateFlow<Map<String, StreamingState>> = _streams.asStateFlow()

    private val active = ConcurrentHashMap<String, ActiveStream>()
    private val writeMutex = Mutex()

    /** True while a stream is active for the conversation. */
    fun isStreaming(conversationId: String): Boolean = active.containsKey(conversationId)

    /** Conversation IDs with an active stream. */
    fun activeConversationIds(): Set<String> = active.keys.toSet()

    /** Start a new assistant response. */
    suspend fun send(input: ChatRunInput): ChatRunResult {
        val now = clock.instant()
        val assistant = newAssistantMessage(
            conversation = input.conversation,
            sequence = input.assistantSequence,
            now = now,
            id = newId(),
        )

        return start(
            conversationId = input.conversation.id,
            assistant = assistant,
            replaceExisting = input.replaceExisting,
            userMessage = input.userMessage,
            context = input.context,
            request = input.request,
        )
    }

    /**
     * Replace an existing assistant message in place.
     *
     * The target's id and sequence are reused, so the persisted history is
     * replaced rather than branched.
     */
    suspend fun regenerate(input: RegenerateInput): ChatRunResult {
        if (input.target.role != MessageRole.ASSISTANT ||
            input.target.conversationId != input.conversation.id
        ) {
            return ChatRunResult.Failure(
                reason = ChatRunFailureReason.INVALID_TARGET,
                message = "This message cannot be regenerated.",
            )
        }

        val now = clock.instant()
        val assistant = input.target.copy(
            providerId = input.conversation.providerId,
            modelId = input.conversation.modelId,
            modelLabel = input.conversation.modelLabel,
            parts = emptyList(),
            status = MessageStatus.STREAMING,
            usage = null,
            finishReason = null,
            error = null,
            latencyMs = null,
            firstTokenMs = null,
            updatedAt = now,
            deletedAt = null,
        )

        return start(
            conversationId = input.conversation.id,
            assistant = assistant,
            replaceExisting = true,
            userMessage = null,
            context = input.context,
            request = input.request,
        )
    }

    /** Retry a failed or cancelled assistant message in place. */
    suspend fun retry(input: RegenerateInput): ChatRunResult {
        if (input.target.role != MessageRole.ASSISTANT ||
            input.target.conversationId != input.conversation.id
        ) {
            return ChatRunResult.Failure(
                reason = ChatRunFailureReason.INVALID_TARGET,
                message = "This message cannot be retried.",
            )
        }

        if (input.target.status != MessageStatus.ERROR &&
            input.target.status != MessageStatus.CANCELLED
        ) {
            return ChatRunResult.Failure(
                reason = ChatRunFailureReason.INVALID_TARGET,
                message = "Only failed or cancelled messages can be retried.",
            )
        }

        return regenerate(input)
    }

    /**
     * Edit the last user message, truncate later messages, and resend.
     *
     * The edited message keeps its id and sequence, so the conversation is
     * rewritten in place rather than branched.
     */
    suspend fun editAndResend(input: EditAndResendInput): ChatRunResult {
        if (input.target.role != MessageRole.USER ||
            input.target.conversationId != input.conversation.id
        ) {
            return ChatRunResult.Failure(
                reason = ChatRunFailureReason.INVALID_TARGET,
                message = "Only the last user message can be edited.",
            )
        }

        val conversationId = input.conversation.id
        val now = clock.instant()
        val assistant = newAssistantMessage(
            conversation = input.conversation,
            sequence = input.target.sequence + 1,
            now = now,
            id = newId(),
        )

        suspend fun prepare(): ChatRunResult? {
            val history = messages.all(conversationId)
            val lastUser = history.lastOrNull { it.role == MessageRole.USER }

            if (lastUser?.id != input.target.id) {
                return ChatRunResult.Failure(
                    reason = ChatRunFailureReason.INVALID_TARGET,
                    message = "Only the last user message can be edited.",
                )
            }

            history
                .filter { it.sequence > input.target.sequence }
                .forEach { messages.deleteMessage(it.id) }
            messages.updateMessage(input.target)
            return null
        }

        return start(
            conversationId = conversationId,
            assistant = assistant,
            replaceExisting = false,
            userMessage = null,
            context = input.context,
            request = input.request,
            prepare = { prepare() },
        )
    }

    /**
     * Cancel the active stream for a conversation.
     *
     * Cancellation cancels the coroutine and the adapter's HTTP call, persists
     * the partial text immediately as `cancelled`, and waits for the run to
     * finish so the final snapshot is durable. Returns null when no stream is
     * active.
     */
    suspend fun cancel(conversationId: String): Message? {
        val activeStream = active[conversationId] ?: return null
        activeStream.cancelled.set(true)
        activeStream.job?.cancel()
        val result = activeStream.result.await()
        return (result as? ChatRunResult.Success)?.message
    }

    /** Persist the current partial text immediately, for lifecycle pause. */
    suspend fun persistNow(conversationId: String) {
        val activeStream = active[conversationId] ?: return
        if (activeStream.state.value.done) return
        activeStream.dirty.set(false)
        persist(activeStream)
    }

    /** Cancel every active stream without waiting for persistence. */
    fun dispose() {
        active.values.forEach { activeStream ->
            activeStream.cancelled.set(true)
            activeStream.job?.cancel()
        }
    }

    private suspend fun start(
        conversationId: String,
        assistant: Message,
        replaceExisting: Boolean,
        userMessage: Message?,
        context: ProviderContext,
        request: NormalizedChatRequest,
        prepare: (suspend () -> ChatRunResult?)? = null,
    ): ChatRunResult {
        val now = clock.instant()
        val activeStream = ActiveStream(
            conversationId = conversationId,
            message = assistant,
            state = MutableStateFlow(
                createStreamingState(
                    conversationId = conversationId,
                    messageId = assistant.id,
                    startedAt = now,
                ),
            ),
        )

        if (active.putIfAbsent(conversationId, activeStream) != null) {
            return ChatRunResult.Failure(
                reason = ChatRunFailureReason.BUSY,
                message = "A response is already streaming for this conversation.",
            )
        }

        activeStream.job = scope.launch {
            run(
                activeStream = activeStream,
                replaceExisting = replaceExisting,
                userMessage = userMessage,
                context = context,
                request = request,
                prepare = prepare,
            )
        }

        activeStream.job?.invokeOnCompletion { cause ->
            if (cause != null && !activeStream.result.isCompleted) {
                activeStream.result.complete(
                    ChatRunResult.Failure(
                        reason = ChatRunFailureReason.STORAGE,
                        message = cause.message ?: "The chat request failed.",
                    ),
                )
            }
        }

        return activeStream.result.await()
    }

    private suspend fun run(
        activeStream: ActiveStream,
        replaceExisting: Boolean,
        userMessage: Message?,
        context: ProviderContext,
        request: NormalizedChatRequest,
        prepare: (suspend () -> ChatRunResult?)?,
    ) {
        var placeholderPersisted = false
        var userMessagePersisted = false

        try {
            val preparationFailure = writeMutex.withLock { prepare?.invoke() }
            if (preparationFailure != null) {
                activeStream.result.complete(preparationFailure)
                return
            }

            if (userMessage != null) {
                writeMutex.withLock { messages.appendMessage(userMessage) }
                userMessagePersisted = true
            }

            writeMutex.withLock {
                if (replaceExisting) {
                    messages.updateMessage(activeStream.message)
                } else {
                    messages.appendMessage(activeStream.message)
                }
            }
            placeholderPersisted = true

            startPersistLoop(activeStream)

            adapter.streamChat(context, request).collect { event ->
                handleEvent(activeStream, event)
            }
        } catch (error: CancellationException) {
            // Cancellation is handled in the finally block.
        } catch (error: Throwable) {
            if (!placeholderPersisted) {
                activeStream.result.complete(
                    ChatRunResult.Failure(
                        reason = ChatRunFailureReason.STORAGE,
                        message = error.message ?: "Could not save the message.",
                        userMessagePersisted = userMessagePersisted,
                    ),
                )
                return
            }

            handleEvent(
                activeStream,
                StreamEvent.ErrorEvent(normalizeError(error, context.provider.id)),
            )
        } finally {
            if (placeholderPersisted) {
                withContext(NonCancellable) {
                    finalizeAndPersist(activeStream)
                }
            } else if (!activeStream.result.isCompleted) {
                activeStream.result.complete(
                    ChatRunResult.Failure(
                        reason = ChatRunFailureReason.STORAGE,
                        message = "The message was not saved.",
                        userMessagePersisted = userMessagePersisted,
                    ),
                )
            }
            active.remove(activeStream.conversationId)
        }
    }

    private fun startPersistLoop(activeStream: ActiveStream) {
        activeStream.persistJob = scope.launch {
            while (isActive) {
                delay(persistIntervalMs)
                if (activeStream.dirty.getAndSet(false)) {
                    persist(activeStream)
                }
            }
        }
    }

    private fun handleEvent(activeStream: ActiveStream, event: StreamEvent) {
        if (activeStream.cancelled.get()) return

        val current = activeStream.state.value
        val next = reduceStream(current, event, clock.instant())
        if (next == current) return

        activeStream.state.value = next
        activeStream.dirty.set(true)
        _streams.update { streams -> streams + (activeStream.conversationId to next) }
    }

    private suspend fun persist(activeStream: ActiveStream) {
        val state = activeStream.state.value
        val message = toMessage(activeStream.message, state)
        writeMutex.withLock {
            messages.updateMessage(message)
        }
    }

    private suspend fun finalizeAndPersist(activeStream: ActiveStream) {
        activeStream.persistJob?.cancel()

        val now = clock.instant()
        val current = activeStream.state.value
        val cancelled = activeStream.cancelled.get()
        val status = when {
            cancelled -> MessageStatus.CANCELLED
            current.error != null -> MessageStatus.ERROR
            else -> MessageStatus.COMPLETE
        }
        val finishReason = when {
            cancelled -> FinishReason.CANCELLED
            current.error != null -> FinishReason.ERROR
            else -> current.finishReason ?: FinishReason.UNKNOWN
        }
        val finalState = current.copy(
            status = status,
            finishReason = finishReason,
            error = if (cancelled) cancelledError() else current.error,
            done = true,
            completedAt = now,
            updatedAt = now,
        )

        activeStream.state.value = finalState
        _streams.update { streams -> streams + (activeStream.conversationId to finalState) }

        val finalMessage = toMessage(activeStream.message, finalState)
        writeMutex.withLock {
            messages.updateMessage(finalMessage)
        }

        if (!activeStream.result.isCompleted) {
            activeStream.result.complete(ChatRunResult.Success(finalMessage, finalState))
        }
    }

    private fun toMessage(message: Message, state: StreamingState): Message {
        val parts = buildList {
            if (state.reasoning.isNotEmpty()) {
                add(ContentPart.Reasoning(state.reasoning))
            }
            if (state.text.isNotEmpty()) {
                add(ContentPart.Text(state.text))
            }
        }

        return message.copy(
            parts = parts,
            status = state.status,
            usage = state.usage,
            finishReason = state.finishReason,
            error = state.error,
            latencyMs = state.completedAt?.let {
                it.toEpochMilli() - state.startedAt.toEpochMilli()
            },
            firstTokenMs = state.firstTokenAt?.let {
                it.toEpochMilli() - state.startedAt.toEpochMilli()
            },
            updatedAt = state.updatedAt,
        )
    }

    private fun newAssistantMessage(
        conversation: Conversation,
        sequence: Long,
        now: Instant,
        id: String,
    ): Message = Message(
        id = id,
        conversationId = conversation.id,
        sequence = sequence,
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        status = MessageStatus.STREAMING,
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

    private fun normalizeError(error: Throwable, providerId: String?): ChatError {
        if (error is OpenRouterRequestException) {
            return ChatError(
                code = error.code,
                message = error.message,
                providerId = error.providerId ?: providerId,
                status = error.status,
                retryable = error.retryable,
                retryAfterSeconds = error.retryAfterSeconds,
                requestId = error.requestId,
                details = error.details,
                at = clock.instant(),
            )
        }

        return ChatError(
            code = ChatErrorCode.UNKNOWN,
            message = error.message ?: "The chat request failed.",
            providerId = providerId,
            status = null,
            retryable = false,
            retryAfterSeconds = null,
            requestId = null,
            details = null,
            at = clock.instant(),
        )
    }

    private fun cancelledError(): ChatError = ChatError(
        code = ChatErrorCode.CANCELLED,
        message = "Stopped by the user.",
        providerId = null,
        status = null,
        retryable = false,
        retryAfterSeconds = null,
        requestId = null,
        details = null,
        at = clock.instant(),
    )

    private class ActiveStream(
        val conversationId: String,
        val message: Message,
        val state: MutableStateFlow<StreamingState>,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val dirty: AtomicBoolean = AtomicBoolean(false),
        val result: CompletableDeferred<ChatRunResult> = CompletableDeferred(),
        var job: Job? = null,
        var persistJob: Job? = null,
    )
}
