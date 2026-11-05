package dev.chatui.feature.chat

import dev.chatui.core.database.attachment.AddAttachmentsResult
import dev.chatui.core.database.attachment.AttachmentInput
import dev.chatui.core.database.attachment.ResolvedAttachmentData
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Message
import java.io.File
import java.time.Instant
import java.util.Base64

/** In-memory attachment store for ViewModel and UI tests. */
class FakeChatAttachmentStore : ChatAttachmentStore {
    val attachments: MutableMap<String, Attachment> = mutableMapOf()
    val bytes: MutableMap<String, ByteArray> = mutableMapOf()
    val files: MutableMap<String, File> = mutableMapOf()
    var cleanupCalls: Int = 0
        private set

    private var nextId = 0

    override suspend fun addFiles(
        inputs: List<AttachmentInput>,
        messageId: String?,
    ): AddAttachmentsResult {
        val added = inputs.map { input ->
            val id = "attachment-${nextId++}"
            val attachment = Attachment(
                id = id,
                messageId = messageId,
                kind = if (input.mimeType.startsWith("image/")) {
                    AttachmentKind.IMAGE
                } else {
                    AttachmentKind.FILE
                },
                mimeType = input.mimeType,
                name = input.name,
                size = input.bytes.size.toLong(),
                sha256 = "sha-$id",
                storageKey = "attachments/sha-$id",
                width = null,
                height = null,
                createdAt = TEST_NOW,
            )
            attachments[id] = attachment
            bytes[id] = input.bytes
            attachment
        }

        return AddAttachmentsResult(attachments = added, errors = emptyList())
    }

    override suspend fun get(id: String): Attachment? = attachments[id]

    override suspend fun forMessage(messageId: String): List<Attachment> =
        attachments.values.filter { it.messageId == messageId }

    override suspend fun attachToMessage(ids: List<String>, messageId: String) {
        ids.forEach { id ->
            attachments[id]?.let { attachments[id] = it.copy(messageId = messageId) }
        }
    }

    override suspend fun remove(id: String) {
        attachments.remove(id)
        bytes.remove(id)
        files.remove(id)
    }

    override suspend fun deleteForMessages(messageIds: List<String>) {
        val allowed = messageIds.toSet()
        attachments.values
            .filter { it.messageId in allowed }
            .map { it.id }
            .forEach { remove(it) }
    }

    override suspend fun resolveForMessages(
        messages: List<Message>,
    ): Map<String, ResolvedAttachmentData> {
        val ids = messages
            .flatMap { it.parts }
            .mapNotNull { part ->
                when (part) {
                    is ContentPart.Image -> part.attachmentId
                    is ContentPart.File -> part.attachmentId
                    else -> null
                }
            }
            .toSet()

        return ids.mapNotNull { id ->
            val attachment = attachments[id] ?: return@mapNotNull null
            val data = bytes[id] ?: return@mapNotNull null
            id to ResolvedAttachmentData(
                mimeType = attachment.mimeType,
                name = attachment.name,
                dataBase64 = Base64.getEncoder().encodeToString(data),
            )
        }.toMap()
    }

    override suspend fun getDataUrl(id: String): String? = null

    override suspend fun fileFor(id: String): File? = files[id]

    override suspend fun cleanupOrphans(before: Instant): List<String> {
        cleanupCalls += 1
        return emptyList()
    }
}
