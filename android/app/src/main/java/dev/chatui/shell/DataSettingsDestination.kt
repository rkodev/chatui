package dev.chatui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
import dev.chatui.feature.settings.DataFileAction
import dev.chatui.feature.settings.DataFileOperation
import dev.chatui.feature.settings.DataFilePhase
import dev.chatui.feature.settings.SettingsRoute
import dev.chatui.feature.settings.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DataDocumentState : ViewModel() {
    var operation by mutableStateOf(DataFileOperation())
        private set

    fun start(action: DataFileAction): Boolean {
        if (operation.active) return false
        operation = DataFileOperation(action, DataFilePhase.PICKING)
        return true
    }

    fun advance(phase: DataFilePhase) {
        operation = operation.copy(phase = phase, error = null)
    }

    fun fail(error: Throwable) {
        operation = operation.copy(
            phase = DataFilePhase.FAILED,
            error = error.message ?: "Could not complete the data operation. Try again.",
        )
    }

    fun clearResult() {
        if (!operation.active) operation = DataFileOperation()
    }
}

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

    var showExportWarning by rememberSaveable { mutableStateOf(false) }
    var showReplaceConfirmation by rememberSaveable { mutableStateOf(false) }
    val documents: DataDocumentState = viewModel()

    val exportLauncher = rememberExportDocumentLauncher(
        artifact = { withContext(Dispatchers.IO) { exportService.exportAll() } },
        onError = documents::fail,
        onPreparing = { documents.advance(DataFilePhase.PREPARING) },
        onPickerOpened = { documents.advance(DataFilePhase.PICKING) },
        onWriting = { documents.advance(DataFilePhase.WORKING) },
        onSuccess = { documents.advance(DataFilePhase.SUCCEEDED) },
        onCancelled = { documents.advance(DataFilePhase.CANCELLED) },
        operationScope = documents.viewModelScope,
    )
    val importLauncher = rememberImportDocumentLauncher(
        onDocument = { uri ->
            val mode = if (documents.operation.action == DataFileAction.IMPORT_REPLACE) {
                ImportMode.REPLACE
            } else {
                ImportMode.MERGE
            }
            withContext(Dispatchers.IO) {
                val bytes = importReader.readBytes(uri)
                importService.importBytes(bytes, ImportOptions(mode = mode))
            }
        },
        onError = documents::fail,
        onReading = { documents.advance(DataFilePhase.WORKING) },
        onSuccess = { documents.advance(DataFilePhase.SUCCEEDED) },
        onCancelled = { documents.advance(DataFilePhase.CANCELLED) },
        operationScope = documents.viewModelScope,
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
        onExportAll = {
            if (documents.start(DataFileAction.EXPORT)) showExportWarning = true
        },
        onImportMerge = {
            if (documents.start(DataFileAction.IMPORT_MERGE)) importLauncher.launch()
        },
        onImportReplace = {
            if (documents.start(DataFileAction.IMPORT_REPLACE)) showReplaceConfirmation = true
        },
        fileOperation = documents.operation,
        onDismissFileError = documents::clearResult,
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
                if (showExportWarning) {
                    showExportWarning = false
                    exportLauncher.launch()
                }
            },
            onDismiss = {
                showExportWarning = false
                documents.advance(DataFilePhase.CANCELLED)
            },
        )
    }

    if (showReplaceConfirmation) {
        ImportReplaceConfirmationDialog(
            onConfirm = {
                if (showReplaceConfirmation) {
                    showReplaceConfirmation = false
                    importLauncher.launch()
                }
            },
            onDismiss = {
                showReplaceConfirmation = false
                documents.advance(DataFilePhase.CANCELLED)
            },
        )
    }
}
