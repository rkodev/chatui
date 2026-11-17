package dev.chatui.feature.providers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    onOpenModelPicker: ((String, () -> Unit) -> Unit)? = null,
) {
    val viewModel: ConversationSettingsViewModel = viewModel(
        key = "conversation-settings-$conversationId",
        factory = ConversationSettingsViewModel.factory(service, conversationId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerOpen by remember(conversationId) { mutableStateOf(false) }
    var pickerReturnVersion by rememberSaveable(conversationId) { mutableStateOf(0) }
    val conversation = state.conversation
    val routing: (@Composable () -> Unit)? = if (
        state.provider?.presetId == "openrouter" && conversation != null
    ) {
        val routingConversation = conversation.copy(params = state.params)
        val routingViewModel: ConversationRoutingViewModel = viewModel(
            key = "conversation-routing-$conversationId-${state.provider?.id}",
            factory = ConversationRoutingViewModel.factory(
                service,
                conversationId,
                initialConversation = routingConversation,
                persistPreferences = viewModel::persistRouting,
            ),
        )
        val routingState by routingViewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(routingConversation, routingState.busy) {
            routingViewModel.onConversationChanged(routingConversation)
        }
        val content: @Composable () -> Unit = {
            ConversationRoutingControls(
                state = routingState,
                onEnabledChanged = routingViewModel::onEnabledChanged,
                onAllowFallbacksChanged = routingViewModel::onAllowFallbacksChanged,
                onSortChanged = routingViewModel::onSortChanged,
                onDenyDataCollectionChanged = routingViewModel::onDenyDataCollectionChanged,
                onRetry = routingViewModel::onRetry,
            )
        }
        content
    } else null

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
        onOpenModelPicker = {
            viewModel.prepareForModelPicker {
                pickerOpen = true
                onOpenModelPicker?.invoke(conversationId) {
                    pickerOpen = false
                    viewModel.refresh()
                    pickerReturnVersion++
                }
            }
        },
        onDelete = viewModel::onDelete,
        onDismissError = viewModel::onDismissError,
        onRetry = viewModel::onRetry,
        routingControls = routing,
        pickerOpen = pickerOpen,
        pickerReturnVersion = pickerReturnVersion,
        modelPickerAvailable = onOpenModelPicker != null,
        modifier = modifier,
    )
}
