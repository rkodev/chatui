package dev.chatui.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import dagger.hilt.android.EntryPointAccessors
import dev.chatui.core.database.import.ImportMode
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.feature.conversations.export.ExportEntryPoint
import dev.chatui.feature.conversations.export.ExportPlaintextWarningDialog
import dev.chatui.feature.conversations.export.rememberExportDocumentLauncher
import dev.chatui.feature.conversations.importer.ImportDocumentReader
import dev.chatui.feature.conversations.importer.ImportEntryPoint
import dev.chatui.feature.conversations.importer.ImportOptions
import dev.chatui.feature.conversations.importer.ImportReplaceConfirmationDialog
import dev.chatui.feature.conversations.importer.rememberImportDocumentLauncher
import dev.chatui.feature.providers.ProviderManagementService
import dev.chatui.feature.settings.DataControlsService
import dev.chatui.feature.settings.SettingsRoute
import dev.chatui.feature.settings.SettingsSection

/**
 * Data settings destination with export/import SAF wiring.
 *
 * The settings feature owns usage, clear-all, and per-key deletion. The shell
 * owns the document pickers and the plaintext/replace confirmations so the
 * settings feature does not depend on the conversations feature.
 */
@Composable
fun DataSettingsDestination(
    navController: NavHostController,
    settingsStore: SettingsDataStore,
    providerService: ProviderManagementService,
    dataControlsService: DataControlsService,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exportService = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ExportEntryPoint::class.java,
        ).exportService()
    }
    val importService = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ImportEntryPoint::class.java,
        ).importService()
    }
    val importReader = remember(context) {
        ImportDocumentReader(context.contentResolver)
    }

    var showExportWarning by remember { mutableStateOf(false) }
    var showReplaceConfirmation by remember { mutableStateOf(false) }
    var pendingImportMode by remember { mutableStateOf(ImportMode.MERGE) }
    var error by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberExportDocumentLauncher(
        artifact = { exportService.exportAll() },
        onError = { error = it.message ?: "Export failed." },
    )
    val importLauncher = rememberImportDocumentLauncher(
        onDocument = { uri ->
            val bytes = importReader.readBytes(uri)
            importService.importBytes(bytes, ImportOptions(mode = pendingImportMode))
        },
        onError = { error = it.message ?: "Import failed." },
    )

    SettingsRoute(
        section = SettingsSection.DATA,
        settingsStore = settingsStore,
        service = providerService,
        dataControlsService = dataControlsService,
        onOpenSection = { section ->
            navController.navigate(SettingsDestination.routeFor(section)) {
                launchSingleTop = true
            }
        },
        onOpenProviders = {
            navController.navigate(ShellDestination.Providers.route) {
                launchSingleTop = true
            }
        },
        onExportAll = { showExportWarning = true },
        onImportMerge = {
            pendingImportMode = ImportMode.MERGE
            importLauncher.launch()
        },
        onImportReplace = {
            pendingImportMode = ImportMode.REPLACE
            showReplaceConfirmation = true
        },
        onOpenBackup = {
            navController.navigate(SettingsDestination.BACKUP) {
                launchSingleTop = true
            }
        },
        modifier = modifier,
    )

    if (showExportWarning) {
        ExportPlaintextWarningDialog(
            onConfirm = {
                showExportWarning = false
                exportLauncher.launch()
            },
            onDismiss = { showExportWarning = false },
        )
    }

    if (showReplaceConfirmation) {
        ImportReplaceConfirmationDialog(
            onConfirm = {
                showReplaceConfirmation = false
                importLauncher.launch()
            },
            onDismiss = { showReplaceConfirmation = false },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Data operation failed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("OK") }
            },
        )
    }
}
