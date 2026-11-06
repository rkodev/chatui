package dev.chatui.feature.chat

import dev.chatui.core.database.attachment.AddAttachmentsResult
import dev.chatui.core.database.attachment.AttachmentInput
import dev.chatui.core.database.attachment.AttachmentService
import dev.chatui.core.database.attachment.ResolvedAttachmentData
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.Message
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Attachment surface used by the chat ViewModel.
 *
 * The production implementation wraps [AttachmentService]; tests use an
 * in-memory fake so the ViewModel can be exercised without Room or files.
 */
interface ChatAttachmentStore {
    suspend fun addFiles(
        inputs: List<AttachmentInput>,
        messageId: String? = null,
    ): AddAttachmentsResult

    suspend fun get(id: String): Attachment?

    suspend fun forMessage(messageId: String): List<Attachment>

    suspend fun attachToMessage(ids: List<String>, messageId: String)

    suspend fun remove(id: String)

    suspend fun deleteForMessages(messageIds: List<String>)

    suspend fun resolveForMessages(messages: List<Message>): Map<String, ResolvedAttachmentData>

    suspend fun getDataUrl(id: String): String?

    suspend fun fileFor(id: String): File?

    suspend fun cleanupOrphans(before: Instant = Instant.MAX): List<String>
}

/** [ChatAttachmentStore] backed by the application attachment service. */
class RepositoryChatAttachmentStore(
    private val service: AttachmentService,
) : ChatAttachmentStore {
    private val cleanedOrphans = AtomicBoolean(false)

    override suspend fun addFiles(
        inputs: List<AttachmentInput>,
        messageId: String?,
    ): AddAttachmentsResult = service.addFiles(inputs, messageId)

    override suspend fun get(id: String): Attachment? = service.get(id)

    override suspend fun forMessage(messageId: String): List<Attachment> =
        service.forMessage(messageId)

    override suspend fun attachToMessage(ids: List<String>, messageId: String) {
        service.attachToMessage(ids, messageId)
    }

    override suspend fun remove(id: String) {
        service.remove(id)
    }

    override suspend fun deleteForMessages(messageIds: List<String>) {
        service.deleteForMessages(messageIds)
    }

    override suspend fun resolveForMessages(
        messages: List<Message>,
    ): Map<String, ResolvedAttachmentData> = service.resolveForMessages(messages)

    override suspend fun getDataUrl(id: String): String? = service.getDataUrl(id)

    override suspend fun fileFor(id: String): File? = service.fileFor(id)

    override suspend fun cleanupOrphans(before: Instant): List<String> {
        if (!cleanedOrphans.compareAndSet(false, true)) return emptyList()

        return try {
            service.cleanupOrphans(before)
        } catch (error: Throwable) {
            cleanedOrphans.set(false)
            throw error
        }
    }
}
