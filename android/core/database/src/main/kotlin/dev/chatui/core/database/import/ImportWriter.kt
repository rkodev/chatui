package dev.chatui.core.database.import

import androidx.room.withTransaction
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.repository.searchableText
import dev.chatui.core.database.repository.toDomain
import dev.chatui.core.database.repository.toEntity
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.Provider
import dev.chatui.core.model.newStorageKey

/** How an import applies an archive to local data. */
enum class ImportMode {
    /** Keep local records and upsert archive records by id. */
    MERGE,

    /** Clear imported data stores first, then write the archive. */
    REPLACE,
}

/**
 * Fully validated data ready for a transactional write.
 *
 * The feature-level import service owns parsing, validation, model-id
 * remapping, and attachment metadata construction. This request is the
 * repository boundary and contains only domain objects.
 */
data class ImportWriteRequest(
    val providers: List<Provider>,
    val models: List<Model>,
    val conversations: List<Conversation>,
    val messages: List<Message>,
    val attachments: List<Attachment>,
    /** Decoded attachment bytes keyed by lower-case SHA-256. */
    val attachmentBytes: Map<String, ByteArray> = emptyMap(),
)

/** Counts returned after a successful import. */
data class ImportWriteResult(
    val mode: ImportMode,
    val conversations: Int,
    val messages: Int,
    val attachments: Int,
    val providers: Int,
    val models: Int,
    val attachmentBytes: Int,
)

/**
 * Applies a validated archive inside one Room transaction.
 *
 * Attachment bytes are content-addressed and written before the transaction.
 * If any write fails, newly created byte files are removed so a failed import
 * cannot leave orphaned blobs behind. Replace mode clears only the data stores
 * represented by the archive; secrets, backup configuration, and the sync queue
 * are deliberately untouched.
 */
class ImportWriter(
    private val database: ChatUiDatabase,
    private val attachmentStorage: AttachmentStorage,
) {
    suspend fun apply(
        request: ImportWriteRequest,
        mode: ImportMode,
    ): ImportWriteResult {
        val writtenStorageKeys = mutableListOf<String>()

        try {
            request.attachmentBytes.forEach { (sha256, bytes) ->
                val storageKey = AttachmentStorage.storageKeyFor(sha256)
                val existed = attachmentStorage.fileFor(storageKey).isFile
                attachmentStorage.put(bytes, sha256)
                if (!existed) {
                    writtenStorageKeys += storageKey
                }
            }

            database.withTransaction {
                if (mode == ImportMode.REPLACE) {
                    database.importDao().clearFts()
                    database.importDao().clearMessages()
                    database.importDao().clearAttachments()
                    database.importDao().clearConversations()
                    database.importDao().clearModels()
                    database.importDao().clearProviders()
                }

                request.providers.forEach { provider ->
                    database.providerDao().upsert(mergeProvider(provider, mode).toEntity())
                }

                request.models.forEach { model ->
                    database.modelDao().upsert(mergeModel(model, mode).toEntity())
                }

                request.conversations.forEach { conversation ->
                    // The plaintext export contract does not carry sync-only
                    // fields, so backfill them on import.
                    val prepared = conversation.copy(
                        storageKey = conversation.storageKey.ifBlank { newStorageKey() },
                        revision = conversation.revision.coerceAtLeast(1),
                    )
                    database.conversationDao().upsert(prepared.toEntity())
                }

                request.messages.forEach { message ->
                    database.importDao().importMessage(
                        message = message.toEntity(),
                        searchText = searchableText(message.parts),
                    )
                }

                request.attachments.forEach { attachment ->
                    database.attachmentDao().upsert(attachment.toEntity())
                }
            }
        } catch (error: Throwable) {
            writtenStorageKeys.forEach { storageKey ->
                attachmentStorage.delete(storageKey)
            }
            throw error
        }

        if (mode == ImportMode.REPLACE) {
            runCatching {
                attachmentStorage.cleanupOrphans(
                    request.attachments.map { it.sha256.lowercase() }.toSet(),
                )
            }
        }

        return ImportWriteResult(
            mode = mode,
            conversations = request.conversations.size,
            messages = request.messages.size,
            attachments = request.attachments.size,
            providers = request.providers.size,
            models = request.models.size,
            attachmentBytes = request.attachmentBytes.size,
        )
    }

    /**
     * Merge keeps user-owned provider state.
     *
     * A local key reference and enabled flag must survive an import because the
     * archive never contains secret material. Replace writes the archive row
     * exactly, including its opaque `secretId` reference.
     */
    private suspend fun mergeProvider(provider: Provider, mode: ImportMode): Provider {
        if (mode == ImportMode.REPLACE) return provider

        val existing = database.providerDao().getById(provider.id)?.toDomain()
            ?: return provider

        return provider.copy(
            enabled = existing.enabled,
            secretId = existing.secretId ?: provider.secretId,
            createdAt = existing.createdAt,
        )
    }

    /**
     * Merge keeps user-owned model flags and a manual source.
     *
     * Model ids are already remapped by the import service when a local model
     * with the same `(providerId, remoteId)` identity exists.
     */
    private suspend fun mergeModel(model: Model, mode: ImportMode): Model {
        if (mode == ImportMode.REPLACE) return model

        val existing = database.modelDao().getById(model.id)?.toDomain()
            ?: return model

        return model.copy(
            hidden = existing.hidden,
            pinned = existing.pinned,
            source = if (existing.source == ModelSource.MANUAL) {
                ModelSource.MANUAL
            } else {
                model.source
            },
            createdAt = existing.createdAt,
        )
    }
}
