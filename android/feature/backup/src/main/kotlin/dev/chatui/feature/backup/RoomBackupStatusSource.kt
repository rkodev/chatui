package dev.chatui.feature.backup

import dev.chatui.core.database.ChatUiDatabase

/**
 * Room-backed [BackupStatusSource].
 *
 * Pending changes come from the sync queue. Storage usage is an estimate built
 * from stored record sizes and attachment metadata; attachment file contents
 * are not read.
 */
class RoomBackupStatusSource(
    private val database: ChatUiDatabase,
) : BackupStatusSource {
    override suspend fun pendingChanges(): Int =
        database.syncQueueDao().all().size

    override suspend fun storageUsageBytes(): Long {
        val conversations = database.conversationDao().all()
        val messages = database.messageDao().all()
        val attachments = database.attachmentDao().all()

        val conversationBytes = conversations.sumOf { conversation ->
            conversation.title.length +
                conversation.systemPrompt.length +
                conversation.paramsJson.length +
                conversation.modelLabel.length +
                conversation.storageKey.length
        }
        val messageBytes = messages.sumOf { message ->
            message.partsJson.length +
                (message.usageJson?.length ?: 0) +
                (message.errorJson?.length ?: 0)
        }
        val attachmentBytes = attachments
            .distinctBy { it.sha256 }
            .sumOf { it.size }

        return conversationBytes.toLong() + messageBytes.toLong() + attachmentBytes
    }
}
