package dev.chatui.core.database.sync

import androidx.room.withTransaction
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.repository.BackupConfigRepository
import dev.chatui.core.database.repository.searchableText
import dev.chatui.core.database.repository.toDomain
import dev.chatui.core.database.repository.toEntity
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.BackupConfig
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.Tombstone

/**
 * Room-backed [SyncLocalStore].
 *
 * Conversation writes are transactional so a restore cannot leave a
 * conversation row without its messages or attachment metadata. Attachment
 * bytes are content-addressed and written through [AttachmentStorage].
 */
class RoomSyncLocalStore(
    private val database: ChatUiDatabase,
    private val attachmentStorage: AttachmentStorage,
) : SyncLocalStore {
    private val backupConfig = BackupConfigRepository(database.backupConfigDao())

    override suspend fun getBackupConfig(): BackupConfig? = backupConfig.get()

    override suspend fun saveBackupConfig(config: BackupConfig) = backupConfig.put(config)

    override suspend fun listConversations(): List<Conversation> =
        database.conversationDao().all().map { it.toDomain() }

    override suspend fun getConversation(id: String): Conversation? =
        database.conversationDao().getById(id)?.toDomain()

    override suspend fun listMessages(conversationId: String): List<Message> =
        database.messageDao().allForConversation(conversationId).map { it.toDomain() }

    override suspend fun listAttachments(): List<Attachment> =
        database.attachmentDao().all().map { it.toDomain() }

    override suspend fun listTombstones(): List<Tombstone> =
        database.tombstoneDao().all().map { it.toDomain() }

    override suspend fun readAttachment(storageKey: String): ByteArray? =
        attachmentStorage.read(storageKey)

    override suspend fun writeAttachment(storageKey: String, bytes: ByteArray) {
        val sha256 = storageKey.substringAfterLast('/')
        attachmentStorage.put(bytes, sha256)
    }

    override suspend fun writeConversation(
        conversation: Conversation,
        messages: List<Message>,
        attachments: List<Attachment>,
    ) {
        database.withTransaction {
            database.conversationDao().upsert(conversation.toEntity())

            messages.forEach { message ->
                database.importDao().importMessage(
                    message = message.toEntity(),
                    searchText = searchableText(message.parts),
                )
            }

            attachments.forEach { attachment ->
                if (database.attachmentDao().getById(attachment.id) == null) {
                    database.attachmentDao().upsert(attachment.toEntity())
                }
            }
        }
    }

    override suspend fun clearConversations() {
        database.withTransaction {
            database.importDao().clearFts()
            database.importDao().clearMessages()
            database.importDao().clearAttachments()
            database.importDao().clearConversations()
        }
    }

    override suspend fun drainSyncQueue(): Int {
        val items = database.syncQueueDao().all()
        database.syncQueueDao().clear()
        return items.size
    }
}
