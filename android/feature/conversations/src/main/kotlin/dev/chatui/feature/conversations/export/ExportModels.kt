package dev.chatui.feature.conversations.export

import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.Model
import dev.chatui.core.model.Provider
import dev.chatui.core.model.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared export contract identifiers. */
const val EXPORT_FORMAT: String = "chatui.export"
const val EXPORT_FORMAT_VERSION: Int = 1
const val EXPORT_APP_VERSION: String = "0.1.0"

/** Shown before any export so the user knows the file is plaintext. */
const val EXPORT_PLAINTEXT_WARNING: String =
    "Exports contain plaintext conversations, prompts, and attachment metadata. " +
        "Anyone with the file can read them. Store exports somewhere you trust."

/**
 * Strict JSON configuration for export archives.
 *
 * Unknown keys are rejected so a malformed archive cannot silently lose data.
 * Defaults are not encoded, which keeps optional pricing fields absent rather
 * than `null`, matching the shared schema.
 */
val ExportJson: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = false
    explicitNulls = true
    prettyPrint = true
}

/** Attachment metadata plus optional base64 bytes. */
@Serializable
data class ExportAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sha256: String,
    val size: Long,
    val dataBase64: String? = null,
)

/** Runtime mirror of `contracts/schema/chat-export.schema.json`. */
@Serializable
data class ChatExport(
    val format: String,
    val formatVersion: Int,
    val exportedAt: String,
    val appVersion: String,
    val conversations: List<Conversation>,
    val messages: List<Message>,
    val attachments: List<ExportAttachment>,
    val providers: List<Provider>,
    val models: List<Model>,
    val settings: Settings,
)

/** Per-conversation output format. */
enum class ExportFormat {
    JSON,
    MARKDOWN,
}

/** Options shared by full and per-conversation JSON exports. */
data class ExportOptions(
    /** Include attachment bytes as base64. Defaults to false. */
    val includeAttachmentBytes: Boolean = false,
    /** Deterministic timestamp for tests. */
    val exportedAt: String? = null,
    /** Application version written to the archive. */
    val appVersion: String? = null,
)

/** Options for a single-conversation export. */
data class ConversationExportOptions(
    val format: ExportFormat = ExportFormat.JSON,
    val includeAttachmentBytes: Boolean = false,
    val exportedAt: String? = null,
    val appVersion: String? = null,
)

/** A formatted export ready for a SAF document write. */
data class ExportArtifact(
    val filename: String,
    val mimeType: String,
    val content: String,
)

/** Reads stored attachment bytes by content-addressed storage key. */
fun interface AttachmentByteReader {
    suspend fun get(storageKey: String): ByteArray?
}
