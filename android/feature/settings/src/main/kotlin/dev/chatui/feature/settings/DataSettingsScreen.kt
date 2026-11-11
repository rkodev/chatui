package dev.chatui.feature.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.ChatUiSnackbarHost
import dev.chatui.core.designsystem.GroupedList
import dev.chatui.core.designsystem.GroupedSection
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.LocalSnackbarHostState
import dev.chatui.core.designsystem.SettingsRow
import dev.chatui.core.designsystem.Skeleton
import dev.chatui.core.designsystem.Typography
import java.util.Locale
import kotlinx.coroutines.launch

/** Test tags exposed by the data settings screen. */
object DataSettingsTestTags {
    const val Root = "data-settings-root"
    const val Usage = "data-usage"
    const val UsageTotal = "data-usage-total"
    const val Keys = "data-keys"
    const val Clear = "data-clear"
    const val ClearConfirmation = "data-clear-confirmation"
    const val ClearButton = "data-clear-button"
    const val Export = "data-export"
    const val ExportButton = "data-export-button"
    const val ImportMerge = "data-import-merge"
    const val ImportReplace = "data-import-replace"
    const val Backup = "data-backup"
    const val BackupButton = "data-backup-button"
    const val Error = "data-error"
    const val Status = "data-status"

    fun usageRow(key: String): String = "data-usage-$key"

    fun key(id: String): String = "data-key-$id"

    fun removeKey(id: String): String = "data-remove-key-$id"

    fun confirmRemoveKey(id: String): String = "data-confirm-remove-key-$id"
}

/** Warning shown before any export is written. */
const val DATA_EXPORT_WARNING: String =
    "Exports are plaintext JSON. Anyone with the file can read your conversations. " +
        "Provider API keys are never included."

/** Format a byte count for the storage usage table. */
fun formatBytes(bytes: Long): String {
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

/**
 * Data settings: storage usage, clear-all, per-key deletion, export/import
 * entry points, and the backup placeholder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    state: DataSettingsUiState,
    onConfirmationPhraseChanged: (String) -> Unit,
    onClearAll: () -> Unit,
    onRequestRemoveKey: (String) -> Unit,
    onCancelRemoveKey: () -> Unit,
    onConfirmRemoveKey: (String) -> Unit,
    onDismissError: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenBackup: () -> Unit,
    onExportAll: () -> Unit,
    onImportMerge: () -> Unit,
    onImportReplace: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = LocalSnackbarHostState.current,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()
    val host = snackbarHostState ?: remember { SnackbarHostState() }
    var showClearSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.status) {
        state.status?.let { host.showSnackbar(it) }
    }
    LaunchedEffect(state.error) {
        state.error?.let { host.showSnackbar(it) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = dimens.sectionGap)
                .testTag(DataSettingsTestTags.Root),
        ) {
            state.error?.let { error ->
                InlineBanner(
                    severity = BannerSeverity.Error,
                    title = "Data operation failed",
                    body = error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimens.screenPadding)
                        .testTag(DataSettingsTestTags.Error),
                    action = {
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    },
                )
                Spacer(Modifier.height(dimens.space3))
            }

            state.status?.let { status ->
                Text(
                    text = status,
                    style = Typography.footnote,
                    color = colors.fgMuted,
                    modifier = Modifier
                        .padding(horizontal = dimens.screenPadding)
                        .testTag(DataSettingsTestTags.Status),
                )
                Spacer(Modifier.height(dimens.space3))
            }

            GroupedList {
                GroupedSection(
                    title = "Storage",
                    modifier = Modifier.testTag(DataSettingsTestTags.Usage),
                    footer = "Sizes are estimated from stored records and attachment " +
                        "metadata. Attachment file contents are not read to calculate this.",
                ) {
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space2),
                    ) {
                        if (state.loading) {
                            repeat(4) {
                                Skeleton(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(20.dp),
                                )
                            }
                        } else {
                            val usage = state.usage
                            if (usage != null) {
                                UsageRow("conversations", "Conversations", usage.conversations)
                                UsageRow("messages", "Messages", usage.messages)
                                UsageRow("attachments", "Attachments", usage.attachments)
                                UsageRow("secrets", "Stored keys", usage.secrets)
                                UsageRow("providers", "Providers", usage.providers)
                                UsageRow("models", "Models", usage.models)
                                HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag(DataSettingsTestTags.UsageTotal),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "Total",
                                        style = Typography.body,
                                        color = colors.fgPrimary,
                                    )
                                    Text(
                                        text = formatBytes(usage.totalBytes),
                                        style = Typography.body,
                                        color = colors.fgSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Stored API keys",
                    modifier = Modifier.testTag(DataSettingsTestTags.Keys),
                    footer = "Keys are stored separately from conversations and are never " +
                        "included in exports. Removing a key clears its cached metadata and " +
                        "disables sending until a new key is added.",
                ) {
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space2),
                    ) {
                        if (state.loading) {
                            repeat(2) {
                                Skeleton(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(20.dp),
                                )
                            }
                        } else {
                            val storedKeys = state.keys.filter { it.hasKey }
                            if (storedKeys.isEmpty()) {
                                Text(
                                    text = "No API keys are stored on this device.",
                                    style = Typography.body,
                                    color = colors.fgMuted,
                                )
                            } else {
                                storedKeys.forEach { key ->
                                    StoredKeyRow(
                                        key = key,
                                        state = state,
                                        onRequestRemoveKey = onRequestRemoveKey,
                                        onCancelRemoveKey = onCancelRemoveKey,
                                        onConfirmRemoveKey = onConfirmRemoveKey,
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = onOpenProviders,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Manage providers and keys")
                        }
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                DangerZone(
                    onOpenClearSheet = { showClearSheet = true },
                )
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Export",
                    modifier = Modifier.testTag(DataSettingsTestTags.Export),
                    footer = DATA_EXPORT_WARNING,
                ) {
                    SettingsRow(
                        label = "Export all data (JSON)",
                        icon = Icons.Rounded.FileUpload,
                        showChevron = true,
                        onClick = {
                            onExportAll()
                            scope.launch {
                                host.showSnackbar("Export started")
                            }
                        },
                        modifier = Modifier.testTag(DataSettingsTestTags.ExportButton),
                        showDivider = false,
                    )
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Import",
                    footer = "Only import chatui.export archives you trust. Imported content " +
                        "is never executed, and invalid archives leave local data unchanged.",
                ) {
                    SettingsRow(
                        label = "Import data (merge)",
                        icon = Icons.Rounded.FileDownload,
                        showChevron = true,
                        onClick = {
                            onImportMerge()
                            scope.launch {
                                host.showSnackbar("Import started")
                            }
                        },
                        modifier = Modifier.testTag(DataSettingsTestTags.ImportMerge),
                    )
                    SettingsRow(
                        label = "Import data (replace)",
                        icon = Icons.Rounded.FileDownload,
                        showChevron = true,
                        onClick = {
                            onImportReplace()
                            scope.launch {
                                host.showSnackbar("Import started")
                            }
                        },
                        modifier = Modifier.testTag(DataSettingsTestTags.ImportReplace),
                        showDivider = false,
                    )
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Backup",
                    modifier = Modifier.testTag(DataSettingsTestTags.Backup),
                    footer = "ChatUI does not back up or sync data in this release. Backup " +
                        "configuration arrives in M5.",
                ) {
                    SettingsRow(
                        label = "Open backup settings",
                        icon = Icons.Rounded.Backup,
                        showChevron = true,
                        onClick = onOpenBackup,
                        modifier = Modifier.testTag(DataSettingsTestTags.BackupButton),
                        showDivider = false,
                    )
                }
            }
        }
        if (snackbarHostState == null) {
            ChatUiSnackbarHost(
                hostState = host,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showClearSheet) {
        ModalBottomSheet(onDismissRequest = { showClearSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(dimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(dimens.space3),
            ) {
                Text(
                    text = "Clear all data",
                    style = Typography.title3,
                    color = colors.danger,
                )
                Text(
                    text = "This permanently deletes conversations, messages, attachments, " +
                        "providers, models, and stored API keys from this device. Appearance " +
                        "and chat preferences are kept. This cannot be undone.",
                    style = Typography.subhead,
                    color = colors.fgSecondary,
                )
                OutlinedTextField(
                    value = state.confirmationPhrase,
                    onValueChange = onConfirmationPhraseChanged,
                    label = { Text("Type $CLEAR_ALL_CONFIRMATION to confirm") },
                    singleLine = true,
                    enabled = !state.clearing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(DataSettingsTestTags.ClearConfirmation),
                )
                Button(
                    onClick = {
                        onClearAll()
                        showClearSheet = false
                    },
                    enabled = state.canClear,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(DataSettingsTestTags.ClearButton),
                ) {
                    Text(if (state.clearing) "Clearing..." else "Clear all data")
                }
                TextButton(
                    onClick = { showClearSheet = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun DangerZone(onOpenClearSheet: () -> Unit) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding),
    ) {
        Text(
            text = "DANGER ZONE",
            style = Typography.footnote,
            color = colors.danger,
            modifier = Modifier.padding(
                start = dimens.cardPadding,
                bottom = dimens.space2,
            ),
        )
        Surface(
            color = colors.danger.copy(alpha = 0.12f),
            shape = RoundedCornerShape(dimens.radiusMd),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                SettingsRow(
                    label = "Clear all data",
                    icon = Icons.Rounded.Delete,
                    showChevron = true,
                    onClick = onOpenClearSheet,
                    modifier = Modifier.testTag(DataSettingsTestTags.Clear),
                    showDivider = false,
                )
                Text(
                    text = "This permanently deletes conversations, messages, attachments, " +
                        "providers, models, and stored API keys from this device. Appearance " +
                        "and chat preferences are kept. This cannot be undone.",
                    style = Typography.footnote,
                    color = colors.danger,
                    modifier = Modifier.padding(
                        start = dimens.cardPadding,
                        end = dimens.cardPadding,
                        bottom = dimens.cardPadding,
                    ),
                )
            }
        }
    }
}

@Composable
private fun UsageRow(key: String, label: String, entry: StorageUsageEntry) {
    val colors = LocalChatUiColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DataSettingsTestTags.usageRow(key)),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = Typography.body,
            color = colors.fgPrimary,
        )
        Text(
            text = "${entry.count} · ${formatBytes(entry.bytes)}",
            style = Typography.body,
            color = colors.fgSecondary,
        )
    }
}

@Composable
private fun StoredKeyRow(
    key: ProviderKeySummary,
    state: DataSettingsUiState,
    onRequestRemoveKey: (String) -> Unit,
    onCancelRemoveKey: () -> Unit,
    onConfirmRemoveKey: (String) -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DataSettingsTestTags.key(key.id)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.rowMinHeight)
                .padding(vertical = dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = key.label,
                    style = Typography.body,
                    color = colors.fgPrimary,
                )
                Text(
                    text = key.presetId + (key.keyLabel?.let { " · $it" } ?: ""),
                    style = Typography.footnote,
                    color = colors.fgMuted,
                )
            }

            if (state.confirmKeyId == key.id) {
                TextButton(
                    onClick = { onConfirmRemoveKey(key.id) },
                    enabled = state.removingKeyId == null,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(DataSettingsTestTags.confirmRemoveKey(key.id)),
                ) {
                    Text(
                        if (state.removingKeyId == key.id) {
                            "Removing..."
                        } else {
                            "Confirm removal"
                        },
                    )
                }
                TextButton(
                    onClick = onCancelRemoveKey,
                    enabled = state.removingKeyId == null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            } else {
                TextButton(
                    onClick = { onRequestRemoveKey(key.id) },
                    enabled = state.removingKeyId == null,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(DataSettingsTestTags.removeKey(key.id)),
                ) {
                    Text("Remove key")
                }
            }
        }
        HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
    }
}
