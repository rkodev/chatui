package dev.chatui.feature.chat

import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.network.NormalizedChatRequest
import dev.chatui.core.network.NormalizedMessage
import dev.chatui.core.network.NormalizedRole
import dev.chatui.core.network.ResolvedAttachment

/**
 * Build the normalized request for the next assistant response.
 *
 * History is sent in sequence order. Empty assistant placeholders, deleted
 * messages, and system messages are skipped; the conversation system prompt is
 * sent separately. Reasoning parts are presentation-only and are not replayed
 * to the provider. Image and file parts are preserved and their bytes are
 * supplied through [attachments].
 */
fun buildChatRequest(
    conversation: Conversation,
    modelRemoteId: String,
    messages: List<Message>,
    attachments: Map<String, ResolvedAttachment> = emptyMap(),
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
        attachments = attachments,
    )
}

private fun Message.toNormalizedMessage(): NormalizedMessage? {
    val normalizedRole = when (role) {
        MessageRole.USER -> NormalizedRole.USER
        MessageRole.ASSISTANT -> NormalizedRole.ASSISTANT
        MessageRole.TOOL -> NormalizedRole.TOOL
        MessageRole.SYSTEM -> return null
    }

    val normalizedParts = parts.mapNotNull { part ->
        when (part) {
            is ContentPart.Text -> part.takeIf { it.text.isNotEmpty() }
            is ContentPart.Image -> part
            is ContentPart.File -> part
            else -> null
        }
    }

    if (normalizedParts.isEmpty()) return null

    return NormalizedMessage(
        role = normalizedRole,
        parts = normalizedParts,
    )
}
