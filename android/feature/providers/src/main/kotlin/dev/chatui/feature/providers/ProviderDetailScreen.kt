package dev.chatui.feature.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.ErrorState
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.SecureScreen
import dev.chatui.core.designsystem.SkeletonList
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.model.Provider
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
            if (provider != null) {
                ProviderHeaderCard(
                    provider = provider,
                    busy = state.busy != null,
                    onToggleEnabled = onToggleEnabled,
                )
            } else {
                Text(
                    text = "Provider",
                    style = Typography.title1,
                    color = LocalChatUiColors.current.fgPrimary,
                )
            }
        }

        if (state.loading) {
            item {
                SkeletonList(
                    modifier = Modifier.testTag(ProviderDetailTestTags.Loading),
                    count = 3,
                )
            }
        }

        state.error?.let { error ->
            item {
                ErrorState(
                    title = "Could not load provider",
                    body = error,
                    onRetry = onRetry,
                    modifier = Modifier.testTag(ProviderDetailTestTags.Error),
                )
            }
        }

        state.notice?.let { notice ->
            item {
                Text(
                    text = notice,
                    style = Typography.subhead,
                    color = LocalChatUiColors.current.fgSecondary,
                    modifier = Modifier.testTag(ProviderDetailTestTags.Notice),
                )
            }
        }

        if (provider != null) {
            item {
                KeyUsageSection(
                    provider = provider,
                    state = state,
                    onValidate = onValidate,
                )
            }
            item {
                ModelsSection(
                    state = state,
                    onRefreshModels = onRefreshModels,
                )
            }
            item {
                AdvancedSection(
                    provider = provider,
                    state = state,
                    onNewKeyChanged = onNewKeyChanged,
                    onRotateKey = onRotateKey,
                    onRemoveKey = onRemoveKey,
                    documentationLauncher = documentationLauncher,
                )
            }
        }
    }

    if (state.confirmRemove) {
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text("Remove key?") },
            text = {
                Text(
                    "Removing the key keeps the provider and its models but disables " +
                        "sending until a new key is added.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onRemoveKey,
                    modifier = Modifier.testTag(ProviderDetailTestTags.ConfirmRemove),
                ) {
                    Text("Remove key")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelRemove) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProviderHeaderCard(
    provider: Provider,
    busy: Boolean,
    onToggleEnabled: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dimens.radiusLg),
        color = colors.bgSurfaceRaised,
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DetailAvatar(label = provider.label)
                Spacer(Modifier.width(dimens.space3))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.label,
                        style = Typography.title2,
                        color = colors.fgPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Base URL: ${provider.baseUrl}",
                        style = Typography.footnote,
                        color = colors.fgSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(ProviderDetailTestTags.BaseUrl),
                    )
                }
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    enabled = !busy,
                    modifier = Modifier.testTag(ProviderDetailTestTags.EnabledToggle),
                )
            }
            Text(
                text = "Preset: ${provider.presetId}",
                style = Typography.footnote,
                color = colors.fgMuted,
                modifier = Modifier.testTag(ProviderDetailTestTags.Preset),
            )
        }
    }
}

@Composable
private fun DetailAvatar(label: String) {
    val colors = LocalChatUiColors.current
    val initial = label.trim().firstOrNull()?.uppercase() ?: "?"

    Box(
        modifier = Modifier
            .size(48.dp)
            .background(colors.accentSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = Typography.title3,
            color = colors.onAccentSoft,
        )
    }
}

@Composable
private fun KeyUsageSection(
    provider: Provider,
    state: ProviderDetailUiState,
    onValidate: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val metadata = getProviderKeyMetadata(provider)
    val busy = state.busy != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProviderDetailTestTags.KeyPanel),
        shape = RoundedCornerShape(dimens.radiusLg),
        color = colors.bgSurfaceRaised,
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Text(
                text = "Key & usage",
                style = Typography.title3,
                color = colors.fgPrimary,
            )
            Text(
                text = "Auth: ${provider.authType.name.lowercase().replace('_', '-')}",
                style = Typography.subhead,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ProviderDetailTestTags.AuthType),
            )
            Text(
                text = "Key label: ${metadata?.label ?: provider.label}",
                style = Typography.subhead,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ProviderDetailTestTags.KeyLabel),
            )

            UsageProgress(metadata = metadata)

            Text(
                text = "Limit: ${metadata?.limitUsd?.let(::formatUsd) ?: "No limit"}",
                style = Typography.subhead,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ProviderDetailTestTags.Limit),
            )
            Text(
                text = "Free tier: ${formatFreeTier(metadata?.isFreeTier)}",
                style = Typography.subhead,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ProviderDetailTestTags.FreeTier),
            )
            Text(
                text = "Rate limit: ${formatRateLimit(metadata?.rateLimit) ?: "Not reported"}",
                style = Typography.subhead,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ProviderDetailTestTags.RateLimit),
            )
            Text(
                text = "CORS: ${formatCors(provider.browserCors)}",
                style = Typography.subhead,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ProviderDetailTestTags.Cors),
            )

            state.cleartextWarning?.let { message ->
                Text(
                    text = message,
                    style = Typography.footnote,
                    color = colors.warning,
                    modifier = Modifier.testTag(ProviderDetailTestTags.CleartextWarning),
                )
            }
            state.cleartextError?.let { message ->
                Text(
                    text = message,
                    style = Typography.footnote,
                    color = colors.danger,
                    modifier = Modifier.testTag(ProviderDetailTestTags.CleartextError),
                )
            }

            if (provider.presetId == "openrouter") {
                Text(
                    text = "OpenRouter may route the same model to different upstream " +
                        "providers with different data policies.",
                    style = Typography.footnote,
                    color = colors.fgMuted,
                )
            }

            Button(
                onClick = onValidate,
                enabled = !busy && provider.secretId != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ProviderDetailTestTags.ValidateButton),
            ) {
                Text("Refresh key details")
            }
        }
    }
}

@Composable
private fun UsageProgress(metadata: dev.chatui.core.providers.ProviderKeyMetadata?) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val usage = metadata?.usageUsd
    val limit = metadata?.limitUsd

    Text(
        text = "Usage: ${formatUsd(usage)}",
        style = Typography.subhead,
        color = colors.fgSecondary,
        modifier = Modifier.testTag(ProviderDetailTestTags.Usage),
    )

    if (usage != null && limit != null && limit > 0.0) {
        val progress = (usage / limit).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(dimens.radiusFull)),
            color = colors.accent,
            trackColor = colors.separator,
        )
        Text(
            text = "${formatUsd(usage)} of ${formatUsd(limit)} used",
            style = Typography.footnote,
            color = colors.fgMuted,
        )
    }
}

@Composable
private fun ModelsSection(
    state: ProviderDetailUiState,
    onRefreshModels: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val busy = state.busy != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dimens.radiusLg),
        color = colors.bgSurfaceRaised,
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Text(
                text = "Models",
                style = Typography.title3,
                color = colors.fgPrimary,
            )
            Text(
                text = "${state.modelCount} models are cached for this provider.",
                style = Typography.subhead,
                color = colors.fgSecondary,
                modifier = Modifier.testTag(ProviderDetailTestTags.ModelCount),
            )
            Button(
                onClick = onRefreshModels,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ProviderDetailTestTags.RefreshModels),
            ) {
                Text("Refresh models")
            }
        }
    }
}

@Composable
private fun AdvancedSection(
    provider: Provider,
    state: ProviderDetailUiState,
    onNewKeyChanged: (String) -> Unit,
    onRotateKey: () -> Unit,
    onRemoveKey: () -> Unit,
    documentationLauncher: DocumentationLauncher,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dimens.radiusLg),
        color = colors.bgSurfaceRaised,
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            Text(
                text = "Advanced",
                style = Typography.title3,
                color = colors.fgPrimary,
            )

            DocsRow(
                provider = provider,
                docsUrl = state.docsUrl,
                documentationLauncher = documentationLauncher,
            )

            HorizontalDivider(color = colors.separator, thickness = 0.5.dp)

            RotateKeyPanel(
                state = state,
                onNewKeyChanged = onNewKeyChanged,
                onRotateKey = onRotateKey,
            )

            HorizontalDivider(color = colors.separator, thickness = 0.5.dp)

            RemoveKeyPanel(
                provider = provider,
                state = state,
                onRemoveKey = onRemoveKey,
            )
        }
    }
}

@Composable
private fun DocsRow(
    provider: Provider,
    docsUrl: String?,
    documentationLauncher: DocumentationLauncher,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val context = LocalContext.current
    var docsError by remember(docsUrl) { mutableStateOf<String?>(null) }

    if (docsUrl == null) {
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space1)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.rowMinHeight)
                .testTag(ProviderDetailTestTags.DocsLink)
                .clickable {
                    docsError = when (val result = documentationLauncher.open(context, docsUrl)) {
                        is DocumentationOpenResult.Failed -> result.reason
                        is DocumentationOpenResult.Refused -> result.reason
                        else -> null
                    }
                }
                .semantics {
                    contentDescription = "Open ${provider.label} documentation"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(dimens.space3))
            Text(
                text = "${provider.label} documentation",
                style = Typography.body,
                color = colors.accent,
                modifier = Modifier.weight(1f),
            )
        }
        docsError?.let { message ->
            Text(
                text = message,
                style = Typography.footnote,
                color = colors.danger,
                modifier = Modifier.testTag(ProviderDetailTestTags.DocsError),
            )
        }
    }
}

@Composable
private fun RotateKeyPanel(
    state: ProviderDetailUiState,
    onNewKeyChanged: (String) -> Unit,
    onRotateKey: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val busy = state.busy != null

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        Text(
            text = "Rotate key",
            style = Typography.headline,
            color = colors.fgPrimary,
        )
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
                .fillMaxWidth()
                .heightIn(min = dimens.touchTargetMin)
                .testTag(ProviderDetailTestTags.RotateButton),
        ) {
            Text(if (state.busy == ProviderDetailAction.ROTATE) "Rotating…" else "Rotate key")
        }
    }
}

@Composable
private fun RemoveKeyPanel(
    provider: Provider,
    state: ProviderDetailUiState,
    onRemoveKey: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val busy = state.busy != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dimens.radiusMd),
        color = colors.danger.copy(alpha = 0.08f),
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Text(
                text = "Remove key",
                style = Typography.headline,
                color = colors.danger,
            )
            Text(
                text = "Removing the key keeps the provider and its models but disables " +
                    "sending until a new key is added.",
                style = Typography.footnote,
                color = colors.fgSecondary,
            )
            Button(
                onClick = onRemoveKey,
                enabled = !busy && provider.secretId != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ProviderDetailTestTags.RemoveButton),
            ) {
                Text(
                    when {
                        state.busy == ProviderDetailAction.REMOVE -> "Removing…"
                        else -> "Remove key"
                    },
                )
            }
        }
    }
}
