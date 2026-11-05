package dev.chatui.feature.chat

import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.OutputModality
import dev.chatui.core.network.ProviderContext
import java.time.Clock

/**
 * Production context resolver.
 *
 * The provider record contains only a `secretId`; the plaintext key is read
 * from the secret store for the duration of the request and never persisted in
 * the chat state.
 */
class DefaultChatContextResolver(
    private val providers: ProviderRepository,
    private val models: ModelRepository,
    private val secrets: SecretStore,
    private val clock: Clock = Clock.systemUTC(),
) : ChatContextResolver {
    override suspend fun resolve(conversation: Conversation): ChatContextResult {
        val provider = providers.get(conversation.providerId)
            ?: return unavailable(
                reason = ChatUnavailableReason.NO_PROVIDER,
                code = ChatErrorCode.PROVIDER,
                message = "The provider for this conversation is unavailable. " +
                    "Choose another model.",
            )

        if (!provider.enabled) {
            return unavailable(
                reason = ChatUnavailableReason.MODEL_UNAVAILABLE,
                code = ChatErrorCode.PROVIDER,
                message = "Provider ${provider.label} is disabled. " +
                    "Enable it or choose another model.",
            )
        }

        val model = models.get(conversation.modelId)
            ?: return unavailable(
                reason = ChatUnavailableReason.MODEL_UNAVAILABLE,
                code = ChatErrorCode.MODEL_NOT_FOUND,
                message = "The model for this conversation is unavailable. " +
                    "Choose another model.",
            )

        if (OutputModality.TEXT !in model.outputModalities) {
            return unavailable(
                reason = ChatUnavailableReason.MODEL_UNAVAILABLE,
                code = ChatErrorCode.MODEL_NOT_FOUND,
                message = "Model ${model.remoteId} does not produce text output. " +
                    "Choose another model.",
            )
        }

        val apiKey = provider.secretId?.let { secrets.get(it) }
            ?: return unavailable(
                reason = ChatUnavailableReason.NO_KEY,
                code = ChatErrorCode.AUTH,
                message = "The API key for this provider is unavailable. " +
                    "Re-enter the key and try again.",
            )

        return ChatContextResult.Ready(
            ChatContext(
                providerContext = ProviderContext(provider, apiKey),
                modelRemoteId = model.remoteId,
                supportedParameters = model.supportedParameters,
                inputModalities = model.inputModalities,
            ),
        )
    }

    private fun unavailable(
        reason: ChatUnavailableReason,
        code: ChatErrorCode,
        message: String,
    ): ChatContextResult.Unavailable = ChatContextResult.Unavailable(
        reason = reason,
        message = message,
        error = ChatError(
            code = code,
            message = message,
            providerId = null,
            status = null,
            retryable = false,
            retryAfterSeconds = null,
            requestId = null,
            details = null,
            at = clock.instant(),
        ),
    )
}
