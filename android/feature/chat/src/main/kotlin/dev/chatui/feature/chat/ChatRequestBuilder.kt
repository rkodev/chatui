package dev.chatui.feature.chat

import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.network.NormalizedChatRequest
import dev.chatui.core.network.NormalizedMessage
import dev.chatui.core.network.NormalizedRole

/**
 * Build the normalized request for the next assistant response.
 *
 * History is sent in sequence order. Empty assistant placeholders, deleted
 * messages, and system messages are skipped; the conversation system prompt is
 * sent separately. Reasoning parts are presentation-only and are not replayed
 * to the provider.
 */
fun buildChatRequest(
    conversation: Conversation,
    modelRemoteId: String,
    messages: List<Message>,
): NormalizedChatRequest {
    val normalized = messages
        .asSequence()
        .filter { it.conversationId == conversation.id && it.deletedAt == null }
        .sortedBy { it.sequence }
        .mapNotNull { message -> message.toNormalizedMessage() }
        .toList()

    return NormalizedChatRequest(
        model = modelRemoteId,
        system = conversation.systemPrompt.trim().ifEmpty { null },
        messages = normalized,
        params = conversation.params,
    )
}

private fun Message.toNormalizedMessage(): NormalizedMessage? {
    val normalizedRole = when (role) {
        MessageRole.USER -> NormalizedRole.USER
        MessageRole.ASSISTANT -> NormalizedRole.ASSISTANT
        MessageRole.TOOL -> NormalizedRole.TOOL
        MessageRole.SYSTEM -> return null
    }

    val text = parts
        .filterIsInstance<ContentPart.Text>()
        .joinToString("\n") { it.text }

    if (text.isBlank()) return null

    return NormalizedMessage(
        role = normalizedRole,
        parts = listOf(ContentPart.Text(text)),
    )
}
