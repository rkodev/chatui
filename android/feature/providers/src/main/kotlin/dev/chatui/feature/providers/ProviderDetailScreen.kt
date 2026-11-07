package dev.chatui.feature.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.SecureScreen
import dev.chatui.core.providers.getProviderKeyMetadata

/** Test tags exposed by provider details. */
object ProviderDetailTestTags {
    const val Screen = "provider-detail-screen"
    const val Loading = "provider-detail-loading"
    const val Error = "provider-detail-error"
    const val Notice = "provider-detail-notice"
    const val KeyPanel = "provider-detail-key"
    const val Preset = "provider-detail-preset"
    const val BaseUrl = "provider-detail-base-url"
    const val CleartextWarning = "provider-detail-cleartext-warning"
    const val CleartextError = "provider-detail-cleartext-error"
    const val AuthType = "provider-detail-auth-type"
    const val KeyLabel = "provider-detail-key-label"
    const val Usage = "provider-detail-usage"
    const val Limit = "provider-detail-limit"
    const val FreeTier = "provider-detail-free-tier"
    const val RateLimit = "provider-detail-rate-limit"
    const val Cors = "provider-detail-cors"
    const val DocsLink = "provider-detail-docs"
    const val DocsError = "provider-detail-docs-error"
    const val EnabledToggle = "provider-detail-enabled"
    const val ValidateButton = "provider-detail-validate"
    const val RefreshModels = "provider-detail-refresh-models"
    const val NewKeyField = "provider-detail-new-key"
    const val RotateButton = "provider-detail-rotate"
    const val RemoveButton = "provider-detail-remove"
    const val ConfirmRemove = "provider-detail-confirm-remove"
    const val ModelCount = "provider-detail-model-count"
}

/**
 * Provider details.
 *
 * The key itself is never rendered. Only the non-secret `/key` metadata cached
 * on the provider record is shown.
 */
@Composable
fun ProviderDetailScreen(
    state: ProviderDetailUiState,
    onNewKeyChanged: (String) -> Unit,
    onRotateKey: () -> Unit,
    onRemoveKey: () -> Unit,
    onCancelRemove: () -> Unit,
    onToggleEnabled: () -> Unit,
    onValidate: () -> Unit,
    onRefreshModels: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    documentationLauncher: DocumentationLauncher = LocalDocumentationLauncher.current,
) {
    SecureScreen {
        ProviderDetailContent(
            state = state,
            onNewKeyChanged = onNewKeyChanged,
            onRotateKey = onRotateKey,
            onRemoveKey = onRemoveKey,
            onCancelRemove = onCancelRemove,
            onToggleEnabled = onToggleEnabled,
            onValidate = onValidate,
            onRefreshModels = onRefreshModels,
            onRetry = onRetry,
            documentationLauncher = documentationLauncher,
            modifier = modifier,
        )
    }
}

@Composable
private fun ProviderDetailContent(
    state: ProviderDetailUiState,
    onNewKeyChanged: (String) -> Unit,
    onRotateKey: () -> Unit,
    onRemoveKey: () -> Unit,
    onCancelRemove: () -> Unit,
    onToggleEnabled: () -> Unit,
    onValidate: () -> Unit,
    onRefreshModels: () -> Unit,
    onRetry: () -> Unit,
    documentationLauncher: DocumentationLauncher,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val provider = state.provider

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(ProviderDetailTestTags.Screen),
        contentPadding = PaddingValues(dimens.padding),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        item {
            Text(
                text = provider?.label ?: "Provider",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        if (state.loading) {
            item {
                Text(
                    text = "Loading provider…",
                    modifier = Modifier.testTag(ProviderDetailTestTags.Loading),
                )
            }
        }

        state.error?.let { error ->
            item {
                Column(
                    modifier = Modifier.testTag(ProviderDetailTestTags.Error),
                    verticalArrangement = Arrangement.spacedBy(dimens.space1),
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }

        state.notice?.let { notice ->
            item {
                Text(
                    text = notice,
                    modifier = Modifier.testTag(ProviderDetailTestTags.Notice),
                )
            }
        }

        if (provider != null) {
            item {
                KeyPanel(
                    provider = provider,
                    docsUrl = state.docsUrl,
                    cleartextWarning = state.cleartextWarning,
                    cleartextError = state.cleartextError,
                    documentationLauncher = documentationLauncher,
                )
            }
            item {
                ProviderControls(
                    state = state,
                    onToggleEnabled = onToggleEnabled,
                    onValidate = onValidate,
                    onRefreshModels = onRefreshModels,
                )
            }
            item {
                RotateKeyPanel(
                    state = state,
                    onNewKeyChanged = onNewKeyChanged,
                    onRotateKey = onRotateKey,
                )
            }
            item {
                RemoveKeyPanel(
                    state = state,
                    onRemoveKey = onRemoveKey,
                    onCancelRemove = onCancelRemove,
                )
            }
            item {
                Text(
                    text = "${state.modelCount} models are cached for this provider.",
                    modifier = Modifier.testTag(ProviderDetailTestTags.ModelCount),
                )
            }
        }
    }
}

@Composable
private fun KeyPanel(
    provider: dev.chatui.core.model.Provider,
    docsUrl: String?,
    cleartextWarning: String?,
    cleartextError: String?,
    documentationLauncher: DocumentationLauncher,
) {
    val dimens = LocalDimens.current
    val metadata = getProviderKeyMetadata(provider)
    val context = LocalContext.current
    var docsError by remember(docsUrl) { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProviderDetailTestTags.KeyPanel),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(dimens.space3),
            verticalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            Text("Key and usage", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Preset: ${provider.presetId}",
                modifier = Modifier.testTag(ProviderDetailTestTags.Preset),
            )
            Text(
                text = "Base URL: ${provider.baseUrl}",
                modifier = Modifier.testTag(ProviderDetailTestTags.BaseUrl),
            )
            cleartextWarning?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag(ProviderDetailTestTags.CleartextWarning),
                )
            }
            cleartextError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ProviderDetailTestTags.CleartextError),
                )
            }
            Text(
                text = "Auth: ${provider.authType.name.lowercase().replace('_', '-')}",
                modifier = Modifier.testTag(ProviderDetailTestTags.AuthType),
            )
            Text(
                text = "Key label: ${metadata?.label ?: provider.label}",
                modifier = Modifier.testTag(ProviderDetailTestTags.KeyLabel),
            )
            Text(
                text = "Usage: ${formatUsd(metadata?.usageUsd)}",
                modifier = Modifier.testTag(ProviderDetailTestTags.Usage),
            )
            Text(
                text = "Limit: ${metadata?.limitUsd?.let(::formatUsd) ?: "No limit"}",
                modifier = Modifier.testTag(ProviderDetailTestTags.Limit),
            )
            Text(
                text = "Free tier: ${formatFreeTier(metadata?.isFreeTier)}",
                modifier = Modifier.testTag(ProviderDetailTestTags.FreeTier),
            )
            Text(
                text = "Rate limit: ${formatRateLimit(metadata?.rateLimit) ?: "Not reported"}",
                modifier = Modifier.testTag(ProviderDetailTestTags.RateLimit),
            )
            Text(
                text = "CORS: ${formatCors(provider.browserCors)}",
                modifier = Modifier.testTag(ProviderDetailTestTags.Cors),
            )
            if (provider.presetId == "openrouter") {
                Text(
                    text = "OpenRouter may route the same model to different upstream " +
                        "providers with different data policies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (docsUrl != null) {
                TextButton(
                    onClick = {
                        docsError = when (val result = documentationLauncher.open(context, docsUrl)) {
                            is DocumentationOpenResult.Failed -> result.reason
                            is DocumentationOpenResult.Refused -> result.reason
                            else -> null
                        }
                    },
                    modifier = Modifier
                        .testTag(ProviderDetailTestTags.DocsLink)
                        .semantics {
                            contentDescription = "Open ${provider.label} documentation"
                        },
                ) {
                    Text("${provider.label} documentation")
                }
                docsError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(ProviderDetailTestTags.DocsError),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderControls(
    state: ProviderDetailUiState,
    onToggleEnabled: () -> Unit,
    onValidate: () -> Unit,
    onRefreshModels: () -> Unit,
) {
    val dimens = LocalDimens.current
    val provider = state.provider ?: return
    val busy = state.busy != null

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enable this provider")
            Switch(
                checked = provider.enabled,
                onCheckedChange = { onToggleEnabled() },
                enabled = !busy,
                modifier = Modifier.testTag(ProviderDetailTestTags.EnabledToggle),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            Button(
                onClick = onValidate,
                enabled = !busy && provider.secretId != null,
                modifier = Modifier
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ProviderDetailTestTags.ValidateButton),
            ) {
                Text("Refresh key details")
            }
            Button(
                onClick = onRefreshModels,
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ProviderDetailTestTags.RefreshModels),
            ) {
                Text("Refresh models")
            }
        }
    }
}

@Composable
private fun RotateKeyPanel(
    state: ProviderDetailUiState,
    onNewKeyChanged: (String) -> Unit,
    onRotateKey: () -> Unit,
) {
    val dimens = LocalDimens.current
    val busy = state.busy != null

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        Text("Rotate key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.newKey,
            onValueChange = onNewKeyChanged,
            label = { Text("New API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProviderDetailTestTags.NewKeyField),
        )
        Button(
            onClick = onRotateKey,
            enabled = !busy && state.newKey.isNotBlank(),
            modifier = Modifier
                .heightIn(min = dimens.touchTargetMin)
                .testTag(ProviderDetailTestTags.RotateButton),
        ) {
            Text(if (state.busy == ProviderDetailAction.ROTATE) "Rotating…" else "Rotate key")
        }
    }
}

@Composable
private fun RemoveKeyPanel(
    state: ProviderDetailUiState,
    onRemoveKey: () -> Unit,
    onCancelRemove: () -> Unit,
) {
    val dimens = LocalDimens.current
    val provider = state.provider ?: return
    val busy = state.busy != null

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        Text("Remove key", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Removing the key keeps the provider and its models but disables " +
                "sending until a new key is added.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            Button(
                onClick = onRemoveKey,
                enabled = !busy && provider.secretId != null,
                modifier = Modifier
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ProviderDetailTestTags.RemoveButton),
            ) {
                Text(
                    when {
                        state.busy == ProviderDetailAction.REMOVE -> "Removing…"
                        state.confirmRemove -> "Confirm removal"
                        else -> "Remove key"
                    },
                )
            }
            if (state.confirmRemove) {
                TextButton(
                    onClick = onCancelRemove,
                    modifier = Modifier.testTag(ProviderDetailTestTags.ConfirmRemove),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
