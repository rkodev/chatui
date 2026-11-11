package dev.chatui.feature.backup

import dev.chatui.core.backup.storage.S3StorageAdapter
import dev.chatui.core.backup.storage.S3StorageConfig
import dev.chatui.core.backup.storage.StorageAdapter
import dev.chatui.core.backup.sync.DELETE_REMOTE_CONFIRMATION
import dev.chatui.core.backup.sync.RestoreOptions
import dev.chatui.core.backup.sync.RestoreResult
import dev.chatui.core.backup.sync.SyncEngine
import dev.chatui.core.backup.sync.SyncResult
import dev.chatui.core.database.repository.BackupConfigRepository
import dev.chatui.core.database.sync.SyncLocalStore
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.BackupBackend
import dev.chatui.core.model.BackupConfig
import dev.chatui.core.model.BackupEncryption
import dev.chatui.core.model.BackupKdf
import java.net.URI
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stable secret id for the S3 access key pair. */
const val BACKUP_CREDENTIALS_SECRET_ID: String = "backup-credentials"

/** Typed confirmation required before a replace restore. */
const val RESTORE_REPLACE_CONFIRMATION: String = "REPLACE LOCAL DATA"

/** Credentials stored as one encrypted secret value. */
@Serializable
data class BackupCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
)

/** Editable backup form values. The passphrase is never persisted. */
data class BackupFormValues(
    val backend: BackupBackend = BackupBackend.S3,
    val endpoint: String = "",
    val region: String = "",
    val bucket: String = "",
    val prefix: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val passphrase: String = "",
)

/** Persisted configuration plus the decrypted credentials for the form. */
data class BackupSettingsSnapshot(
    val config: BackupConfig?,
    val credentials: BackupCredentials?,
)

/** Options for a restore request. */
data class RestoreRequest(
    val replace: Boolean = false,
    val confirmation: String? = null,
)

/** Minimal sync-engine surface used by the settings service. */
interface BackupSyncEngine {
    suspend fun backupNow(): SyncResult

    suspend fun restore(options: RestoreOptions): RestoreResult

    suspend fun deleteRemote(confirmation: String)
}

/** Backup configuration and manual sync actions for the settings screen. */
interface BackupSettingsServiceLike {
    suspend fun load(): BackupSettingsSnapshot

    suspend fun saveConfig(values: BackupFormValues, enabled: Boolean = false): BackupConfig

    suspend fun setEnabled(enabled: Boolean, values: BackupFormValues): BackupConfig?

    suspend fun testConnection(values: BackupFormValues)

    suspend fun backupNow(): SyncResult

    suspend fun restore(request: RestoreRequest = RestoreRequest()): RestoreResult

    suspend fun deleteRemote(confirmation: String)

    suspend fun getStatus(): BackupStatus
}

/** Validate the storage fields. The passphrase is validated separately. */
fun validateBackupForm(values: BackupFormValues): String? {
    if (values.backend != BackupBackend.S3) {
        return "Only S3-compatible storage is available in this release."
    }

    val endpoint = values.endpoint.trim()
    if (endpoint.isEmpty()) {
        return "Endpoint is required."
    }

    val uri = runCatching { URI(endpoint) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return "Endpoint must be a valid http or https URL."
    }

    if (values.region.trim().isEmpty()) {
        return "Region is required."
    }
    if (values.bucket.trim().isEmpty()) {
        return "Bucket is required."
    }
    if (values.accessKeyId.trim().isEmpty()) {
        return "Access key ID is required."
    }
    if (values.secretAccessKey.isEmpty()) {
        return "Secret access key is required."
    }

    return null
}

/** Validate the passphrase. */
fun validatePassphrase(passphrase: String): String? =
    if (passphrase.isEmpty()) "Passphrase is required." else null

/**
 * Backup configuration and manual sync actions.
 *
 * Credentials are written to [SecretStore] as one encrypted JSON value. The
 * passphrase is only kept in this instance's memory and is never written to
 * Room or [BackupConfig]. A process restart therefore requires the user to
 * enter it again.
 */
class BackupSettingsService(
    private val store: SyncLocalStore,
    private val secrets: SecretStore,
    private val configRepository: BackupConfigRepository,
    private val statusSource: BackupStatusSource,
    private val createStorage: (BackupConfig, BackupCredentials) -> StorageAdapter =
        ::defaultCreateStorage,
    private val createEngine: (SyncLocalStore, StorageAdapter, String) -> BackupSyncEngine =
        ::defaultCreateEngine,
    private val now: () -> Instant = Instant::now,
) : BackupSettingsServiceLike {
    private val json = Json { ignoreUnknownKeys = true }
    private var passphrase: String = ""
    private var lastError: String? = null

    override suspend fun load(): BackupSettingsSnapshot {
        val config = configRepository.get()
            ?: return BackupSettingsSnapshot(config = null, credentials = null)
        return BackupSettingsSnapshot(
            config = config,
            credentials = loadCredentials(config),
        )
    }

    override suspend fun saveConfig(
        values: BackupFormValues,
        enabled: Boolean,
    ): BackupConfig {
        validateBackupForm(values)?.let { throw IllegalArgumentException(it) }
        validatePassphrase(values.passphrase)?.let { throw IllegalArgumentException(it) }

        val existing = configRepository.get()
        val secretId = existing?.credentialSecretId ?: BACKUP_CREDENTIALS_SECRET_ID
        val credentials = BackupCredentials(
            accessKeyId = values.accessKeyId.trim(),
            secretAccessKey = values.secretAccessKey,
        )
        secrets.put(secretId, json.encodeToString(BackupCredentials.serializer(), credentials))

        val config = BackupConfig(
            enabled = enabled,
            backend = values.backend,
            endpoint = values.endpoint.trim(),
            region = values.region.trim(),
            bucket = values.bucket.trim(),
            prefix = values.prefix.trim(),
            credentialSecretId = secretId,
            encryption = existing?.encryption ?: BackupEncryption.AES_256_GCM,
            kdf = existing?.kdf ?: BackupKdf.PBKDF2_SHA256,
            kdfSalt = existing?.kdfSalt,
            lastSyncAt = existing?.lastSyncAt,
            lastManifestEtag = existing?.lastManifestEtag,
            deviceId = existing?.deviceId.orEmpty(),
        )
        configRepository.put(config)
        passphrase = values.passphrase
        lastError = null
        return config
    }

    override suspend fun setEnabled(
        enabled: Boolean,
        values: BackupFormValues,
    ): BackupConfig? {
        if (enabled) {
            return saveConfig(values, enabled = true)
        }

        val existing = configRepository.get() ?: return null
        val config = existing.copy(enabled = false)
        configRepository.put(config)
        return config
    }

    override suspend fun testConnection(values: BackupFormValues) {
        validateBackupForm(values)?.let { throw IllegalArgumentException(it) }

        val config = configFromValues(values, enabled = false)
        val credentials = BackupCredentials(
            accessKeyId = values.accessKeyId.trim(),
            secretAccessKey = values.secretAccessKey,
        )
        createStorage(config, credentials).testConnection()
    }

    override suspend fun backupNow(): SyncResult {
        val config = requireConfig()
        val credentials = requireCredentials(config)
        val engine = createEngine(
            store,
            createStorage(config, credentials),
            requirePassphrase(),
        )

        return try {
            val result = engine.backupNow()
            configRepository.put(config.copy(lastSyncAt = now()))
            lastError = null
            result
        } catch (error: Throwable) {
            lastError = error.message ?: "Backup failed."
            throw error
        }
    }

    override suspend fun restore(request: RestoreRequest): RestoreResult {
        if (request.replace && request.confirmation != RESTORE_REPLACE_CONFIRMATION) {
            throw IllegalArgumentException(
                "Replace restore requires the typed confirmation \"$RESTORE_REPLACE_CONFIRMATION\".",
            )
        }

        val config = requireConfig()
        val credentials = requireCredentials(config)
        val engine = createEngine(
            store,
            createStorage(config, credentials),
            requirePassphrase(),
        )

        return try {
            val result = engine.restore(RestoreOptions(replace = request.replace))
            lastError = null
            result
        } catch (error: Throwable) {
            lastError = error.message ?: "Restore failed."
            throw error
        }
    }

    override suspend fun deleteRemote(confirmation: String) {
        if (confirmation != DELETE_REMOTE_CONFIRMATION) {
            throw IllegalArgumentException(
                "Remote backup deletion requires the typed confirmation \"$DELETE_REMOTE_CONFIRMATION\".",
            )
        }

        val config = requireConfig()
        val credentials = requireCredentials(config)
        val engine = createEngine(
            store,
            createStorage(config, credentials),
            requirePassphrase(),
        )

        try {
            engine.deleteRemote(confirmation)
            configRepository.put(
                config.copy(lastSyncAt = null, lastManifestEtag = null),
            )
            lastError = null
        } catch (error: Throwable) {
            lastError = error.message ?: "Could not delete the remote backup."
            throw error
        }
    }

    override suspend fun getStatus(): BackupStatus {
        val config = configRepository.get()
        return BackupStatus(
            enabled = config?.enabled ?: false,
            lastBackupAt = config?.lastSyncAt,
            pendingChanges = statusSource.pendingChanges(),
            lastError = lastError,
            storageUsageBytes = statusSource.storageUsageBytes(),
        )
    }

    private fun configFromValues(values: BackupFormValues, enabled: Boolean): BackupConfig =
        BackupConfig(
            enabled = enabled,
            backend = values.backend,
            endpoint = values.endpoint.trim(),
            region = values.region.trim(),
            bucket = values.bucket.trim(),
            prefix = values.prefix.trim(),
            credentialSecretId = null,
            encryption = BackupEncryption.AES_256_GCM,
            kdf = BackupKdf.PBKDF2_SHA256,
            kdfSalt = null,
            lastSyncAt = null,
            lastManifestEtag = null,
            deviceId = "",
        )

    private suspend fun loadCredentials(config: BackupConfig): BackupCredentials? {
        val secretId = config.credentialSecretId ?: return null
        val raw = secrets.get(secretId) ?: return null
        return runCatching {
            json.decodeFromString(BackupCredentials.serializer(), raw)
        }.getOrNull()
    }

    private suspend fun requireConfig(): BackupConfig =
        configRepository.get()
            ?: throw IllegalStateException("Backup is not configured yet.")

    private suspend fun requireCredentials(config: BackupConfig): BackupCredentials =
        loadCredentials(config)
            ?: throw IllegalStateException(
                "Backup credentials are missing. Save the configuration again.",
            )

    private fun requirePassphrase(): String {
        if (passphrase.isEmpty()) {
            throw IllegalStateException("Enter your backup passphrase to continue.")
        }
        return passphrase
    }
}

private fun defaultCreateStorage(
    config: BackupConfig,
    credentials: BackupCredentials,
): StorageAdapter {
    require(config.backend == BackupBackend.S3) {
        "Only S3-compatible storage is available in this release."
    }
    val endpoint = config.endpoint
        ?: throw IllegalArgumentException("Endpoint is required.")
    val region = config.region
        ?: throw IllegalArgumentException("Region is required.")

    return S3StorageAdapter(
        S3StorageConfig(
            endpoint = endpoint,
            bucket = config.bucket,
            region = region,
            accessKeyId = credentials.accessKeyId,
            secretAccessKey = credentials.secretAccessKey,
            prefix = config.prefix,
        ),
    )
}

private fun defaultCreateEngine(
    store: SyncLocalStore,
    storage: StorageAdapter,
    passphrase: String,
): BackupSyncEngine = SyncEngineAdapter(
    SyncEngine(
        store = store,
        storage = storage,
        passphrase = passphrase,
    ),
)

private class SyncEngineAdapter(
    private val engine: SyncEngine,
) : BackupSyncEngine {
    override suspend fun backupNow(): SyncResult = engine.backupNow()

    override suspend fun restore(options: RestoreOptions): RestoreResult =
        engine.restore(options)

    override suspend fun deleteRemote(confirmation: String) =
        engine.deleteRemote(confirmation)
}
