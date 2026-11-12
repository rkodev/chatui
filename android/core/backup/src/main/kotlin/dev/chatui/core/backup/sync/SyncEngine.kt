package dev.chatui.core.backup.sync

import dev.chatui.core.backup.BackupCrypto
import dev.chatui.core.backup.BackupEnvelope
import dev.chatui.core.backup.BackupEnvelopeErrorCode
import dev.chatui.core.backup.BackupEnvelopeException
import dev.chatui.core.backup.BackupManifest
import dev.chatui.core.backup.BackupManifestBody
import dev.chatui.core.backup.BackupObjectType
import dev.chatui.core.backup.ConversationPayload
import dev.chatui.core.backup.ConversationPayloadCodec
import dev.chatui.core.backup.ManifestConversationEntry
import dev.chatui.core.backup.storage.ListObjectsOptions
import dev.chatui.core.backup.storage.PutObjectOptions
import dev.chatui.core.backup.storage.StorageAdapter
import dev.chatui.core.backup.storage.StorageError
import dev.chatui.core.backup.storage.StorageErrorCode
import dev.chatui.core.database.sync.SyncLocalStore
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.BackupBackend
import dev.chatui.core.model.BackupConfig
import dev.chatui.core.model.BackupEncryption
import dev.chatui.core.model.BackupKdf
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.Tombstone
import dev.chatui.core.model.newId
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.delay

/** Object key of the encrypted manifest. */
const val MANIFEST_OBJECT_KEY: String = "manifest.enc"

/** Exact token a caller must pass to [SyncEngine.deleteRemote]. */
const val DELETE_REMOTE_CONFIRMATION: String = "DELETE REMOTE BACKUP"

private const val DEFAULT_MAX_CONFLICT_RETRIES = 4
private const val DEFAULT_BASE_BACKOFF_MS = 50L

/** Device identity created on first setup. */
data class BackupIdentity(
    val deviceId: String,
    val salt: ByteArray,
)

/** Result of a successful [SyncEngine.sync]. */
data class SyncResult(
    val uploadedConversations: List<String>,
    val uploadedAttachments: List<String>,
    val downloadedConversations: List<String>,
    val manifestEtag: String?,
    val drainedQueue: Int,
)

/** Options for [SyncEngine.restore]. */
data class RestoreOptions(
    /** Replace local conversations instead of merging. Requires explicit opt-in. */
    val replace: Boolean = false,
)

/** Result of [SyncEngine.restore]. */
data class RestoreResult(
    val mergedConversations: List<String>,
    val replaced: Boolean,
)

private data class RemoteManifest(
    val manifest: BackupManifestBody,
    val etag: String?,
)

/**
 * Android sync engine.
 *
 * The engine reads the encrypted remote manifest, merges it with the local
 * index by conversation revision, uploads dirty encrypted objects, and rewrites
 * the manifest with an `If-Match` precondition. Restore is non-destructive by
 * default and a wrong passphrase fails before any conversation data changes.
 *
 * The engine never uploads plaintext: every object goes through the envelope
 * codec and the storage adapter only accepts `ByteArray` bodies.
 */
class SyncEngine(
    private val store: SyncLocalStore,
    private val storage: StorageAdapter,
    private val passphrase: String,
    private val now: () -> Instant = Instant::now,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxConflictRetries: Int = DEFAULT_MAX_CONFLICT_RETRIES,
    private val baseBackoffMs: Long = DEFAULT_BASE_BACKOFF_MS,
    /** Test-only PBKDF2 iteration override. Production callers must omit this. */
    private val iterations: Int = BackupCrypto.PBKDF2_ITERATIONS,
) {
    /**
     * Create and persist the device ID and backup salt on first setup.
     *
     * Both values live in `backup_config`; the salt is stored base64-encoded so
     * it survives the Room text column unchanged.
     */
    suspend fun ensureIdentity(): BackupIdentity {
        val existing = store.getBackupConfig()
        val deviceId = existing?.deviceId?.takeIf { it.isNotBlank() } ?: newId()
        val kdfSalt = existing?.kdfSalt?.takeIf { it.isNotBlank() }
            ?: Base64.getEncoder().encodeToString(
                BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES),
            )

        if (existing == null || existing.deviceId != deviceId || existing.kdfSalt != kdfSalt) {
            val base = existing ?: defaultBackupConfig(deviceId, kdfSalt)
            store.saveBackupConfig(base.copy(deviceId = deviceId, kdfSalt = kdfSalt))
        }

        return BackupIdentity(
            deviceId = deviceId,
            salt = Base64.getDecoder().decode(kdfSalt),
        )
    }

    /** Back up local changes and merge remote changes. */
    suspend fun sync(): SyncResult {
        val identity = ensureIdentity()
        val remote = readRemoteManifest(identity.deviceId)
        val localTombstones = store.listTombstones()
        val messageTombstones = localTombstones.filter { it.messageId != null }

        val downloadedConversations = mutableListOf<String>()
        for (entry in remote.manifest.conversations) {
            val localConversation = store.getConversation(entry.id)
            if (localConversation != null && localConversation.revision > entry.revision) {
                continue
            }

            val payload = downloadConversation(entry)
            val changed = applyRemoteConversation(
                payload = payload,
                entry = entry,
                localConversation = localConversation,
                conversationTombstones = remote.manifest.tombstones,
                messageTombstones = messageTombstones,
                localDeviceId = identity.deviceId,
            )
            if (changed) {
                downloadedConversations += entry.id
            }
        }

        val localManifest = buildLocalManifest(identity.deviceId, localTombstones)
        val merged = mergeManifests(localManifest, remote.manifest)
        val localById = store.listConversations().associateBy { it.id }
        val remoteById = remote.manifest.conversations.associateBy { it.id }

        val uploadedConversations = mutableListOf<String>()
        for (entry in merged.conversations) {
            val localConversation = localById[entry.id] ?: continue
            val remoteEntry = remoteById[entry.id]
            if (remoteEntry != null && remoteEntry.revision >= localConversation.revision) {
                continue
            }

            val payload = buildLocalPayload(localConversation)
            val bytes = BackupEnvelope.encryptObject(
                passphrase = passphrase,
                salt = identity.salt,
                objectType = BackupObjectType.CONVERSATION,
                objectKey = entry.objectKey,
                plaintext = ConversationPayloadCodec.encode(payload).toByteArray(Charsets.UTF_8),
                iterations = iterations,
            )
            storage.putObject(entry.objectKey, bytes)
            uploadedConversations += entry.id
        }

        val uploadedAttachments = mutableListOf<String>()
        val remoteAttachmentShas = remote.manifest.attachments.map { it.sha256 }.toSet()
        for (entry in merged.attachments) {
            if (entry.sha256 in remoteAttachmentShas) {
                continue
            }

            val bytes = store.readAttachment("attachments/${entry.sha256}") ?: continue
            val encrypted = BackupEnvelope.encryptObject(
                passphrase = passphrase,
                salt = identity.salt,
                objectType = BackupObjectType.ATTACHMENT,
                objectKey = entry.objectKey,
                plaintext = bytes,
                compress = false,
                iterations = iterations,
            )
            storage.putObject(entry.objectKey, encrypted)
            uploadedAttachments += entry.sha256
        }

        val written = writeManifest(localManifest, remote, identity)
        val drainedQueue = store.drainSyncQueue()
        updateConfig(identity, written.etag)

        return SyncResult(
            uploadedConversations = uploadedConversations,
            uploadedAttachments = uploadedAttachments,
            downloadedConversations = downloadedConversations,
            manifestEtag = written.etag,
            drainedQueue = drainedQueue,
        )
    }

    /** Explicit "back up now" entry point. */
    suspend fun backupNow(): SyncResult = sync()

    /**
     * Restore remote data into local storage.
     *
     * The default merges and never deletes local conversations. `replace = true`
     * is the only path that clears local conversations, and callers must obtain
     * explicit user confirmation before passing it.
     */
    suspend fun restore(options: RestoreOptions = RestoreOptions()): RestoreResult {
        val identity = ensureIdentity()
        val remote = readRemoteManifest(identity.deviceId)
        val localTombstones = store.listTombstones()
        val messageTombstones = localTombstones.filter { it.messageId != null }

        if (options.replace) {
            store.clearConversations()
        }

        val mergedConversations = mutableListOf<String>()
        for (entry in remote.manifest.conversations) {
            val localConversation = store.getConversation(entry.id)
            val payload = downloadConversation(entry)
            val changed = applyRemoteConversation(
                payload = payload,
                entry = entry,
                localConversation = localConversation,
                conversationTombstones = remote.manifest.tombstones,
                messageTombstones = messageTombstones,
                localDeviceId = identity.deviceId,
            )
            if (changed) {
                mergedConversations += entry.id
            }
        }

        return RestoreResult(
            mergedConversations = mergedConversations,
            replaced = options.replace,
        )
    }

    /**
     * Delete every remote backup object. The caller must pass the exact typed
     * confirmation token. Local data is never touched.
     */
    suspend fun deleteRemote(confirmation: String) {
        if (confirmation != DELETE_REMOTE_CONFIRMATION) {
            throw IllegalArgumentException(
                "Remote backup deletion requires the typed confirmation token",
            )
        }

        try {
            storage.deleteObject(MANIFEST_OBJECT_KEY)
        } catch (error: StorageError) {
            if (error.code != StorageErrorCode.NOT_FOUND) {
                throw error
            }
        }

        var continuationToken: String? = null
        do {
            val page = storage.listObjects(
                ListObjectsOptions(
                    prefix = "",
                    continuationToken = continuationToken,
                ),
            )
            for (storageObject in page.objects) {
                if (storageObject.key == MANIFEST_OBJECT_KEY) {
                    continue
                }
                storage.deleteObject(storageObject.key)
            }
            continuationToken = page.nextContinuationToken
        } while (continuationToken != null)
    }

    private suspend fun readRemoteManifest(deviceId: String): RemoteManifest {
        val storageObject = try {
            storage.getObject(MANIFEST_OBJECT_KEY)
        } catch (error: StorageError) {
            if (error.code == StorageErrorCode.NOT_FOUND) {
                return RemoteManifest(emptyManifest(deviceId), null)
            }
            throw error
        }

        val decrypted = BackupEnvelope.decryptObject(passphrase, storageObject.bytes)
        if (
            decrypted.header.objectType != BackupObjectType.MANIFEST ||
            decrypted.header.objectKey != MANIFEST_OBJECT_KEY
        ) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.INVALID_HEADER,
                "Remote manifest has an unexpected envelope header",
            )
        }

        return RemoteManifest(
            manifest = BackupManifest.parse(decrypted.plaintext),
            etag = storageObject.etag,
        )
    }

    private suspend fun downloadConversation(
        entry: ManifestConversationEntry,
    ): ConversationPayload {
        val storageObject = storage.getObject(entry.objectKey)
        val decrypted = BackupEnvelope.decryptObject(passphrase, storageObject.bytes)
        if (
            decrypted.header.objectType != BackupObjectType.CONVERSATION ||
            decrypted.header.objectKey != entry.objectKey
        ) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.INVALID_HEADER,
                "Remote conversation ${entry.id} has an unexpected envelope header",
            )
        }
        return ConversationPayloadCodec.parse(decrypted.plaintext)
    }

    private suspend fun buildLocalPayload(conversation: Conversation): ConversationPayload {
        val messages = store.listMessages(conversation.id)
        val referenced = referencedAttachmentIds(messages)
        val attachments = store.listAttachments().filter { it.id in referenced }
        return ConversationPayloadCodec.build(conversation, messages, attachments)
    }

    private suspend fun applyRemoteConversation(
        payload: ConversationPayload,
        entry: ManifestConversationEntry,
        localConversation: Conversation?,
        conversationTombstones: List<dev.chatui.core.backup.ManifestTombstone>,
        messageTombstones: List<Tombstone>,
        localDeviceId: String,
    ): Boolean {
        if (localConversation == null) {
            val tombstone = conversationTombstones.firstOrNull {
                it.conversationId == payload.id
            }
            if (tombstone != null && tombstone.deletedAt >= payload.updatedAt) {
                return false
            }

            store.writeConversation(
                conversation = payloadToConversation(payload, payload.revision, null),
                messages = payloadToMessages(payload),
                attachments = payloadToAttachments(payload),
            )
            return true
        }

        val localPayload = buildLocalPayload(localConversation)
        val merged = mergeConversation(
            local = localPayload,
            remote = payload,
            options = MergeConversationOptions(
                localDeviceId = localDeviceId,
                remoteDeviceId = entry.deviceId,
                conversationTombstones = conversationTombstones,
                messageTombstones = messageTombstones,
            ),
        )

        if (merged.deleted) {
            return false
        }

        if (merged.payload == localPayload) {
            return false
        }

        val revision = maxOf(localConversation.revision, entry.revision) + 1
        store.writeConversation(
            conversation = payloadToConversation(merged.payload, revision, localConversation),
            messages = payloadToMessages(merged.payload),
            attachments = payloadToAttachments(merged.payload),
        )
        return true
    }

    private suspend fun buildLocalManifest(
        deviceId: String,
        tombstones: List<Tombstone>,
    ): BackupManifestBody {
        val timestamp = now()
        return BackupManifest.build(
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = deviceId,
            conversations = store.listConversations(),
            attachments = store.listAttachments(),
            tombstones = tombstones.filter { it.messageId == null },
        )
    }

    private suspend fun writeManifest(
        localManifest: BackupManifestBody,
        initialRemote: RemoteManifest,
        identity: BackupIdentity,
    ): RemoteManifest {
        var remote = initialRemote

        var attempt = 0
        while (true) {
            val merged = mergeManifests(localManifest, remote.manifest)
            val body = BackupManifestBody(
                formatVersion = BackupManifest.FORMAT_VERSION,
                createdAt = remote.manifest.createdAt,
                updatedAt = now().toString(),
                deviceId = identity.deviceId,
                conversations = merged.conversations,
                attachments = merged.attachments,
                tombstones = merged.tombstones,
            )

            val bytes = BackupEnvelope.encryptObject(
                passphrase = passphrase,
                salt = identity.salt,
                objectType = BackupObjectType.MANIFEST,
                objectKey = MANIFEST_OBJECT_KEY,
                plaintext = BackupManifest.encode(body).toByteArray(Charsets.UTF_8),
                compress = false,
                iterations = iterations,
            )

            try {
                val result = storage.putObject(
                    key = MANIFEST_OBJECT_KEY,
                    encryptedBytes = bytes,
                    options = if (remote.etag == null) {
                        PutObjectOptions(ifNoneMatch = "*")
                    } else {
                        PutObjectOptions(ifMatch = remote.etag)
                    },
                )
                return RemoteManifest(body, result.etag)
            } catch (error: StorageError) {
                if (
                    error.code == StorageErrorCode.CONFLICT &&
                    attempt < maxConflictRetries
                ) {
                    sleep(baseBackoffMs * (1L shl attempt))
                    remote = readRemoteManifest(identity.deviceId)
                    attempt += 1
                    continue
                }
                throw error
            }
        }
    }

    private suspend fun updateConfig(identity: BackupIdentity, etag: String?) {
        val existing = store.getBackupConfig() ?: return
        store.saveBackupConfig(
            existing.copy(
                deviceId = identity.deviceId,
                lastSyncAt = now(),
                lastManifestEtag = etag,
            ),
        )
    }

    private fun emptyManifest(deviceId: String): BackupManifestBody {
        val timestamp = now().toString()
        return BackupManifestBody(
            formatVersion = BackupManifest.FORMAT_VERSION,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = deviceId,
            conversations = emptyList(),
            attachments = emptyList(),
            tombstones = emptyList(),
        )
    }

    private fun defaultBackupConfig(deviceId: String, kdfSalt: String): BackupConfig =
        BackupConfig(
            enabled = false,
            backend = BackupBackend.S3,
            endpoint = null,
            region = null,
            bucket = "",
            prefix = "",
            credentialSecretId = null,
            encryption = BackupEncryption.AES_256_GCM,
            kdf = BackupKdf.PBKDF2_SHA256,
            kdfSalt = kdfSalt,
            lastSyncAt = null,
            lastManifestEtag = null,
            deviceId = deviceId,
        )
}

private fun referencedAttachmentIds(messages: List<Message>): Set<String> = messages
    .flatMap { it.parts }
    .mapNotNull { part ->
        when (part) {
            is ContentPart.Image -> part.attachmentId
            is ContentPart.File -> part.attachmentId
            else -> null
        }
    }
    .toSet()

private fun payloadToConversation(
    payload: ConversationPayload,
    revision: Long,
    existing: Conversation?,
): Conversation = Conversation(
    id = payload.id,
    title = payload.title,
    systemPrompt = payload.systemPrompt,
    providerId = payload.providerId,
    modelId = payload.modelId,
    modelLabel = payload.modelLabel,
    params = payload.params,
    pinned = payload.pinned,
    archived = payload.archived,
    temporary = payload.temporary,
    createdAt = Instant.parse(payload.createdAt),
    updatedAt = Instant.parse(payload.updatedAt),
    lastMessageAt = Instant.parse(payload.lastMessageAt),
    messageCount = payload.messages.size,
    lastError = existing?.lastError,
    sync = existing?.sync,
    storageKey = payload.storageKey,
    revision = revision,
)

private fun payloadToMessages(payload: ConversationPayload): List<Message> =
    payload.messages.map { message ->
        Message(
            id = message.id,
            conversationId = payload.id,
            sequence = message.sequence,
            role = message.role,
            parts = message.parts,
            status = message.status,
            providerId = null,
            modelId = null,
            modelLabel = null,
            usage = message.usage,
            finishReason = null,
            error = null,
            latencyMs = null,
            firstTokenMs = null,
            createdAt = Instant.parse(message.createdAt),
            updatedAt = Instant.parse(message.updatedAt),
            deletedAt = message.deletedAt?.let(Instant::parse),
        )
    }

private fun payloadToAttachments(payload: ConversationPayload): List<Attachment> =
    payload.attachments.map { attachment ->
        Attachment(
            id = attachment.id,
            messageId = null,
            kind = if (attachment.mimeType.startsWith("image/")) {
                AttachmentKind.IMAGE
            } else {
                AttachmentKind.FILE
            },
            mimeType = attachment.mimeType,
            name = attachment.name,
            size = attachment.size,
            sha256 = attachment.sha256,
            storageKey = attachment.storageKey,
            width = null,
            height = null,
            createdAt = Instant.parse(payload.createdAt),
        )
    }
