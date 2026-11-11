package dev.chatui.feature.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.backup.sync.DELETE_REMOTE_CONFIRMATION
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.BackupBackend
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `main controls and status render`() {
        composeRule.setContent {
            ChatUiTheme {
                BackupSettingsScreen(
                    state = state(),
                    onFormChanged = {},
                    onTestConnection = {},
                    onEnableChanged = {},
                    onBackupNow = {},
                    onRestoreMerge = {},
                    onRequestRestoreReplace = {},
                    onReplaceConfirmationChanged = {},
                    onConfirmRestoreReplace = {},
                    onRequestDeleteRemote = {},
                    onDeleteConfirmationChanged = {},
                    onConfirmDeleteRemote = {},
                    onDismissDialogs = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(BackupSettingsTestTags.Root)
            .assertIsDisplayed()

        listOf(
            BackupSettingsTestTags.Backend,
            BackupSettingsTestTags.Endpoint,
            BackupSettingsTestTags.Region,
            BackupSettingsTestTags.Bucket,
            BackupSettingsTestTags.AccessKey,
            BackupSettingsTestTags.SecretKey,
            BackupSettingsTestTags.Passphrase,
            BackupSettingsTestTags.TestConnection,
            BackupSettingsTestTags.Enable,
            BackupSettingsTestTags.Status,
            BackupSettingsTestTags.LastBackup,
            BackupSettingsTestTags.PendingChanges,
            BackupSettingsTestTags.StorageUsage,
            BackupSettingsTestTags.BackupNow,
            BackupSettingsTestTags.RestoreMerge,
            BackupSettingsTestTags.RestoreReplace,
            BackupSettingsTestTags.DeleteRemote,
            BackupSettingsTestTags.ClientSideStatement,
            BackupSettingsTestTags.PassphraseWarning,
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun `enable switch reports changes`() {
        var enabled: Boolean? = null
        composeRule.setContent {
            ChatUiTheme {
                BackupSettingsScreen(
                    state = state(enabled = false),
                    onFormChanged = {},
                    onTestConnection = {},
                    onEnableChanged = { enabled = it },
                    onBackupNow = {},
                    onRestoreMerge = {},
                    onRequestRestoreReplace = {},
                    onReplaceConfirmationChanged = {},
                    onConfirmRestoreReplace = {},
                    onRequestDeleteRemote = {},
                    onDeleteConfirmationChanged = {},
                    onConfirmDeleteRemote = {},
                    onDismissDialogs = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(BackupSettingsTestTags.Enable)
            .performScrollTo()
            .performClick()

        assertThat(enabled).isTrue()
    }

    @Test
    fun `delete dialog requires the exact typed confirmation`() {
        var phrase by mutableStateOf("")
        composeRule.setContent {
            ChatUiTheme {
                BackupSettingsScreen(
                    state = state(
                        showDeleteDialog = true,
                        deleteConfirmation = phrase,
                    ),
                    onFormChanged = {},
                    onTestConnection = {},
                    onEnableChanged = {},
                    onBackupNow = {},
                    onRestoreMerge = {},
                    onRequestRestoreReplace = {},
                    onReplaceConfirmationChanged = {},
                    onConfirmRestoreReplace = {},
                    onRequestDeleteRemote = {},
                    onDeleteConfirmationChanged = { phrase = it },
                    onConfirmDeleteRemote = {},
                    onDismissDialogs = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(BackupSettingsTestTags.DeleteConfirmation)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(BackupSettingsTestTags.DeleteConfirmButton)
            .assertIsNotEnabled()

        phrase = DELETE_REMOTE_CONFIRMATION
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BackupSettingsTestTags.DeleteConfirmButton)
            .assertIsEnabled()
    }

    @Test
    fun `backend selector offers every backend`() {
        composeRule.setContent {
            ChatUiTheme {
                BackupSettingsScreen(
                    state = state(),
                    onFormChanged = {},
                    onTestConnection = {},
                    onEnableChanged = {},
                    onBackupNow = {},
                    onRestoreMerge = {},
                    onRequestRestoreReplace = {},
                    onReplaceConfirmationChanged = {},
                    onConfirmRestoreReplace = {},
                    onRequestDeleteRemote = {},
                    onDeleteConfirmationChanged = {},
                    onConfirmDeleteRemote = {},
                    onDismissDialogs = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(BackupSettingsTestTags.Backend).performClick()

        listOf(
            "S3-compatible",
            "Azure Blob",
            "Google Cloud Storage",
            "Google Drive",
            "WebDAV",
        ).forEach { label ->
            composeRule.onAllNodesWithText(label).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun `additional backend fields render`() {
        var backend by mutableStateOf(BackupBackend.AZURE_BLOB)
        composeRule.setContent {
            ChatUiTheme {
                BackupSettingsScreen(
                    state = state(backend = backend),
                    onFormChanged = {},
                    onTestConnection = {},
                    onEnableChanged = {},
                    onBackupNow = {},
                    onRestoreMerge = {},
                    onRequestRestoreReplace = {},
                    onReplaceConfirmationChanged = {},
                    onConfirmRestoreReplace = {},
                    onRequestDeleteRemote = {},
                    onDeleteConfirmationChanged = {},
                    onConfirmDeleteRemote = {},
                    onDismissDialogs = {},
                    onDismissError = {},
                )
            }
        }

        val cases = listOf(
            BackupBackend.AZURE_BLOB to listOf(
                BackupSettingsTestTags.AccountName,
                BackupSettingsTestTags.AccountKey,
                BackupSettingsTestTags.SasToken,
                BackupSettingsTestTags.Bucket,
                BackupSettingsTestTags.Endpoint,
                BackupSettingsTestTags.Prefix,
            ),
            BackupBackend.GCS to listOf(
                BackupSettingsTestTags.Bucket,
                BackupSettingsTestTags.ServiceAccountJson,
                BackupSettingsTestTags.Prefix,
            ),
            BackupBackend.GOOGLE_DRIVE to listOf(
                BackupSettingsTestTags.AccessToken,
                BackupSettingsTestTags.DriveFolder,
                BackupSettingsTestTags.Prefix,
            ),
            BackupBackend.WEBDAV to listOf(
                BackupSettingsTestTags.Endpoint,
                BackupSettingsTestTags.WebDavUsername,
                BackupSettingsTestTags.WebDavPassword,
                BackupSettingsTestTags.Prefix,
            ),
        )

        for ((value, tags) in cases) {
            backend = value
            composeRule.waitForIdle()
            tags.forEach { tag ->
                composeRule.onNodeWithTag(tag)
                    .performScrollTo()
                    .assertIsDisplayed()
            }
        }
    }

    private fun state(
        enabled: Boolean = true,
        showDeleteDialog: Boolean = false,
        deleteConfirmation: String = "",
        backend: BackupBackend = BackupBackend.S3,
    ) = BackupUiState(
        loading = false,
        form = BackupFormValues(
            backend = backend,
            endpoint = "https://s3.example.com",
            region = "us-east-1",
            bucket = "chatui",
            prefix = "backups",
            accessKeyId = "AKIA",
            secretAccessKey = "secret",
            passphrase = "passphrase",
        ),
        enabled = enabled,
        status = BackupStatus(
            enabled = enabled,
            pendingChanges = 2,
            storageUsageBytes = 2048,
        ),
        showDeleteDialog = showDeleteDialog,
        deleteConfirmation = deleteConfirmation,
    )
}
