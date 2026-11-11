package dev.chatui.feature.backup

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.backup.storage.ListObjectsOptions
import dev.chatui.core.backup.storage.ListObjectsResult
import dev.chatui.core.backup.storage.PutObjectOptions
import dev.chatui.core.backup.storage.StorageAdapter
import dev.chatui.core.backup.storage.StorageObject
import dev.chatui.core.backup.sync.DELETE_REMOTE_CONFIRMATION
import dev.chatui.core.backup.sync.RestoreOptions
import dev.chatui.core.backup.sync.RestoreResult
import dev.chatui.core.backup.sync.SyncResult
import dev.chatui.core.database.repository.BackupConfigRepository
import dev.chatui.core.database.sync.SyncLocalStore
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.BackupBackend
import dev.chatui.core.model.BackupConfig
import dev.chatui.core.model.BackupEncryption
import dev.chatui.core.model.BackupKdf
import dev.chatui.feature.backup.work.BackupScheduling
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackupSettingsServiceTest {
    private val store: SyncLocalStore = mockk(relaxed = true)
    private val secrets: SecretStore = mockk(relaxed = true)
    private val repository: BackupConfigRepository = mockk(relaxed = true)
    private val statusSource = FakeStatusSource()
    private val storage = FakeStorage()
    private val engine = FakeEngine()
    private val scheduler = FakeScheduler()
    private val now = Instant.parse("2026-11-11T12:00:00Z")

    private fun service(): BackupSettingsService = BackupSettingsService(
        store = store,
        secrets = secrets,
        configRepository = repository,
        statusSource = statusSource,
        createStorage = { _, _ -> storage },
        createEngine = { _, _, _ -> engine },
        scheduler = scheduler,
        now = { now },
    )

    private fun form(passphrase: String = "correct horse battery staple") = BackupFormValues(
        backend = BackupBackend.S3,
        endpoint = " https://s3.example.com ",
        region = " us-east-1 ",
        bucket = " chatui ",
        prefix = " backups ",
        accessKeyId = " AKIAEXAMPLE ",
        secretAccessKey = "secret-value",
        passphrase = passphrase,
    )

    @Test
    fun `saveConfig stores credentials as a secret and never persists the passphrase`() = runTest {
        coEvery { repository.get() } returns null
        val secretValue = slot<String>()
        coEvery { secrets.put(any(), capture(secretValue)) } returns Unit
        val saved = slot<BackupConfig>()
        coEvery { repository.put(capture(saved)) } returns Unit

        val config = service().saveConfig(form(), enabled = true)

        assertThat(config.enabled).isTrue()
        assertThat(config.endpoint).isEqualTo("https://s3.example.com")
        assertThat(config.region).isEqualTo("us-east-1")
        assertThat(config.bucket).isEqualTo("chatui")
        assertThat(config.prefix).isEqualTo("backups")
        assertThat(config.credentialSecretId).isEqualTo(BACKUP_CREDENTIALS_SECRET_ID)
        assertThat(secretValue.captured).contains("AKIAEXAMPLE")
        assertThat(secretValue.captured).contains("secret-value")
        assertThat(secretValue.captured).doesNotContain("correct horse battery staple")
        assertThat(saved.captured).isEqualTo(config)
    }

    @Test
    fun `enabling backup schedules periodic work`() = runTest {
        coEvery { repository.get() } returns null

        service().setEnabled(enabled = true, values = form())

        assertThat(scheduler.scheduleCalls).isEqualTo(1)
        assertThat(scheduler.cancelCalls).isEqualTo(0)
    }

    @Test
    fun `disabling backup cancels scheduled work`() = runTest {
        coEvery { repository.get() } returns savedConfig().copy(enabled = true)

        service().setEnabled(enabled = false, values = form())

        assertThat(scheduler.cancelCalls).isEqualTo(1)
        assertThat(scheduler.scheduleCalls).isEqualTo(0)
    }

    @Test
    fun `testConnection succeeds and propagates typed failures`() = runTest {
        val service = service()

        service.testConnection(form())
        assertThat(storage.testCalls).isEqualTo(1)

        storage.failure = IllegalStateException("HTTP 403")
        val error = runCatching { service.testConnection(form()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error?.message).isEqualTo("HTTP 403")
    }

    @Test
    fun `backupNow calls the sync engine and records the last sync time`() = runTest {
        coEvery { repository.get() } returns savedConfig()
        coEvery { secrets.get(BACKUP_CREDENTIALS_SECRET_ID) } returns
            """{"accessKeyId":"AKIAEXAMPLE","secretAccessKey":"secret-value"}"""
        val saved = slot<BackupConfig>()
        coEvery { repository.put(capture(saved)) } returns Unit

        val service = service()
        service.saveConfig(form())
        val result = service.backupNow()

        assertThat(result).isEqualTo(engine.result)
        assertThat(engine.backupCalls).isEqualTo(1)
        assertThat(saved.captured.lastSyncAt).isEqualTo(now)
    }

    @Test
    fun `restore defaults to merge`() = runTest {
        coEvery { repository.get() } returns savedConfig()
        coEvery { secrets.get(BACKUP_CREDENTIALS_SECRET_ID) } returns
            """{"accessKeyId":"AKIAEXAMPLE","secretAccessKey":"secret-value"}"""

        val service = service()
        service.saveConfig(form())
        service.restore()

        assertThat(engine.restoreOptions).isEqualTo(RestoreOptions(replace = false))
    }

    @Test
    fun `replace restore requires the typed confirmation`() = runTest {
        coEvery { repository.get() } returns savedConfig()
        coEvery { secrets.get(BACKUP_CREDENTIALS_SECRET_ID) } returns
            """{"accessKeyId":"AKIAEXAMPLE","secretAccessKey":"secret-value"}"""

        val service = service()
        service.saveConfig(form())

        val error = runCatching {
            service.restore(RestoreRequest(replace = true, confirmation = "wrong"))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(engine.restoreOptions).isNull()

        service.restore(
            RestoreRequest(
                replace = true,
                confirmation = RESTORE_REPLACE_CONFIRMATION,
            ),
        )
        assertThat(engine.restoreOptions).isEqualTo(RestoreOptions(replace = true))
    }

    @Test
    fun `delete remote requires the typed confirmation and keeps local data`() = runTest {
        coEvery { repository.get() } returns savedConfig()
        coEvery { secrets.get(BACKUP_CREDENTIALS_SECRET_ID) } returns
            """{"accessKeyId":"AKIAEXAMPLE","secretAccessKey":"secret-value"}"""

        val service = service()
        service.saveConfig(form())

        val error = runCatching { service.deleteRemote("wrong") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(engine.deleteCalls).isEqualTo(0)

        service.deleteRemote(DELETE_REMOTE_CONFIRMATION)
        assertThat(engine.deleteCalls).isEqualTo(1)
        coVerify(exactly = 0) { store.clearConversations() }
    }

    @Test
    fun `getStatus reports config and status source values`() = runTest {
        coEvery { repository.get() } returns savedConfig().copy(
            enabled = true,
            lastSyncAt = now,
        )
        statusSource.pending = 3
        statusSource.usage = 4096

        val status = service().getStatus()

        assertThat(status.enabled).isTrue()
        assertThat(status.lastBackupAt).isEqualTo(now)
        assertThat(status.pendingChanges).isEqualTo(3)
        assertThat(status.storageUsageBytes).isEqualTo(4096)
    }

    @Test
    fun `load returns the saved config and decrypted credentials`() = runTest {
        coEvery { repository.get() } returns savedConfig()
        coEvery { secrets.get(BACKUP_CREDENTIALS_SECRET_ID) } returns
            """{"accessKeyId":"AKIAEXAMPLE","secretAccessKey":"secret-value"}"""

        val snapshot = service().load()

        assertThat(snapshot.config).isEqualTo(savedConfig())
        assertThat(snapshot.credentials).isEqualTo(
            BackupCredentials("AKIAEXAMPLE", "secret-value"),
        )
    }

    @Test
    fun `validation rejects missing fields and non-http endpoints`() {
        assertThat(validateBackupForm(form().copy(endpoint = "")))
            .isEqualTo("Endpoint is required.")
        assertThat(validateBackupForm(form().copy(endpoint = "ftp://example.com")))
            .isEqualTo("Endpoint must be a valid http or https URL.")
        assertThat(validateBackupForm(form().copy(region = "")))
            .isEqualTo("Region is required.")
        assertThat(validateBackupForm(form().copy(bucket = "")))
            .isEqualTo("Bucket is required.")
        assertThat(validateBackupForm(form().copy(accessKeyId = "")))
            .isEqualTo("Access key ID is required.")
        assertThat(validateBackupForm(form().copy(secretAccessKey = "")))
            .isEqualTo("Secret access key is required.")
        assertThat(validateBackupForm(form())).isNull()
        assertThat(validatePassphrase("")).isEqualTo("Passphrase is required.")
        assertThat(validatePassphrase("x")).isNull()
    }

    private fun savedConfig() = BackupConfig(
        enabled = false,
        backend = BackupBackend.S3,
        endpoint = "https://s3.example.com",
        region = "us-east-1",
        bucket = "chatui",
        prefix = "backups",
        credentialSecretId = BACKUP_CREDENTIALS_SECRET_ID,
        encryption = BackupEncryption.AES_256_GCM,
        kdf = BackupKdf.PBKDF2_SHA256,
        kdfSalt = null,
        lastSyncAt = null,
        lastManifestEtag = null,
        deviceId = "device-1",
    )
}

private class FakeScheduler : BackupScheduling {
    var scheduleCalls: Int = 0
    var cancelCalls: Int = 0

    override fun schedulePeriodic() {
        scheduleCalls += 1
    }

    override fun cancel() {
        cancelCalls += 1
    }
}

private class FakeStatusSource : BackupStatusSource {
    var pending: Int = 0
    var usage: Long = 0

    override suspend fun pendingChanges(): Int = pending

    override suspend fun storageUsageBytes(): Long = usage
}

private class FakeStorage : StorageAdapter {
    var testCalls: Int = 0
    var failure: Throwable? = null

    override suspend fun testConnection() {
        testCalls += 1
        failure?.let { throw it }
    }

    override suspend fun getObject(key: String): StorageObject =
        throw UnsupportedOperationException()

    override suspend fun putObject(
        key: String,
        encryptedBytes: ByteArray,
        options: PutObjectOptions,
    ): StorageObject = throw UnsupportedOperationException()

    override suspend fun headObject(key: String): StorageObject =
        throw UnsupportedOperationException()

    override suspend fun deleteObject(key: String) = Unit

    override suspend fun listObjects(options: ListObjectsOptions): ListObjectsResult =
        ListObjectsResult(emptyList())
}

private class FakeEngine : BackupSyncEngine {
    val result = SyncResult(
        uploadedConversations = listOf("conversation-1"),
        uploadedAttachments = emptyList(),
        downloadedConversations = emptyList(),
        manifestEtag = "etag",
        drainedQueue = 0,
    )
    var backupCalls: Int = 0
    var restoreOptions: RestoreOptions? = null
    var deleteCalls: Int = 0

    override suspend fun backupNow(): SyncResult {
        backupCalls += 1
        return result
    }

    override suspend fun restore(options: RestoreOptions): RestoreResult {
        restoreOptions = options
        return RestoreResult(mergedConversations = listOf("conversation-1"), replaced = options.replace)
    }

    override suspend fun deleteRemote(confirmation: String) {
        deleteCalls += 1
    }
}
