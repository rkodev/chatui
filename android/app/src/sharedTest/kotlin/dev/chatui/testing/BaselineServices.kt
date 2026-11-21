package dev.chatui.testing

import dev.chatui.core.backup.storage.ListObjectsOptions
import dev.chatui.core.backup.storage.ListObjectsResult
import dev.chatui.core.backup.storage.PutObjectOptions
import dev.chatui.core.backup.storage.StorageAdapter
import dev.chatui.core.backup.storage.StorageObject
import dev.chatui.core.backup.sync.RestoreOptions
import dev.chatui.core.backup.sync.RestoreResult
import dev.chatui.core.backup.sync.SyncResult
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.repository.BackupConfigRepository
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.database.sync.RoomSyncLocalStore
import dev.chatui.core.datastore.secret.SecretCipher
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.network.ModelInfo
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderKeyInfo
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.providers.ModelService
import dev.chatui.core.providers.ProviderGateway
import dev.chatui.core.providers.ProviderService
import dev.chatui.feature.backup.BackupSettingsService
import dev.chatui.feature.backup.BackupStatusSource
import dev.chatui.feature.backup.BackupSyncEngine
import dev.chatui.feature.conversations.export.AttachmentStorageByteReader
import dev.chatui.feature.conversations.export.ExportModule
import dev.chatui.feature.conversations.export.ExportService
import dev.chatui.feature.conversations.importer.DataStoreImportSettingsStore
import dev.chatui.feature.conversations.importer.ImportService
import dev.chatui.feature.onboarding.DefaultOnboardingService
import dev.chatui.feature.providers.DefaultProviderManagementService
import dev.chatui.feature.settings.DataControlsService
import java.time.Clock

/** Production facades with fixture-only provider/storage boundaries; never opens a socket. */
class BaselineServices(
    database: ChatUiDatabase,
    storage: AttachmentStorage,
    val settings: SettingsDataStore,
    clock: Clock,
) {
    private var nextId = 0
    var storageFailure: Throwable? = null
    var backupFailure: Throwable? = null
    val secrets = SecretStore(
        database.secretDao(),
        object : SecretCipher {
            private val values = mutableMapOf<String, String>()
            override fun encrypt(plaintext: String): String =
                "fixture-envelope-${nextId++}".also { values[it] = plaintext }
            override fun decrypt(ciphertext: String): String = values.getValue(ciphertext)
        },
        clock = { clock.instant() },
    )
    private val registry = ProviderRegistry.load()
    private val providers = ProviderRepository(database.providerDao(), clock)
    private val models = ModelRepository(database.modelDao())
    private val conversations = ConversationRepository(database.conversationDao(), clock)
    private val gateway = object : ProviderGateway {
        override suspend fun validateKey(context: ProviderContext) =
            ProviderKeyInfo(label = "Invented fixture key", isFreeTier = true)

        override suspend fun listModels(context: ProviderContext, forceRefresh: Boolean) =
            BaselineFixtures.models.filterNot { it.hidden }.map {
                ModelInfo(
                    remoteId = it.remoteId, label = it.label,
                    contextWindow = it.contextWindow, maxOutputTokens = it.maxOutputTokens,
                    capabilities = it.capabilities, pricing = it.pricing,
                    inputModalities = it.inputModalities, outputModalities = it.outputModalities,
                    hidden = it.hidden,
                )
            }
    }
    private val providerService = ProviderService(
        providers, secrets, gateway, registry, clock,
        generateId = { "fixture-provider-${nextId++}" },
    )
    private val modelService = ModelService(
        providers, models, secrets, gateway, registry, clock,
        generateId = { "fixture-model-${nextId++}" },
    )
    val management = DefaultProviderManagementService(
        providers, models, conversations, providerService, modelService, settings, registry, clock,
    )
    val data = DataControlsService(database, secrets, storage, management)
    val importer = ImportService(
        database, storage, DataStoreImportSettingsStore(settings),
        now = { clock.instant().toString() },
    )
    val exporter = ExportService(
        reader = ExportModule.provideExportReader(database, settings, clock),
        attachmentBytes = AttachmentStorageByteReader(storage),
        now = { clock.instant().toString() },
    )
    val onboarding = DefaultOnboardingService(providerService, modelService, gateway, registry, settings, clock)
    val backup = BackupSettingsService(
        store = RoomSyncLocalStore(database, storage),
        secrets = secrets,
        configRepository = BackupConfigRepository(database.backupConfigDao()),
        statusSource = object : BackupStatusSource {
            override suspend fun pendingChanges() = 2
            override suspend fun storageUsageBytes() = 2048L
        },
        createStorage = { _, _ ->
            object : StorageAdapter {
                override suspend fun testConnection() { storageFailure?.let { throw it } }
                override suspend fun getObject(key: String): StorageObject = error("No remote fixture data")
                override suspend fun putObject(key: String, encryptedBytes: ByteArray, options: PutObjectOptions): StorageObject =
                    error("Fixture engine does not upload objects")
                override suspend fun headObject(key: String): StorageObject = error("No remote fixture data")
                override suspend fun deleteObject(key: String) = error("Fixture engine does not delete objects")
                override suspend fun listObjects(options: ListObjectsOptions) = ListObjectsResult(emptyList())
            }
        },
        createEngine = { _, _, _ ->
            object : BackupSyncEngine {
                override suspend fun backupNow(): SyncResult {
                    backupFailure?.let { throw it }
                    return SyncResult(emptyList(), emptyList(), emptyList(), "fixture-etag", 0)
                }
                override suspend fun restore(options: RestoreOptions) = RestoreResult(emptyList(), options.replace)
                override suspend fun deleteRemote(confirmation: String) = Unit
            }
        },
        now = { clock.instant() },
    )
}
