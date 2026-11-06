package dev.chatui.core.database.attachment

import dev.chatui.core.model.AttachmentKind
import kotlin.math.roundToInt

/**
 * Attachment validation rules shared by every Android input path.
 *
 * Limits mirror the web client and spec 3.6. Validation classifies bytes by
 * MIME type and extension only; it never executes or renders file content.
 */
const val MAX_IMAGE_BYTES: Long = 20L * 1024 * 1024
const val MAX_TEXT_BYTES: Long = 2L * 1024 * 1024
const val MAX_IMAGE_EDGE: Int = 2048
const val MAX_FILE_NAME_LENGTH: Int = 255

val SUPPORTED_IMAGE_MIME_TYPES: Set<String> = setOf(
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
)

val SUPPORTED_TEXT_MIME_TYPES: Set<String> = setOf(
    "text/plain",
    "text/markdown",
    "text/csv",
    "text/xml",
    "text/html",
    "text/css",
    "application/json",
    "application/xml",
    "application/javascript",
)

/** Source-file extensions that are safe to treat as plain text. */
val SUPPORTED_TEXT_EXTENSIONS: Set<String> = setOf(
    "txt",
    "md",
    "markdown",
    "json",
    "jsonc",
    "csv",
    "tsv",
    "log",
    "yml",
    "yaml",
    "xml",
    "html",
    "htm",
    "css",
    "scss",
    "less",
    "js",
    "jsx",
    "mjs",
    "cjs",
    "ts",
    "tsx",
    "py",
    "rb",
    "go",
    "rs",
    "java",
    "kt",
    "kts",
    "c",
    "h",
    "cpp",
    "hpp",
    "cc",
    "cs",
    "php",
    "swift",
    "sh",
    "bash",
    "zsh",
    "toml",
    "ini",
    "cfg",
    "sql",
    "gradle",
    "dart",
    "lua",
    "r",
)

private val IMAGE_EXTENSIONS: Map<String, String> = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "gif" to "image/gif",
)

/** Why an attachment was rejected. */
enum class AttachmentValidationCode {
    EMPTY,
    TOO_LARGE,
    UNSUPPORTED_TYPE,
    MISSING_NAME,
}

/** One validation failure with user-facing copy. */
data class AttachmentValidationError(
    val code: AttachmentValidationCode,
    val message: String,
)

/** A file that passed validation. */
data class ValidatedAttachmentFile(
    val kind: AttachmentKind,
    val mimeType: String,
    val name: String,
)

/** Result of validating one input file. */
sealed interface AttachmentValidationResult {
    data class Valid(val value: ValidatedAttachmentFile) : AttachmentValidationResult

    data class Invalid(val error: AttachmentValidationError) : AttachmentValidationResult
}

/** Lower-case extension without the dot, or an empty string. */
fun fileExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot < 0) "" else name.substring(dot + 1).lowercase()
}

/** Strip path separators and control characters from a display name. */
fun sanitizeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base
        .filter { character -> character.code >= 0x20 && character.code != 0x7f }
        .replace(Regex("\\s+"), " ")
        .trim()

    if (cleaned.isEmpty()) return "attachment"
    return cleaned.take(MAX_FILE_NAME_LENGTH)
}

private fun mimeTypeForExtension(extension: String): String = when (extension) {
    "md", "markdown" -> "text/markdown"
    "csv", "tsv" -> "text/csv"
    "json", "jsonc" -> "application/json"
    "xml" -> "application/xml"
    "html", "htm" -> "text/html"
    "css", "scss", "less" -> "text/css"
    else -> "text/plain"
}

/**
 * Classify a file and reject unsupported, empty, or oversized input.
 *
 * An unknown declared MIME type is never overridden by the extension, so a
 * disguised executable cannot be accepted as text.
 */
fun validateAttachmentFile(
    name: String,
    mimeType: String,
    size: Long,
): AttachmentValidationResult {
    val sanitized = sanitizeFileName(name)

    if (sanitized == "attachment") {
        return AttachmentValidationResult.Invalid(
            AttachmentValidationError(
                code = AttachmentValidationCode.MISSING_NAME,
                message = "The file has no usable name.",
            ),
        )
    }

    if (size <= 0) {
        return AttachmentValidationResult.Invalid(
            AttachmentValidationError(
                code = AttachmentValidationCode.EMPTY,
                message = "$sanitized is empty.",
            ),
        )
    }

    val declared = mimeType.lowercase().trim()
    val extension = fileExtension(sanitized)
    val imageByMime = declared in SUPPORTED_IMAGE_MIME_TYPES
    val imageByExtension = declared.isEmpty() && extension in IMAGE_EXTENSIONS
    val textByMime = declared in SUPPORTED_TEXT_MIME_TYPES
    val textByExtension = declared.isEmpty() && extension in SUPPORTED_TEXT_EXTENSIONS

    if (imageByMime || imageByExtension) {
        if (size > MAX_IMAGE_BYTES) {
            return AttachmentValidationResult.Invalid(
                AttachmentValidationError(
                    code = AttachmentValidationCode.TOO_LARGE,
                    message = "$sanitized is larger than the " +
                        "${MAX_IMAGE_BYTES / (1024 * 1024)} MB image limit.",
                ),
            )
        }

        return AttachmentValidationResult.Valid(
            ValidatedAttachmentFile(
                kind = AttachmentKind.IMAGE,
                mimeType = if (imageByMime) declared else IMAGE_EXTENSIONS.getValue(extension),
                name = if (extension.isEmpty()) "$sanitized.png" else sanitized,
            ),
        )
    }

    if (textByMime || textByExtension) {
        if (size > MAX_TEXT_BYTES) {
            return AttachmentValidationResult.Invalid(
                AttachmentValidationError(
                    code = AttachmentValidationCode.TOO_LARGE,
                    message = "$sanitized is larger than the " +
                        "${MAX_TEXT_BYTES / (1024 * 1024)} MB text-file limit.",
                ),
            )
        }

        return AttachmentValidationResult.Valid(
            ValidatedAttachmentFile(
                kind = AttachmentKind.FILE,
                mimeType = if (textByMime) declared else mimeTypeForExtension(extension),
                name = sanitized,
            ),
        )
    }

    return AttachmentValidationResult.Invalid(
        AttachmentValidationError(
            code = AttachmentValidationCode.UNSUPPORTED_TYPE,
            message = "$sanitized is not a supported image or text file.",
        ),
    )
}

/** Scale dimensions so the longest edge is at most [maxEdge]. Never upscales. */
fun scaledDimensions(
    width: Int,
    height: Int,
    maxEdge: Int = MAX_IMAGE_EDGE,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return width to height

    val longest = maxOf(width, height)
    if (longest <= maxEdge) return width to height

    val ratio = maxEdge.toDouble() / longest
    return maxOf(1, (width * ratio).roundToInt()) to maxOf(1, (height * ratio).roundToInt())
}

/** Prefer WebP for images that may have transparency, JPEG for opaque photos. */
fun outputMimeTypeFor(inputMimeType: String): String =
    if (inputMimeType.equals("image/jpeg", ignoreCase = true)) "image/jpeg" else "image/webp"

/** Human-readable size for chips and message rendering. */
fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
