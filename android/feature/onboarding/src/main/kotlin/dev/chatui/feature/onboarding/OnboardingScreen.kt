package dev.chatui.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.SecureScreen

/** Test tags exposed by the onboarding screen. */
object OnboardingTestTags {
    const val Screen = "onboarding-screen"
    const val Welcome = "onboarding-welcome"
    const val ProviderPicker = "onboarding-provider-picker"
    const val ContinueButton = "onboarding-continue"
    const val KeyEntry = "onboarding-key-entry"
    const val ApiKeyField = "onboarding-api-key"
    const val LabelField = "onboarding-label"
    const val ConnectButton = "onboarding-connect"
    const val TestingStatus = "onboarding-testing"
    const val ConnectedStatus = "onboarding-connected"
    const val Notice = "onboarding-notice"
    const val ErrorCard = "onboarding-error"
    const val ErrorDetailsToggle = "onboarding-error-details-toggle"
    const val ErrorDetails = "onboarding-error-details"
    const val Catalog = "onboarding-catalog"
    const val CatalogLoading = "onboarding-catalog-loading"
    const val CatalogError = "onboarding-catalog-error"
    const val CatalogEmpty = "onboarding-catalog-empty"
    const val CatalogList = "onboarding-catalog-list"
    const val RetryCatalog = "onboarding-catalog-retry"
    const val StartChattingButton = "onboarding-start-chatting"
    const val BackButton = "onboarding-back"

    fun providerOption(id: String): String = "onboarding-provider-$id"
}

/**
 * Stateless onboarding screen.
 *
 * The welcome step explains BYOK/on-device storage and shows the provider
 * picker with OpenRouter preselected. The key-entry step shows the form, the
 * public catalog, and a disabled "Start chatting" action until validation
 * succeeds.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onProviderSelected: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRetryCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.step == OnboardingStep.KEY_ENTRY) {
        SecureScreen {
            OnboardingContent(
                state = state,
                onApiKeyChanged = onApiKeyChanged,
                onLabelChanged = onLabelChanged,
                onProviderSelected = onProviderSelected,
                onContinue = onContinue,
                onBack = onBack,
                onConnect = onConnect,
                onRetryCatalog = onRetryCatalog,
                modifier = modifier,
            )
        }
    } else {
        OnboardingContent(
            state = state,
            onApiKeyChanged = onApiKeyChanged,
            onLabelChanged = onLabelChanged,
            onProviderSelected = onProviderSelected,
            onContinue = onContinue,
            onBack = onBack,
            onConnect = onConnect,
            onRetryCatalog = onRetryCatalog,
            modifier = modifier,
        )
    }
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onProviderSelected: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRetryCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val catalog = state.catalog

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(OnboardingTestTags.Screen),
        contentPadding = PaddingValues(dimens.padding),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        when (state.step) {
            OnboardingStep.WELCOME -> {
                item { WelcomeSection() }
                item { ProviderPicker(state, onProviderSelected) }
                item {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(OnboardingTestTags.ContinueButton),
                    ) {
                        Text("Continue")
                    }
                }
            }

            OnboardingStep.KEY_ENTRY -> {
                item { KeyEntryHeader(state) }
                item {
                    KeyEntryForm(
                        state = state,
                        onApiKeyChanged = onApiKeyChanged,
                        onLabelChanged = onLabelChanged,
                        onConnect = onConnect,
                        onBack = onBack,
                    )
                }
                item { CatalogHeader(catalog, onRetryCatalog) }
                if (catalog is CatalogUiState.Ready) {
                    items(catalog.models, key = { it.remoteId }) { model ->
                        CatalogModelRow(model)
                    }
                }
                item { StartChattingSection(state) }
            }
        }
    }
}

@Composable
private fun WelcomeSection() {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier.testTag(OnboardingTestTags.Welcome),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Welcome to ChatUI",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "ChatUI is bring-your-own-key (BYOK). You connect your own " +
                "OpenRouter API key, and your conversations, settings, and key " +
                "stay on this device. Nothing is sent to a ChatUI server.",
        )
        Text(
            text = "OpenRouter is recommended for this preview. Other providers " +
                "are visible below but are coming later.",
        )
    }
}

@Composable
private fun ProviderPicker(
    state: OnboardingUiState,
    onProviderSelected: (String) -> Unit,
) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier.testTag(OnboardingTestTags.ProviderPicker),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Text(
            text = "Choose a provider",
            style = MaterialTheme.typography.titleMedium,
        )

        state.providers.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dimens.touchTargetMin)
                    .selectable(
                        selected = option.id == state.selectedProviderId,
                        enabled = option.enabled,
                        onClick = { onProviderSelected(option.id) },
                    )
                    .testTag(OnboardingTestTags.providerOption(option.id))
                    .padding(vertical = dimens.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = option.id == state.selectedProviderId,
                    onClick = null,
                    enabled = option.enabled,
                )
                Spacer(Modifier.width(dimens.space2))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (option.recommended) {
                        Text(
                            text = "Recommended",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    option.statusLabel?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyEntryHeader(state: OnboardingUiState) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier.testTag(OnboardingTestTags.KeyEntry),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Connect OpenRouter",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Your key is stored on this device and is never sent to a " +
                "ChatUI server.",
        )
        state.selectedProvider?.let { provider ->
            Text(
                text = "Provider: ${provider.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyEntryForm(
    state: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    val dimens = LocalDimens.current
    val enabled = !state.testing && !state.connected

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChanged,
            label = { Text("OpenRouter API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OnboardingTestTags.ApiKeyField),
        )
        Text(
            text = "Your key is stored on this device and is never sent to a " +
                "ChatUI server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.label,
            onValueChange = onLabelChanged,
            label = { Text("Label (optional)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OnboardingTestTags.LabelField),
        )
        Button(
            onClick = onConnect,
            enabled = state.canConnect,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.touchTargetMin)
                .testTag(OnboardingTestTags.ConnectButton),
        ) {
            Text(
                when {
                    state.testing -> "Testing…"
                    state.connected -> "Connected"
                    else -> "Connect"
                },
            )
        }

        if (state.testing) {
            Row(
                modifier = Modifier.testTag(OnboardingTestTags.TestingStatus),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(dimens.space2))
                Text("Testing key…")
            }
        }

        if (state.connected) {
            Text(
                text = "Connected. Opening a new chat…",
                modifier = Modifier.testTag(OnboardingTestTags.ConnectedStatus),
            )
        }

        state.notice?.let { notice ->
            Text(
                text = notice,
                modifier = Modifier.testTag(OnboardingTestTags.Notice),
            )
        }

        state.error?.let { error ->
            ErrorCard(error)
        }

        TextButton(
            onClick = onBack,
            enabled = enabled,
            modifier = Modifier.testTag(OnboardingTestTags.BackButton),
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun ErrorCard(error: OnboardingErrorCopy) {
    val dimens = LocalDimens.current
    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.ErrorCard),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(dimens.space3),
            verticalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            Text(
                text = error.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = error.message,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            error.details?.let { details ->
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.testTag(OnboardingTestTags.ErrorDetailsToggle),
                ) {
                    Text(if (showDetails) "Hide details" else "Details")
                }
                if (showDetails) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.testTag(OnboardingTestTags.ErrorDetails),
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogHeader(
    catalog: CatalogUiState,
    onRetryCatalog: () -> Unit,
) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.Catalog),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Available models",
            style = MaterialTheme.typography.titleMedium,
        )

        when (catalog) {
            CatalogUiState.Loading -> Row(
                modifier = Modifier.testTag(OnboardingTestTags.CatalogLoading),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(dimens.space2))
                Text("Loading the public model catalog…")
            }

            is CatalogUiState.Error -> Column(
                modifier = Modifier.testTag(OnboardingTestTags.CatalogError),
                verticalArrangement = Arrangement.spacedBy(dimens.space1),
            ) {
                Text(catalog.message)
                TextButton(
                    onClick = onRetryCatalog,
                    modifier = Modifier.testTag(OnboardingTestTags.RetryCatalog),
                ) {
                    Text("Retry")
                }
            }

            is CatalogUiState.Ready -> if (catalog.models.isEmpty()) {
                Text(
                    text = "No models were returned. You can still connect; " +
                        "models will load after validation.",
                    modifier = Modifier.testTag(OnboardingTestTags.CatalogEmpty),
                )
            } else {
                Text(
                    text = "${catalog.models.size} models are available. " +
                        "Chat stays disabled until your key is validated.",
                    modifier = Modifier.testTag(OnboardingTestTags.CatalogList),
                )
            }
        }
    }
}

@Composable
private fun CatalogModelRow(model: CatalogModel) {
    val dimens = LocalDimens.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = model.remoteId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (model.isFree) {
            Text(
                text = "Free",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StartChattingSection(state: OnboardingUiState) {
    val dimens = LocalDimens.current

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space1)) {
        Button(
            onClick = {},
            enabled = state.connected,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.touchTargetMin)
                .testTag(OnboardingTestTags.StartChattingButton),
        ) {
            Text("Start chatting")
        }
        Text(
            text = "Chat stays disabled until your key is validated.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
