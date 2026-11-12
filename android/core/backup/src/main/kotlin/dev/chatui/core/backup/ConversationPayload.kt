package dev.chatui.core.backup

import dev.chatui.core.model.Attachment
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Usage
import dev.chatui.core.model.isStorageKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One message inside a self-contained conversation payload. */
@Serializable
data class ConversationPayloadMessage(
    val id: String,
    val sequence: Long,
    val role: MessageRole,
    val parts: List<ContentPart>,
    val status: MessageStatus,
    val usage: Usage?,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
)

/** One content-addressed attachment reference inside a conversation payload. */
@Serializable
data class ConversationPayloadAttachment(
    val id: String,
    val sha256: String,
    val mimeType: String,
    val name: String,
    val size: Long,
    val storageKey: String,
)

/**
 * Self-contained conversation payload from
 * `contracts/schema/backup-conversation.schema.json`.
 *
 * The payload carries conversation metadata, every message, and attachment
 * references. It deliberately has no provider secret, API key, or passphrase
 * field.
 */
@Serializable
data class ConversationPayload(
    val id: String,
    val title: String,
    val systemPrompt: String,
    val providerId: String,
    val modelId: String,
    val modelLabel: String,
    val params: ChatParams,
    val pinned: Boolean,
    val archived: Boolean,
    val temporary: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val lastMessageAt: String,
    val revision: Long,
    val storageKey: String,
    val messages: List<ConversationPayloadMessage>,
    val attachments: List<ConversationPayloadAttachment>,
)

/**
 * Builds and parses self-contained conversation payloads.
 *
 * The payload is canonical JSON: compact, schema field order, no unknown
 * fields. Parsing validates storage keys, revisions, attachment hashes, and
 * attachment references.
 */
object ConversationPayloadCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    /** Builds a payload from a conversation, its messages, and its attachments. */
    fun build(
        conversation: Conversation,
        messages: List<Message>,
        attachments: List<Attachment>,
    ): ConversationPayload {
        require(isStorageKey(conversation.storageKey)) {
            "Conversation storageKey must be 32 lowercase hex characters"
        }
        require(conversation.revision >= 1) { "Conversation revision must be at least 1" }

        val payload = ConversationPayload(
            id = conversation.id,
            title = conversation.title,
            systemPrompt = conversation.systemPrompt,
            providerId = conversation.providerId,
            modelId = conversation.modelId,
            modelLabel = conversation.modelLabel,
            params = conversation.params,
            pinned = conversation.pinned,
            archived = conversation.archived,
            temporary = conversation.temporary,
            createdAt = conversation.createdAt.toString(),
            updatedAt = conversation.updatedAt.toString(),
            lastMessageAt = conversation.lastMessageAt.toString(),
            revision = conversation.revision,
            storageKey = conversation.storageKey,
            messages = messages.map { message ->
                ConversationPayloadMessage(
                    id = message.id,
                    sequence = message.sequence,
                    role = message.role,
                    parts = message.parts,
                    status = message.status,
                    usage = message.usage,
                    createdAt = message.createdAt.toString(),
                    updatedAt = message.updatedAt.toString(),
                    deletedAt = message.deletedAt?.toString(),
                )
            },
            attachments = attachments.map { attachment ->
                ConversationPayloadAttachment(
                    id = attachment.id,
                    sha256 = attachment.sha256,
                    mimeType = attachment.mimeType,
                    name = attachment.name,
                    size = attachment.size,
                    storageKey = attachment.sha256,
                )
            },
        )
        validate(payload)
        return payload
    }

    /** Serializes a payload as canonical JSON. */
    fun encode(payload: ConversationPayload): String {
        validate(payload)
        return json.encodeToString(ConversationPayload.serializer(), payload)
    }

    /** Parses and validates a payload from UTF-8 bytes. */
    fun parse(bytes: ByteArray): ConversationPayload =
        parse(String(bytes, Charsets.UTF_8))

    /** Parses and validates a payload from JSON text. */
    fun parse(text: String): ConversationPayload {
        val payload = try {
            json.decodeFromString(ConversationPayload.serializer(), text)
        } catch (exception: Exception) {
            throw IllegalArgumentException(
                "Conversation payload is not valid canonical JSON",
                exception,
            )
        }
        validate(payload)
        return payload
    }

    private fun validate(payload: ConversationPayload) {
        require(payload.id.isNotBlank()) { "Payload conversation id must not be blank" }
        require(payload.providerId.isNotBlank()) { "Payload providerId must not be blank" }
        require(payload.modelId.isNotBlank()) { "Payload modelId must not be blank" }
        require(isStorageKey(payload.storageKey)) {
            "Payload storageKey must be 32 lowercase hex characters"
        }
        require(payload.revision >= 1) { "Payload revision must be at least 1" }
        require(payload.createdAt.isNotBlank()) { "Payload createdAt must not be blank" }
        require(payload.updatedAt.isNotBlank()) { "Payload updatedAt must not be blank" }
        require(payload.lastMessageAt.isNotBlank()) {
            "Payload lastMessageAt must not be blank"
        }

        val attachmentIds = mutableSetOf<String>()
        payload.attachments.forEach { attachment ->
            require(attachment.id.isNotBlank()) {
                "Payload attachment id must not be blank"
            }
            require(attachmentIds.add(attachment.id)) {
                "Duplicate payload attachment id: ${attachment.id}"
            }
            require(SHA256_PATTERN.matches(attachment.sha256)) {
                "Payload attachment sha256 must be 64 lowercase hex characters"
            }
            require(attachment.storageKey == attachment.sha256) {
                "Payload attachment storageKey must equal its sha256"
            }
            require(attachment.mimeType.isNotBlank()) {
                "Payload attachment mimeType must not be blank"
            }
            require(attachment.name.isNotBlank()) {
                "Payload attachment name must not be blank"
            }
            require(attachment.size >= 0) {
                "Payload attachment size must not be negative"
            }
        }

        val messageIds = mutableSetOf<String>()
        payload.messages.forEach { message ->
            require(message.id.isNotBlank()) { "Payload message id must not be blank" }
            require(messageIds.add(message.id)) {
                "Duplicate payload message id: ${message.id}"
            }
            require(message.sequence >= 0) { "Payload message sequence must not be negative" }
            require(message.createdAt.isNotBlank()) {
                "Payload message createdAt must not be blank"
            }
            require(message.updatedAt.isNotBlank()) {
                "Payload message updatedAt must not be blank"
            }
            message.parts.forEach { part ->
                val attachmentId = when (part) {
                    is ContentPart.Image -> part.attachmentId
                    is ContentPart.File -> part.attachmentId
                    else -> null
                }
                if (attachmentId != null) {
                    require(attachmentId in attachmentIds) {
                        "Payload message references missing attachment: $attachmentId"
                    }
                }
            }
        }
    }
}
