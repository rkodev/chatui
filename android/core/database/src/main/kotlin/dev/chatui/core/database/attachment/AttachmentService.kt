package dev.chatui.core.database.attachment

import dev.chatui.core.database.repository.AttachmentRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Message
import dev.chatui.core.model.newId
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64

/** One file supplied by a picker, clipboard, or drag-and-drop. */
data class AttachmentInput(
    val name: String,
    val mimeType: String,
    val size: Long,
    val bytes: ByteArray,
)

/** One rejected input with user-facing copy. */
data class AttachmentInputError(
    val fileName: String,
    val code: AttachmentValidationCode,
    val message: String,
)

/** Result of adding a batch of files. */
data class AddAttachmentsResult(
    val attachments: List<Attachment>,
    val errors: List<AttachmentInputError>,
)

/** Attachment bytes resolved for provider request serialization. */
data class ResolvedAttachmentData(
    val mimeType: String,
    val name: String?,
    val dataBase64: String,
)

/**
 * Validate, process, hash, store, and persist attachment metadata.
 *
 * Bytes are written before metadata. Metadata for one batch commits in a single
 * Room transaction, so a failed write cannot leave a partially attached
 * message. Bytes that never gained metadata are removed by [cleanupOrphans].
 */
class AttachmentService(
    private val repository: AttachmentRepository,
    private val messages: MessageRepository,
    private val storage: AttachmentStorage,
    private val imageProcessor: ImageProcessor = AndroidImageProcessor(),
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = ::newId,
) {
    suspend fun addFiles(
        inputs: List<AttachmentInput>,
        messageId: String? = null,
    ): AddAttachmentsResult {
        val attachments = mutableListOf<Attachment>()
        val errors = mutableListOf<AttachmentInputError>()

        inputs.forEach { input ->
            val validation = validateAttachmentFile(
                name = input.name,
                mimeType = input.mimeType,
                size = input.bytes.size.toLong(),
            )

            when (validation) {
                is AttachmentValidationResult.Invalid -> {
                    errors += AttachmentInputError(
                        fileName = input.name,
                        code = validation.error.code,
                        message = validation.error.message,
                    )
                }

                is AttachmentValidationResult.Valid -> {
                    try {
                        attachments += store(input, validation.value, messageId)
                    } catch (error: Exception) {
                        errors += AttachmentInputError(
                            fileName = input.name,
                            code = AttachmentValidationCode.UNSUPPORTED_TYPE,
                            message = error.message ?: "${input.name} could not be processed.",
                        )
                    }
                }
            }
        }

        if (attachments.isNotEmpty()) {
            repository.upsertAll(attachments)
        }

        return AddAttachmentsResult(attachments = attachments, errors = errors)
    }

    private suspend fun store(
        input: AttachmentInput,
        validated: ValidatedAttachmentFile,
        messageId: String?,
    ): Attachment {
        var bytes = input.bytes
        var mimeType = validated.mimeType
        var width: Int? = null
        var height: Int? = null

        if (validated.kind == AttachmentKind.IMAGE) {
            val processed = imageProcessor.process(bytes, mimeType)
            if (processed.width > 0 && processed.height > 0) {
                bytes = processed.bytes
                mimeType = processed.mimeType
                width = processed.width
                height = processed.height
            }
        }

        val sha256 = sha256Hex(bytes)
        val storageKey = storage.put(bytes, sha256)

        return Attachment(
            id = newId(),
            messageId = messageId,
            kind = validated.kind,
            mimeType = mimeType,
            name = validated.name,
            size = bytes.size.toLong(),
            sha256 = sha256,
            storageKey = storageKey,
            width = width,
            height = height,
            createdAt = clock.instant(),
        )
    }

    suspend fun get(id: String): Attachment? = repository.get(id)

    suspend fun forMessage(messageId: String): List<Attachment> =
        repository.forMessage(messageId)

    suspend fun all(): List<Attachment> = repository.all()

    /** Bind draft attachments to a persisted message. */
    suspend fun attachToMessage(ids: List<String>, messageId: String) {
        if (ids.isEmpty()) return
        repository.attachToMessage(ids, messageId)
    }

    /**
     * Remove one attachment and its bytes when no other metadata row shares the
     * same content hash.
     */
    suspend fun remove(id: String) {
        val attachment = repository.get(id) ?: return
        repository.deleteById(id)

        if (repository.findBySha256(attachment.sha256).isEmpty()) {
            storage.delete(attachment.storageKey)
        }
    }

    /** Remove every attachment bound to the given messages. */
    suspend fun deleteForMessages(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val allowed = messageIds.toSet()
        repository.all()
            .filter { it.messageId != null && it.messageId in allowed }
            .forEach { remove(it.id) }
    }

    /**
     * Resolve bytes for every image/file part in a request history.
     *
     * Missing metadata or missing bytes throw instead of sending a malformed
     * provider request.
     */
    suspend fun resolveForMessages(messages: List<Message>): Map<String, ResolvedAttachmentData> {
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

        val resolved = mutableMapOf<String, ResolvedAttachmentData>()

        ids.forEach { id ->
            val attachment = repository.get(id)
                ?: throw IllegalStateException("Attachment $id is missing from local storage.")
            val bytes = storage.read(attachment.storageKey)
                ?: throw IllegalStateException("Attachment $id bytes are missing from local storage.")

            resolved[id] = ResolvedAttachmentData(
                mimeType = attachment.mimeType,
                name = attachment.name,
                dataBase64 = Base64.getEncoder().encodeToString(bytes),
            )
        }

        return resolved
    }

    /** Data URL for local thumbnail rendering. Never fetches a provider URL. */
    suspend fun getDataUrl(id: String): String? {
        val attachment = repository.get(id) ?: return null
        if (attachment.kind != AttachmentKind.IMAGE) return null
        val bytes = storage.read(attachment.storageKey) ?: return null
        return "data:${attachment.mimeType};base64," +
            Base64.getEncoder().encodeToString(bytes)
    }

    /** Local file for thumbnail decoding, or null when the row is missing. */
    suspend fun fileFor(id: String): File? {
        val attachment = repository.get(id) ?: return null
        return storage.fileFor(attachment.storageKey)
    }

    /**
     * Remove metadata that no longer belongs to a message and bytes with no
     * metadata row. Draft rows (`messageId: null`) are treated as orphans on
     * startup because drafts are not restored across process death.
     *
     * Only rows created before [before] are considered, so attachments added
     * after startup cleanup began are never removed.
     */
    suspend fun cleanupOrphans(before: Instant = Instant.MAX): List<String> {
        val messageIds = messages.allIds().toSet()
        val attachments = repository.all()
        val stale = attachments.filter { attachment ->
            attachment.createdAt < before &&
                (attachment.messageId == null || attachment.messageId !in messageIds)
        }

        stale.forEach { repository.deleteById(it.id) }

        val remaining = if (stale.isEmpty()) attachments else repository.all()
        val referenced = remaining.map { it.sha256 }.toSet()
        return storage.cleanupOrphans(referenced)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}
