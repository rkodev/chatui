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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.SecureFlagPolicy
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.EmptyState
import dev.chatui.core.designsystem.ErrorState
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.SecureScreen
import dev.chatui.core.designsystem.SkeletonList
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.providers.getProviderKeyMetadata

object ProviderListTestTags {
    const val Screen = "provider-list-screen"
    const val Title = "provider-list-title"
    const val Refresh = "provider-list-refresh"
    const val Loading = "provider-list-loading"
    const val Empty = "provider-list-empty"
    const val Error = "provider-list-error"
    const val AddProvider = "provider-list-add"
    const val AddSheet = "provider-list-add-sheet"
    const val CancelAdd = "provider-list-cancel-add"
    const val ChangePreset = "provider-list-change-preset"
    const val CustomOption = "provider-list-custom-option"
    const val PresetForm = "provider-list-preset-form"
    const val PresetLabel = "provider-list-preset-label"
    const val PresetOptional = "provider-list-preset-optional"
    const val PresetApiKey = "provider-list-preset-api-key"
    const val PresetShowKey = "provider-list-preset-show-key"
    const val PresetCreate = "provider-list-preset-create"
    const val PresetError = "provider-list-preset-error"
    const val CustomForm = "provider-list-custom-form"
    const val CustomOptional = "provider-list-custom-optional"
    const val CustomLabel = "provider-list-custom-label"
    const val CustomBaseUrl = "provider-list-custom-base-url"
    const val CustomAuthType = "provider-list-custom-auth-type"
    const val CustomApiKey = "provider-list-custom-api-key"
    const val CustomShowKey = "provider-list-custom-show-key"
    const val CustomAddHeader = "provider-list-custom-add-header"
    const val CustomCreate = "provider-list-custom-create"
    const val CustomError = "provider-list-custom-error"
    const val CustomNotice = "provider-list-custom-notice"
    const val CustomCleartextWarning = "provider-list-custom-cleartext-warning"
    const val CustomCleartextError = "provider-list-custom-cleartext-error"

    fun provider(id: String): String = "provider-list-item-$id"
    fun providerOption(id: String): String = "provider-list-option-$id"
    fun authOption(id: String): String = "provider-list-auth-option-$id"
    fun customHeaderName(index: Int): String = "provider-list-custom-header-name-$index"
    fun customHeaderValue(index: Int): String = "provider-list-custom-header-value-$index"
    fun customHeaderRemove(index: Int): String = "provider-list-custom-header-remove-$index"
}

private enum class AddProviderStep { Closed, Choose, Preset, Custom }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    state: ProviderListUiState,
    onOpenProvider: (String) -> Unit,
    onRetry: () -> Unit,
    onPresetSelected: (String) -> Unit = {},
    onPresetLabelChanged: (String) -> Unit = {},
    onPresetApiKeyChanged: (String) -> Unit = {},
    onCreatePresetProvider: () -> Unit = {},
    onCustomLabelChanged: (String) -> Unit = {},
    onCustomBaseUrlChanged: (String) -> Unit = {},
    onCustomAuthTypeChanged: (ProviderAuthType) -> Unit = {},
    onCustomApiKeyChanged: (String) -> Unit = {},
    onAddCustomHeader: () -> Unit = {},
    onCustomHeaderNameChanged: (Int, String) -> Unit = { _, _ -> },
    onCustomHeaderValueChanged: (Int, String) -> Unit = { _, _ -> },
    onRemoveCustomHeader: (Int) -> Unit = {},
    onCreateCustomProvider: () -> Unit = {},
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    val dimens = LocalDimens.current
    var addStep by rememberSaveable { mutableStateOf(AddProviderStep.Closed) }
    var restoreAddFocus by remember { mutableStateOf(false) }
    val addFocus = remember { FocusRequester() }
    val creating = state.creatingPreset || state.creatingCustom
    val pending by rememberUpdatedState(creating)
    var handledCreationVersion by rememberSaveable { mutableStateOf(state.creationVersion) }
    val closeAdd = {
        addStep = AddProviderStep.Closed
        restoreAddFocus = true
    }

    LaunchedEffect(state.creationVersion) {
        if (state.creationVersion > handledCreationVersion) {
            handledCreationVersion = state.creationVersion
            closeAdd()
        }
    }
    LaunchedEffect(restoreAddFocus) {
        if (restoreAddFocus) {
            addFocus.requestFocus()
            restoreAddFocus = false
        }
    }

    PullToRefreshBox(
        isRefreshing = state.loading && state.providers.isNotEmpty(),
        onRefresh = onRetry,
        modifier = modifier.fillMaxSize().testTag(ProviderListTestTags.Refresh),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(ProviderListTestTags.Screen),
            contentPadding = PaddingValues(dimens.padding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            item {
                Column {
                    if (showTitle) {
                        Text(
                            text = "Providers",
                            style = Typography.title1,
                            color = LocalChatUiColors.current.fgPrimary,
                            modifier = Modifier.testTag(ProviderListTestTags.Title),
                        )
                    }
                    TextButton(
                        onClick = { addStep = AddProviderStep.Choose },
                        enabled = !creating,
                        modifier = Modifier
                            .focusRequester(addFocus)
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ProviderListTestTags.AddProvider),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text("Add provider")
                    }
                }
            }
            state.notice?.let { notice ->
                item {
                    Text(
                        text = notice,
                        style = Typography.footnote,
                        color = LocalChatUiColors.current.fgSecondary,
                        modifier = Modifier.testTag(ProviderListTestTags.CustomNotice),
                    )
                }
            }
            state.error?.let { error ->
                item {
                    ErrorState(
                        title = "Could not load providers",
                        body = error,
                        onRetry = onRetry,
                        modifier = Modifier.testTag(ProviderListTestTags.Error),
                    )
                }
            }
            if (state.loading && state.providers.isEmpty()) {
                item {
                    SkeletonList(
                        count = 4,
                        modifier = Modifier.testTag(ProviderListTestTags.Loading),
                    )
                }
            } else if (state.providers.isEmpty() && state.error == null) {
                item {
                    EmptyState(
                        title = "No providers yet",
                        body = "Add a preset or any OpenAI-compatible server to start.",
                        modifier = Modifier.testTag(ProviderListTestTags.Empty),
                    )
                }
            }
            items(state.providers, key = { it.id }) { provider ->
                ProviderRow(
                    provider = provider,
                    onOpen = { onOpenProvider(provider.id) },
                    modifier = Modifier.animateItem(),
                )
            }
            item {
                Text(
                    text = "Configured means the required settings are stored, " +
                        "not that a live connection has been verified.",
                    style = Typography.footnote,
                    color = LocalChatUiColors.current.fgSecondary,
                )
                TextButton(onClick = onRetry, enabled = !state.loading) {
                    Text("Refresh providers")
                }
            }
        }
    }

    if (addStep != AddProviderStep.Closed) {
        SecureScreen {
            ModalBottomSheet(
                onDismissRequest = { if (!pending) closeAdd() },
                sheetState = rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = { it != SheetValue.Hidden || !pending },
                ),
                properties = ModalBottomSheetProperties(
                    securePolicy = SecureFlagPolicy.SecureOn,
                    shouldDismissOnBackPress = !creating,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(dimens.padding)
                        .testTag(ProviderListTestTags.AddSheet),
                    verticalArrangement = Arrangement.spacedBy(dimens.space2),
                ) {
                    Text("Add provider", style = Typography.title2)
                    if (addStep == AddProviderStep.Choose) {
                        AddProviderChooser(
                            options = state.options,
                            onPreset = {
                                onPresetSelected(it)
                                addStep = AddProviderStep.Preset
                            },
                            onCustom = { addStep = AddProviderStep.Custom },
                        )
                    } else {
                        TextButton(
                            onClick = { addStep = AddProviderStep.Choose },
                            enabled = !creating,
                            modifier = Modifier.testTag(ProviderListTestTags.ChangePreset),
                        ) {
                            Text("Change provider")
                        }
                        if (addStep == AddProviderStep.Preset) {
                            PresetProviderForm(
                                options = state.options,
                                form = state.presetForm,
                                creating = creating,
                                onLabelChanged = onPresetLabelChanged,
                                onApiKeyChanged = onPresetApiKeyChanged,
                                onCreate = onCreatePresetProvider,
                            )
                        } else {
                            CustomProviderForm(
                                form = state.customForm,
                                creating = creating,
                                onLabelChanged = onCustomLabelChanged,
                                onBaseUrlChanged = onCustomBaseUrlChanged,
                                onAuthTypeChanged = onCustomAuthTypeChanged,
                                onApiKeyChanged = onCustomApiKeyChanged,
                                onAddHeader = onAddCustomHeader,
                                onHeaderNameChanged = onCustomHeaderNameChanged,
                                onHeaderValueChanged = onCustomHeaderValueChanged,
                                onRemoveHeader = onRemoveCustomHeader,
                                onCreate = onCreateCustomProvider,
                            )
                        }
                    }
                    TextButton(
                        onClick = closeAdd,
                        enabled = !creating,
                        modifier = Modifier.testTag(ProviderListTestTags.CancelAdd),
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(provider: Provider, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val haptics = rememberHaptics()
    val metadata = getProviderKeyMetadata(provider)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.rowMinHeight)
            .testTag(ProviderListTestTags.provider(provider.id))
            .clickable { haptics.tick(); onOpen() }
            .padding(vertical = dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.space1)) {
            Text(provider.label, style = Typography.headline, color = colors.fgPrimary)
            Text(
                providerConfigurationStatus(provider),
                style = Typography.footnote,
                color = colors.fgSecondary,
            )
            val reported = listOfNotNull(
                metadata?.label,
                metadata?.usageUsd?.let { "Usage ${formatUsd(it)}" },
            )
            if (reported.isNotEmpty()) {
                Text(reported.joinToString(" · "), style = Typography.footnote, color = colors.fgSecondary)
            }
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.fgMuted)
    }
}

@Composable
private fun AddProviderChooser(
    options: List<ProviderAvailability>,
    onPreset: (String) -> Unit,
    onCustom: () -> Unit,
) {
    Text(
        "Choose a preset or connect any OpenAI-compatible server.",
        style = Typography.footnote,
    )
    options.filter { it.id != "custom" }.forEach { option ->
        TextButton(
            onClick = { onPreset(option.id) },
            enabled = option.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LocalDimens.current.touchTargetMin)
                .testTag(ProviderListTestTags.providerOption(option.id)),
        ) {
            Column(Modifier.weight(1f)) {
                Text(option.label, style = Typography.headline)
                Text(
                    when {
                        !option.enabled -> option.statusLabel ?: "Unavailable"
                        option.recommended -> "Recommended"
                        else -> option.statusLabel ?: "Available"
                    },
                    style = Typography.footnote,
                )
            }
        }
    }
    TextButton(
        onClick = onCustom,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalDimens.current.touchTargetMin)
            .testTag(ProviderListTestTags.CustomOption),
    ) {
        Text("Custom provider")
    }
}

@Composable
private fun PresetProviderForm(
    options: List<ProviderAvailability>,
    form: PresetProviderFormState,
    creating: Boolean,
    onLabelChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val selected = options.firstOrNull { it.id == form.presetId }
    val dimens = LocalDimens.current
    var optionalExpanded by remember(form.presetId) { mutableStateOf(form.label.isNotEmpty()) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ProviderListTestTags.PresetForm),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(selected?.label ?: "Choose a provider preset", style = Typography.title3)
        if (selected?.requiresKey == true) {
            ProviderKeyField(
                value = form.apiKey,
                onValueChange = onApiKeyChanged,
                enabled = !creating,
                fieldTag = ProviderListTestTags.PresetApiKey,
                toggleTag = ProviderListTestTags.PresetShowKey,
            )
        } else if (selected != null) {
            Text("No API key is required for this preset.", style = Typography.footnote)
        }
        ProviderSectionToggle(
            title = "Optional label",
            expanded = optionalExpanded,
            onClick = { optionalExpanded = !optionalExpanded },
            modifier = Modifier.testTag(ProviderListTestTags.PresetOptional),
        )
        if (optionalExpanded) {
            OutlinedTextField(
                value = form.label,
                onValueChange = onLabelChanged,
                label = { Text("Label (optional)") },
                enabled = !creating,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ProviderListTestTags.PresetLabel),
            )
        }
        form.error?.let {
            FormError("Could not add provider", it, ProviderListTestTags.PresetError)
        }
        Button(
            onClick = onCreate,
            enabled = !creating && selected?.enabled == true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.touchTargetMin)
                .testTag(ProviderListTestTags.PresetCreate),
        ) {
            Text(if (creating) "Adding provider..." else "Add provider")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomProviderForm(
    form: CustomProviderFormState,
    creating: Boolean,
    onLabelChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onAuthTypeChanged: (ProviderAuthType) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onAddHeader: () -> Unit,
    onHeaderNameChanged: (Int, String) -> Unit,
    onHeaderValueChanged: (Int, String) -> Unit,
    onRemoveHeader: (Int) -> Unit,
    onCreate: () -> Unit,
) {
    val dimens = LocalDimens.current
    var optionalExpanded by remember {
        mutableStateOf(form.label.isNotEmpty() || form.headers.isNotEmpty())
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ProviderListTestTags.CustomForm),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text("Custom provider", style = Typography.title3)
        Text(
            "Use an OpenAI-compatible server. Keys are stored separately from provider records.",
            style = Typography.footnote,
        )
        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = onBaseUrlChanged,
            label = { Text("Base URL") },
            placeholder = { Text("https://api.example.com/v1") },
            enabled = !creating,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(ProviderListTestTags.CustomBaseUrl),
        )
        form.cleartextWarning?.let {
            InlineBanner(
                severity = BannerSeverity.Warning,
                title = "Cleartext connection",
                body = it,
                modifier = Modifier.testTag(ProviderListTestTags.CustomCleartextWarning),
            )
        }
        form.cleartextError?.let {
            FormError("Cleartext connection blocked", it, ProviderListTestTags.CustomCleartextError)
        }
        Text("Authentication", style = Typography.footnote)
        FlowRow(
            modifier = Modifier.testTag(ProviderListTestTags.CustomAuthType),
            horizontalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            listOf(
                ProviderAuthType.BEARER to "Bearer",
                ProviderAuthType.X_API_KEY to "x-api-key",
                ProviderAuthType.NONE to "None",
            ).forEach { (type, label) ->
                FilterChip(
                    selected = form.authType == type,
                    onClick = { onAuthTypeChanged(type) },
                    enabled = !creating,
                    label = { Text(label) },
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ProviderListTestTags.authOption(type.name)),
                )
            }
        }
        if (form.authType != ProviderAuthType.NONE) {
            ProviderKeyField(
                value = form.apiKey,
                onValueChange = onApiKeyChanged,
                enabled = !creating,
                fieldTag = ProviderListTestTags.CustomApiKey,
                toggleTag = ProviderListTestTags.CustomShowKey,
            )
        }
        ProviderSectionToggle(
            title = "Optional label and headers",
            expanded = optionalExpanded,
            onClick = { optionalExpanded = !optionalExpanded },
            modifier = Modifier.testTag(ProviderListTestTags.CustomOptional),
        )
        if (optionalExpanded) {
            OutlinedTextField(
                value = form.label,
                onValueChange = onLabelChanged,
                label = { Text("Label (optional)") },
                enabled = !creating,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ProviderListTestTags.CustomLabel),
            )
            Text("Non-secret custom headers", style = Typography.footnote)
            form.headers.forEachIndexed { index, header ->
                OutlinedTextField(
                    value = header.name,
                    onValueChange = { onHeaderNameChanged(index, it) },
                    label = { Text("Header name") },
                    enabled = !creating,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .testTag(ProviderListTestTags.customHeaderName(index)),
                )
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { onHeaderValueChanged(index, it) },
                    label = { Text("Header value") },
                    enabled = !creating,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .testTag(ProviderListTestTags.customHeaderValue(index)),
                )
                TextButton(
                    onClick = { onRemoveHeader(index) },
                    enabled = !creating,
                    modifier = Modifier.testTag(ProviderListTestTags.customHeaderRemove(index)),
                ) {
                    Text("Remove header")
                }
            }
            TextButton(
                onClick = onAddHeader,
                enabled = !creating,
                modifier = Modifier.testTag(ProviderListTestTags.CustomAddHeader),
            ) {
                Text("Add header")
            }
        }
        form.error?.let {
            FormError("Could not add provider", it, ProviderListTestTags.CustomError)
        }
        Button(
            onClick = onCreate,
            enabled = !creating,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.touchTargetMin)
                .testTag(ProviderListTestTags.CustomCreate),
        ) {
            Text(if (creating) "Adding provider..." else "Add provider")
        }
    }
}

@Composable
private fun FormError(title: String, message: String, tag: String) {
    InlineBanner(
        severity = BannerSeverity.Error,
        title = title,
        body = message,
        modifier = Modifier.testTag(tag),
    )
}
