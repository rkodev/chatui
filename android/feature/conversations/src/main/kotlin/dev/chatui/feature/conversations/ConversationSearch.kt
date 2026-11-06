package dev.chatui.feature.conversations

import dev.chatui.core.database.repository.searchableText
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import java.time.Instant

/**
 * One conversation in a search result set.
 *
 * A conversation appears once even when both its title and several messages
 * match. [messageMatches] is ordered newest-first and [titleMatched] tells the
 * UI whether the title itself matched.
 */
data class ConversationSearchResult(
    val conversation: Conversation,
    val titleMatched: Boolean,
    val messageMatches: List<ConversationSearchMessageMatch>,
)

/** One matching message inside a [ConversationSearchResult]. */
data class ConversationSearchMessageMatch(
    val messageId: String,
    val sequence: Long,
    val role: MessageRole,
    val snippet: String,
    val createdAt: Instant,
)

/**
 * Convert user input into a safe FTS4 query.
 *
 * Only letters, digits, and underscores survive, so FTS operators such as
 * quotes, parentheses, `NEAR`, and `-` cannot produce a syntax error. Each
 * token becomes a prefix term; adjacent terms are implicitly ANDed by FTS4.
 */
internal fun buildFtsQuery(query: String): String? {
    val tokens = query.split(Regex("\\s+"))
        .map { token -> token.filter { it.isLetterOrDigit() || it == '_' } }
        .filter { it.isNotEmpty() }

    if (tokens.isEmpty()) return null

    return tokens.joinToString(" ") { "$it*" }
}

/**
 * Build a short, single-line context snippet around the first query token.
 *
 * The snippet is derived from the already-persisted message parts; it never
 * triggers an extra database read.
 */
internal fun searchSnippet(
    message: Message,
    query: String,
    maxLength: Int = 120,
): String {
    val text = searchableText(message.parts)
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    if (text.isEmpty()) return ""

    val firstToken = query.split(Regex("\\s+"))
        .firstOrNull { it.isNotBlank() }
        ?: query
    val index = text.indexOf(firstToken, ignoreCase = true)
    val start = if (index >= 0) (index - 40).coerceAtLeast(0) else 0
    val end = (start + maxLength).coerceAtMost(text.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""

    return prefix + text.substring(start, end).trim() + suffix
}
