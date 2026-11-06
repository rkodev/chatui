package dev.chatui.feature.providers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Stateful conversation settings entry point.
 *
 * The shell resolves [ProviderManagementService] from the singleton graph and
 * passes it in so navigation tests can render the sheet without Hilt.
 */
@Composable
fun ConversationSettingsRoute(
    service: ProviderManagementService,
    conversationId: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ConversationSettingsViewModel = viewModel(
        key = conversationId,
        factory = ConversationSettingsViewModel.factory(service, conversationId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            onDeleted()
        }
    }

    ConversationSettingsSheet(
        state = state,
        onDismiss = onDismiss,
        onSystemPromptChanged = viewModel::onSystemPromptChanged,
        onTemperatureChanged = viewModel::onTemperatureChanged,
        onTopPChanged = viewModel::onTopPChanged,
        onMaxOutputTokensChanged = viewModel::onMaxOutputTokensChanged,
        onStopChanged = viewModel::onStopChanged,
        onReasoningEffortChanged = viewModel::onReasoningEffortChanged,
        onStreamingChanged = viewModel::onStreamingChanged,
        onExtrasChanged = viewModel::onExtrasChanged,
        onModelSelected = viewModel::onModelSelected,
        onDelete = viewModel::onDelete,
        onDismissError = viewModel::onDismissError,
        modifier = modifier,
    )
}
