package dev.chatui.core.network

import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.StreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * Provider adapter contract from spec section 7.1.
 *
 * This module contains interfaces only. Implementations, HTTP clients, SSE
 * parsers, and endpoint logic belong to later milestones.
 *
 * Cancellation is coroutine cancellation; an implementation must cancel its
 * underlying HTTP call when the collecting coroutine is cancelled.
 */
interface ProviderAdapter {
    val kind: ProviderKind

    /** Validate the supplied key and return normalized key metadata. */
    suspend fun validateKey(context: ProviderContext): ProviderKeyInfo

    /** List the provider's available models. */
    suspend fun listModels(context: ProviderContext): List<ModelInfo>

    /**
     * Stream a normalized chat response.
     *
     * The returned flow is cold: the request starts when collection starts and
     * is cancelled when collection is cancelled.
     */
    fun streamChat(
        context: ProviderContext,
        request: NormalizedChatRequest,
    ): Flow<StreamEvent>
}
