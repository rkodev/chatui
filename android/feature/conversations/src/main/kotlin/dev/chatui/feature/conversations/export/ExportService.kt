package dev.chatui.feature.conversations.export

import dev.chatui.core.database.export.ExportReaderLike
import dev.chatui.core.database.export.ExportSnapshot
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.Model
import dev.chatui.core.model.Provider
import dev.chatui.core.model.Settings
import java.text.Normalizer
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.SerializationException

/**
 * Build a `chatui.export` archive from a repository snapshot.
 *
 * Attachment metadata is always included for the snapshot's attachments.
 * `dataBase64` is added only when [ExportOptions.includeAttachmentBytes] is
 * true; Markdown never embeds bytes.
 */
suspend fun buildChatExport(
    snapshot: ExportSnapshot,
    options: ExportOptions = ExportOptions(),
    bytes: AttachmentByteReader? = null,
    now: () -> String = { Instant.now().toString() },
    appVersion: String = EXPORT_APP_VERSION,
): ChatExport {
    val exportedAt = options.exportedAt ?: now()
    val includeBytes = options.includeAttachmentBytes

    val archive = ChatExport(
        format = EXPORT_FORMAT,
        formatVersion = EXPORT_FORMAT_VERSION,
        exportedAt = exportedAt,
        appVersion = options.appVersion ?: appVersion,
        conversations = snapshot.conversations,
        messages = snapshot.messages,
        attachments = snapshot.attachments.map { attachment ->
            val exported = ExportAttachment(
                id = attachment.id,
                name = attachment.name,
                mimeType = attachment.mimeType,
                sha256 = attachment.sha256,
                size = attachment.size,
            )

            if (!includeBytes) {
                exported
            } else {
                val reader = bytes ?: throw IllegalStateException(
                    "Attachment bytes were requested but no attachment reader is available.",
                )
                val data = reader.get(attachment.storageKey) ?: throw IllegalStateException(
                    "Attachment ${attachment.id} bytes are missing from local storage.",
                )
                exported.copy(dataBase64 = Base64.getEncoder().encodeToString(data))
            }
        },
        providers = snapshot.providers,
        models = snapshot.models,
        settings = snapshot.settings,
    )

    validateChatExport(archive)
    return archive
}

/** Serialize and validate an archive, returning the exact file content. */
fun encodeArchive(archive: ChatExport): String {
    val json = ExportJson.encodeToString(ChatExport.serializer(), archive)
    validateChatExport(json)
    return "$json\n"
}

/** Decode and validate an archive, throwing [IllegalArgumentException] on failure. */
fun validateChatExport(json: String): ChatExport {
    val archive = try {
        ExportJson.decodeFromString(ChatExport.serializer(), json)
    } catch (error: SerializationException) {
        throw IllegalArgumentException(
            "Export is not a valid chatui.export archive: ${error.message}",
            error,
        )
    }

    validateChatExport(archive)
    return archive
}

/** Validate the contract constraints that serialization alone cannot express. */
fun validateChatExport(archive: ChatExport) {
    val errors = mutableListOf<String>()

    if (archive.format != EXPORT_FORMAT) {
        errors += "format must be $EXPORT_FORMAT"
    }
    if (archive.formatVersion < 1) {
        errors += "formatVersion must be at least 1"
    }
    if (!APP_VERSION_REGEX.matches(archive.appVersion)) {
        errors += "appVersion must be a semantic version"
    }
    if (!isIsoInstant(archive.exportedAt)) {
        errors += "exportedAt must be an ISO date-time"
    }

    archive.conversations.forEachIndexed { index, conversation ->
        validateConversation(index, conversation, errors)
    }
    archive.messages.forEachIndexed { index, message ->
        validateMessage(index, message, errors)
    }
    archive.attachments.forEachIndexed { index, attachment ->
        validateAttachment(index, attachment, errors)
    }
    archive.providers.forEachIndexed { index, provider ->
        validateProvider(index, provider, errors)
    }
    archive.models.forEachIndexed { index, model ->
        validateModel(index, model, errors)
    }
    validateSettings(archive.settings, errors)

    if (errors.isNotEmpty()) {
        throw IllegalArgumentException(
            "Invalid chatui.export archive: ${errors.joinToString("; ")}",
        )
    }
}

/**
 * Human-readable per-conversation export.
 *
 * Message order follows `sequence`, and every content part keeps its original
 * wording. Attachment references stay inline so a reader can match them to the
 * attachment array emitted by the JSON export.
 */
fun formatConversationMarkdown(snapshot: ExportSnapshot): String {
    val conversation = snapshot.conversations.firstOrNull()
        ?: throw IllegalArgumentException(
            "A conversation snapshot is required for Markdown export.",
        )

    val lines = mutableListOf<String>()
    lines += "# ${conversation.title}"
    lines += ""
    lines += "- Conversation ID: `${conversation.id}`"
    lines += "- Provider: ${conversation.providerId}"
    lines += "- Model: ${conversation.modelLabel} (`${conversation.modelId}`)"
    lines += "- Created: ${conversation.createdAt}"
    lines += "- Updated: ${conversation.updatedAt}"
    lines += "- Last message: ${conversation.lastMessageAt}"
    lines += "- Messages: ${conversation.messageCount}"
    lines += "- Pinned: ${if (conversation.pinned) "yes" else "no"}"
    lines += "- Archived: ${if (conversation.archived) "yes" else "no"}"
    lines += "- Temporary: ${if (conversation.temporary) "yes" else "no"}"
    lines += ""

    if (conversation.systemPrompt.isNotBlank()) {
        lines += "## System prompt"
        lines += ""
        lines += conversation.systemPrompt
        lines += ""
    }

    lines += "## Messages"
    lines += ""

    snapshot.messages.forEach { message ->
        val snapshotLabel = if (message.modelLabel == null) {
            ""
        } else {
            val modelId = if (message.modelId == null) "" else " (`${message.modelId}`)"
            " — ${message.modelLabel}$modelId"
        }
        lines += "### ${message.sequence + 1}. ${message.role.name.lowercase()} — " +
            "${message.createdAt}$snapshotLabel"
        lines += ""

        message.parts.forEach { part ->
            lines += renderPart(snapshot, part)
            lines += ""
        }

        message.usage?.let { usage ->
            val estimated = if (usage.estimated) " (estimated)" else ""
            lines += "_Usage: ${usage.inputTokens ?: "?"} in / " +
                "${usage.outputTokens ?: "?"} out / " +
                "${usage.totalTokens ?: "?"} total${estimated}_"
            lines += ""
        }

        message.error?.let { error ->
            lines += "_Error (${error.code.name.lowercase()}): ${error.message}_"
            lines += ""
        }
    }

    return lines.joinToString("\n").trimEnd() + "\n"
}

/** Build per-conversation and full `chatui.export` archives. */
class ExportService(
    private val reader: ExportReaderLike,
    private val attachmentBytes: AttachmentByteReader? = null,
    private val now: () -> String = { Instant.now().toString() },
    private val appVersion: String = EXPORT_APP_VERSION,
) {
    suspend fun listConversations(): List<Conversation> = reader.listConversations()

    suspend fun exportAll(options: ExportOptions = ExportOptions()): ExportArtifact {
        val exportedAt = options.exportedAt ?: now()
        val snapshot = reader.readAll()
        val archive = buildChatExport(
            snapshot = snapshot,
            options = options.copy(
                exportedAt = exportedAt,
                appVersion = options.appVersion ?: appVersion,
            ),
            bytes = attachmentBytes,
            now = now,
            appVersion = appVersion,
        )

        return ExportArtifact(
            filename = exportFilename(exportedAt),
            mimeType = "application/json",
            content = encodeArchive(archive),
        )
    }

    suspend fun exportConversation(
        conversationId: String,
        options: ConversationExportOptions = ConversationExportOptions(),
    ): ExportArtifact {
        val snapshot = reader.readConversation(conversationId)
            ?: throw IllegalArgumentException("Conversation $conversationId was not found.")
        val conversation = snapshot.conversations.first()
        val exportedAt = options.exportedAt ?: now()

        return when (options.format) {
            ExportFormat.MARKDOWN -> ExportArtifact(
                filename = conversationFilename(conversation, ExportFormat.MARKDOWN),
                mimeType = "text/markdown",
                content = formatConversationMarkdown(snapshot),
            )

            ExportFormat.JSON -> {
                val archive = buildChatExport(
                    snapshot = snapshot,
                    options = ExportOptions(
                        includeAttachmentBytes = options.includeAttachmentBytes,
                        exportedAt = exportedAt,
                        appVersion = options.appVersion ?: appVersion,
                    ),
                    bytes = attachmentBytes,
                    now = now,
                    appVersion = appVersion,
                )

                ExportArtifact(
                    filename = conversationFilename(conversation, ExportFormat.JSON),
                    mimeType = "application/json",
                    content = encodeArchive(archive),
                )
            }
        }
    }
}

/** Full-archive filename for a timestamp. */
fun exportFilename(exportedAt: String): String =
    "chatui-export-${timestampForFilename(exportedAt)}.json"

/** Per-conversation filename for a timestamp and format. */
fun conversationFilename(
    conversation: Conversation,
    format: ExportFormat,
): String {
    val extension = if (format == ExportFormat.JSON) "json" else "md"
    return "chatui-${slugify(conversation.title)}-${conversation.id}.$extension"
}

private fun renderPart(snapshot: ExportSnapshot, part: ContentPart): String = when (part) {
    is ContentPart.Text -> part.text
    is ContentPart.Reasoning -> "_Reasoning:_\n\n${part.text}"
    is ContentPart.ToolCall ->
        "_Tool call_ `${part.name}` (${part.id}):\n\n```json\n${part.argumentsJson}\n```"
    is ContentPart.ToolResult -> "_Tool result_ (${part.toolCallId}):\n\n${part.content}"
    is ContentPart.Image -> {
        val attachment = snapshot.attachments.firstOrNull { it.id == part.attachmentId }
        val name = attachment?.name ?: part.attachmentId
        val size = attachment?.let { ", ${it.size} bytes" } ?: ""
        val alt = if (part.alt.isNotEmpty()) " — ${part.alt}" else ""
        "_[Image: $name (${part.mimeType}$size), attachment ${part.attachmentId}]_$alt"
    }
    is ContentPart.File -> {
        val attachment = snapshot.attachments.firstOrNull { it.id == part.attachmentId }
        val name = attachment?.name ?: part.name
        val size = attachment?.let { ", ${it.size} bytes" } ?: ""
        "_[File: $name (${part.mimeType}$size), attachment ${part.attachmentId}]_"
    }
}

private fun slugify(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
    val slug = normalized
        .replace(Regex("[^a-zA-Z0-9]+"), "-")
        .trim('-')
        .lowercase()
        .take(48)

    return slug.ifEmpty { "conversation" }
}

private fun timestampForFilename(iso: String): String = iso.replace(Regex("[:.]"), "-")

private val APP_VERSION_REGEX = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+([+-].*)?$")

private fun isIsoInstant(value: String): Boolean =
    runCatching { Instant.parse(value) }.isSuccess

private fun validateConversation(
    index: Int,
    conversation: Conversation,
    errors: MutableList<String>,
) {
    val path = "conversations[$index]"
    if (conversation.id.isEmpty()) errors += "$path.id must not be empty"
    if (conversation.providerId.isEmpty()) errors += "$path.providerId must not be empty"
    if (conversation.modelId.isEmpty()) errors += "$path.modelId must not be empty"
    if (conversation.messageCount < 0) errors += "$path.messageCount must be at least 0"
    validateParams("$path.params", conversation.params, errors)
    if (!isIsoInstant(conversation.createdAt.toString())) {
        errors += "$path.createdAt must be an ISO date-time"
    }
    if (!isIsoInstant(conversation.updatedAt.toString())) {
        errors += "$path.updatedAt must be an ISO date-time"
    }
    if (!isIsoInstant(conversation.lastMessageAt.toString())) {
        errors += "$path.lastMessageAt must be an ISO date-time"
    }
    conversation.lastError?.let { error ->
        if (!isIsoInstant(error.at.toString())) {
            errors += "$path.lastError.at must be an ISO date-time"
        }
        val retryAfter = error.retryAfterSeconds
        if (retryAfter != null && retryAfter < 0) {
            errors += "$path.lastError.retryAfterSeconds must be at least 0"
        }
    }
}

private fun validateMessage(index: Int, message: Message, errors: MutableList<String>) {
    val path = "messages[$index]"
    if (message.id.isEmpty()) errors += "$path.id must not be empty"
    if (message.conversationId.isEmpty()) {
        errors += "$path.conversationId must not be empty"
    }
    if (message.sequence < 0) errors += "$path.sequence must be at least 0"
    message.parts.forEachIndexed { partIndex, part ->
        val partPath = "$path.parts[$partIndex]"
        when (part) {
            is ContentPart.Image -> {
                if (part.attachmentId.isEmpty()) {
                    errors += "$partPath.attachmentId must not be empty"
                }
                if (part.mimeType.isEmpty()) {
                    errors += "$partPath.mimeType must not be empty"
                }
            }

            is ContentPart.File -> {
                if (part.attachmentId.isEmpty()) {
                    errors += "$partPath.attachmentId must not be empty"
                }
                if (part.mimeType.isEmpty()) {
                    errors += "$partPath.mimeType must not be empty"
                }
                if (part.name.isEmpty()) errors += "$partPath.name must not be empty"
            }

            is ContentPart.ToolCall -> {
                if (part.id.isEmpty()) errors += "$partPath.id must not be empty"
                if (part.name.isEmpty()) errors += "$partPath.name must not be empty"
            }

            is ContentPart.ToolResult -> {
                if (part.toolCallId.isEmpty()) {
                    errors += "$partPath.toolCallId must not be empty"
                }
            }

            is ContentPart.Text, is ContentPart.Reasoning -> Unit
        }
    }
    message.usage?.let { usage ->
        val inputTokens = usage.inputTokens
        val outputTokens = usage.outputTokens
        val totalTokens = usage.totalTokens
        val costUsd = usage.costUsd
        if (inputTokens != null && inputTokens < 0) {
            errors += "$path.usage.inputTokens must be at least 0"
        }
        if (outputTokens != null && outputTokens < 0) {
            errors += "$path.usage.outputTokens must be at least 0"
        }
        if (totalTokens != null && totalTokens < 0) {
            errors += "$path.usage.totalTokens must be at least 0"
        }
        if (costUsd != null && costUsd < 0) {
            errors += "$path.usage.costUsd must be at least 0"
        }
    }
    val latencyMs = message.latencyMs
    if (latencyMs != null && latencyMs < 0) {
        errors += "$path.latencyMs must be at least 0"
    }
    val firstTokenMs = message.firstTokenMs
    if (firstTokenMs != null && firstTokenMs < 0) {
        errors += "$path.firstTokenMs must be at least 0"
    }
    if (!isIsoInstant(message.createdAt.toString())) {
        errors += "$path.createdAt must be an ISO date-time"
    }
    if (!isIsoInstant(message.updatedAt.toString())) {
        errors += "$path.updatedAt must be an ISO date-time"
    }
    message.deletedAt?.let { deletedAt ->
        if (!isIsoInstant(deletedAt.toString())) {
            errors += "$path.deletedAt must be an ISO date-time"
        }
    }
    message.error?.let { error ->
        if (!isIsoInstant(error.at.toString())) {
            errors += "$path.error.at must be an ISO date-time"
        }
        val retryAfter = error.retryAfterSeconds
        if (retryAfter != null && retryAfter < 0) {
            errors += "$path.error.retryAfterSeconds must be at least 0"
        }
    }
}

private fun validateAttachment(
    index: Int,
    attachment: ExportAttachment,
    errors: MutableList<String>,
) {
    val path = "attachments[$index]"
    if (attachment.id.isEmpty()) errors += "$path.id must not be empty"
    if (attachment.name.isEmpty()) errors += "$path.name must not be empty"
    if (attachment.mimeType.isEmpty()) errors += "$path.mimeType must not be empty"
    if (attachment.sha256.isEmpty()) errors += "$path.sha256 must not be empty"
    if (attachment.size < 0) errors += "$path.size must be at least 0"
}

private fun validateProvider(index: Int, provider: Provider, errors: MutableList<String>) {
    val path = "providers[$index]"
    if (provider.id.isEmpty()) errors += "$path.id must not be empty"
    if (provider.presetId.isEmpty()) errors += "$path.presetId must not be empty"
    if (provider.baseUrl.isEmpty()) errors += "$path.baseUrl must not be empty"
    if (!isIsoInstant(provider.createdAt.toString())) {
        errors += "$path.createdAt must be an ISO date-time"
    }
    if (!isIsoInstant(provider.updatedAt.toString())) {
        errors += "$path.updatedAt must be an ISO date-time"
    }
}

private fun validateModel(index: Int, model: Model, errors: MutableList<String>) {
    val path = "models[$index]"
    if (model.id.isEmpty()) errors += "$path.id must not be empty"
    if (model.providerId.isEmpty()) errors += "$path.providerId must not be empty"
    if (model.remoteId.isEmpty()) errors += "$path.remoteId must not be empty"
    if (model.label.isEmpty()) errors += "$path.label must not be empty"
    val contextWindow = model.contextWindow
    if (contextWindow != null && contextWindow < 1) {
        errors += "$path.contextWindow must be at least 1"
    }
    val maxOutputTokens = model.maxOutputTokens
    if (maxOutputTokens != null && maxOutputTokens < 1) {
        errors += "$path.maxOutputTokens must be at least 1"
    }
    model.pricing?.let { pricing ->
        val inputPerMTok = pricing.inputPerMTok
        val outputPerMTok = pricing.outputPerMTok
        val perRequest = pricing.perRequest
        val currency = pricing.currency
        if (inputPerMTok != null && inputPerMTok < 0) {
            errors += "$path.pricing.inputPerMTok must be at least 0"
        }
        if (outputPerMTok != null && outputPerMTok < 0) {
            errors += "$path.pricing.outputPerMTok must be at least 0"
        }
        if (perRequest != null && perRequest < 0) {
            errors += "$path.pricing.perRequest must be at least 0"
        }
        if (currency != null && currency.isEmpty()) {
            errors += "$path.pricing.currency must not be empty"
        }
    }
    if (!isIsoInstant(model.createdAt.toString())) {
        errors += "$path.createdAt must be an ISO date-time"
    }
    if (!isIsoInstant(model.updatedAt.toString())) {
        errors += "$path.updatedAt must be an ISO date-time"
    }
}

private fun validateSettings(settings: Settings, errors: MutableList<String>) {
    validateParams("settings.defaultParams", settings.defaultParams, errors)
    settings.backup?.let { backup ->
        if (backup.bucket.isEmpty()) errors += "settings.backup.bucket must not be empty"
        if (backup.prefix.isEmpty()) errors += "settings.backup.prefix must not be empty"
        if (backup.deviceId.isEmpty()) errors += "settings.backup.deviceId must not be empty"
        backup.lastSyncAt?.let { lastSyncAt ->
            if (!isIsoInstant(lastSyncAt.toString())) {
                errors += "settings.backup.lastSyncAt must be an ISO date-time"
            }
        }
    }
}

private fun validateParams(path: String, params: ChatParams, errors: MutableList<String>) {
    val temperature = params.temperature
    if (temperature != null && temperature !in 0.0..2.0) {
        errors += "$path.temperature must be between 0 and 2"
    }
    val topP = params.topP
    if (topP != null && topP !in 0.0..1.0) {
        errors += "$path.topP must be between 0 and 1"
    }
    val maxOutputTokens = params.maxOutputTokens
    if (maxOutputTokens != null && maxOutputTokens < 1) {
        errors += "$path.maxOutputTokens must be at least 1"
    }
}
