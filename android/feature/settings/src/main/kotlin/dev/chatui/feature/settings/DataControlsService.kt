package dev.chatui.feature.settings

import androidx.room.withTransaction
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.entity.ConversationEntity
import dev.chatui.core.database.entity.MessageEntity
import dev.chatui.core.database.entity.ModelEntity
import dev.chatui.core.database.entity.ProviderEntity
import dev.chatui.core.database.entity.SecretEntity
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.Provider
import dev.chatui.feature.providers.ProviderManagementService
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One usage row: record count plus an approximate byte size. */
data class StorageUsageEntry(
    val count: Int,
    val bytes: Long,
)

/**
 * Storage usage shown on the data settings screen.
 *
 * Attachment bytes come from attachment metadata (`size`) deduplicated by
 * SHA-256. Attachment file contents are never read to calculate usage.
 */
data class StorageUsage(
    val conversations: StorageUsageEntry,
    val messages: StorageUsageEntry,
    val attachments: StorageUsageEntry,
    val secrets: StorageUsageEntry,
    val providers: StorageUsageEntry,
    val models: StorageUsageEntry,
) {
    val totalBytes: Long
        get() = conversations.bytes +
            messages.bytes +
            attachments.bytes +
            secrets.bytes +
            providers.bytes +
            models.bytes
}

/** Non-secret summary of one provider key for the data screen. */
data class ProviderKeySummary(
    val id: String,
    val label: String,
    val presetId: String,
    val hasKey: Boolean,
    val keyLabel: String?,
)

/** Counts returned after a successful clear-all. */
data class ClearAllResult(
    val conversations: Int,
    val messages: Int,
    val attachments: Int,
    val secrets: Int,
    val providers: Int,
    val models: Int,
)

/** Data controls surface used by the settings UI and tests. */
interface DataControls {
    suspend fun getUsage(): StorageUsage

    suspend fun clearAll(): ClearAllResult

    suspend fun listProviderKeys(): List<ProviderKeySummary>

    suspend fun removeProviderKey(providerId: String)
}

/**
 * Storage usage, clear-all, and per-key deletion for the Android data screen.
 *
 * Clear-all removes conversations, messages, attachments, providers, models,
 * stored secrets, and the sync queue in one Room transaction. Appearance and
 * chat preferences are intentionally kept so the app remains usable after a
 * reset. Attachment bytes are removed through the content-addressed store.
 */
class DataControlsService(
    private val database: ChatUiDatabase,
    private val secrets: SecretStore,
    private val attachmentStorage: AttachmentStorage,
    private val providerService: ProviderManagementService,
) : DataControls {
    override suspend fun getUsage(): StorageUsage {
        val conversations = database.conversationDao().all()
        val messages = database.messageDao().all()
        val attachments = database.attachmentDao().all()
        val providers = database.providerDao().observeAll().first()
        val models = database.modelDao().all()
        val secretRecords = database.secretDao().getAll()

        val attachmentSizes = attachments
            .associateBy { it.sha256 }
            .values
            .sumOf { it.size }

        return StorageUsage(
            conversations = StorageUsageEntry(
                count = conversations.size,
                bytes = conversations.sumOf(::estimateConversationBytes),
            ),
            messages = StorageUsageEntry(
                count = messages.size,
                bytes = messages.sumOf(::estimateMessageBytes),
            ),
            attachments = StorageUsageEntry(
                count = attachments.size,
                bytes = attachmentSizes,
            ),
            secrets = StorageUsageEntry(
                count = secretRecords.size,
                bytes = secretRecords.sumOf(::estimateSecretBytes),
            ),
            providers = StorageUsageEntry(
                count = providers.size,
                bytes = providers.sumOf(::estimateProviderBytes),
            ),
            models = StorageUsageEntry(
                count = models.size,
                bytes = models.sumOf(::estimateModelBytes),
            ),
        )
    }

    override suspend fun clearAll(): ClearAllResult {
        val before = getUsage()

        database.withTransaction {
            database.importDao().clearFts()
            database.importDao().clearMessages()
            database.importDao().clearAttachments()
            database.importDao().clearConversations()
            database.importDao().clearModels()
            database.importDao().clearProviders()
            secrets.forgetAll()
            database.syncQueueDao().clear()
        }

        attachmentStorage.cleanupOrphans(emptySet())

        return ClearAllResult(
            conversations = before.conversations.count,
            messages = before.messages.count,
            attachments = before.attachments.count,
            secrets = before.secrets.count,
            providers = before.providers.count,
            models = before.models.count,
        )
    }

    override suspend fun listProviderKeys(): List<ProviderKeySummary> =
        providerService.listProviders()
            .map { provider ->
                ProviderKeySummary(
                    id = provider.id,
                    label = provider.label,
                    presetId = provider.presetId,
                    hasKey = provider.secretId != null,
                    keyLabel = keyLabel(provider),
                )
            }
            .sortedWith(compareBy({ it.label }, { it.id }))

    override suspend fun removeProviderKey(providerId: String) {
        providerService.removeKey(providerId)
    }

    private fun keyLabel(provider: Provider): String? {
        val key = provider.metadata?.get("key") as? JsonObject ?: return null
        val label = (key["label"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        return label?.takeIf { it.isNotBlank() }
    }

    private fun estimateConversationBytes(entity: ConversationEntity): Long =
        estimateBytes(
            entity.id,
            entity.title,
            entity.systemPrompt,
            entity.providerId,
            entity.modelId,
            entity.modelLabel,
            entity.paramsJson,
            entity.lastErrorJson,
            entity.syncState,
            entity.createdAt,
            entity.updatedAt,
            entity.lastMessageAt,
        )

    private fun estimateMessageBytes(entity: MessageEntity): Long =
        estimateBytes(
            entity.id,
            entity.conversationId,
            entity.role,
            entity.partsJson,
            entity.status,
            entity.providerId,
            entity.modelId,
            entity.modelLabel,
            entity.usageJson,
            entity.finishReason,
            entity.errorJson,
            entity.createdAt,
            entity.updatedAt,
            entity.deletedAt,
        )

    private fun estimateProviderBytes(entity: ProviderEntity): Long =
        estimateBytes(
            entity.id,
            entity.presetId,
            entity.label,
            entity.kind,
            entity.baseUrl,
            entity.authType,
            entity.secretId,
            entity.headersJson,
            entity.browserCors,
            entity.metadataJson,
            entity.createdAt,
            entity.updatedAt,
        )

    private fun estimateModelBytes(entity: ModelEntity): Long =
        estimateBytes(
            entity.id,
            entity.providerId,
            entity.remoteId,
            entity.label,
            entity.capabilitiesJson,
            entity.pricingJson,
            entity.canonicalSlug,
            entity.inputModalitiesJson,
            entity.outputModalitiesJson,
            entity.supportedParametersJson,
            entity.metadataJson,
            entity.source,
            entity.createdAt,
            entity.updatedAt,
        )

    private fun estimateSecretBytes(entity: SecretEntity): Long =
        estimateBytes(entity.id, entity.ciphertext, entity.createdAt, entity.updatedAt)

    private fun estimateBytes(vararg values: String?): Long =
        values.sumOf { value ->
            value?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        }
}
