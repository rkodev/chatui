package dev.chatui.core.backup

import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Tombstone
import dev.chatui.core.model.Usage
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Deterministic backup codec fixtures. */
internal object BackupTestData {
    val NOW: Instant = Instant.parse("2026-11-02T00:00:00Z")
    const val STORAGE_KEY: String = "00112233445566778899aabbccddeeff"
    const val SHA256: String =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    fun conversation(
        id: String = "conversation-1",
        storageKey: String = STORAGE_KEY,
        revision: Long = 3,
    ): Conversation = Conversation(
        id = id,
        title = "Synthetic conversation",
        systemPrompt = "Be helpful",
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model One",
        params = ChatParams(
            temperature = 0.7,
            topP = 1.0,
            maxOutputTokens = 1024,
            stop = emptyList(),
            reasoningEffort = null,
            streaming = true,
            extra = buildJsonObject { put("route", "test") },
        ),
        pinned = false,
        archived = false,
        temporary = false,
        createdAt = NOW,
        updatedAt = NOW,
        lastMessageAt = NOW,
        messageCount = 1,
        lastError = null,
        sync = null,
        storageKey = storageKey,
        revision = revision,
    )

    fun message(
        id: String = "message-1",
        conversationId: String = "conversation-1",
        sequence: Long = 0,
    ): Message = Message(
        id = id,
        conversationId = conversationId,
        sequence = sequence,
        role = MessageRole.USER,
        parts = listOf(
            ContentPart.Text("Synthetic hello"),
            ContentPart.Image("attachment-1", "image/png", "Synthetic image"),
        ),
        status = MessageStatus.COMPLETE,
        providerId = null,
        modelId = null,
        modelLabel = null,
        usage = Usage(
            inputTokens = 12,
            outputTokens = 8,
            totalTokens = 20,
            costUsd = 0.0001,
            estimated = false,
        ),
        finishReason = null,
        error = null,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = NOW,
        updatedAt = NOW,
        deletedAt = null,
    )

    fun attachment(
        id: String = "attachment-1",
        sha256: String = SHA256,
    ): Attachment = Attachment(
        id = id,
        messageId = "message-1",
        kind = AttachmentKind.IMAGE,
        mimeType = "image/png",
        name = "synthetic.png",
        size = 1234,
        sha256 = sha256,
        storageKey = "attachments/$sha256",
        width = 1,
        height = 1,
        createdAt = NOW,
    )

    fun tombstone(
        id: String = "tombstone-1",
        conversationId: String = "conversation-2",
    ): Tombstone = Tombstone(
        id = id,
        conversationId = conversationId,
        messageId = null,
        deletedAt = NOW,
        deviceId = "device-1",
    )
}
