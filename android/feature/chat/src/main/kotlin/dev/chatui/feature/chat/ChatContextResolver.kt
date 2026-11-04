package dev.chatui.feature.chat

import dev.chatui.core.model.ChatError
import dev.chatui.core.model.Conversation
import dev.chatui.core.network.ProviderContext

/** Everything the runner needs to start one provider request. */
data class ChatContext(
    val providerContext: ProviderContext,
    val modelRemoteId: String,
    val supportedParameters: List<String>,
)

/** Why a conversation cannot currently send. */
enum class ChatUnavailableReason {
    NO_PROVIDER,
    MODEL_UNAVAILABLE,
    NO_KEY,
}

/** Result of resolving the provider, model, and key for a conversation. */
sealed interface ChatContextResult {
    data class Ready(val context: ChatContext) : ChatContextResult

    data class Unavailable(
        val reason: ChatUnavailableReason,
        val message: String,
        val error: ChatError,
    ) : ChatContextResult
}

/** Resolves the provider context and model for a conversation. */
interface ChatContextResolver {
    suspend fun resolve(conversation: Conversation): ChatContextResult
}

/** Minimal conversation lookup used by the chat ViewModel. */
interface ChatConversationSource {
    suspend fun getConversation(id: String): Conversation?
}
