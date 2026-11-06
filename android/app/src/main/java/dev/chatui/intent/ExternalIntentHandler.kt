package dev.chatui.intent

import dev.chatui.feature.chat.ChatDrafts
import dev.chatui.feature.conversations.ConversationService
import dev.chatui.feature.conversations.CreateConversationResult
import dev.chatui.shell.ChatDestination
import kotlinx.coroutines.CancellationException

/** Navigation outcome produced by [ExternalIntentHandler]. */
sealed interface ExternalIntentOutcome {
    /** Open the newly created conversation. */
    data class NavigateToChat(val route: String) : ExternalIntentOutcome

    /** Send the user to provider setup because no provider/model is configured. */
    data object OpenProviders : ExternalIntentOutcome

    /** Send the user to onboarding because setup has not completed. */
    data object OpenOnboarding : ExternalIntentOutcome
}

/**
 * Turns a pending external intent into a navigation outcome.
 *
 * A share always creates a new conversation and stores the shared text as that
 * conversation's draft. Existing drafts are never touched, so the active
 * composer cannot be overwritten by an inbound share.
 */
class ExternalIntentHandler(
    private val router: ExternalIntentRouter,
    private val conversationService: ConversationService,
    private val drafts: ChatDrafts,
) {
    suspend fun handle(
        action: ExternalIntentAction,
        onboardingCompleted: Boolean,
    ): ExternalIntentOutcome = when (action) {
        is ExternalIntentAction.ShareText -> handleShare(action, onboardingCompleted)
    }

    private suspend fun handleShare(
        action: ExternalIntentAction.ShareText,
        onboardingCompleted: Boolean,
    ): ExternalIntentOutcome {
        return try {
            when (val result = conversationService.createConversation()) {
                is CreateConversationResult.Created -> {
                    drafts.setDraft(result.conversation.id, action.text)
                    router.consume(action)
                    ExternalIntentOutcome.NavigateToChat(
                        ChatDestination.createRoute(result.conversation.id),
                    )
                }

                is CreateConversationResult.NotConfigured -> notConfiguredOutcome(
                    onboardingCompleted,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Leave the action pending so the text is not silently dropped.
            notConfiguredOutcome(onboardingCompleted)
        }
    }

    private fun notConfiguredOutcome(onboardingCompleted: Boolean): ExternalIntentOutcome =
        if (onboardingCompleted) {
            ExternalIntentOutcome.OpenProviders
        } else {
            ExternalIntentOutcome.OpenOnboarding
        }
}
