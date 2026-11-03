package dev.chatui.shell

import android.net.Uri

/**
 * Chat route that carries the conversation id.
 *
 * The bare [ShellDestination.Chat] route remains for the bottom navigation and
 * onboarding hand-off. Opening a specific conversation uses [createRoute].
 */
object ChatDestination {
    const val ARG_CONVERSATION_ID: String = "conversationId"
    const val ROUTE: String = "chat/{$ARG_CONVERSATION_ID}"

    fun createRoute(conversationId: String): String = "chat/${Uri.encode(conversationId)}"
}

/** Test tags exposed by the chat destination placeholder. */
object ChatDestinationTestTags {
    fun conversation(conversationId: String?): String =
        "chat-destination-${conversationId ?: "none"}"
}
