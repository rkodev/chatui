package dev.chatui.feature.settings

import androidx.compose.runtime.Composable
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
) {
    val viewModel: DataSettingsViewModel = viewModel(
        factory = DataSettingsViewModel.factory(service),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    DataSettingsScreen(
        state = state,
        onConfirmationPhraseChanged = viewModel::onConfirmationPhraseChanged,
        onClearAll = viewModel::onClearAll,
        onRequestRemoveKey = viewModel::onRequestRemoveKey,
        onCancelRemoveKey = viewModel::onCancelRemoveKey,
        onConfirmRemoveKey = viewModel::onConfirmRemoveKey,
        onDismissError = viewModel::onDismissError,
        onOpenProviders = onOpenProviders,
        onOpenBackup = onOpenBackup,
        onExportAll = onExportAll,
        onImportMerge = onImportMerge,
        onImportReplace = onImportReplace,
        modifier = modifier,
    )
}
