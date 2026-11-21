package dev.chatui.feature.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.SecureFlagPolicy
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.ErrorState
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.SecureScreen
import dev.chatui.core.designsystem.SkeletonList
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
import dev.chatui.core.model.Provider
import dev.chatui.core.providers.ProviderKeyMetadata
import dev.chatui.core.providers.getProviderKeyMetadata

object ProviderDetailTestTags {
    const val Screen = "provider-detail-screen"
    const val Loading = "provider-detail-loading"
    const val Error = "provider-detail-error"
    const val Notice = "provider-detail-notice"
    const val Status = "provider-detail-status"
    const val KeyPanel = "provider-detail-key"
    const val ConnectionDetails = "provider-detail-connection-details"
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
    const val ChangeKey = "provider-detail-change-key"
    const val KeyEditor = "provider-detail-key-editor"
    const val CancelKeyEditor = "provider-detail-cancel-key-editor"
    const val ShowKey = "provider-detail-show-key"
    const val NewKeyField = "provider-detail-new-key"
    const val RotateButton = "provider-detail-rotate"
    const val RemoveButton = "provider-detail-remove"
    const val ConfirmRemove = "provider-detail-confirm-remove"
    const val ModelCount = "provider-detail-model-count"
    const val ManageModels = "provider-detail-manage-models"
}

private const val REMOVE_KEY_EXPLANATION =
    "Removing the key keeps the provider, its models, and your conversations. " +
        "Sending with a key-required provider is unavailable until a new key is added."

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onOpenModels: () -> Unit = {},
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val provider = state.provider
    val busy = state.busy != null
    val pending by rememberUpdatedState(busy)
    val haptics = rememberHaptics()
    var detailsExpanded by rememberSaveable(provider?.id) { mutableStateOf(false) }
    var editingKey by remember(provider?.id) { mutableStateOf(false) }
    var restoreKeyFocus by remember { mutableStateOf(false) }
    val changeKeyFocus = remember { FocusRequester() }
    val modelsFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var handledModelReturn by rememberSaveable(provider?.id) { mutableStateOf(0) }
    val closeEditor = {
        editingKey = false
        onNewKeyChanged("")
        restoreKeyFocus = true
    }

    LaunchedEffect(state.keyChangeVersion) {
        if (state.keyChangeVersion > 0 && editingKey) closeEditor()
    }
    LaunchedEffect(restoreKeyFocus) {
        if (restoreKeyFocus && provider != null) {
            changeKeyFocus.requestFocus()
            restoreKeyFocus = false
        }
    }
    LaunchedEffect(state.modelReturnVersion, state.loading) {
        if (state.modelReturnVersion > handledModelReturn && !state.loading && provider != null) {
            val modelsIndex = 2 + (if (!editingKey && state.error != null) 1 else 0) +
                (if (state.notice != null) 1 else 0)
            listState.scrollToItem(modelsIndex)
            withFrameNanos { }
            modelsFocus.requestFocus()
            handledModelReturn = state.modelReturnVersion
        }
    }

    SecureScreen {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize().testTag(ProviderDetailTestTags.Screen),
            contentPadding = PaddingValues(dimens.padding),
            verticalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            if (state.loading && provider == null) item {
                SkeletonList(count = 3, modifier = Modifier.testTag(ProviderDetailTestTags.Loading))
            }
            if (!editingKey) state.error?.let { error ->
                item {
                    ErrorState(
                        title = if (provider == null) "Could not load provider" else "Could not update provider",
                        body = error,
                        onRetry = onRetry,
                        modifier = Modifier.testTag(ProviderDetailTestTags.Error),
                    )
                }
            }
            state.notice?.let { notice ->
                item {
                    Text(
                        notice,
                        style = Typography.footnote,
                        color = colors.fgSecondary,
                        modifier = Modifier.testTag(ProviderDetailTestTags.Notice),
                    )
                }
            }
            if (provider != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
                        Text(provider.label, style = Typography.title2, color = colors.fgPrimary)
                        Text(
                            providerConfigurationStatus(provider),
                            style = Typography.subhead,
                            color = colors.fgSecondary,
                            modifier = Modifier.testTag(ProviderDetailTestTags.Status),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (provider.enabled) "Provider enabled" else "Provider off",
                                color = colors.fgPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = provider.enabled,
                                onCheckedChange = { onToggleEnabled() },
                                enabled = !busy,
                                modifier = Modifier
                                    .testTag(ProviderDetailTestTags.EnabledToggle)
                                    .semantics { contentDescription = "Provider enabled" },
                            )
                        }
                    }
                }
                item { KeyUsageOverview(provider) }
                item {
                    Text(
                        "${state.modelCount} models are cached for this provider.",
                        style = Typography.subhead,
                        color = colors.fgSecondary,
                        modifier = Modifier.testTag(ProviderDetailTestTags.ModelCount),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
                        TextButton(
                            onClick = onOpenModels,
                            modifier = Modifier.focusRequester(modelsFocus)
                                .testTag(ProviderDetailTestTags.ManageModels),
                        ) {
                            Text("Manage models")
                        }
                        TextButton(
                            onClick = onRefreshModels,
                            enabled = !busy,
                            modifier = Modifier.testTag(ProviderDetailTestTags.RefreshModels),
                        ) {
                            Text(
                                if (state.busy == ProviderDetailAction.REFRESH) "Refreshing models..."
                                else "Refresh models",
                            )
                        }
                        TextButton(
                            onClick = { haptics.confirm(); onValidate() },
                            enabled = !busy && provider.secretId != null,
                            modifier = Modifier.testTag(ProviderDetailTestTags.ValidateButton),
                        ) {
                            Text(
                                if (state.busy == ProviderDetailAction.VALIDATE) "Refreshing key details..."
                                else "Refresh key details",
                            )
                        }
                    }
                }
                state.cleartextWarning?.let { message ->
                    item {
                        InlineBanner(
                            severity = BannerSeverity.Warning,
                            title = "Cleartext connection",
                            body = message,
                            modifier = Modifier.testTag(ProviderDetailTestTags.CleartextWarning),
                        )
                    }
                }
                state.cleartextError?.let { message ->
                    item {
                        InlineBanner(
                            severity = BannerSeverity.Error,
                            title = "Cleartext connection blocked",
                            body = message,
                            modifier = Modifier.testTag(ProviderDetailTestTags.CleartextError),
                        )
                    }
                }
                item {
                    ProviderSectionToggle(
                        title = "Connection details",
                        expanded = detailsExpanded,
                        onClick = { detailsExpanded = !detailsExpanded },
                        modifier = Modifier.testTag(ProviderDetailTestTags.ConnectionDetails),
                    )
                }
                if (detailsExpanded) item {
                    ConnectionDetails(
                        provider = provider,
                        docsUrl = state.docsUrl,
                        documentationLauncher = documentationLauncher,
                    )
                }
                item {
                    TextButton(
                        onClick = { editingKey = true },
                        enabled = !busy,
                        modifier = Modifier
                            .focusRequester(changeKeyFocus)
                            .testTag(ProviderDetailTestTags.ChangeKey),
                    ) {
                        Text("Change key")
                    }
                }
                item {
                    Text(REMOVE_KEY_EXPLANATION, style = Typography.footnote, color = colors.fgSecondary)
                    TextButton(
                        onClick = onRemoveKey,
                        enabled = !busy && provider.secretId != null,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.danger),
                        modifier = Modifier.testTag(ProviderDetailTestTags.RemoveButton),
                    ) {
                        Text(if (state.busy == ProviderDetailAction.REMOVE) "Removing key..." else "Remove key")
                    }
                }
            }
        }

        if (editingKey && provider != null) {
            ModalBottomSheet(
                onDismissRequest = { if (!pending) closeEditor() },
                sheetState = rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = { it != SheetValue.Hidden || !pending },
                ),
                properties = ModalBottomSheetProperties(
                    securePolicy = SecureFlagPolicy.SecureOn,
                    shouldDismissOnBackPress = !busy,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(dimens.padding)
                        .testTag(ProviderDetailTestTags.KeyEditor),
                    verticalArrangement = Arrangement.spacedBy(dimens.space2),
                ) {
                    Text("Change key", style = Typography.title2)
                    Text(
                        "Enter a replacement for ${provider.label}. The stored key is never displayed.",
                        style = Typography.footnote,
                    )
                    ProviderKeyField(
                        value = state.newKey,
                        onValueChange = onNewKeyChanged,
                        label = "New API key",
                        enabled = !busy,
                        fieldTag = ProviderDetailTestTags.NewKeyField,
                        toggleTag = ProviderDetailTestTags.ShowKey,
                    )
                    state.error?.let { error ->
                        InlineBanner(
                            severity = BannerSeverity.Error,
                            title = "Could not change key",
                            body = error,
                            modifier = Modifier.testTag(ProviderDetailTestTags.Error),
                        )
                    }
                    Button(
                        onClick = { haptics.confirm(); onRotateKey() },
                        enabled = !busy && state.newKey.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ProviderDetailTestTags.RotateButton),
                    ) {
                        Text(if (state.busy == ProviderDetailAction.ROTATE) "Changing key..." else "Change and validate key")
                    }
                    TextButton(
                        onClick = closeEditor,
                        enabled = !busy,
                        modifier = Modifier.testTag(ProviderDetailTestTags.CancelKeyEditor),
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }

        if (state.confirmRemove) {
            AlertDialog(
                onDismissRequest = onCancelRemove,
                title = { Text("Remove key?") },
                text = { Text(REMOVE_KEY_EXPLANATION) },
                confirmButton = {
                    TextButton(
                        onClick = onRemoveKey,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.danger),
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
}

@Composable
private fun KeyUsageOverview(provider: Provider) {
    val metadata = getProviderKeyMetadata(provider)
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ProviderDetailTestTags.KeyPanel),
        verticalArrangement = Arrangement.spacedBy(LocalDimens.current.space1),
    ) {
        DetailValue("Key label", metadata?.label ?: "Not reported", ProviderDetailTestTags.KeyLabel)
        UsageProgress(metadata)
        DetailValue("Limit", metadata?.limitUsd?.let(::formatUsd) ?: "Not reported", ProviderDetailTestTags.Limit)
        DetailValue("Free tier", formatFreeTier(metadata?.isFreeTier), ProviderDetailTestTags.FreeTier)
        Text(
            "Stored configuration is not a live connection check.",
            style = Typography.footnote,
            color = LocalChatUiColors.current.fgSecondary,
        )
    }
}

@Composable
private fun UsageProgress(metadata: ProviderKeyMetadata?) {
    val usage = metadata?.usageUsd
    val limit = metadata?.limitUsd
    DetailValue("Usage", usage?.let(::formatUsd) ?: "Not reported", ProviderDetailTestTags.Usage)
    if (usage != null && limit != null && limit > 0.0) {
        LinearProgressIndicator(
            progress = { (usage / limit).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${formatUsd(usage)} of ${formatUsd(limit)} used",
            style = Typography.footnote,
            color = LocalChatUiColors.current.fgSecondary,
        )
    }
}

@Composable
private fun ConnectionDetails(
    provider: Provider,
    docsUrl: String?,
    documentationLauncher: DocumentationLauncher,
) {
    val metadata = getProviderKeyMetadata(provider)
    Column(verticalArrangement = Arrangement.spacedBy(LocalDimens.current.space2)) {
        DetailValue("Preset", provider.presetId, ProviderDetailTestTags.Preset)
        DetailValue("Base URL", provider.baseUrl, ProviderDetailTestTags.BaseUrl)
        DetailValue(
            "Auth",
            provider.authType.name.lowercase().replace('_', '-'),
            ProviderDetailTestTags.AuthType,
        )
        DetailValue(
            "Rate limit",
            formatRateLimit(metadata?.rateLimit) ?: "Not reported",
            ProviderDetailTestTags.RateLimit,
        )
        DetailValue("CORS", formatCors(provider.browserCors), ProviderDetailTestTags.Cors)
        if (provider.presetId == "openrouter") {
            Text(
                "OpenRouter may route the same model to different upstream " +
                    "providers with different data policies.",
                style = Typography.footnote,
                color = LocalChatUiColors.current.fgSecondary,
            )
        }
        DocsRow(provider, docsUrl, documentationLauncher)
    }
}

@Composable
private fun DetailValue(label: String, value: String, tag: String) {
    Text(
        text = "$label: $value",
        style = Typography.subhead,
        color = LocalChatUiColors.current.fgPrimary,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun DocsRow(
    provider: Provider,
    docsUrl: String?,
    documentationLauncher: DocumentationLauncher,
) {
    if (docsUrl == null) return
    val context = LocalContext.current
    var error by remember(docsUrl) { mutableStateOf<String?>(null) }
    Text(
        "${provider.label} documentation",
        color = LocalChatUiColors.current.accent,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalDimens.current.touchTargetMin)
            .testTag(ProviderDetailTestTags.DocsLink)
            .clickable {
                error = when (val result = documentationLauncher.open(context, docsUrl)) {
                    is DocumentationOpenResult.Failed -> result.reason
                    is DocumentationOpenResult.Refused -> result.reason
                    else -> null
                }
            }
            .semantics { contentDescription = "Open ${provider.label} documentation" },
    )
    error?.let {
        InlineBanner(
            severity = BannerSeverity.Error,
            title = "Could not open documentation",
            body = it,
            modifier = Modifier.testTag(ProviderDetailTestTags.DocsError),
        )
    }
}
