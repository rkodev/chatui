package dev.chatui.feature.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chatui.core.backup.sync.DELETE_REMOTE_CONFIRMATION
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.GroupedList
import dev.chatui.core.designsystem.GroupedSection
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.PrimaryButton
import dev.chatui.core.designsystem.SecondaryButton
import dev.chatui.core.designsystem.SettingsRow
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.model.BackupBackend
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Test tags exposed by the backup settings screen. */
object BackupSettingsTestTags {
    const val Root = "backup-settings-root"
    const val Backend = "backup-backend"
    const val Endpoint = "backup-endpoint"
    const val Region = "backup-region"
    const val Bucket = "backup-bucket"
    const val Prefix = "backup-prefix"
    const val AccessKey = "backup-access-key"
    const val SecretKey = "backup-secret-key"
    const val AccountName = "backup-account-name"
    const val AccountKey = "backup-account-key"
    const val SasToken = "backup-sas-token"
    const val ServiceAccountJson = "backup-service-account-json"
    const val AccessToken = "backup-access-token"
    const val DriveFolder = "backup-drive-folder"
    const val WebDavUsername = "backup-webdav-username"
    const val WebDavPassword = "backup-webdav-password"
    const val Passphrase = "backup-passphrase"
    const val TestConnection = "backup-test-connection"
    const val Enable = "backup-enable"
    const val Status = "backup-status"
    const val LastBackup = "backup-last-backup"
    const val PendingChanges = "backup-pending-changes"
    const val StorageUsage = "backup-storage-usage"
    const val LastError = "backup-last-error"
    const val Error = "backup-error"
    const val TestResult = "backup-test-result"
    const val Message = "backup-message"
    const val BackupNow = "backup-now"
    const val RestoreMerge = "backup-restore-merge"
    const val RestoreReplace = "backup-restore-replace"
    const val DeleteRemote = "backup-delete-remote"
    const val DeleteConfirmation = "backup-delete-confirmation"
    const val DeleteConfirmButton = "backup-delete-confirm"
    const val ReplaceConfirmation = "backup-replace-confirmation"
    const val ReplaceConfirmButton = "backup-replace-confirm"
    const val ClientSideStatement = "backup-client-side-statement"
    const val PassphraseWarning = "backup-passphrase-warning"
}

/** Client-side statement shown on the backup screen. */
const val BACKUP_CLIENT_SIDE_STATEMENT: String =
    "Credentials and your passphrase stay on this device. Backup objects are " +
        "encrypted before upload, so the storage provider only sees opaque ciphertext."

/** Passphrase-loss warning shown on the backup screen. */
const val BACKUP_PASSPHRASE_WARNING: String =
    "If you lose your passphrase, your backup cannot be recovered. ChatUI cannot " +
        "reset it or read your encrypted backup without it."

/** Stateful backup settings entry point. */
@Composable
fun BackupSettingsRoute(
    service: BackupSettingsServiceLike,
    modifier: Modifier = Modifier,
) {
    val viewModel: BackupViewModel = viewModel(
        factory = BackupViewModel.factory(service),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackupSettingsScreen(
        state = state,
        onFormChanged = viewModel::onFormChanged,
        onTestConnection = viewModel::onTestConnection,
        onEnableChanged = viewModel::onEnableChanged,
        onBackupNow = viewModel::onBackupNow,
        onRestoreMerge = viewModel::onRestoreMerge,
        onRequestRestoreReplace = viewModel::onRequestRestoreReplace,
        onReplaceConfirmationChanged = viewModel::onReplaceConfirmationChanged,
        onConfirmRestoreReplace = viewModel::onConfirmRestoreReplace,
        onRequestDeleteRemote = viewModel::onRequestDeleteRemote,
        onDeleteConfirmationChanged = viewModel::onDeleteConfirmationChanged,
        onConfirmDeleteRemote = viewModel::onConfirmDeleteRemote,
        onDismissDialogs = viewModel::onDismissDialogs,
        onDismissError = viewModel::onDismissError,
        modifier = modifier,
    )
}

/**
 * Backup configuration, status, and manual actions.
 *
 * Restore merges by default. Replace restore and remote deletion both require
 * the exact typed confirmation phrase.
 */
@Composable
fun BackupSettingsScreen(
    state: BackupUiState,
    onFormChanged: (BackupFormValues) -> Unit,
    onTestConnection: () -> Unit,
    onEnableChanged: (Boolean) -> Unit,
    onBackupNow: () -> Unit,
    onRestoreMerge: () -> Unit,
    onRequestRestoreReplace: () -> Unit,
    onReplaceConfirmationChanged: (String) -> Unit,
    onConfirmRestoreReplace: () -> Unit,
    onRequestDeleteRemote: () -> Unit,
    onDeleteConfirmationChanged: (String) -> Unit,
    onConfirmDeleteRemote: () -> Unit,
    onDismissDialogs: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val form = state.form
    val busy = state.busy != null

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = dimens.sectionGap)
                .testTag(BackupSettingsTestTags.Root),
        ) {
            state.error?.let { error ->
                InlineBanner(
                    severity = BannerSeverity.Error,
                    title = "Backup action failed",
                    body = error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimens.screenPadding)
                        .testTag(BackupSettingsTestTags.Error),
                    action = {
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    },
                )
                Spacer(Modifier.height(dimens.space3))
            }

            state.testResult?.let { result ->
                InlineBanner(
                    severity = if (result.success) {
                        BannerSeverity.Success
                    } else {
                        BannerSeverity.Error
                    },
                    title = if (result.success) "Connection succeeded" else "Connection failed",
                    body = result.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimens.screenPadding)
                        .testTag(BackupSettingsTestTags.TestResult),
                )
                Spacer(Modifier.height(dimens.space3))
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = Typography.footnote,
                    color = colors.fgMuted,
                    modifier = Modifier
                        .padding(horizontal = dimens.screenPadding)
                        .testTag(BackupSettingsTestTags.Message),
                )
                Spacer(Modifier.height(dimens.space3))
            }

            GroupedList {
                GroupedSection(
                    title = "Backend",
                    footer = "Choose the storage provider. Credentials stay on this device " +
                        "and backup objects are encrypted before upload.",
                ) {
                    BackendSelector(
                        backend = form.backend,
                        onBackendChanged = { onFormChanged(form.copy(backend = it)) },
                    )
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Storage",
                    footer = storageFooter(form.backend),
                ) {
                    StorageFields(
                        form = form,
                        busy = busy,
                        onFormChanged = onFormChanged,
                    )
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Encryption",
                    footer = BACKUP_PASSPHRASE_WARNING,
                ) {
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space3),
                    ) {
                        BackupTextField(
                            value = form.passphrase,
                            onValueChange = { onFormChanged(form.copy(passphrase = it)) },
                            label = "Passphrase",
                            tag = BackupSettingsTestTags.Passphrase,
                            enabled = !busy,
                            password = true,
                        )
                        Text(
                            text = BACKUP_PASSPHRASE_WARNING,
                            style = Typography.footnote,
                            color = colors.danger,
                            modifier = Modifier.testTag(
                                BackupSettingsTestTags.PassphraseWarning,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(title = "Connection") {
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space3),
                    ) {
                        SecondaryButton(
                            text = if (state.busy == BackupAction.TEST) {
                                "Testing..."
                            } else {
                                "Test connection"
                            },
                            onClick = onTestConnection,
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(BackupSettingsTestTags.TestConnection),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = dimens.rowMinHeight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable backup",
                                    style = Typography.body,
                                    color = colors.fgPrimary,
                                )
                                Text(
                                    text = "Save this configuration and run the first backup.",
                                    style = Typography.footnote,
                                    color = colors.fgMuted,
                                )
                            }
                            Switch(
                                checked = state.enabled,
                                onCheckedChange = onEnableChanged,
                                enabled = !busy,
                                modifier = Modifier.testTag(BackupSettingsTestTags.Enable),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Status",
                    modifier = Modifier.testTag(BackupSettingsTestTags.Status),
                ) {
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space2),
                    ) {
                        StatusRow(
                            label = "Last backup",
                            value = formatBackupTimestamp(state.status?.lastBackupAt),
                            tag = BackupSettingsTestTags.LastBackup,
                        )
                        StatusRow(
                            label = "Pending changes",
                            value = (state.status?.pendingChanges ?: 0).toString(),
                            tag = BackupSettingsTestTags.PendingChanges,
                        )
                        StatusRow(
                            label = "Storage usage",
                            value = formatBackupBytes(state.status?.storageUsageBytes ?: 0),
                            tag = BackupSettingsTestTags.StorageUsage,
                        )
                        StatusRow(
                            label = "Last error",
                            value = state.error ?: state.status?.lastError ?: "None",
                            tag = BackupSettingsTestTags.LastError,
                        )
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Actions",
                    footer = "Restore merges remote data into this device by default. " +
                        "Replace and delete require a typed confirmation.",
                ) {
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space3),
                    ) {
                        PrimaryButton(
                            text = if (state.busy == BackupAction.BACKUP) {
                                "Backing up..."
                            } else {
                                "Back up now"
                            },
                            onClick = onBackupNow,
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(BackupSettingsTestTags.BackupNow),
                        )
                        SecondaryButton(
                            text = if (state.busy == BackupAction.RESTORE) {
                                "Restoring..."
                            } else {
                                "Restore from backup (merge)"
                            },
                            onClick = onRestoreMerge,
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(BackupSettingsTestTags.RestoreMerge),
                        )
                        SecondaryButton(
                            text = "Restore and replace local data",
                            onClick = onRequestRestoreReplace,
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(BackupSettingsTestTags.RestoreReplace),
                        )
                        TextButton(
                            onClick = onRequestDeleteRemote,
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = dimens.touchTargetMin)
                                .testTag(BackupSettingsTestTags.DeleteRemote),
                        ) {
                            Text(
                                text = "Delete remote backup",
                                color = colors.danger,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(title = "Privacy") {
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space2),
                    ) {
                        Text(
                            text = BACKUP_CLIENT_SIDE_STATEMENT,
                            style = Typography.subhead,
                            color = colors.fgSecondary,
                            modifier = Modifier.testTag(
                                BackupSettingsTestTags.ClientSideStatement,
                            ),
                        )
                    }
                }
            }
        }
    }

    if (state.showDeleteDialog) {
        ConfirmationDialog(
            title = "Delete remote backup",
            body = "This deletes every encrypted backup object in the configured " +
                "bucket prefix. Local conversations on this device are kept.",
            phrase = state.deleteConfirmation,
            expected = DELETE_REMOTE_CONFIRMATION,
            confirmLabel = "Delete remote backup",
            onPhraseChanged = onDeleteConfirmationChanged,
            onConfirm = onConfirmDeleteRemote,
            onDismiss = onDismissDialogs,
            fieldTag = BackupSettingsTestTags.DeleteConfirmation,
            confirmTag = BackupSettingsTestTags.DeleteConfirmButton,
            enabled = state.canDeleteRemote,
        )
    }

    if (state.showReplaceDialog) {
        ConfirmationDialog(
            title = "Replace local data",
            body = "This clears local conversations and restores the remote backup. " +
                "This cannot be undone.",
            phrase = state.replaceConfirmation,
            expected = RESTORE_REPLACE_CONFIRMATION,
            confirmLabel = "Replace local data",
            onPhraseChanged = onReplaceConfirmationChanged,
            onConfirm = onConfirmRestoreReplace,
            onDismiss = onDismissDialogs,
            fieldTag = BackupSettingsTestTags.ReplaceConfirmation,
            confirmTag = BackupSettingsTestTags.ReplaceConfirmButton,
            enabled = state.canReplace,
        )
    }
}

@Composable
private fun StorageFields(
    form: BackupFormValues,
    busy: Boolean,
    onFormChanged: (BackupFormValues) -> Unit,
) {
    val dimens = LocalDimens.current
    Column(
        modifier = Modifier.padding(dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.space3),
    ) {
        when (form.backend) {
            BackupBackend.S3 -> {
                BackupTextField(
                    value = form.endpoint,
                    onValueChange = { onFormChanged(form.copy(endpoint = it)) },
                    label = "Endpoint",
                    tag = BackupSettingsTestTags.Endpoint,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.region,
                    onValueChange = { onFormChanged(form.copy(region = it)) },
                    label = "Region",
                    tag = BackupSettingsTestTags.Region,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.bucket,
                    onValueChange = { onFormChanged(form.copy(bucket = it)) },
                    label = "Bucket",
                    tag = BackupSettingsTestTags.Bucket,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.prefix,
                    onValueChange = { onFormChanged(form.copy(prefix = it)) },
                    label = "Prefix (optional)",
                    tag = BackupSettingsTestTags.Prefix,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.accessKeyId,
                    onValueChange = { onFormChanged(form.copy(accessKeyId = it)) },
                    label = "Access key ID",
                    tag = BackupSettingsTestTags.AccessKey,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.secretAccessKey,
                    onValueChange = { onFormChanged(form.copy(secretAccessKey = it)) },
                    label = "Secret access key",
                    tag = BackupSettingsTestTags.SecretKey,
                    enabled = !busy,
                    password = true,
                )
            }

            BackupBackend.AZURE_BLOB -> {
                BackupTextField(
                    value = form.accountName,
                    onValueChange = { onFormChanged(form.copy(accountName = it)) },
                    label = "Account name",
                    tag = BackupSettingsTestTags.AccountName,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.accountKey,
                    onValueChange = { onFormChanged(form.copy(accountKey = it)) },
                    label = "Account key (optional with SAS)",
                    tag = BackupSettingsTestTags.AccountKey,
                    enabled = !busy,
                    password = true,
                )
                BackupTextField(
                    value = form.sasToken,
                    onValueChange = { onFormChanged(form.copy(sasToken = it)) },
                    label = "SAS token (optional with account key)",
                    tag = BackupSettingsTestTags.SasToken,
                    enabled = !busy,
                    password = true,
                )
                BackupTextField(
                    value = form.bucket,
                    onValueChange = { onFormChanged(form.copy(bucket = it)) },
                    label = "Container",
                    tag = BackupSettingsTestTags.Bucket,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.endpoint,
                    onValueChange = { onFormChanged(form.copy(endpoint = it)) },
                    label = "Endpoint (optional)",
                    tag = BackupSettingsTestTags.Endpoint,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.prefix,
                    onValueChange = { onFormChanged(form.copy(prefix = it)) },
                    label = "Prefix (optional)",
                    tag = BackupSettingsTestTags.Prefix,
                    enabled = !busy,
                )
            }

            BackupBackend.GCS -> {
                BackupTextField(
                    value = form.bucket,
                    onValueChange = { onFormChanged(form.copy(bucket = it)) },
                    label = "Bucket",
                    tag = BackupSettingsTestTags.Bucket,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.serviceAccountJson,
                    onValueChange = { onFormChanged(form.copy(serviceAccountJson = it)) },
                    label = "Service account JSON",
                    tag = BackupSettingsTestTags.ServiceAccountJson,
                    enabled = !busy,
                    password = true,
                )
                BackupTextField(
                    value = form.prefix,
                    onValueChange = { onFormChanged(form.copy(prefix = it)) },
                    label = "Prefix (optional)",
                    tag = BackupSettingsTestTags.Prefix,
                    enabled = !busy,
                )
            }

            BackupBackend.GOOGLE_DRIVE -> {
                BackupTextField(
                    value = form.accessToken,
                    onValueChange = { onFormChanged(form.copy(accessToken = it)) },
                    label = "OAuth access token",
                    tag = BackupSettingsTestTags.AccessToken,
                    enabled = !busy,
                    password = true,
                )
                BackupTextField(
                    value = form.driveFolderId,
                    onValueChange = { onFormChanged(form.copy(driveFolderId = it)) },
                    label = "Folder ID (blank for app data folder)",
                    tag = BackupSettingsTestTags.DriveFolder,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.prefix,
                    onValueChange = { onFormChanged(form.copy(prefix = it)) },
                    label = "Prefix (optional)",
                    tag = BackupSettingsTestTags.Prefix,
                    enabled = !busy,
                )
            }

            BackupBackend.WEBDAV -> {
                BackupTextField(
                    value = form.endpoint,
                    onValueChange = { onFormChanged(form.copy(endpoint = it)) },
                    label = "WebDAV URL",
                    tag = BackupSettingsTestTags.Endpoint,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.webdavUsername,
                    onValueChange = { onFormChanged(form.copy(webdavUsername = it)) },
                    label = "Username",
                    tag = BackupSettingsTestTags.WebDavUsername,
                    enabled = !busy,
                )
                BackupTextField(
                    value = form.webdavPassword,
                    onValueChange = { onFormChanged(form.copy(webdavPassword = it)) },
                    label = "Password",
                    tag = BackupSettingsTestTags.WebDavPassword,
                    enabled = !busy,
                    password = true,
                )
                BackupTextField(
                    value = form.prefix,
                    onValueChange = { onFormChanged(form.copy(prefix = it)) },
                    label = "Prefix (optional)",
                    tag = BackupSettingsTestTags.Prefix,
                    enabled = !busy,
                )
            }
        }
    }
}

private fun storageFooter(backend: BackupBackend): String = when (backend) {
    BackupBackend.S3 -> "The endpoint, region, bucket, and prefix identify where " +
        "encrypted backup objects are stored."
    BackupBackend.AZURE_BLOB -> "Use a shared account key or a SAS token. Objects are " +
        "stored as block blobs in the container."
    BackupBackend.GCS -> "Use a service account JSON key. The adapter exchanges a signed " +
        "JWT for a short-lived OAuth2 token."
    BackupBackend.GOOGLE_DRIVE -> "Use an OAuth access token. Leave the folder blank to " +
        "use the app data folder."
    BackupBackend.WEBDAV -> "Use the collection URL and Basic credentials. WebDAV has " +
        "weaker consistency, so writes are re-read before they are trusted."
}

@Composable
private fun BackendSelector(
    backend: BackupBackend,
    onBackendChanged: (BackupBackend) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = backendLabel(backend)

    Box {
        SettingsRow(
            label = "Backend",
            value = label,
            showChevron = true,
            onClick = { expanded = true },
            modifier = Modifier.testTag(BackupSettingsTestTags.Backend),
            showDivider = false,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("S3-compatible") },
                onClick = {
                    expanded = false
                    onBackendChanged(BackupBackend.S3)
                },
            )
            listOf(
                "Azure Blob" to BackupBackend.AZURE_BLOB,
                "Google Cloud Storage" to BackupBackend.GCS,
                "Google Drive" to BackupBackend.GOOGLE_DRIVE,
                "WebDAV" to BackupBackend.WEBDAV,
            ).forEach { (name, value) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onBackendChanged(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun BackupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    enabled: Boolean,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (password) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

@Composable
private fun StatusRow(label: String, value: String, tag: String) {
    val colors = LocalChatUiColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = Typography.body,
            color = colors.fgPrimary,
        )
        Text(
            text = value,
            style = Typography.body,
            color = colors.fgSecondary,
        )
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    phrase: String,
    expected: String,
    confirmLabel: String,
    onPhraseChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    fieldTag: String,
    confirmTag: String,
    enabled: Boolean,
) {
    val dimens = LocalDimens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
                Text(text = body, style = Typography.subhead)
                OutlinedTextField(
                    value = phrase,
                    onValueChange = onPhraseChanged,
                    label = { Text("Type $expected to confirm") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(fieldTag),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = enabled,
                modifier = Modifier.testTag(confirmTag),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun backendLabel(backend: BackupBackend): String = when (backend) {
    BackupBackend.S3 -> "S3-compatible"
    BackupBackend.AZURE_BLOB -> "Azure Blob"
    BackupBackend.GCS -> "Google Cloud Storage"
    BackupBackend.GOOGLE_DRIVE -> "Google Drive"
    BackupBackend.WEBDAV -> "WebDAV"
}

private fun formatBackupTimestamp(value: Instant?): String {
    if (value == null) return "Never"
    return DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(value)
}

private fun formatBackupBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1

    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }

    return if (value >= 10) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
