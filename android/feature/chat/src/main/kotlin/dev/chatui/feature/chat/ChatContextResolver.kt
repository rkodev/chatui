package dev.chatui.feature.chat

import dev.chatui.core.model.ChatError
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.InputModality
import dev.chatui.core.network.ProviderContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Everything the runner needs to start one provider request. */
data class ChatContext(
    val providerContext: ProviderContext,
    val modelRemoteId: String,
    val supportedParameters: List<String>,
    val inputModalities: List<InputModality> = listOf(InputModality.TEXT),
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

    /** Observe conversation changes so model switches update capability warnings. */
    fun observeConversation(id: String): Flow<Conversation?> = flow {
        emit(getConversation(id))
    }
}
