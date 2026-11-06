package dev.chatui.feature.providers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Stateful provider list entry point.
 *
 * The shell resolves [ProviderManagementService] from the singleton graph and
 * passes it in so navigation tests can render the route without Hilt.
 */
@Composable
fun ProviderListRoute(
    service: ProviderManagementService,
    onOpenProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProviderListViewModel = viewModel(
        factory = ProviderListViewModel.factory(service),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    ProviderListScreen(
        state = state,
        onOpenProvider = onOpenProvider,
        onRetry = viewModel::onRetry,
        onPresetSelected = viewModel::onPresetSelected,
        onPresetLabelChanged = viewModel::onPresetLabelChanged,
        onPresetApiKeyChanged = viewModel::onPresetApiKeyChanged,
        onCreatePresetProvider = viewModel::onCreatePresetProvider,
        onCustomLabelChanged = viewModel::onCustomLabelChanged,
        onCustomBaseUrlChanged = viewModel::onCustomBaseUrlChanged,
        onCustomAuthTypeChanged = viewModel::onCustomAuthTypeChanged,
        onCustomApiKeyChanged = viewModel::onCustomApiKeyChanged,
        onAddCustomHeader = viewModel::onAddCustomHeader,
        onCustomHeaderNameChanged = viewModel::onCustomHeaderNameChanged,
        onCustomHeaderValueChanged = viewModel::onCustomHeaderValueChanged,
        onRemoveCustomHeader = viewModel::onRemoveCustomHeader,
        onCreateCustomProvider = viewModel::onCreateCustomProvider,
        modifier = modifier,
    )
}

/** Stateful provider detail entry point. */
@Composable
fun ProviderDetailRoute(
    service: ProviderManagementService,
    providerId: String,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProviderDetailViewModel = viewModel(
        key = providerId,
        factory = ProviderDetailViewModel.factory(service, providerId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    ProviderDetailScreen(
        state = state,
        onNewKeyChanged = viewModel::onNewKeyChanged,
        onRotateKey = viewModel::onRotateKey,
        onRemoveKey = viewModel::onRemoveKey,
        onCancelRemove = viewModel::onCancelRemove,
        onToggleEnabled = viewModel::onToggleEnabled,
        onValidate = viewModel::onValidate,
        onRefreshModels = viewModel::onRefreshModels,
        onRetry = viewModel::onRetry,
        modifier = modifier,
    )
}
