package dev.chatui.core.database.sync

import dev.chatui.core.model.Attachment
import dev.chatui.core.model.BackupConfig
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.Tombstone

/**
 * Local persistence boundary used by the backup sync engine.
 *
 * The engine lives in `core:backup` and must not depend on Room directly. This
 * interface exposes only the reads and writes the sync protocol needs, so the
 * engine can be tested with an in-memory store and the production
 * implementation stays in `core:database`.
 */
interface SyncLocalStore {
    suspend fun getBackupConfig(): BackupConfig?

    suspend fun saveBackupConfig(config: BackupConfig)

    suspend fun listConversations(): List<Conversation>

    suspend fun getConversation(id: String): Conversation?

    suspend fun listMessages(conversationId: String): List<Message>

    suspend fun listAttachments(): List<Attachment>

    suspend fun listTombstones(): List<Tombstone>

    /** Read attachment bytes by the model storage key (`attachments/<sha256>`). */
    suspend fun readAttachment(storageKey: String): ByteArray?

    /** Write attachment bytes under the model storage key (`attachments/<sha256>`). */
    suspend fun writeAttachment(storageKey: String, bytes: ByteArray)

    /**
     * Insert or replace one conversation with its messages and attachment
     * metadata. Implementations must be atomic.
     */
    suspend fun writeConversation(
        conversation: Conversation,
        messages: List<Message>,
        attachments: List<Attachment>,
    )

    /** Remove every local conversation, message, and attachment row. */
    suspend fun clearConversations()

    /** Delete every queued sync item and return how many were removed. */
    suspend fun drainSyncQueue(): Int
}
