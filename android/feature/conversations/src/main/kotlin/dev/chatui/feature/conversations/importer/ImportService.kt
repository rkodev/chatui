package dev.chatui.feature.conversations.importer

import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.import.ImportMode
import dev.chatui.core.database.import.ImportWriteRequest
import dev.chatui.core.database.import.ImportWriter
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Message
import dev.chatui.core.model.Model
import dev.chatui.feature.conversations.export.ChatExport
import dev.chatui.feature.conversations.export.ExportAttachment
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Safe `chatui.export` import.
 *
 * Validation and attachment byte verification happen before any write. Merge
 * is the default and upserts by id without deleting local records. Replace
 * clears the imported data stores inside the same Room transaction that writes
 * the archive. If the transaction fails, newly written attachment bytes are
 * removed and replace-mode settings are restored.
 */
class ImportService(
    private val database: ChatUiDatabase,
    private val attachmentStorage: AttachmentStorage,
    private val settingsStore: ImportSettingsStore,
    private val now: () -> String = { Instant.now().toString() },
    private val maxBytes: Int = MAX_IMPORT_BYTES,
    private val maxAttachmentBytes: Int = MAX_IMPORT_ATTACHMENT_BYTES,
) {
    private val writer = ImportWriter(database, attachmentStorage)
    private val models = ModelRepository(database.modelDao())

    suspend fun importText(
        text: String,
        options: ImportOptions = ImportOptions(),
    ): ImportResult = importBytes(text.toByteArray(Charsets.UTF_8), options)

    suspend fun importBytes(
        bytes: ByteArray,
        options: ImportOptions = ImportOptions(),
    ): ImportResult {
        if (bytes.size > maxBytes) {
            throw ImportValidationError(
                ImportErrorCode.TOO_LARGE,
                "The archive is larger than the $maxBytes byte import limit.",
            )
        }

        val text = bytes.toString(Charsets.UTF_8)
        try {
            Json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw ImportValidationError(
                ImportErrorCode.INVALID_JSON,
                "The archive is not valid JSON.",
                error,
            )
        }

        val archive = decodeImportArchive(text)
        val decoded = decodeAttachmentBytes(archive)
        val existingModels = if (options.mode == ImportMode.MERGE) {
            models.all()
        } else {
            emptyList()
        }
        val modelPlan = planModels(archive.models, existingModels)
        val conversations = archive.conversations.map { conversation ->
            conversation.copy(modelId = modelPlan.remap(conversation.modelId))
        }
        val messages = archive.messages.map { message ->
            message.copy(modelId = message.modelId?.let(modelPlan::remap))
        }
        val settings = archive.settings.copy(
            defaultModelId = archive.settings.defaultModelId?.let(modelPlan::remap),
        )
        val attachments = buildAttachments(archive, messages)

        val previousSettings = if (options.mode == ImportMode.REPLACE) {
            settingsStore.current()
        } else {
            null
        }

        if (options.mode == ImportMode.REPLACE) {
            settingsStore.save(settings.toAndroidSettings())
        }

        val result = try {
            writer.apply(
                request = ImportWriteRequest(
                    providers = archive.providers,
                    models = modelPlan.models,
                    conversations = conversations,
                    messages = messages,
                    attachments = attachments,
                    attachmentBytes = decoded,
                ),
                mode = options.mode,
            )
        } catch (error: Throwable) {
            if (previousSettings != null) {
                runCatching { settingsStore.save(previousSettings) }
            }
            throw error
        }

        return ImportResult(
            mode = result.mode,
            conversations = result.conversations,
            messages = result.messages,
            attachments = result.attachments,
            providers = result.providers,
            models = result.models,
            attachmentBytes = result.attachmentBytes,
        )
    }

    private fun decodeAttachmentBytes(archive: ChatExport): Map<String, ByteArray> {
        val decoded = mutableMapOf<String, ByteArray>()

        archive.attachments.forEach { attachment ->
            val data = attachment.dataBase64 ?: return@forEach
            val bytes = try {
                Base64.getDecoder().decode(data)
            } catch (error: IllegalArgumentException) {
                throw ImportValidationError(
                    ImportErrorCode.INVALID_ATTACHMENT,
                    "Attachment ${attachment.id} has invalid base64 data.",
                    error,
                )
            }

            if (bytes.size > maxAttachmentBytes) {
                throw ImportValidationError(
                    ImportErrorCode.TOO_LARGE,
                    "Attachment ${attachment.id} is larger than the " +
                        "$maxAttachmentBytes byte import limit.",
                )
            }

            if (bytes.size.toLong() != attachment.size) {
                throw ImportValidationError(
                    ImportErrorCode.INVALID_ATTACHMENT,
                    "Attachment ${attachment.id} byte length does not match its declared size.",
                )
            }

            val digest = sha256Hex(bytes)
            if (!digest.equals(attachment.sha256, ignoreCase = true)) {
                throw ImportValidationError(
                    ImportErrorCode.INVALID_ATTACHMENT,
                    "Attachment ${attachment.id} bytes do not match its SHA-256 value.",
                )
            }

            decoded[attachment.sha256.lowercase()] = bytes
        }

        return decoded
    }

    private fun buildAttachments(
        archive: ChatExport,
        messages: List<Message>,
    ): List<Attachment> {
        val references = mutableMapOf<String, AttachmentReference>()

        messages.forEach { message ->
            message.parts.forEach { part ->
                when (part) {
                    is ContentPart.Image -> references.putIfAbsent(
                        part.attachmentId,
                        AttachmentReference(
                            messageId = message.id,
                            kind = AttachmentKind.IMAGE,
                            createdAt = message.createdAt,
                        ),
                    )

                    is ContentPart.File -> references.putIfAbsent(
                        part.attachmentId,
                        AttachmentReference(
                            messageId = message.id,
                            kind = AttachmentKind.FILE,
                            createdAt = message.createdAt,
                        ),
                    )

                    else -> Unit
                }
            }
        }

        return archive.attachments.map { attachment ->
            val reference = references[attachment.id]
            val sha256 = attachment.sha256.lowercase()

            Attachment(
                id = attachment.id,
                messageId = reference?.messageId,
                kind = reference?.kind ?: kindFor(attachment),
                mimeType = attachment.mimeType,
                name = attachment.name,
                size = attachment.size,
                sha256 = sha256,
                storageKey = AttachmentStorage.storageKeyFor(sha256),
                width = null,
                height = null,
                createdAt = reference?.createdAt ?: Instant.parse(now()),
            )
        }
    }

    private fun kindFor(attachment: ExportAttachment): AttachmentKind =
        if (attachment.mimeType.startsWith("image/")) {
            AttachmentKind.IMAGE
        } else {
            AttachmentKind.FILE
        }

    private fun planModels(
        imported: List<Model>,
        existing: List<Model>,
    ): ModelPlan {
        val byRemote = existing.associateBy { model -> model.providerId to model.remoteId }
        val remap = mutableMapOf<String, String>()
        val models = mutableListOf<Model>()

        imported.forEach { model ->
            val local = byRemote[model.providerId to model.remoteId]

            if (local != null && local.id != model.id) {
                remap[model.id] = local.id
            } else {
                models += model
            }
        }

        return ModelPlan(models = models, remap = remap)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class AttachmentReference(
        val messageId: String,
        val kind: AttachmentKind,
        val createdAt: Instant,
    )

    private data class ModelPlan(
        val models: List<Model>,
        val remap: Map<String, String>,
    ) {
        fun remap(id: String): String = remap[id] ?: id
    }
}
