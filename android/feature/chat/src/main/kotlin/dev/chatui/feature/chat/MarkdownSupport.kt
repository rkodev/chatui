package dev.chatui.feature.chat

import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Message
import dev.snipme.highlights.model.SyntaxLanguage

/** Text copied from a message: every text part separated by a blank line. */
fun Message.copyableText(): String = parts
    .filterIsInstance<ContentPart.Text>()
    .map { it.text }
    .joinToString("\n\n")

private val HAS_PROTOCOL = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)

/**
 * Return a safe Markdown URL, or null when the URL is not allowed.
 *
 * M1 allows absolute `http`, `https`, and `mailto` URLs plus in-app `#anchor`
 * links. Relative paths, protocol-relative URLs, and other schemes are
 * rejected.
 */
fun sanitizeMarkdownUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null

    val trimmed = url.trim()
    if (trimmed.startsWith("#")) return trimmed
    if (!HAS_PROTOCOL.containsMatchIn(trimmed)) return null

    return runCatching {
        val scheme = trimmed.substringBefore(':').lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "mailto") trimmed else null
    }.getOrNull()
}

/**
 * Close an unterminated fenced code block for rendering only.
 *
 * Streaming text can end in the middle of a fence. Appending the matching
 * closing fence lets the parser render a code block instead of flashing raw
 * backticks. The persisted source text is never changed.
 */
fun closeIncompleteFence(markdown: String): String {
    if (markdown.isEmpty()) return markdown

    val lines = markdown.split('\n')
    var open: Pair<Char, Int>? = null

    for (line in lines) {
        val match = FENCE_PATTERN.find(line) ?: continue
        val fence = match.groupValues[1]
        val marker = fence.first()
        val length = fence.length

        if (open == null) {
            open = marker to length
            continue
        }

        if (marker == open.first &&
            length >= open.second &&
            match.groupValues[2].isBlank()
        ) {
            open = null
        }
    }

    val (marker, length) = open ?: return markdown
    val closing = marker.toString().repeat(length)
    return if (markdown.endsWith('\n')) {
        "$markdown$closing"
    } else {
        "$markdown\n$closing"
    }
}

internal val FENCE_PATTERN = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

/** Bounded highlighter language set for M1 code blocks. */
val SUPPORTED_CODE_LANGUAGES: Set<SyntaxLanguage> = setOf(
    SyntaxLanguage.C,
    SyntaxLanguage.CPP,
    SyntaxLanguage.CSHARP,
    SyntaxLanguage.JAVA,
    SyntaxLanguage.KOTLIN,
    SyntaxLanguage.RUST,
    SyntaxLanguage.JAVASCRIPT,
    SyntaxLanguage.TYPESCRIPT,
    SyntaxLanguage.PYTHON,
    SyntaxLanguage.RUBY,
    SyntaxLanguage.PHP,
    SyntaxLanguage.GO,
    SyntaxLanguage.SWIFT,
    SyntaxLanguage.SHELL,
    SyntaxLanguage.DART,
    SyntaxLanguage.PERL,
    SyntaxLanguage.COFFEESCRIPT,
)

private val LANGUAGE_ALIASES: Map<String, SyntaxLanguage> = mapOf(
    "c" to SyntaxLanguage.C,
    "h" to SyntaxLanguage.C,
    "cpp" to SyntaxLanguage.CPP,
    "c++" to SyntaxLanguage.CPP,
    "cc" to SyntaxLanguage.CPP,
    "hpp" to SyntaxLanguage.CPP,
    "cs" to SyntaxLanguage.CSHARP,
    "csharp" to SyntaxLanguage.CSHARP,
    "c#" to SyntaxLanguage.CSHARP,
    "java" to SyntaxLanguage.JAVA,
    "kt" to SyntaxLanguage.KOTLIN,
    "kotlin" to SyntaxLanguage.KOTLIN,
    "kts" to SyntaxLanguage.KOTLIN,
    "rs" to SyntaxLanguage.RUST,
    "rust" to SyntaxLanguage.RUST,
    "js" to SyntaxLanguage.JAVASCRIPT,
    "javascript" to SyntaxLanguage.JAVASCRIPT,
    "jsx" to SyntaxLanguage.JAVASCRIPT,
    "mjs" to SyntaxLanguage.JAVASCRIPT,
    "cjs" to SyntaxLanguage.JAVASCRIPT,
    "ts" to SyntaxLanguage.TYPESCRIPT,
    "typescript" to SyntaxLanguage.TYPESCRIPT,
    "tsx" to SyntaxLanguage.TYPESCRIPT,
    "py" to SyntaxLanguage.PYTHON,
    "python" to SyntaxLanguage.PYTHON,
    "rb" to SyntaxLanguage.RUBY,
    "ruby" to SyntaxLanguage.RUBY,
    "php" to SyntaxLanguage.PHP,
    "go" to SyntaxLanguage.GO,
    "golang" to SyntaxLanguage.GO,
    "swift" to SyntaxLanguage.SWIFT,
    "sh" to SyntaxLanguage.SHELL,
    "bash" to SyntaxLanguage.SHELL,
    "shell" to SyntaxLanguage.SHELL,
    "shellscript" to SyntaxLanguage.SHELL,
    "zsh" to SyntaxLanguage.SHELL,
    "dart" to SyntaxLanguage.DART,
    "perl" to SyntaxLanguage.PERL,
    "coffeescript" to SyntaxLanguage.COFFEESCRIPT,
    "coffee" to SyntaxLanguage.COFFEESCRIPT,
)

/** Normalize a Markdown fence info string to a bounded highlighter language. */
fun normalizeCodeLanguage(language: String?): SyntaxLanguage? {
    val normalized = language
        ?.trim()
        ?.lowercase()
        ?.substringBefore(' ')
        ?.removePrefix(".")
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return LANGUAGE_ALIASES[normalized]
}

/** Visible language label for a fence info string. */
fun codeLanguageLabel(language: String?): String =
    language?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: "Code"
