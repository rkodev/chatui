package dev.chatui.core.common

/**
 * Central redaction helpers for diagnostics.
 *
 * Header matching is case-insensitive. Secret values are replaced before any
 * text leaves the app, so callers must route diagnostic headers and messages
 * through these helpers instead of logging raw values.
 */
object Redaction {
    const val REDACTED: String = "[REDACTED]"

    /** Header names whose values must never be logged. */
    val DEFAULT_SECRET_HEADERS: Set<String> = setOf(
        "authorization",
        "x-api-key",
        "x-goog-api-key",
    )

    /**
     * Return a copy of [headers] with every configured secret header value
     * replaced. Non-secret headers are preserved.
     */
    fun redactHeaders(
        headers: Map<String, String>,
        secretHeaders: Set<String> = DEFAULT_SECRET_HEADERS,
        replacement: String = REDACTED,
    ): Map<String, String> {
        val normalized = secretHeaders.mapTo(HashSet()) { it.trim().lowercase() }
        return headers.mapValues { (name, value) ->
            if (name.trim().lowercase() in normalized) replacement else value
        }
    }

    /**
     * Redact secret header values from a diagnostic string. Handles
     * header-style (`Authorization: ...`), assignment-style (`x-api-key=...`),
     * and JSON-style (`"Authorization": "..."`) text. Bare bearer tokens are
     * also redacted as a safety net.
     */
    fun redactText(
        text: String,
        secretHeaders: Set<String> = DEFAULT_SECRET_HEADERS,
        replacement: String = REDACTED,
    ): String {
        var redacted = text
        for (header in secretHeaders) {
            val normalized = header.trim()
            if (normalized.isEmpty()) continue
            val pattern = Regex(
                "(${Regex.escape(normalized)})(\\s*\"?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\r\\n]*)",
                RegexOption.IGNORE_CASE,
            )
            redacted = pattern.replace(redacted) { match ->
                val name = match.groupValues[1]
                val separator = match.groupValues[2]
                val value = match.groupValues[3]
                val quote = when {
                    value.startsWith("\"") -> "\""
                    value.startsWith("'") -> "'"
                    else -> ""
                }
                "$name$separator$quote$replacement$quote"
            }
        }

        return Regex("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
            .replace(redacted) { "Bearer $replacement" }
    }
}
