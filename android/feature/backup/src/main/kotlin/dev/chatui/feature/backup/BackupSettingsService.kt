package dev.chatui.feature.backup

import dev.chatui.core.backup.storage.AzureBlobStorageAdapter
import dev.chatui.core.backup.storage.AzureBlobStorageConfig
import dev.chatui.core.backup.storage.GcsStorageAdapter
import dev.chatui.core.backup.storage.GcsStorageConfig
import dev.chatui.core.backup.storage.GoogleDriveStorageAdapter
import dev.chatui.core.backup.storage.GoogleDriveStorageConfig
import dev.chatui.core.backup.storage.S3StorageAdapter
import dev.chatui.core.backup.storage.S3StorageConfig
import dev.chatui.core.backup.storage.StorageAdapter
import dev.chatui.core.backup.storage.WebDavStorageAdapter
import dev.chatui.core.backup.storage.WebDavStorageConfig
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
import dev.chatui.feature.backup.work.BackupScheduling
import dev.chatui.feature.backup.work.NoOpBackupScheduling
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
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val accountName: String = "",
    val accountKey: String = "",
    val sasToken: String = "",
    val serviceAccountJson: String = "",
    val accessToken: String = "",
    val driveFolderId: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",
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
    val accountName: String = "",
    val accountKey: String = "",
    val sasToken: String = "",
    val serviceAccountJson: String = "",
    val accessToken: String = "",
    val driveFolderId: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",
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
fun validateBackupForm(values: BackupFormValues): String? = when (values.backend) {
    BackupBackend.S3 -> validateS3Form(values)
    BackupBackend.AZURE_BLOB -> validateAzureForm(values)
    BackupBackend.GCS -> validateGcsForm(values)
    BackupBackend.GOOGLE_DRIVE -> validateGoogleDriveForm(values)
    BackupBackend.WEBDAV -> validateWebDavForm(values)
}

private fun validateS3Form(values: BackupFormValues): String? {
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

private fun validateAzureForm(values: BackupFormValues): String? {
    if (values.accountName.trim().isEmpty()) {
        return "Azure account name is required."
    }
    if (values.bucket.trim().isEmpty()) {
        return "Azure container is required."
    }
    if (values.accountKey.isEmpty() && values.sasToken.trim().isEmpty()) {
        return "Azure account key or SAS token is required."
    }
    val endpoint = values.endpoint.trim()
    if (endpoint.isNotEmpty()) {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "Azure endpoint must be a valid http or https URL."
        }
    }
    return null
}

private fun validateGcsForm(values: BackupFormValues): String? {
    if (values.bucket.trim().isEmpty()) {
        return "GCS bucket is required."
    }
    if (values.serviceAccountJson.trim().isEmpty()) {
        return "Service account JSON is required."
    }
    val parsed = runCatching {
        Json { ignoreUnknownKeys = true }
            .parseToJsonElement(values.serviceAccountJson)
    }.getOrNull()
    if (parsed == null) {
        return "Service account JSON is not valid JSON."
    }
    val objectValue = parsed as? kotlinx.serialization.json.JsonObject
        ?: return "Service account JSON must be an object."
    if (objectValue["client_email"] == null || objectValue["private_key"] == null) {
        return "Service account JSON must include client_email and private_key."
    }
    return null
}

private fun validateGoogleDriveForm(values: BackupFormValues): String? {
    if (values.accessToken.trim().isEmpty()) {
        return "Google Drive access token is required."
    }
    return null
}

private fun validateWebDavForm(values: BackupFormValues): String? {
    val endpoint = values.endpoint.trim()
    if (endpoint.isEmpty()) {
        return "WebDAV URL is required."
    }
    val uri = runCatching { URI(endpoint) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return "WebDAV URL must be a valid http or https URL."
    }
    if (values.webdavUsername.trim().isEmpty()) {
        return "WebDAV username is required."
    }
    if (values.webdavPassword.isEmpty()) {
        return "WebDAV password is required."
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
    private val scheduler: BackupScheduling = NoOpBackupScheduling,
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
        val credentials = credentialsFromValues(values)
        secrets.put(secretId, json.encodeToString(BackupCredentials.serializer(), credentials))

        val config = BackupConfig(
            enabled = enabled,
            backend = values.backend,
            endpoint = configEndpoint(values),
            region = configRegion(values),
            bucket = configBucket(values),
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
        if (enabled) {
            scheduler.schedulePeriodic()
        }
        return config
    }

    override suspend fun setEnabled(
        enabled: Boolean,
        values: BackupFormValues,
    ): BackupConfig? {
        if (enabled) {
            return saveConfig(values, enabled = true)
        }

        val existing = configRepository.get()
        if (existing == null) {
            scheduler.cancel()
            return null
        }
        val config = existing.copy(enabled = false)
        configRepository.put(config)
        scheduler.cancel()
        return config
    }

    override suspend fun testConnection(values: BackupFormValues) {
        validateBackupForm(values)?.let { throw IllegalArgumentException(it) }

        val config = configFromValues(values, enabled = false)
        val credentials = credentialsFromValues(values)
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
            endpoint = configEndpoint(values),
            region = configRegion(values),
            bucket = configBucket(values),
            prefix = values.prefix.trim(),
            credentialSecretId = null,
            encryption = BackupEncryption.AES_256_GCM,
            kdf = BackupKdf.PBKDF2_SHA256,
            kdfSalt = null,
            lastSyncAt = null,
            lastManifestEtag = null,
            deviceId = "",
        )

    private fun credentialsFromValues(values: BackupFormValues): BackupCredentials =
        BackupCredentials(
            accessKeyId = values.accessKeyId.trim(),
            secretAccessKey = values.secretAccessKey,
            accountName = values.accountName.trim(),
            accountKey = values.accountKey,
            sasToken = values.sasToken.trim(),
            serviceAccountJson = values.serviceAccountJson,
            accessToken = values.accessToken.trim(),
            driveFolderId = values.driveFolderId.trim(),
            webdavUsername = values.webdavUsername.trim(),
            webdavPassword = values.webdavPassword,
        )

    private fun configEndpoint(values: BackupFormValues): String? = when (values.backend) {
        BackupBackend.S3 -> values.endpoint.trim()
        BackupBackend.AZURE_BLOB -> values.endpoint.trim().ifEmpty { null }
        BackupBackend.GCS -> null
        BackupBackend.GOOGLE_DRIVE -> null
        BackupBackend.WEBDAV -> values.endpoint.trim()
    }

    private fun configRegion(values: BackupFormValues): String? = when (values.backend) {
        BackupBackend.S3 -> values.region.trim()
        else -> null
    }

    private fun configBucket(values: BackupFormValues): String = when (values.backend) {
        BackupBackend.S3 -> values.bucket.trim()
        BackupBackend.AZURE_BLOB -> values.bucket.trim()
        BackupBackend.GCS -> values.bucket.trim()
        BackupBackend.GOOGLE_DRIVE -> values.driveFolderId.trim().ifEmpty { "appDataFolder" }
        BackupBackend.WEBDAV -> ""
    }

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
): StorageAdapter = when (config.backend) {
    BackupBackend.S3 -> {
        val endpoint = config.endpoint
            ?: throw IllegalArgumentException("Endpoint is required.")
        val region = config.region
            ?: throw IllegalArgumentException("Region is required.")
        S3StorageAdapter(
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

    BackupBackend.AZURE_BLOB -> AzureBlobStorageAdapter(
        AzureBlobStorageConfig(
            accountName = credentials.accountName,
            accountKey = credentials.accountKey.ifBlank { null },
            sasToken = credentials.sasToken.ifBlank { null },
            container = config.bucket,
            endpoint = config.endpoint,
            prefix = config.prefix,
        ),
    )

    BackupBackend.GCS -> GcsStorageAdapter(
        GcsStorageConfig(
            serviceAccountJson = credentials.serviceAccountJson,
            bucket = config.bucket,
            prefix = config.prefix,
        ),
    )

    BackupBackend.GOOGLE_DRIVE -> GoogleDriveStorageAdapter(
        GoogleDriveStorageConfig(
            accessToken = credentials.accessToken,
            folderId = config.bucket.ifBlank { "appDataFolder" },
            prefix = config.prefix,
        ),
    )

    BackupBackend.WEBDAV -> {
        val endpoint = config.endpoint
            ?: throw IllegalArgumentException("WebDAV URL is required.")
        WebDavStorageAdapter(
            WebDavStorageConfig(
                baseUrl = endpoint,
                username = credentials.webdavUsername,
                password = credentials.webdavPassword,
                prefix = config.prefix,
            ),
        )
    }
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
