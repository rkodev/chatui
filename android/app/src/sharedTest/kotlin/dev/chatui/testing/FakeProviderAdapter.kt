package dev.chatui.testing

import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.network.ModelInfo
import dev.chatui.core.network.NormalizedChatRequest
import dev.chatui.core.network.ProviderAdapter
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderKeyInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Controllable provider adapter for ViewModel, Compose, and instrumentation tests.
 *
 * Tests push events into [events] and close the channel to complete the stream.
 * Cancelling the collecting coroutine sets [cancelled], mirroring the
 * production adapter's OkHttp cancellation contract.
 */
class FakeProviderAdapter : ProviderAdapter {
    override val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE

    val events = Channel<StreamEvent>(Channel.UNLIMITED)
    val started = CompletableDeferred<Unit>()

    var cancelled: Boolean = false
        private set
    var lastContext: ProviderContext? = null
        private set
    var lastRequest: NormalizedChatRequest? = null
        private set

    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo =
        error("validateKey is not used in fake provider tests")

    override suspend fun listModels(context: ProviderContext): List<ModelInfo> =
        error("listModels is not used in fake provider tests")

    override fun streamChat(
        context: ProviderContext,
        request: NormalizedChatRequest,
    ): Flow<StreamEvent> = flow {
        lastContext = context
        lastRequest = request
        started.complete(Unit)

        try {
            for (event in events) {
                emit(event)
            }
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        }
    }

    /** Queue [values] and close the channel so the stream completes. */
    fun emitAndFinish(vararg values: StreamEvent) {
        values.forEach { events.trySend(it) }
        events.close()
    }
}
