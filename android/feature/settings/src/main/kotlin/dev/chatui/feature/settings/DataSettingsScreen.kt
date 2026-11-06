package dev.chatui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale

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
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(DataSettingsTestTags.Root),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Data", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Conversations, messages, attachments, provider records, and settings " +
                "are stored on this device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Provider API keys are never included in exports. Import validates the " +
                "archive before writing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.error?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(DataSettingsTestTags.Error),
                )
                TextButton(onClick = onDismissError) { Text("Dismiss") }
            }
        }

        state.status?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(DataSettingsTestTags.Status),
            )
        }

        StorageUsagePanel(state = state)
        HorizontalDivider()
        StoredKeysPanel(
            state = state,
            onRequestRemoveKey = onRequestRemoveKey,
            onCancelRemoveKey = onCancelRemoveKey,
            onConfirmRemoveKey = onConfirmRemoveKey,
            onOpenProviders = onOpenProviders,
        )
        HorizontalDivider()
        ClearAllPanel(
            state = state,
            onConfirmationPhraseChanged = onConfirmationPhraseChanged,
            onClearAll = onClearAll,
        )
        HorizontalDivider()
        ExportPanel(onExportAll = onExportAll)
        HorizontalDivider()
        ImportPanel(
            onImportMerge = onImportMerge,
            onImportReplace = onImportReplace,
        )
        HorizontalDivider()
        BackupPanel(onOpenBackup = onOpenBackup)
    }
}

@Composable
private fun StorageUsagePanel(state: DataSettingsUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DataSettingsTestTags.Usage),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Storage usage", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Sizes are estimated from stored records and attachment metadata. " +
                "Attachment file contents are not read to calculate this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.loading) {
            Text("Calculating storage usage...", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        val usage = state.usage ?: return@Column

        UsageRow("conversations", "Conversations", usage.conversations)
        UsageRow("messages", "Messages", usage.messages)
        UsageRow("attachments", "Attachments", usage.attachments)
        UsageRow("secrets", "Stored keys", usage.secrets)
        UsageRow("providers", "Providers", usage.providers)
        UsageRow("models", "Models", usage.models)
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DataSettingsTestTags.UsageTotal),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Total", style = MaterialTheme.typography.bodyMedium)
            Text(formatBytes(usage.totalBytes), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UsageRow(key: String, label: String, entry: StorageUsageEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DataSettingsTestTags.usageRow(key)),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "${entry.count} · ${formatBytes(entry.bytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StoredKeysPanel(
    state: DataSettingsUiState,
    onRequestRemoveKey: (String) -> Unit,
    onCancelRemoveKey: () -> Unit,
    onConfirmRemoveKey: (String) -> Unit,
    onOpenProviders: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DataSettingsTestTags.Keys),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Stored API keys", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Keys are stored separately from conversations and are never included " +
                "in exports. Removing a key clears its cached metadata and disables sending " +
                "until a new key is added.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.loading) {
            Text("Loading stored keys...", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        val storedKeys = state.keys.filter { it.hasKey }
        if (storedKeys.isEmpty()) {
            Text(
                text = "No API keys are stored on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            storedKeys.forEach { key ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(DataSettingsTestTags.key(key.id)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(key.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = key.presetId +
                                (key.keyLabel?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun ClearAllPanel(
    state: DataSettingsUiState,
    onConfirmationPhraseChanged: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DataSettingsTestTags.Clear),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Clear all data", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "This permanently deletes conversations, messages, attachments, " +
                "providers, models, and stored API keys from this device. Appearance and " +
                "chat preferences are kept. This cannot be undone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
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
            onClick = onClearAll,
            enabled = state.canClear,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(DataSettingsTestTags.ClearButton),
        ) {
            Text(if (state.clearing) "Clearing..." else "Clear all data")
        }
    }
}

@Composable
private fun ExportPanel(onExportAll: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DataSettingsTestTags.Export),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Export", style = MaterialTheme.typography.titleMedium)
        Text(
            text = DATA_EXPORT_WARNING,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Button(
            onClick = onExportAll,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(DataSettingsTestTags.ExportButton),
        ) {
            Text("Export all data (JSON)")
        }
    }
}

@Composable
private fun ImportPanel(
    onImportMerge: () -> Unit,
    onImportReplace: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Import", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Only import chatui.export archives you trust. Imported content is never " +
                "executed, and invalid archives leave local data unchanged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onImportMerge,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(DataSettingsTestTags.ImportMerge),
        ) {
            Text("Import data (merge)")
        }
        TextButton(
            onClick = onImportReplace,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(DataSettingsTestTags.ImportReplace),
        ) {
            Text("Import data (replace)")
        }
        Text(
            text = "Replace deletes local conversations, messages, attachments, providers, " +
                "models, and settings after confirmation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun BackupPanel(onOpenBackup: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DataSettingsTestTags.Backup),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "ChatUI does not back up or sync data in this release. Backup " +
                "configuration arrives in M5.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = onOpenBackup,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(DataSettingsTestTags.BackupButton),
        ) {
            Text("Open backup settings")
        }
    }
}
