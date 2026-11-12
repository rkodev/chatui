package dev.chatui.core.database

import dev.chatui.core.database.entity.AttachmentEntity
import dev.chatui.core.database.entity.BackupConfigEntity
import dev.chatui.core.database.entity.ConversationEntity
import dev.chatui.core.database.entity.MessageEntity
import dev.chatui.core.database.entity.ModelEntity
import dev.chatui.core.database.entity.ProviderEntity
import dev.chatui.core.database.entity.SecretEntity
import dev.chatui.core.database.entity.SyncQueueEntity
import dev.chatui.core.database.entity.TombstoneEntity

/** Deterministic entity builders shared by the database tests. */
internal object TestData {
    const val NOW: String = "2026-11-02T00:00:00Z"

    fun conversation(
        id: String = "conversation-1",
        title: String = "Conversation",
        pinned: Boolean = false,
        archived: Boolean = false,
        lastMessageAt: String = NOW,
        updatedAt: String = NOW,
        storageKey: String = "",
        revision: Long = 1,
    ): ConversationEntity = ConversationEntity(
        id = id,
        title = title,
        systemPrompt = "",
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model One",
        paramsJson = "{}",
        pinned = pinned,
        archived = archived,
        temporary = false,
        createdAt = NOW,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
        messageCount = 0,
        lastErrorJson = null,
        syncState = null,
        storageKey = storageKey,
        revision = revision,
    )

    fun message(
        id: String,
        conversationId: String = "conversation-1",
        sequence: Long,
        role: String = "user",
        partsJson: String = """[{"type":"text","text":"hello"}]""",
        status: String = "complete",
        deletedAt: String? = null,
        updatedAt: String = NOW,
    ): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        sequence = sequence,
        role = role,
        partsJson = partsJson,
        status = status,
        providerId = null,
        modelId = null,
        modelLabel = null,
        usageJson = null,
        finishReason = null,
        errorJson = null,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = NOW,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    fun attachment(
        id: String = "attachment-1",
        messageId: String? = "message-1",
        sha256: String = "abc123",
    ): AttachmentEntity = AttachmentEntity(
        id = id,
        messageId = messageId,
        kind = "image",
        mimeType = "image/png",
        name = "image.png",
        size = 42L,
        sha256 = sha256,
        storageKey = "attachments/$sha256",
        width = 1,
        height = 1,
        createdAt = NOW,
    )

    fun provider(
        id: String = "provider-1",
        label: String = "Provider One",
        secretId: String? = null,
    ): ProviderEntity = ProviderEntity(
        id = id,
        presetId = "custom",
        label = label,
        kind = "openai-compatible",
        baseUrl = "https://example.test/v1",
        authType = "bearer",
        secretId = secretId,
        headersJson = "{}",
        enabled = true,
        browserCors = "unknown",
        metadataJson = null,
        createdAt = NOW,
        updatedAt = NOW,
    )

    fun model(
        id: String = "model-1",
        providerId: String = "provider-1",
        remoteId: String = "remote-1",
        label: String = "Model One",
    ): ModelEntity = ModelEntity(
        id = id,
        providerId = providerId,
        remoteId = remoteId,
        label = label,
        contextWindow = 128_000,
        maxOutputTokens = 4_096,
        capabilitiesJson = """["text"]""",
        pricingJson = null,
        canonicalSlug = null,
        inputModalitiesJson = """["text"]""",
        outputModalitiesJson = """["text"]""",
        supportedParametersJson = """[]""",
        metadataJson = null,
        hidden = false,
        pinned = false,
        source = "discovered",
        createdAt = NOW,
        updatedAt = NOW,
    )

    fun secret(
        id: String = "secret-1",
        ciphertext: String = "ciphertext",
    ): SecretEntity = SecretEntity(
        id = id,
        ciphertext = ciphertext,
        createdAt = NOW,
        updatedAt = NOW,
    )

    fun backupConfig(
        id: String = "default",
    ): BackupConfigEntity = BackupConfigEntity(
        id = id,
        enabled = false,
        backend = "s3",
        endpoint = null,
        region = null,
        bucket = "bucket",
        prefix = "chatui/",
        credentialSecretId = null,
        encryption = "aes-256-gcm",
        kdf = "pbkdf2-sha256",
        kdfSalt = null,
        lastSyncAt = null,
        lastManifestEtag = null,
        deviceId = "device-1",
        updatedAt = NOW,
    )

    fun syncQueueItem(
        id: String = "sync-1",
        conversationId: String = "conversation-1",
    ): SyncQueueEntity = SyncQueueEntity(
        id = id,
        conversationId = conversationId,
        operation = "upsert",
        payloadJson = "{}",
        attempts = 0,
        lastAttemptAt = null,
        nextAttemptAt = null,
        lastError = null,
        createdAt = NOW,
        updatedAt = NOW,
    )

    fun tombstone(
        id: String = "tombstone-1",
        conversationId: String = "conversation-1",
        messageId: String? = null,
        deletedAt: String = NOW,
        deviceId: String = "device-1",
    ): TombstoneEntity = TombstoneEntity(
        id = id,
        conversationId = conversationId,
        messageId = messageId,
        deletedAt = deletedAt,
        deviceId = deviceId,
    )
}
