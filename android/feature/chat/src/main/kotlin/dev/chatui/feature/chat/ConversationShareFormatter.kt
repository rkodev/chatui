package dev.chatui.feature.chat

import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole

/** Output format for an outbound conversation share. */
enum class ConversationShareFormat {
    TEXT,
    MARKDOWN,
}

/**
 * Pure conversation-to-text formatter for outbound sharing.
 *
 * Only visible user and assistant text is included. System prompts, tool
 * traffic, reasoning, provider metadata, attachment bytes, and storage keys are
 * never emitted. Attachment names are included only when they look like a plain
 * display name rather than a path or a secret.
 */
object ConversationShareFormatter {
    fun format(
        title: String,
        messages: List<Message>,
        format: ConversationShareFormat = ConversationShareFormat.TEXT,
        includeReasoning: Boolean = false,
    ): String {
        val safeTitle = sanitizeTitle(title)
        val visible = messages
            .asSequence()
            .filter { it.deletedAt == null }
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .sortedBy { it.sequence }
            .mapNotNull { message ->
                val content = message.shareableContent(includeReasoning)
                if (content.isBlank()) null else message.role to content
            }
            .toList()

        return when (format) {
            ConversationShareFormat.TEXT -> buildString {
                append(safeTitle)
                if (visible.isNotEmpty()) {
                    append("\n\n")
                    append(
                        visible.joinToString("\n\n") { (role, content) ->
                            "${roleLabel(role)}:\n$content"
                        },
                    )
                }
            }

            ConversationShareFormat.MARKDOWN -> buildString {
                append("# ").append(safeTitle)
                if (visible.isNotEmpty()) {
                    append("\n\n")
                    append(
                        visible.joinToString("\n\n") { (role, content) ->
                            "**${roleLabel(role)}**\n\n$content"
                        },
                    )
                }
            }
        }
    }

    private fun roleLabel(role: MessageRole): String = when (role) {
        MessageRole.USER -> "User"
        MessageRole.ASSISTANT -> "Assistant"
        MessageRole.SYSTEM -> "System"
        MessageRole.TOOL -> "Tool"
    }

    private fun Message.shareableContent(includeReasoning: Boolean): String = parts
        .mapNotNull { part ->
            when (part) {
                is ContentPart.Text -> part.text.takeIf { it.isNotBlank() }
                is ContentPart.Reasoning ->
                    if (includeReasoning) part.text.takeIf { it.isNotBlank() } else null
                is ContentPart.Image -> imageLabel(part.alt)
                is ContentPart.File -> fileLabel(part.name)
                is ContentPart.ToolCall, is ContentPart.ToolResult -> null
            }
        }
        .joinToString("\n\n")

    private fun imageLabel(alt: String): String {
        val safeAlt = safeAttachmentName(alt)
        return if (safeAlt == null) "[Image]" else "[Image: $safeAlt]"
    }

    private fun fileLabel(name: String): String {
        val safeName = safeAttachmentName(name)
        return if (safeName == null) "[File]" else "[File: $safeName]"
    }

    private fun sanitizeTitle(title: String): String {
        val cleaned = title.replace(WHITESPACE, " ").trim()
        return cleaned.ifEmpty { "Conversation" }.take(MAX_TITLE_LENGTH)
    }

    /**
     * Return a display-safe attachment name, or null when the name could leak a
     * path, a storage key, or a credential.
     */
    private fun safeAttachmentName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_ATTACHMENT_NAME_LENGTH) return null
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) return null
        if (CONTROL_CHARACTERS.containsMatchIn(trimmed)) return null
        if (SENSITIVE_NAME.containsMatchIn(trimmed)) return null
        return trimmed
    }

    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_ATTACHMENT_NAME_LENGTH = 120

    private val WHITESPACE = Regex("\\s+")
    private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
    private val SENSITIVE_NAME = Regex(
        "(?i)(api[_-]?key|secret|token|password|passwd|bearer|authorization|" +
            "credential|private[_-]?key|sk-[a-z0-9])",
    )
}
