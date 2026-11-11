package dev.chatui.core.backup.sync

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.backup.BackupEnvelope
import dev.chatui.core.backup.BackupEnvelopeException
import dev.chatui.core.backup.BackupManifest
import dev.chatui.core.backup.BackupManifestBody
import dev.chatui.core.backup.BackupObjectType
import dev.chatui.core.backup.ConversationPayload
import dev.chatui.core.backup.ConversationPayloadCodec
import dev.chatui.core.backup.ManifestAttachmentEntry
import dev.chatui.core.backup.ManifestConversationEntry
import dev.chatui.core.backup.ManifestTombstone
import dev.chatui.core.backup.storage.ListObjectsOptions
import dev.chatui.core.backup.storage.ListObjectsResult
import dev.chatui.core.backup.storage.PutObjectOptions
import dev.chatui.core.backup.storage.StorageAdapter
import dev.chatui.core.backup.storage.StorageError
import dev.chatui.core.backup.storage.StorageErrorCode
import dev.chatui.core.backup.storage.StorageObject
import dev.chatui.core.database.sync.SyncLocalStore
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.BackupConfig
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Tombstone
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class SyncEngineTest {
    private val base = Instant.parse("2026-11-02T00:00:00Z")
    private val passphrase = "correct horse battery staple"
    private val iterations = 1_000
    private val storageKey = "00112233445566778899aabbccddeeff"
    private val sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `ensureIdentity creates and persists a device id and salt`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val engine = engine(store)

        val first = engine.ensureIdentity()
        val second = engine.ensureIdentity()

        assertThat(first.deviceId).isNotEmpty()
        assertThat(first.salt).hasLength(16)
        assertThat(second.deviceId).isEqualTo(first.deviceId)
        assertThat(second.salt).isEqualTo(first.salt)
        assertThat(store.config?.deviceId).isEqualTo(first.deviceId)
        assertThat(store.config?.kdfSalt).isNotEmpty()
    }

    @Test
    fun `sync creates a manifest when the remote is empty`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val conversation = conversation()
        store.conversations[conversation.id] = conversation
        store.messages[conversation.id] = mutableListOf(message(conversation.id))
        val storage = FakeStorageAdapter()
        val engine = engine(store, storage)

        val result = engine.sync()

        assertThat(result.uploadedConversations).containsExactly(conversation.id)
        assertThat(storage.objects).containsKey(MANIFEST_OBJECT_KEY)
        val manifest = readManifest(storage)
        assertThat(manifest.conversations.map { it.id }).containsExactly(conversation.id)
    }

    @Test
    fun `sync uploads local conversations and attachments encrypted`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val conversation = conversation()
        val attachment = attachment()
        store.conversations[conversation.id] = conversation
        store.messages[conversation.id] = mutableListOf(
            message(conversation.id, parts = listOf(ContentPart.Image(attachment.id, "image/png", "x"))),
        )
        store.attachments[attachment.id] = attachment
        store.attachmentBytes[attachment.storageKey] = byteArrayOf(1, 2, 3, 4)
        val storage = FakeStorageAdapter()
        val engine = engine(store, storage)

        val result = engine.sync()

        assertThat(result.uploadedAttachments).containsExactly(sha256)
        val conversationObject = storage.objects.getValue("conversations/$storageKey.enc")
        val decryptedConversation = BackupEnvelope.decryptObject(passphrase, conversationObject)
        val payload = ConversationPayloadCodec.parse(decryptedConversation.plaintext)
        assertThat(payload.messages).hasSize(1)

        val attachmentObject = storage.objects.getValue("attachments/$sha256.enc")
        val decryptedAttachment = BackupEnvelope.decryptObject(passphrase, attachmentObject)
        assertThat(decryptedAttachment.plaintext).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `sync downloads a remote-only conversation`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val storage = FakeStorageAdapter()
        val identity = engine(store, storage).ensureIdentity()
        val remote = conversation(id = "remote-conversation", title = "Remote")
        putRemoteConversation(storage, payload(remote, listOf(message(remote.id))), identity.salt)
        putRemoteManifest(
            storage,
            manifestBody(
                deviceId = "remote-device",
                conversations = listOf(manifestEntry(remote, "remote-device")),
            ),
            identity.salt,
        )
        val engine = engine(store, storage)

        val result = engine.sync()

        assertThat(result.downloadedConversations).containsExactly(remote.id)
        assertThat(store.conversations).containsKey(remote.id)
        assertThat(store.messages[remote.id]!!).hasSize(1)
    }

    @Test
    fun `sync merges both sides changes`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val storage = FakeStorageAdapter()
        val identity = engine(store, storage).ensureIdentity()
        val local = conversation(revision = 2, title = "Local")
        store.conversations[local.id] = local
        store.messages[local.id] = mutableListOf(message(local.id, id = "local-message", sequence = 0))

        val remote = local.copy(revision = 3, title = "Remote")
        putRemoteConversation(
            storage,
            payload(remote, listOf(message(remote.id, id = "remote-message", sequence = 0))),
            identity.salt,
        )
        putRemoteManifest(
            storage,
            manifestBody(
                deviceId = "remote-device",
                conversations = listOf(manifestEntry(remote, "remote-device")),
            ),
            identity.salt,
        )
        val engine = engine(store, storage)

        val result = engine.sync()

        assertThat(result.downloadedConversations).containsExactly(local.id)
        assertThat(store.messages[local.id]!!.map { it.id })
            .containsExactly("local-message", "remote-message")
        assertThat(store.conversations.getValue(local.id).title).isEqualTo("Remote")
        assertThat(store.conversations.getValue(local.id).revision).isGreaterThan(3)
    }

    @Test
    fun `manifest conflict retries and re-reads`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val conversation = conversation()
        store.conversations[conversation.id] = conversation
        store.messages[conversation.id] = mutableListOf(message(conversation.id))
        val storage = FakeStorageAdapter()
        storage.conflictOnManifestPut = 1
        val engine = engine(store, storage)

        engine.sync()

        assertThat(storage.putCalls.count { it == MANIFEST_OBJECT_KEY }).isEqualTo(2)
        assertThat(storage.getCalls.count { it == MANIFEST_OBJECT_KEY }).isEqualTo(2)
        assertThat(storage.objects).containsKey(MANIFEST_OBJECT_KEY)
    }

    @Test
    fun `interrupted upload leaves local data unchanged`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val conversation = conversation(title = "Keep me")
        store.conversations[conversation.id] = conversation
        store.messages[conversation.id] = mutableListOf(message(conversation.id))
        val storage = FakeStorageAdapter()
        storage.failPut = { key ->
            if (key.startsWith("conversations/")) {
                StorageError(StorageErrorCode.NETWORK, "Simulated network failure")
            } else {
                null
            }
        }
        val engine = engine(store, storage)

        val failure = runCatching { engine.sync() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(StorageError::class.java)
        assertThat(store.conversations.getValue(conversation.id).title).isEqualTo("Keep me")
        assertThat(store.conversations.getValue(conversation.id).revision)
            .isEqualTo(conversation.revision)
        assertThat(storage.objects).doesNotContainKey(MANIFEST_OBJECT_KEY)
    }

    @Test
    fun `restore merges by default and never wipes local data`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val storage = FakeStorageAdapter()
        val identity = engine(store, storage).ensureIdentity()
        val local = conversation(id = "local-conversation", title = "Local")
        store.conversations[local.id] = local
        store.messages[local.id] = mutableListOf(message(local.id))

        val remote = conversation(id = "remote-conversation", title = "Remote")
        putRemoteConversation(storage, payload(remote, listOf(message(remote.id))), identity.salt)
        putRemoteManifest(
            storage,
            manifestBody(
                deviceId = "remote-device",
                conversations = listOf(manifestEntry(remote, "remote-device")),
            ),
            identity.salt,
        )
        val engine = engine(store, storage)

        val result = engine.restore()

        assertThat(result.replaced).isFalse()
        assertThat(result.mergedConversations).containsExactly(remote.id)
        assertThat(store.conversations).containsKey(local.id)
        assertThat(store.conversations).containsKey(remote.id)
    }

    @Test
    fun `restore replace clears local conversations only when requested`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val storage = FakeStorageAdapter()
        val identity = engine(store, storage).ensureIdentity()
        val local = conversation(id = "local-conversation", title = "Local")
        store.conversations[local.id] = local
        store.messages[local.id] = mutableListOf(message(local.id))

        val remote = conversation(id = "remote-conversation", title = "Remote")
        putRemoteConversation(storage, payload(remote, listOf(message(remote.id))), identity.salt)
        putRemoteManifest(
            storage,
            manifestBody(
                deviceId = "remote-device",
                conversations = listOf(manifestEntry(remote, "remote-device")),
            ),
            identity.salt,
        )
        val engine = engine(store, storage)

        val result = engine.restore(RestoreOptions(replace = true))

        assertThat(result.replaced).isTrue()
        assertThat(store.conversations).doesNotContainKey(local.id)
        assertThat(store.conversations).containsKey(remote.id)
    }

    @Test
    fun `wrong passphrase fails before any local mutation`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val storage = FakeStorageAdapter()
        val identity = engine(store, storage).ensureIdentity()
        val local = conversation(title = "Untouched")
        store.conversations[local.id] = local
        store.messages[local.id] = mutableListOf(message(local.id))

        putRemoteManifest(
            storage,
            manifestBody(deviceId = "remote-device", conversations = emptyList()),
            identity.salt,
            passphrase = "wrong passphrase",
        )
        val engine = engine(store, storage)

        val syncFailure = runCatching { engine.sync() }.exceptionOrNull()
        val restoreFailure = runCatching { engine.restore() }.exceptionOrNull()

        assertThat(syncFailure).isInstanceOf(BackupEnvelopeException::class.java)
        assertThat(restoreFailure).isInstanceOf(BackupEnvelopeException::class.java)
        assertThat(store.conversations.getValue(local.id).title).isEqualTo("Untouched")
        assertThat(store.conversations.getValue(local.id).revision)
            .isEqualTo(local.revision)
    }

    @Test
    fun `sync drains the sync queue after success`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val conversation = conversation()
        store.conversations[conversation.id] = conversation
        store.messages[conversation.id] = mutableListOf(message(conversation.id))
        store.syncQueueCount = 2
        val engine = engine(store, FakeStorageAdapter())

        val result = engine.sync()

        assertThat(result.drainedQueue).isEqualTo(2)
        assertThat(store.syncQueueCount).isEqualTo(0)
    }

    @Test
    fun `deleteRemote requires the typed token and never deletes local data`(): Unit = runBlocking {
        val store = FakeSyncLocalStore()
        val conversation = conversation()
        store.conversations[conversation.id] = conversation
        val storage = FakeStorageAdapter()
        storage.objects[MANIFEST_OBJECT_KEY] = byteArrayOf(1)
        storage.objects["conversations/$storageKey.enc"] = byteArrayOf(2)
        val engine = engine(store, storage)

        val failure = runCatching { engine.deleteRemote("wrong") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)

        engine.deleteRemote(DELETE_REMOTE_CONFIRMATION)

        assertThat(storage.objects).isEmpty()
        assertThat(store.conversations).containsKey(conversation.id)
    }

    private fun engine(
        store: FakeSyncLocalStore,
        storage: FakeStorageAdapter = FakeStorageAdapter(),
    ): SyncEngine = SyncEngine(
        store = store,
        storage = storage,
        passphrase = passphrase,
        now = { base },
        sleep = {},
        iterations = iterations,
    )

    private fun conversation(
        id: String = "conversation-1",
        title: String = "Conversation",
        revision: Long = 1,
        updatedAt: Instant = base,
    ): Conversation = Conversation(
        id = id,
        title = title,
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
        createdAt = base,
        updatedAt = updatedAt,
        lastMessageAt = updatedAt,
        messageCount = 0,
        lastError = null,
        sync = null,
        storageKey = storageKey,
        revision = revision,
    )

    private fun message(
        conversationId: String,
        id: String = "message-1",
        sequence: Long = 0,
        parts: List<ContentPart> = listOf(ContentPart.Text("hello")),
    ): Message = Message(
        id = id,
        conversationId = conversationId,
        sequence = sequence,
        role = MessageRole.USER,
        parts = parts,
        status = MessageStatus.COMPLETE,
        providerId = null,
        modelId = null,
        modelLabel = null,
        usage = null,
        finishReason = null,
        error = null,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = base,
        updatedAt = base,
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
        createdAt = base,
    )

    private fun payload(
        conversation: Conversation,
        messages: List<Message>,
        attachments: List<Attachment> = emptyList(),
    ): ConversationPayload = ConversationPayloadCodec.build(conversation, messages, attachments)

    private fun manifestEntry(
        conversation: Conversation,
        deviceId: String,
    ): ManifestConversationEntry = ManifestConversationEntry(
        id = conversation.id,
        storageKey = conversation.storageKey,
        revision = conversation.revision,
        updatedAt = conversation.updatedAt.toString(),
        deletedAt = null,
        deviceId = deviceId,
        objectKey = "conversations/${conversation.storageKey}.enc",
    )

    private fun manifestBody(
        deviceId: String,
        conversations: List<ManifestConversationEntry>,
        attachments: List<ManifestAttachmentEntry> = emptyList(),
        tombstones: List<ManifestTombstone> = emptyList(),
    ): BackupManifestBody = BackupManifestBody(
        formatVersion = BackupManifest.FORMAT_VERSION,
        createdAt = base.toString(),
        updatedAt = base.toString(),
        deviceId = deviceId,
        conversations = conversations,
        attachments = attachments,
        tombstones = tombstones,
    )

    private fun putRemoteManifest(
        storage: FakeStorageAdapter,
        manifest: BackupManifestBody,
        salt: ByteArray,
        passphrase: String = this.passphrase,
    ) {
        val bytes = BackupEnvelope.encryptObject(
            passphrase = passphrase,
            salt = salt,
            objectType = BackupObjectType.MANIFEST,
            objectKey = MANIFEST_OBJECT_KEY,
            plaintext = BackupManifest.encode(manifest).toByteArray(Charsets.UTF_8),
            compress = false,
            iterations = iterations,
        )
        storage.objects[MANIFEST_OBJECT_KEY] = bytes
        storage.etags[MANIFEST_OBJECT_KEY] = "\"remote-etag\""
    }

    private fun putRemoteConversation(
        storage: FakeStorageAdapter,
        payload: ConversationPayload,
        salt: ByteArray,
    ) {
        val objectKey = "conversations/${payload.storageKey}.enc"
        val bytes = BackupEnvelope.encryptObject(
            passphrase = passphrase,
            salt = salt,
            objectType = BackupObjectType.CONVERSATION,
            objectKey = objectKey,
            plaintext = ConversationPayloadCodec.encode(payload).toByteArray(Charsets.UTF_8),
            iterations = iterations,
        )
        storage.objects[objectKey] = bytes
        storage.etags[objectKey] = "\"remote-conversation-etag\""
    }

    private fun readManifest(storage: FakeStorageAdapter): BackupManifestBody {
        val bytes = storage.objects.getValue(MANIFEST_OBJECT_KEY)
        val decrypted = BackupEnvelope.decryptObject(passphrase, bytes)
        return BackupManifest.parse(decrypted.plaintext)
    }
}

private class FakeStorageAdapter : StorageAdapter {
    val objects = mutableMapOf<String, ByteArray>()
    val etags = mutableMapOf<String, String>()
    val putCalls = mutableListOf<String>()
    val getCalls = mutableListOf<String>()
    var failPut: ((String) -> StorageError?)? = null
    var conflictOnManifestPut: Int = 0
    private var counter = 0

    override suspend fun testConnection() = Unit

    override suspend fun getObject(key: String): StorageObject {
        getCalls += key
        val bytes = objects[key]
            ?: throw StorageError(StorageErrorCode.NOT_FOUND, "Object not found")
        return StorageObject(key = key, bytes = bytes, etag = etags[key])
    }

    override suspend fun putObject(
        key: String,
        encryptedBytes: ByteArray,
        options: PutObjectOptions,
    ): StorageObject {
        putCalls += key
        failPut?.invoke(key)?.let { throw it }

        if (key == MANIFEST_OBJECT_KEY && conflictOnManifestPut > 0) {
            conflictOnManifestPut -= 1
            throw StorageError(StorageErrorCode.CONFLICT, "Simulated conflict", 412)
        }
        if (options.ifMatch != null && etags[key] != options.ifMatch) {
            throw StorageError(StorageErrorCode.CONFLICT, "ETag mismatch", 412)
        }
        if (options.ifNoneMatch == "*" && objects.containsKey(key)) {
            throw StorageError(StorageErrorCode.CONFLICT, "Object already exists", 412)
        }

        objects[key] = encryptedBytes
        val etag = "\"etag-${++counter}\""
        etags[key] = etag
        return StorageObject(key = key, bytes = encryptedBytes, etag = etag)
    }

    override suspend fun headObject(key: String): StorageObject {
        val bytes = objects[key]
            ?: throw StorageError(StorageErrorCode.NOT_FOUND, "Object not found")
        return StorageObject(key = key, bytes = ByteArray(0), etag = etags[key])
    }

    override suspend fun deleteObject(key: String) {
        objects.remove(key)
        etags.remove(key)
    }

    override suspend fun listObjects(options: ListObjectsOptions): ListObjectsResult {
        val prefix = options.prefix ?: ""
        val keys = objects.keys.filter { it.startsWith(prefix) }.sorted()
        return ListObjectsResult(
            objects = keys.map { StorageObject(key = it, bytes = ByteArray(0), etag = etags[it]) },
        )
    }
}

private class FakeSyncLocalStore : SyncLocalStore {
    var config: BackupConfig? = null
    val conversations = linkedMapOf<String, Conversation>()
    val messages = linkedMapOf<String, MutableList<Message>>()
    val attachments = linkedMapOf<String, Attachment>()
    val tombstones = mutableListOf<Tombstone>()
    val attachmentBytes = mutableMapOf<String, ByteArray>()
    var syncQueueCount: Int = 0

    override suspend fun getBackupConfig(): BackupConfig? = config

    override suspend fun saveBackupConfig(config: BackupConfig) {
        this.config = config
    }

    override suspend fun listConversations(): List<Conversation> = conversations.values.toList()

    override suspend fun getConversation(id: String): Conversation? = conversations[id]

    override suspend fun listMessages(conversationId: String): List<Message> =
        messages[conversationId]?.toList() ?: emptyList()

    override suspend fun listAttachments(): List<Attachment> = attachments.values.toList()

    override suspend fun listTombstones(): List<Tombstone> = tombstones.toList()

    override suspend fun readAttachment(storageKey: String): ByteArray? =
        attachmentBytes[storageKey]

    override suspend fun writeAttachment(storageKey: String, bytes: ByteArray) {
        attachmentBytes[storageKey] = bytes
    }

    override suspend fun writeConversation(
        conversation: Conversation,
        messages: List<Message>,
        attachments: List<Attachment>,
    ) {
        conversations[conversation.id] = conversation
        this.messages[conversation.id] = messages.toMutableList()
        attachments.forEach { this.attachments[it.id] = it }
    }

    override suspend fun clearConversations() {
        conversations.clear()
        messages.clear()
        attachments.clear()
    }

    override suspend fun drainSyncQueue(): Int {
        val drained = syncQueueCount
        syncQueueCount = 0
        return drained
    }
}
