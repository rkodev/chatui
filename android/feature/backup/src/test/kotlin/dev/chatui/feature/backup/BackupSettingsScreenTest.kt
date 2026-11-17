package dev.chatui.feature.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.backup.sync.DELETE_REMOTE_CONFIRMATION
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.BackupBackend
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale

    @After
    fun restoreFontScale() {
        RuntimeEnvironment.setFontScale(originalFontScale)
    }

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
            BackupSettingsTestTags.Passphrase,
            BackupSettingsTestTags.Status,
            BackupSettingsTestTags.LastBackup,
            BackupSettingsTestTags.PendingChanges,
            BackupSettingsTestTags.BackupNow,
            BackupSettingsTestTags.RestoreMerge,
            BackupSettingsTestTags.ClientSideStatement,
            BackupSettingsTestTags.PassphraseWarning,
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
        }
        composeRule.onNodeWithTag(BackupSettingsTestTags.Endpoint).assertDoesNotExist()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Details).performScrollTo().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.StorageUsage).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(BackupSettingsTestTags.AdvancedRecovery).performScrollTo().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.RestoreReplace).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(BackupSettingsTestTags.DeleteRemote).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Configure).performScrollTo().performClick()
        listOf(
            BackupSettingsTestTags.Backend, BackupSettingsTestTags.Endpoint, BackupSettingsTestTags.Region,
            BackupSettingsTestTags.Bucket, BackupSettingsTestTags.AccessKey, BackupSettingsTestTags.SecretKey,
            BackupSettingsTestTags.TestConnection, BackupSettingsTestTags.Enable,
        ).forEach { composeRule.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun `setup mode hides management actions and status`() {
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
                    setupMode = true,
                )
            }
        }

        listOf(
            BackupSettingsTestTags.Status,
            BackupSettingsTestTags.BackupNow,
            BackupSettingsTestTags.RestoreMerge,
            BackupSettingsTestTags.RestoreReplace,
            BackupSettingsTestTags.DeleteRemote,
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertDoesNotExist()
        }
        composeRule.onNodeWithTag(BackupSettingsTestTags.Enable)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Passphrase)
            .performScrollTo()
            .assertIsDisplayed()
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

        composeRule.onNodeWithTag(BackupSettingsTestTags.Configure).performScrollTo().performClick()
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

        phrase = DELETE_REMOTE_CONFIRMATION.lowercase()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BackupSettingsTestTags.DeleteConfirmButton).assertIsNotEnabled()
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

        composeRule.onNodeWithTag(BackupSettingsTestTags.Configure).performScrollTo().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Backend).performScrollTo().performClick()

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

        composeRule.onNodeWithTag(BackupSettingsTestTags.Configure).performScrollTo().performClick()
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

    @Test
    fun `unconfigured overview offers setup rather than fabricated status or recovery`() {
        render { state().copy(configured = false, committedBackend = null, status = null) }
        composeRule.onNodeWithTag(BackupSettingsTestTags.Configure).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Backend).assertExists()
        composeRule.onNodeWithTag(BackupSettingsTestTags.BackupNow).assertDoesNotExist()
        composeRule.onNodeWithTag(BackupSettingsTestTags.AdvancedRecovery).assertDoesNotExist()
    }

    @Test
    fun `unknown paused and busy status never fabricates zero or allows duplicate work`() {
        var current by mutableStateOf(state(enabled = false).copy(status = null))
        render { current }
        composeRule.onNodeWithText("Backup paused").assertExists()
        composeRule.onAllNodesWithText("Not available").assertCountEquals(2)
        composeRule.onNodeWithTag(BackupSettingsTestTags.BackupNow).assertIsEnabled()
        current = current.copy(busy = BackupAction.BACKUP)
        composeRule.onNodeWithTag(BackupSettingsTestTags.BackupNow).assertIsNotEnabled()
        composeRule.onNodeWithTag(BackupSettingsTestTags.RestoreMerge).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a failed connection or backup has one error owner`() {
        val error = "Invented storage is unavailable."
        var current by mutableStateOf(
            state().copy(
                error = error,
                testResult = BackupTestResult(false, error),
                status = state().status!!.copy(lastError = error),
            ),
        )
        render { current }
        composeRule.onAllNodesWithText(error).assertCountEquals(1)
        composeRule.onNodeWithTag(BackupSettingsTestTags.TestResult).assertDoesNotExist()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Details).performScrollTo().performClick()
        composeRule.onAllNodesWithText(error).assertCountEquals(1)
        current = current.copy(error = null, testResult = null)
        composeRule.onAllNodesWithText(error).assertCountEquals(1)
    }

    @Test
    fun `dismissing a failed first backup does not permit completing setup`() {
        var current by mutableStateOf(state().copy(firstBackupCompleted = false))
        render(setupMode = true) { current }
        composeRule.onNodeWithTag(BackupSettingsTestTags.SetupComplete).performScrollTo().assertIsNotEnabled()
        current = current.copy(firstBackupCompleted = true)
        composeRule.onNodeWithTag(BackupSettingsTestTags.SetupComplete).assertIsEnabled()
        current = current.copy(configurationEdited = true)
        composeRule.onNodeWithTag(BackupSettingsTestTags.SetupComplete).assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun `large text recovery keeps targets reachable without destructive autofocus`() {
        render(fontScale = 2f) { state(showDeleteDialog = true) }
        composeRule.onNodeWithTag(BackupSettingsTestTags.DeleteConfirmation).performScrollTo()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsNotFocused()
        composeRule.onNodeWithTag(BackupSettingsTestTags.DeleteConfirmButton).assertIsNotEnabled()
    }

    private fun render(
        setupMode: Boolean = false,
        fontScale: Float = 1f,
        current: () -> BackupUiState,
    ) {
        RuntimeEnvironment.setFontScale(fontScale)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChatUiTheme {
                    BackupSettingsScreen(
                        state = current(),
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
                        setupMode = setupMode,
                        onSetupComplete = {},
                    )
                }
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
        configured = true,
        committedBackend = backend,
        status = BackupStatus(
            enabled = enabled,
            pendingChanges = 2,
            storageUsageBytes = 2048,
        ),
        showDeleteDialog = showDeleteDialog,
        deleteConfirmation = deleteConfirmation,
    )
}
