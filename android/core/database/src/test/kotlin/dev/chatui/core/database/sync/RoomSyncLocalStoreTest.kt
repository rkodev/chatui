package dev.chatui.core.database.sync

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.entity.SyncQueueEntity
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.BackupBackend
import dev.chatui.core.model.BackupConfig
import dev.chatui.core.model.BackupEncryption
import dev.chatui.core.model.BackupKdf
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class RoomSyncLocalStoreTest : DatabaseTestBase() {
    private val now = Instant.parse("2026-11-02T00:00:00Z")
    private val storageKey = "00112233445566778899aabbccddeeff"
    private val sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `persists backup config`(): Unit = runBlocking {
        val store = store()
        val config = backupConfig()

        store.saveBackupConfig(config)

        assertThat(store.getBackupConfig()).isEqualTo(config)
    }

    @Test
    fun `writes a conversation with messages and attachments atomically`(): Unit = runBlocking {
        val store = store()
        val conversation = conversation()
        val message = message()
        val attachment = attachment()

        store.writeConversation(conversation, listOf(message), listOf(attachment))

        assertThat(store.listConversations().map { it.id }).containsExactly(conversation.id)
        assertThat(store.listMessages(conversation.id).map { it.id })
            .containsExactly(message.id)
        assertThat(store.listAttachments().map { it.id }).containsExactly(attachment.id)
    }

    @Test
    fun `drains the sync queue`(): Unit = runBlocking {
        val store = store()
        db.syncQueueDao().upsert(syncQueueItem("queue-1"))
        db.syncQueueDao().upsert(syncQueueItem("queue-2"))

        val drained = store.drainSyncQueue()

        assertThat(drained).isEqualTo(2)
        assertThat(db.syncQueueDao().all()).isEmpty()
    }

    @Test
    fun `clearConversations removes conversations messages and attachments`(): Unit = runBlocking {
        val store = store()
        store.writeConversation(conversation(), listOf(message()), listOf(attachment()))

        store.clearConversations()

        assertThat(store.listConversations()).isEmpty()
        assertThat(store.listMessages("conversation-1")).isEmpty()
        assertThat(store.listAttachments()).isEmpty()
    }

    private fun store(): RoomSyncLocalStore {
        val root = Files.createTempDirectory("chatui-sync-test").toFile()
        return RoomSyncLocalStore(db, AttachmentStorage(root))
    }

    private fun backupConfig(): BackupConfig = BackupConfig(
        enabled = false,
        backend = BackupBackend.S3,
        endpoint = null,
        region = null,
        bucket = "bucket",
        prefix = "",
        credentialSecretId = null,
        encryption = BackupEncryption.AES_256_GCM,
        kdf = BackupKdf.PBKDF2_SHA256,
        kdfSalt = "c2FsdA==",
        lastSyncAt = null,
        lastManifestEtag = null,
        deviceId = "device-1",
    )

    private fun conversation(): Conversation = Conversation(
        id = "conversation-1",
        title = "Conversation",
        systemPrompt = "",
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model",
        params = ChatParams(
            temperature = null,
            topP = null,
            maxOutputTokens = null,
            stop = emptyList(),
            reasoningEffort = null,
            streaming = true,
            extra = buildJsonObject {},
        ),
        pinned = false,
        archived = false,
        temporary = false,
        createdAt = now,
        updatedAt = now,
        lastMessageAt = now,
        messageCount = 1,
        lastError = null,
        sync = null,
        storageKey = storageKey,
        revision = 1,
    )

    private fun message(): Message = Message(
        id = "message-1",
        conversationId = "conversation-1",
        sequence = 0,
        role = MessageRole.USER,
        parts = listOf(ContentPart.Text("hello")),
        status = MessageStatus.COMPLETE,
        providerId = null,
        modelId = null,
        modelLabel = null,
        usage = null,
        finishReason = null,
        error = null,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = now,
        updatedAt = now,
        deletedAt = null,
    )

    private fun attachment(): Attachment = Attachment(
        id = "attachment-1",
        messageId = "message-1",
        kind = AttachmentKind.IMAGE,
        mimeType = "image/png",
        name = "image.png",
        size = 4,
        sha256 = sha256,
        storageKey = "attachments/$sha256",
        width = null,
        height = null,
        createdAt = now,
    )

    private fun syncQueueItem(id: String): SyncQueueEntity = SyncQueueEntity(
        id = id,
        conversationId = "conversation-1",
        operation = "upsert",
        payloadJson = "{}",
        attempts = 0,
        lastAttemptAt = null,
        nextAttemptAt = null,
        lastError = null,
        createdAt = now.toString(),
        updatedAt = now.toString(),
    )
}
