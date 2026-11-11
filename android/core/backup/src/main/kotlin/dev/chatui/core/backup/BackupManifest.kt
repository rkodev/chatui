package dev.chatui.core.backup

import dev.chatui.core.model.Attachment
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Tombstone
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One conversation entry in the backup manifest index. */
@Serializable
data class ManifestConversationEntry(
    val id: String,
    val storageKey: String,
    val revision: Long,
    val updatedAt: String,
    val deletedAt: String?,
    val deviceId: String,
    val objectKey: String,
)

/** One content-addressed attachment entry in the backup manifest index. */
@Serializable
data class ManifestAttachmentEntry(
    val sha256: String,
    val size: Long,
    val objectKey: String,
    val createdAt: String,
)

/** One deletion tombstone in the backup manifest. */
@Serializable
data class ManifestTombstone(
    val conversationId: String,
    val deletedAt: String,
    val deviceId: String,
)

/**
 * Plaintext manifest body from `contracts/schema/backup-manifest-body.schema.json`.
 *
 * Field order is the schema order so [BackupManifest.encode] produces canonical
 * JSON. The manifest is encrypted by the envelope codec; this type never
 * contains secrets or provider keys.
 */
@Serializable
data class BackupManifestBody(
    val formatVersion: Int,
    val createdAt: String,
    val updatedAt: String,
    val deviceId: String,
    val conversations: List<ManifestConversationEntry>,
    val attachments: List<ManifestAttachmentEntry>,
    val tombstones: List<ManifestTombstone>,
)

/**
 * Builds and parses the backup manifest body.
 *
 * The manifest is canonical JSON: compact, schema field order, no unknown
 * fields. Parsing validates every field that the schema constrains, including
 * opaque object keys and storage-key uniqueness.
 */
object BackupManifest {
    const val FORMAT_VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    private val STORAGE_KEY_PATTERN = Regex("^[0-9a-f]{32}$")
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val CONVERSATION_OBJECT_KEY_PATTERN = Regex("^conversations/[0-9a-f]{32}\\.enc$")
    private val ATTACHMENT_OBJECT_KEY_PATTERN = Regex("^attachments/[0-9a-f]{64}\\.enc$")

    /** Opaque object key for a conversation payload. */
    fun conversationObjectKey(storageKey: String): String = "conversations/$storageKey.enc"

    /** Opaque object key for an attachment payload. */
    fun attachmentObjectKey(sha256: String): String = "attachments/$sha256.enc"

    /**
     * Builds a manifest from local domain records.
     *
     * Conversations are active rows, so their index `deletedAt` is null.
     * Deletions are represented by [tombstones] instead.
     */
    fun build(
        createdAt: Instant,
        updatedAt: Instant,
        deviceId: String,
        conversations: List<Conversation>,
        attachments: List<Attachment>,
        tombstones: List<Tombstone>,
    ): BackupManifestBody {
        require(deviceId.isNotBlank()) { "Manifest deviceId must not be blank" }

        val body = BackupManifestBody(
            formatVersion = FORMAT_VERSION,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
            deviceId = deviceId,
            conversations = conversations.map { conversation ->
                ManifestConversationEntry(
                    id = conversation.id,
                    storageKey = conversation.storageKey,
                    revision = conversation.revision,
                    updatedAt = conversation.updatedAt.toString(),
                    deletedAt = null,
                    deviceId = deviceId,
                    objectKey = conversationObjectKey(conversation.storageKey),
                )
            },
            attachments = attachments.map { attachment ->
                ManifestAttachmentEntry(
                    sha256 = attachment.sha256,
                    size = attachment.size,
                    objectKey = attachmentObjectKey(attachment.sha256),
                    createdAt = attachment.createdAt.toString(),
                )
            },
            tombstones = tombstones.map { tombstone ->
                ManifestTombstone(
                    conversationId = tombstone.conversationId,
                    deletedAt = tombstone.deletedAt.toString(),
                    deviceId = tombstone.deviceId,
                )
            },
        )
        validate(body)
        return body
    }

    /** Serializes a manifest body as canonical JSON. */
    fun encode(body: BackupManifestBody): String {
        validate(body)
        return json.encodeToString(BackupManifestBody.serializer(), body)
    }

    /** Parses and validates a manifest body from UTF-8 bytes. */
    fun parse(bytes: ByteArray): BackupManifestBody =
        parse(String(bytes, Charsets.UTF_8))

    /** Parses and validates a manifest body from JSON text. */
    fun parse(text: String): BackupManifestBody {
        val body = try {
            json.decodeFromString(BackupManifestBody.serializer(), text)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Manifest is not valid canonical JSON", exception)
        }
        validate(body)
        return body
    }

    private fun validate(body: BackupManifestBody) {
        require(body.formatVersion == FORMAT_VERSION) {
            "Unsupported manifest formatVersion: ${body.formatVersion}"
        }
        require(body.deviceId.isNotBlank()) { "Manifest deviceId must not be blank" }
        require(body.createdAt.isNotBlank()) { "Manifest createdAt must not be blank" }
        require(body.updatedAt.isNotBlank()) { "Manifest updatedAt must not be blank" }

        val conversationIds = mutableSetOf<String>()
        val storageKeys = mutableSetOf<String>()
        body.conversations.forEach { entry ->
            require(entry.id.isNotBlank()) { "Manifest conversation id must not be blank" }
            require(conversationIds.add(entry.id)) {
                "Duplicate manifest conversation id: ${entry.id}"
            }
            require(STORAGE_KEY_PATTERN.matches(entry.storageKey)) {
                "Manifest storageKey must be 32 lowercase hex characters"
            }
            require(storageKeys.add(entry.storageKey)) {
                "Duplicate manifest storageKey: ${entry.storageKey}"
            }
            require(entry.revision >= 1) { "Manifest revision must be at least 1" }
            require(entry.updatedAt.isNotBlank()) {
                "Manifest conversation updatedAt must not be blank"
            }
            require(entry.deviceId.isNotBlank()) {
                "Manifest conversation deviceId must not be blank"
            }
            require(CONVERSATION_OBJECT_KEY_PATTERN.matches(entry.objectKey)) {
                "Manifest conversation objectKey is invalid"
            }
            require(entry.objectKey == conversationObjectKey(entry.storageKey)) {
                "Manifest conversation objectKey does not match its storageKey"
            }
        }

        body.attachments.forEach { entry ->
            require(SHA256_PATTERN.matches(entry.sha256)) {
                "Manifest attachment sha256 must be 64 lowercase hex characters"
            }
            require(entry.size >= 0) { "Manifest attachment size must not be negative" }
            require(entry.createdAt.isNotBlank()) {
                "Manifest attachment createdAt must not be blank"
            }
            require(ATTACHMENT_OBJECT_KEY_PATTERN.matches(entry.objectKey)) {
                "Manifest attachment objectKey is invalid"
            }
            require(entry.objectKey == attachmentObjectKey(entry.sha256)) {
                "Manifest attachment objectKey does not match its sha256"
            }
        }

        body.tombstones.forEach { entry ->
            require(entry.conversationId.isNotBlank()) {
                "Manifest tombstone conversationId must not be blank"
            }
            require(entry.deletedAt.isNotBlank()) {
                "Manifest tombstone deletedAt must not be blank"
            }
            require(entry.deviceId.isNotBlank()) {
                "Manifest tombstone deviceId must not be blank"
            }
        }
    }
}
