package dev.chatui.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Stateful data settings entry point.
 *
 * The ViewModel owns usage loading, clear-all confirmation, and per-key
 * deletion. Export/import and backup navigation are supplied by the shell so
 * this feature does not depend on the conversations feature.
 */
@Composable
fun DataSettingsRoute(
    service: DataControls,
    onOpenProviders: () -> Unit,
    onOpenBackup: () -> Unit,
    onExportAll: () -> Unit,
    onImportMerge: () -> Unit,
    onImportReplace: () -> Unit,
    modifier: Modifier = Modifier,
    fileOperation: DataFileOperation = DataFileOperation(),
    onDismissFileError: () -> Unit = {},
) {
    val viewModel: DataSettingsViewModel = viewModel(
        factory = DataSettingsViewModel.factory(service),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(fileOperation.phase) {
        if (fileOperation.phase == DataFilePhase.SUCCEEDED) viewModel.refresh()
    }

    DataSettingsScreen(
        state = state.copy(fileOperation = fileOperation),
        onConfirmationPhraseChanged = viewModel::onConfirmationPhraseChanged,
        onClearAll = {
            if (!fileOperation.active) {
                onDismissFileError()
                viewModel.onClearAll()
            }
        },
        onRequestRemoveKey = {
            if (!fileOperation.active) {
                onDismissFileError()
                viewModel.onRequestRemoveKey(it)
            }
        },
        onCancelRemoveKey = viewModel::onCancelRemoveKey,
        onConfirmRemoveKey = {
            if (!fileOperation.active) {
                onDismissFileError()
                viewModel.onConfirmRemoveKey(it)
            }
        },
        onDismissError = {
            if (fileOperation.error != null) onDismissFileError() else viewModel.onDismissError()
        },
        onOpenProviders = onOpenProviders,
        onOpenBackup = onOpenBackup,
        onExportAll = {
            viewModel.onStartFileOperation()
            onExportAll()
        },
        onImportMerge = {
            viewModel.onStartFileOperation()
            onImportMerge()
        },
        onImportReplace = {
            viewModel.onStartFileOperation()
            onImportReplace()
        },
        modifier = modifier,
    )
}
