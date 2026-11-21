package dev.chatui.feature.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
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
import dev.chatui.core.designsystem.SecureScreen
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
    const val SetupCancel = "backup-setup-cancel"
    const val SetupComplete = "backup-setup-complete"
    const val Configuration = "backup-configuration"
    const val ConfigurationBack = "backup-configuration-back"
    const val Configure = "backup-configure"
    const val ApplyConfiguration = "backup-apply-configuration"
    const val Details = "backup-details"
    const val AdvancedRecovery = "backup-advanced-recovery"
}

/** Client-side statement shown on the backup screen. */
const val BACKUP_CLIENT_SIDE_STATEMENT: String =
    "Your passphrase stays in memory on this device. Storage credentials are stored " +
        "encrypted and used to authenticate with your storage. Backup contents are " +
        "encrypted before upload."

/** Passphrase-loss warning shown on the backup screen. */
const val BACKUP_PASSPHRASE_WARNING: String =
    "If you lose your passphrase, your backup cannot be recovered. ChatUI cannot " +
        "reset it or read your encrypted backup without it."

/** A separate setup surface; callers must mount it only after an explicit choice. */
@Composable
fun BackupSetupRoute(
    service: BackupSettingsServiceLike,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackupSettingsRoute(
        service = service,
        setupMode = true,
        onSetupCancel = onCancel,
        onSetupComplete = onComplete,
        modifier = modifier.imePadding(),
    )
}

/** Stateful backup settings entry point. */
@Composable
fun BackupSettingsRoute(
    service: BackupSettingsServiceLike,
    modifier: Modifier = Modifier,
    setupMode: Boolean = false,
    onSetupCancel: (() -> Unit)? = null,
    onSetupComplete: (() -> Unit)? = null,
) {
    val viewModel: BackupViewModel = viewModel(
        factory = BackupViewModel.factory(service),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = setupMode && onSetupCancel != null) {
        if (state.busy == null) onSetupCancel?.invoke()
    }

    SecureScreen {
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
            setupMode = setupMode,
            onSetupCancel = onSetupCancel,
            onSetupComplete = onSetupComplete,
            modifier = modifier,
        )
    }
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
    setupMode: Boolean = false,
    onSetupCancel: (() -> Unit)? = null,
    onSetupComplete: (() -> Unit)? = null,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val form = state.form
    val busy = state.busy != null || state.loading
    var configuration by rememberSaveable { mutableStateOf(setupMode) }
    val overviewScroll = rememberScrollState()
    val configurationScroll = rememberScrollState()
    val configurationFocus = remember { FocusRequester() }
    var restoreConfigurationFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = !setupMode && configuration) {
        if (!busy) {
            configuration = false
            restoreConfigurationFocus = true
        }
    }
    LaunchedEffect(configuration, restoreConfigurationFocus) {
        if (!configuration && restoreConfigurationFocus) {
            withFrameNanos { }
            configurationFocus.requestFocus()
            restoreConfigurationFocus = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(if (configuration) configurationScroll else overviewScroll)
                .padding(vertical = dimens.sectionGap)
                .testTag(BackupSettingsTestTags.Root),
        ) {
            if (setupMode) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(dimens.space2),
                ) {
                    Text("Set up encrypted backup", style = Typography.title2, color = colors.fgPrimary)
                    onSetupCancel?.let { cancel ->
                        TextButton(
                            onClick = cancel,
                            enabled = !busy,
                            modifier = Modifier.testTag(BackupSettingsTestTags.SetupCancel),
                        ) { Text("Cancel setup") }
                    }
                }
            } else if (configuration) {
                TextButton(
                    onClick = {
                        configuration = false
                        restoreConfigurationFocus = true
                    },
                    enabled = !busy,
                    modifier = Modifier.padding(horizontal = dimens.screenPadding)
                        .testTag(BackupSettingsTestTags.ConfigurationBack),
                ) { Text("Back to backup overview") }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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

            state.testResult?.takeIf { it.success }?.let { result ->
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
                        .testTag(BackupSettingsTestTags.Message)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(dimens.space3))
            }

            GroupedList {
                if (configuration) {
                    BackupConfiguration(state, busy, onFormChanged, onTestConnection, onEnableChanged)
                } else {
                    BackupOverview(
                        state = state,
                        busy = busy,
                        onConfigure = { configuration = true },
                        configurationFocus = configurationFocus,
                        onFormChanged = onFormChanged,
                        onBackupNow = onBackupNow,
                        onRestoreMerge = onRestoreMerge,
                        onRequestRestoreReplace = onRequestRestoreReplace,
                        onRequestDeleteRemote = onRequestDeleteRemote,
                    )
                }
            }
            if (setupMode) onSetupComplete?.let { complete ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(dimens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(dimens.space2),
                ) {
                    Text(
                        "Enable backup to finish setup, or cancel to continue without it.",
                        style = Typography.footnote,
                    )
                    PrimaryButton(
                        text = "Done",
                        onClick = complete,
                        enabled = state.enabled && state.firstBackupCompleted && !busy &&
                            !state.configurationEdited && state.error == null,
                        modifier = Modifier.fillMaxWidth().testTag(BackupSettingsTestTags.SetupComplete),
                    )
                }
            }
        }
    }

    if (!setupMode && state.showDeleteDialog) {
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

    if (!setupMode && state.showReplaceDialog) {
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
private fun BackupConfiguration(
    state: BackupUiState,
    busy: Boolean,
    onFormChanged: (BackupFormValues) -> Unit,
    onTestConnection: () -> Unit,
    onEnableChanged: (Boolean) -> Unit,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val form = state.form
    GroupedSection(
        title = "Configuration",
        modifier = Modifier.testTag(BackupSettingsTestTags.Configuration),
        footer = "Editing or testing does not save these values. " +
            "Enable backup, or apply changes, to commit and run a backup.",
    ) {
        BackendSelector(
            backend = form.backend,
            onBackendChanged = { onFormChanged(form.copy(backend = it)) },
            enabled = !busy,
        )
    }
    Spacer(Modifier.height(dimens.sectionGap))
    GroupedSection(title = "Storage", footer = storageFooter(form.backend)) {
        StorageFields(form = form, busy = busy, onFormChanged = onFormChanged)
    }
    Spacer(Modifier.height(dimens.sectionGap))
    EncryptionSection(form, busy, onFormChanged)
    Spacer(Modifier.height(dimens.sectionGap))
    GroupedSection(title = "Connection") {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            SecondaryButton(
                text = if (state.busy == BackupAction.TEST) "Testing..." else "Test connection",
                onClick = onTestConnection,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag(BackupSettingsTestTags.TestConnection),
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = dimens.rowMinHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable backup", style = Typography.body, color = colors.fgPrimary)
                    Text(
                        "Save this configuration and run the first backup.",
                        style = Typography.footnote,
                        color = colors.fgMuted,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onEnableChanged,
                    enabled = !busy,
                    modifier = Modifier.testTag(BackupSettingsTestTags.Enable)
                        .semantics { contentDescription = "Enable backup" },
                )
            }
            if (state.enabled && (state.configurationEdited || !state.firstBackupCompleted)) {
                PrimaryButton(
                    text = if (state.configurationEdited) {
                        "Apply configuration and back up"
                    } else {
                        "Retry first backup"
                    },
                    onClick = { onEnableChanged(true) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(BackupSettingsTestTags.ApplyConfiguration),
                )
            }
        }
    }
}

@Composable
private fun EncryptionSection(
    form: BackupFormValues,
    busy: Boolean,
    onFormChanged: (BackupFormValues) -> Unit,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    GroupedSection(title = "Encryption") {
        Column(
            Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            BackupTextField(
                value = form.passphrase,
                onValueChange = { onFormChanged(form.copy(passphrase = it)) },
                label = "Passphrase (required for backup and recovery)",
                tag = BackupSettingsTestTags.Passphrase,
                enabled = !busy,
                password = true,
            )
            Text(
                BACKUP_PASSPHRASE_WARNING,
                style = Typography.footnote,
                color = colors.danger,
                modifier = Modifier.testTag(BackupSettingsTestTags.PassphraseWarning),
            )
            Text(
                BACKUP_CLIENT_SIDE_STATEMENT,
                style = Typography.subhead,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(BackupSettingsTestTags.ClientSideStatement),
            )
        }
    }
}

@Composable
private fun BackupOverview(
    state: BackupUiState,
    busy: Boolean,
    onConfigure: () -> Unit,
    configurationFocus: FocusRequester,
    onFormChanged: (BackupFormValues) -> Unit,
    onBackupNow: () -> Unit,
    onRestoreMerge: () -> Unit,
    onRequestRestoreReplace: () -> Unit,
    onRequestDeleteRemote: () -> Unit,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    var details by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val canRun = !busy && !state.configurationEdited
    if (!state.configured) {
        GroupedSection(
            footer = "Optional backup encrypts conversations on this device before uploading " +
                "to your own storage. Keep your passphrase safe.",
        ) {
            PrimaryButton(
                text = "Set up encrypted backup",
                onClick = onConfigure,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(dimens.cardPadding)
                    .focusRequester(configurationFocus).testTag(BackupSettingsTestTags.Configure),
            )
        }
        return
    }
    GroupedSection(modifier = Modifier.testTag(BackupSettingsTestTags.Status)) {
        Column(
            Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            Text(
                if (state.enabled) "Backup enabled" else "Backup paused",
                style = Typography.headline,
                color = colors.fgPrimary,
            )
            state.committedBackend?.let {
                Text(backendLabel(it), style = Typography.subhead, color = colors.fgSecondary)
            }
            StatusRow(
                "Last backup",
                if (state.status == null) "Not available" else formatBackupTimestamp(state.status.lastBackupAt),
                BackupSettingsTestTags.LastBackup,
            )
            StatusRow(
                "Pending changes", state.status?.pendingChanges?.toString() ?: "Not available",
                BackupSettingsTestTags.PendingChanges,
            )
            PrimaryButton(
                text = if (state.busy == BackupAction.BACKUP) "Backing up..." else "Back up now",
                onClick = onBackupNow,
                enabled = canRun,
                modifier = Modifier.fillMaxWidth().testTag(BackupSettingsTestTags.BackupNow),
            )
            if (state.configurationEdited) Text(
                "Configuration edits are not saved. Apply them in Configuration before backup or recovery.",
                style = Typography.footnote,
            )
            TextButton(
                onClick = onRestoreMerge,
                enabled = canRun,
                modifier = Modifier.heightIn(min = dimens.touchTargetMin)
                    .testTag(BackupSettingsTestTags.RestoreMerge),
            ) { Text(if (state.busy == BackupAction.RESTORE) "Restoring..." else "Restore from backup (merge)") }
        }
        SettingsRow(
            label = "Configuration",
            value = if (state.configurationEdited) "Unsaved edits" else "Storage, test connection, and enable/disable",
            showChevron = true,
            onClick = if (busy) null else onConfigure,
            modifier = Modifier.focusRequester(configurationFocus).testTag(BackupSettingsTestTags.Configure),
            showDivider = false,
        )
    }
    Spacer(Modifier.height(dimens.sectionGap))
    EncryptionSection(state.form, busy, onFormChanged)
    Spacer(Modifier.height(dimens.sectionGap))
    GroupedSection {
        SettingsRow(
            label = "Storage and error details",
            value = state.status?.lastError?.takeUnless { it == state.error }
                ?.let { "Last backup needs attention" },
            onClick = { details = !details },
            showChevron = true,
            modifier = Modifier.testTag(BackupSettingsTestTags.Details)
                .semantics { stateDescription = if (details) "Expanded" else "Collapsed" },
        )
        if (details) Column(Modifier.padding(dimens.cardPadding)) {
            StatusRow(
                "Estimated local backup size",
                state.status?.storageUsageBytes?.let(::formatBackupBytes) ?: "Not available",
                BackupSettingsTestTags.StorageUsage,
            )
            Text("This is an estimate of local data, not remote storage usage or quota.", style = Typography.footnote)
            if (state.status?.lastError != state.error || state.error == null) StatusRow(
                "Last error", state.status?.lastError ?: if (state.status == null) "Not available" else "None",
                BackupSettingsTestTags.LastError,
            )
        }
    }
    Spacer(Modifier.height(dimens.sectionGap))
    GroupedSection(footer = "Restore merges by default. These destructive alternatives require the exact typed phrase.") {
        SettingsRow(
            label = "Advanced recovery",
            value = "Replace local data or delete remote backup",
            onClick = { advanced = !advanced },
            showChevron = true,
            modifier = Modifier.testTag(BackupSettingsTestTags.AdvancedRecovery)
                .semantics { stateDescription = if (advanced) "Expanded" else "Collapsed" },
        )
        if (advanced) Column(Modifier.padding(dimens.cardPadding)) {
            TextButton(
                onClick = onRequestRestoreReplace,
                enabled = canRun,
                modifier = Modifier.heightIn(min = dimens.touchTargetMin)
                    .testTag(BackupSettingsTestTags.RestoreReplace),
            ) { Text("Restore and replace local data", color = colors.danger) }
            TextButton(
                onClick = onRequestDeleteRemote,
                enabled = canRun,
                modifier = Modifier.heightIn(min = dimens.touchTargetMin)
                    .testTag(BackupSettingsTestTags.DeleteRemote),
            ) { Text("Delete remote backup", color = colors.danger) }
        }
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
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = backendLabel(backend)

    Box {
        SettingsRow(
            label = "Backend",
            value = label,
            showChevron = true,
            onClick = if (enabled) ({ expanded = true }) else null,
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
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            autoCorrectEnabled = false,
        ),
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
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
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        cancelFocus.requestFocus()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(dimens.space3),
            ) {
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
                modifier = Modifier.heightIn(min = dimens.touchTargetMin).testTag(confirmTag),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = dimens.touchTargetMin)
                    .focusRequester(cancelFocus).focusProperties { canFocus = true },
            ) { Text("Cancel") }
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
