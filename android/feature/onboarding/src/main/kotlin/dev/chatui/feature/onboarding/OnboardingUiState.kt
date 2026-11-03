package dev.chatui.feature.onboarding

/** Steps in the first-run flow. */
enum class OnboardingStep {
    WELCOME,
    KEY_ENTRY,
}

/** A provider preset shown in the onboarding picker. */
data class ProviderOption(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val recommended: Boolean,
    val statusLabel: String?,
)

/** A catalog entry shown before the key is validated. */
data class CatalogModel(
    val remoteId: String,
    val label: String,
    val contextWindow: Int?,
    val isFree: Boolean,
)

/** Public catalog loading state. */
sealed interface CatalogUiState {
    data object Loading : CatalogUiState

    data class Ready(val models: List<CatalogModel>) : CatalogUiState

    data class Error(val message: String) : CatalogUiState
}

/** Plain-language onboarding failure shown inline. */
data class OnboardingErrorCopy(
    val title: String,
    val message: String,
    val details: String?,
)

/**
 * One immutable state for the onboarding screen.
 *
 * The typed key and label stay in state after a failure so the form is never
 * cleared. The key is never logged or echoed in error copy.
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val providers: List<ProviderOption> = emptyList(),
    val selectedProviderId: String = "openrouter",
    val apiKey: String = "",
    val label: String = "",
    val testing: Boolean = false,
    val connected: Boolean = false,
    val catalog: CatalogUiState = CatalogUiState.Loading,
    val error: OnboardingErrorCopy? = null,
    val notice: String? = null,
) {
    val selectedProvider: ProviderOption?
        get() = providers.firstOrNull { it.id == selectedProviderId }

    val canConnect: Boolean
        get() = !testing && !connected && apiKey.isNotBlank()
}

/** One-shot navigation events emitted by the ViewModel. */
sealed interface OnboardingEvent {
    data object Connected : OnboardingEvent
}
