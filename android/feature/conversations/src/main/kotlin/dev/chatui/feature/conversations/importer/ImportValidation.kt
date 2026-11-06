package dev.chatui.feature.conversations.importer

import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Model
import dev.chatui.feature.conversations.export.ChatExport
import dev.chatui.feature.conversations.export.EXPORT_FORMAT_VERSION
import dev.chatui.feature.conversations.export.ExportAttachment
import dev.chatui.feature.conversations.export.validateChatExport

private val UNSAFE_ATTACHMENT_NAME = Regex("[\\\\/:*?\"<>|]")
private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

/**
 * Validate the archive shape and every cross-record reference before any write.
 *
 * This is the untrusted boundary: imported content is never executed, and the
 * function rejects duplicate identities, dangling conversation/message/
 * provider/model references, missing attachment references, unsafe attachment
 * names, and malformed attachment hashes.
 */
fun validateImportArchive(archive: ChatExport): ChatExport {
    if (archive.formatVersion != EXPORT_FORMAT_VERSION) {
        throw ImportValidationError(
            ImportErrorCode.SCHEMA,
            "Unsupported export formatVersion ${archive.formatVersion}; " +
                "expected $EXPORT_FORMAT_VERSION.",
        )
    }

    val conversationIds = uniqueIds(
        archive.conversations.map { it.id },
        "conversation",
    )
    uniqueIds(archive.messages.map { it.id }, "message")
    val attachmentIds = uniqueIds(
        archive.attachments.map { it.id },
        "attachment",
    )
    val providerIds = uniqueIds(archive.providers.map { it.id }, "provider")
    uniqueIds(archive.models.map { it.id }, "model")

    val modelsById = mutableMapOf<String, Model>()
    val modelRemoteKeys = mutableSetOf<String>()

    archive.models.forEach { model ->
        if (model.providerId !in providerIds) {
            throw ImportValidationError(
                ImportErrorCode.INVALID_REFERENCE,
                "Model ${model.id} references missing provider ${model.providerId}.",
            )
        }

        val key = "${model.providerId}\u0000${model.remoteId}"
        if (!modelRemoteKeys.add(key)) {
            throw ImportValidationError(
                ImportErrorCode.DUPLICATE_ID,
                "Duplicate model identity ${model.providerId}/${model.remoteId}.",
            )
        }

        modelsById[model.id] = model
    }

    val messageSequences = mutableSetOf<String>()

    archive.messages.forEach { message ->
        if (message.conversationId !in conversationIds) {
            throw ImportValidationError(
                ImportErrorCode.INVALID_REFERENCE,
                "Message ${message.id} references missing conversation " +
                    "${message.conversationId}.",
            )
        }

        val sequenceKey = "${message.conversationId}\u0000${message.sequence}"
        if (!messageSequences.add(sequenceKey)) {
            throw ImportValidationError(
                ImportErrorCode.DUPLICATE_ID,
                "Duplicate message sequence ${message.sequence} in conversation " +
                    "${message.conversationId}.",
            )
        }

        message.providerId?.let { providerId ->
            if (providerId !in providerIds) {
                throw ImportValidationError(
                    ImportErrorCode.INVALID_REFERENCE,
                    "Message ${message.id} references missing provider $providerId.",
                )
            }
        }

        message.modelId?.let { modelId ->
            val model = modelsById[modelId] ?: throw ImportValidationError(
                ImportErrorCode.INVALID_REFERENCE,
                "Message ${message.id} references missing model $modelId.",
            )

            if (message.providerId != null && model.providerId != message.providerId) {
                throw ImportValidationError(
                    ImportErrorCode.INVALID_REFERENCE,
                    "Message ${message.id} model $modelId does not belong to provider " +
                        "${message.providerId}.",
                )
            }
        }

        message.parts.forEach { part ->
            when (part) {
                is ContentPart.Image -> requireAttachment(part.attachmentId, attachmentIds, message.id)
                is ContentPart.File -> requireAttachment(part.attachmentId, attachmentIds, message.id)
                else -> Unit
            }
        }
    }

    archive.conversations.forEach { conversation ->
        if (conversation.providerId !in providerIds) {
            throw ImportValidationError(
                ImportErrorCode.INVALID_REFERENCE,
                "Conversation ${conversation.id} references missing provider " +
                    "${conversation.providerId}.",
            )
        }

        val model = modelsById[conversation.modelId] ?: throw ImportValidationError(
            ImportErrorCode.INVALID_REFERENCE,
            "Conversation ${conversation.id} references missing model " +
                "${conversation.modelId}.",
        )

        if (model.providerId != conversation.providerId) {
            throw ImportValidationError(
                ImportErrorCode.INVALID_REFERENCE,
                "Conversation ${conversation.id} model ${conversation.modelId} does not " +
                    "belong to provider ${conversation.providerId}.",
            )
        }
    }

    archive.attachments.forEach { attachment ->
        validateAttachmentMetadata(attachment)
    }

    val settings = archive.settings

    settings.defaultProviderId?.let { providerId ->
        if (providerId !in providerIds) {
            throw ImportValidationError(
                ImportErrorCode.INVALID_REFERENCE,
                "Settings reference missing provider $providerId.",
            )
        }
    }

    settings.defaultModelId?.let { modelId ->
        val model = modelsById[modelId] ?: throw ImportValidationError(
            ImportErrorCode.INVALID_REFERENCE,
            "Settings reference missing model $modelId.",
        )

        if (settings.defaultProviderId != null &&
            model.providerId != settings.defaultProviderId
        ) {
            throw ImportValidationError(
                ImportErrorCode.INVALID_REFERENCE,
                "Settings model $modelId does not belong to provider " +
                    "${settings.defaultProviderId}.",
            )
        }
    }

    settings.lastActiveConversationId?.let { conversationId ->
        if (conversationId !in conversationIds) {
            throw ImportValidationError(
                ImportErrorCode.INVALID_REFERENCE,
                "Settings reference missing conversation $conversationId.",
            )
        }
    }

    return archive
}

/** Decode and validate an archive, throwing [ImportValidationError] on failure. */
fun decodeImportArchive(json: String): ChatExport {
    val archive = try {
        validateChatExport(json)
    } catch (error: IllegalArgumentException) {
        throw ImportValidationError(
            ImportErrorCode.SCHEMA,
            error.message ?: "The archive does not match the chatui.export schema.",
            error,
        )
    }

    return validateImportArchive(archive)
}

private fun requireAttachment(
    attachmentId: String,
    attachmentIds: Set<String>,
    messageId: String,
) {
    if (attachmentId !in attachmentIds) {
        throw ImportValidationError(
            ImportErrorCode.INVALID_REFERENCE,
            "Message $messageId references missing attachment $attachmentId.",
        )
    }
}

private fun uniqueIds(values: List<String>, label: String): Set<String> {
    val seen = mutableSetOf<String>()

    values.forEach { value ->
        validateId(value, label)

        if (!seen.add(value)) {
            throw ImportValidationError(
                ImportErrorCode.DUPLICATE_ID,
                "Duplicate $label id: $value",
            )
        }
    }

    return seen
}

private fun validateId(id: String, label: String) {
    if (id.isEmpty() || id.length > MAX_IMPORT_ID_LENGTH || hasControlCharacter(id)) {
        throw ImportValidationError(
            ImportErrorCode.INVALID_ID,
            "$label id is not a safe identifier.",
        )
    }
}

private fun validateAttachmentMetadata(attachment: ExportAttachment) {
    validateAttachmentName(attachment.name)

    if (!SHA256_PATTERN.matches(attachment.sha256)) {
        throw ImportValidationError(
            ImportErrorCode.INVALID_ATTACHMENT,
            "Attachment ${attachment.id} has an invalid SHA-256 value.",
        )
    }
}

private fun validateAttachmentName(name: String) {
    if (name.isEmpty() ||
        name.length > MAX_IMPORT_ATTACHMENT_NAME_LENGTH ||
        name.trim() != name ||
        name == "." ||
        name == ".." ||
        hasControlCharacter(name) ||
        UNSAFE_ATTACHMENT_NAME.containsMatchIn(name)
    ) {
        throw ImportValidationError(
            ImportErrorCode.UNSAFE_ATTACHMENT_NAME,
            "Unsafe attachment name: ${name.quote()}",
        )
    }
}

private fun hasControlCharacter(value: String): Boolean =
    value.any { character -> character.code < 0x20 || character.code == 0x7f }

private fun String.quote(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
