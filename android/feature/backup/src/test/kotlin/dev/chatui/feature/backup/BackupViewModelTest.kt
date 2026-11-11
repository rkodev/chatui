package dev.chatui.feature.backup

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.backup.sync.DELETE_REMOTE_CONFIRMATION
import dev.chatui.core.backup.sync.RestoreResult
import dev.chatui.core.backup.sync.SyncResult
import dev.chatui.core.model.BackupBackend
import dev.chatui.core.model.BackupConfig
import dev.chatui.core.model.BackupEncryption
import dev.chatui.core.model.BackupKdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load populates the form and status without loading the passphrase`() = runTest(dispatcher) {
        val service = FakeBackupService().apply {
            snapshot = BackupSettingsSnapshot(
                config = config(enabled = true),
                credentials = BackupCredentials("AKIA", "secret"),
            )
            status = BackupStatus(
                enabled = true,
                pendingChanges = 2,
                storageUsageBytes = 1024,
            )
        }

        val viewModel = BackupViewModel(service)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.form.endpoint).isEqualTo("https://s3.example.com")
        assertThat(state.form.accessKeyId).isEqualTo("AKIA")
        assertThat(state.form.secretAccessKey).isEqualTo("secret")
        assertThat(state.form.passphrase).isEmpty()
        assertThat(state.enabled).isTrue()
        assertThat(state.status?.pendingChanges).isEqualTo(2)
    }

    @Test
    fun `load populates backend-specific credentials`() = runTest(dispatcher) {
        val service = FakeBackupService().apply {
            snapshot = BackupSettingsSnapshot(
                config = config(enabled = false).copy(
                    backend = BackupBackend.GOOGLE_DRIVE,
                    endpoint = null,
                    region = null,
                    bucket = "folder-1",
                ),
                credentials = BackupCredentials(
                    accessToken = "drive-token",
                    driveFolderId = "folder-1",
                ),
            )
        }

        val viewModel = BackupViewModel(service)
        advanceUntilIdle()

        val form = viewModel.state.value.form
        assertThat(form.backend).isEqualTo(BackupBackend.GOOGLE_DRIVE)
        assertThat(form.accessToken).isEqualTo("drive-token")
        assertThat(form.driveFolderId).isEqualTo("folder-1")
        assertThat(form.bucket).isEqualTo("folder-1")
    }

    @Test
    fun `load maps webdav config to the url and credentials`() = runTest(dispatcher) {
        val service = FakeBackupService().apply {
            snapshot = BackupSettingsSnapshot(
                config = config(enabled = false).copy(
                    backend = BackupBackend.WEBDAV,
                    endpoint = "https://dav.example.com/dav",
                    region = null,
                    bucket = "",
                ),
                credentials = BackupCredentials(
                    webdavUsername = "dav-user",
                    webdavPassword = "dav-pass",
                ),
            )
        }

        val viewModel = BackupViewModel(service)
        advanceUntilIdle()

        val form = viewModel.state.value.form
        assertThat(form.backend).isEqualTo(BackupBackend.WEBDAV)
        assertThat(form.endpoint).isEqualTo("https://dav.example.com/dav")
        assertThat(form.bucket).isEmpty()
        assertThat(form.webdavUsername).isEqualTo("dav-user")
        assertThat(form.webdavPassword).isEqualTo("dav-pass")
    }

    @Test
    fun `test connection validates required fields before calling the service`() = runTest(dispatcher) {
        val service = FakeBackupService()
        val viewModel = BackupViewModel(service)
        advanceUntilIdle()

        viewModel.onTestConnection()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Endpoint is required.")
        assertThat(service.testCalls).isEqualTo(0)
    }

    @Test
    fun `test connection reports success and failure`() = runTest(dispatcher) {
        val service = FakeBackupService()
        val viewModel = BackupViewModel(service)
        advanceUntilIdle()
        viewModel.onFormChanged(validForm())

        viewModel.onTestConnection()
        advanceUntilIdle()
        assertThat(viewModel.state.value.testResult?.success).isTrue()
        assertThat(service.testCalls).isEqualTo(1)

        service.testError = IllegalStateException("HTTP 403")
        viewModel.onTestConnection()
        advanceUntilIdle()

        assertThat(viewModel.state.value.testResult?.success).isFalse()
        assertThat(viewModel.state.value.error).isEqualTo("HTTP 403")
    }

    @Test
    fun `enable saves the config and runs the first backup`() = runTest(dispatcher) {
        val service = FakeBackupService()
        val viewModel = BackupViewModel(service)
        advanceUntilIdle()
        viewModel.onFormChanged(validForm())

        viewModel.onEnableChanged(true)
        advanceUntilIdle()

        assertThat(service.enabledCalls).containsExactly(true)
        assertThat(service.backupCalls).isEqualTo(1)
        assertThat(viewModel.state.value.enabled).isTrue()
    }

    @Test
    fun `disable saves a disabled config`() = runTest(dispatcher) {
        val service = FakeBackupService()
        val viewModel = BackupViewModel(service)
        advanceUntilIdle()
        viewModel.onFormChanged(validForm())

        viewModel.onEnableChanged(false)
        advanceUntilIdle()

        assertThat(service.enabledCalls).containsExactly(false)
        assertThat(viewModel.state.value.enabled).isFalse()
    }

    @Test
    fun `back up now calls the sync engine`() = runTest(dispatcher) {
        val service = FakeBackupService()
        val viewModel = BackupViewModel(service)
        advanceUntilIdle()
        viewModel.onFormChanged(validForm())

        viewModel.onBackupNow()
        advanceUntilIdle()

        assertThat(service.backupCalls).isEqualTo(1)
        assertThat(viewModel.state.value.message).contains("Backed up")
    }

    @Test
    fun `restore defaults to merge`() = runTest(dispatcher) {
        val service = FakeBackupService()
        val viewModel = BackupViewModel(service)
        advanceUntilIdle()
        viewModel.onFormChanged(validForm())

        viewModel.onRestoreMerge()
        advanceUntilIdle()

        assertThat(service.restoreRequests).hasSize(1)
        assertThat(service.restoreRequests.single().replace).isFalse()
    }

    @Test
    fun `replace restore requires the typed confirmation`() = runTest(dispatcher) {
        val service = FakeBackupService()
        val viewModel = BackupViewModel(service)
        advanceUntilIdle()
        viewModel.onFormChanged(validForm())

        viewModel.onRequestRestoreReplace()
        assertThat(viewModel.state.value.showReplaceDialog).isTrue()

        viewModel.onConfirmRestoreReplace()
        advanceUntilIdle()
        assertThat(service.restoreRequests).isEmpty()

        viewModel.onReplaceConfirmationChanged(RESTORE_REPLACE_CONFIRMATION)
        viewModel.onConfirmRestoreReplace()
        advanceUntilIdle()

        assertThat(service.restoreRequests).hasSize(1)
        assertThat(service.restoreRequests.single().replace).isTrue()
        assertThat(service.restoreRequests.single().confirmation)
            .isEqualTo(RESTORE_REPLACE_CONFIRMATION)
        assertThat(viewModel.state.value.showReplaceDialog).isFalse()
    }

    @Test
    fun `delete remote requires the typed confirmation`() = runTest(dispatcher) {
        val service = FakeBackupService()
        val viewModel = BackupViewModel(service)
        advanceUntilIdle()
        viewModel.onFormChanged(validForm())

        viewModel.onRequestDeleteRemote()
        viewModel.onConfirmDeleteRemote()
        advanceUntilIdle()
        assertThat(service.deleteConfirmations).isEmpty()

        viewModel.onDeleteConfirmationChanged(DELETE_REMOTE_CONFIRMATION)
        viewModel.onConfirmDeleteRemote()
        advanceUntilIdle()

        assertThat(service.deleteConfirmations)
            .containsExactly(DELETE_REMOTE_CONFIRMATION)
        assertThat(viewModel.state.value.showDeleteDialog).isFalse()
    }

    @Test
    fun `actions require a passphrase`() = runTest(dispatcher) {
        val service = FakeBackupService()
        val viewModel = BackupViewModel(service)
        advanceUntilIdle()
        viewModel.onFormChanged(validForm().copy(passphrase = ""))

        viewModel.onBackupNow()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Passphrase is required.")
        assertThat(service.backupCalls).isEqualTo(0)
    }

    private fun validForm() = BackupFormValues(
        backend = BackupBackend.S3,
        endpoint = "https://s3.example.com",
        region = "us-east-1",
        bucket = "chatui",
        prefix = "backups",
        accessKeyId = "AKIA",
        secretAccessKey = "secret",
        passphrase = "passphrase",
    )

    private fun config(enabled: Boolean) = BackupConfig(
        enabled = enabled,
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

private class FakeBackupService : BackupSettingsServiceLike {
    var snapshot = BackupSettingsSnapshot(config = null, credentials = null)
    var status = BackupStatus()
    var testError: Throwable? = null
    var backupError: Throwable? = null
    var restoreError: Throwable? = null
    var deleteError: Throwable? = null
    var testCalls = 0
    var backupCalls = 0
    val enabledCalls = mutableListOf<Boolean>()
    val restoreRequests = mutableListOf<RestoreRequest>()
    val deleteConfirmations = mutableListOf<String>()

    override suspend fun load(): BackupSettingsSnapshot = snapshot

    override suspend fun saveConfig(
        values: BackupFormValues,
        enabled: Boolean,
    ): BackupConfig = throw UnsupportedOperationException()

    override suspend fun setEnabled(
        enabled: Boolean,
        values: BackupFormValues,
    ): BackupConfig? {
        enabledCalls += enabled
        return null
    }

    override suspend fun testConnection(values: BackupFormValues) {
        testCalls += 1
        testError?.let { throw it }
    }

    override suspend fun backupNow(): SyncResult {
        backupCalls += 1
        backupError?.let { throw it }
        return SyncResult(
            uploadedConversations = listOf("conversation-1"),
            uploadedAttachments = emptyList(),
            downloadedConversations = emptyList(),
            manifestEtag = "etag",
            drainedQueue = 0,
        )
    }

    override suspend fun restore(request: RestoreRequest): RestoreResult {
        restoreRequests += request
        restoreError?.let { throw it }
        return RestoreResult(
            mergedConversations = listOf("conversation-1"),
            replaced = request.replace,
        )
    }

    override suspend fun deleteRemote(confirmation: String) {
        deleteConfirmations += confirmation
        deleteError?.let { throw it }
    }

    override suspend fun getStatus(): BackupStatus = status
}
