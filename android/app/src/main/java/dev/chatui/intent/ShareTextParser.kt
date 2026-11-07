package dev.chatui.intent

import android.content.Intent

/**
 * Parses inbound `ACTION_SEND` intents that carry plain text.
 *
 * The parser is deliberately pure so the accepted shape is testable without an
 * activity. Shared text is trimmed and capped at [MAX_TEXT_LENGTH] characters;
 * blank, non-text, and non-send intents are ignored.
 */
object ShareTextParser {
    /** Maximum number of characters accepted from a share. */
    const val MAX_TEXT_LENGTH: Int = 20_000

    /**
     * Returns the trimmed shared text, or `null` when the intent is not a
     * supported plain-text share.
     */
    fun parse(action: String?, type: String?, text: CharSequence?): String? {
        if (action != Intent.ACTION_SEND) return null
        if (!isPlainText(type)) return null

        val trimmed = text?.toString()?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        return trimmed.take(MAX_TEXT_LENGTH)
    }

    /** Convenience overload for an already-received intent. */
    fun parse(intent: Intent?): String? {
        if (intent == null) return null

        return parse(
            action = intent.action,
            type = intent.type,
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
        )
    }

    private fun isPlainText(type: String?): Boolean {
        val normalized = type
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?: return false

        return normalized == "text/plain"
    }
}
