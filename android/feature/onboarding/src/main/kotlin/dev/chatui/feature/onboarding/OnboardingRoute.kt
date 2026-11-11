package dev.chatui.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Stateful onboarding entry point.
 *
 * The ViewModel is scoped to the current navigation back-stack entry. A
 * successful connection shows the completion state; the shell navigates to
 * chat only when the user taps "Start chatting".
 */
@Composable
fun OnboardingRoute(
    service: OnboardingService,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: OnboardingViewModel = viewModel(
        factory = OnboardingViewModel.factory(service),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnboardingScreen(
        state = state,
        onApiKeyChanged = viewModel::onApiKeyChanged,
        onLabelChanged = viewModel::onLabelChanged,
        onProviderSelected = viewModel::onProviderSelected,
        onContinue = viewModel::onContinue,
        onBack = viewModel::onBack,
        onConnect = viewModel::onConnect,
        onRetryCatalog = viewModel::onRetryCatalog,
        onStartChatting = onConnected,
        modifier = modifier,
    )
}
