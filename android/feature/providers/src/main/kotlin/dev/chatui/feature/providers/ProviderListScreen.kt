package dev.chatui.feature.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.EmptyState
import dev.chatui.core.designsystem.ErrorState
import dev.chatui.core.designsystem.IconAction
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.SkeletonList
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.providers.getProviderKeyMetadata

/** Test tags exposed by the provider list. */
object ProviderListTestTags {
    const val Screen = "provider-list-screen"
    const val Refresh = "provider-list-refresh"
    const val Loading = "provider-list-loading"
    const val Empty = "provider-list-empty"
    const val Error = "provider-list-error"
    const val AddProvider = "provider-list-add"
    const val AddSheet = "provider-list-add-sheet"
    const val CustomOption = "provider-list-custom-option"
    const val SectionConnected = "provider-list-section-connected"
    const val SectionAvailable = "provider-list-section-available"
    const val PresetForm = "provider-list-preset-form"
    const val PresetLabel = "provider-list-preset-label"
    const val PresetApiKey = "provider-list-preset-api-key"
    const val PresetCreate = "provider-list-preset-create"
    const val PresetError = "provider-list-preset-error"
    const val CustomForm = "provider-list-custom-form"
    const val CustomLabel = "provider-list-custom-label"
    const val CustomBaseUrl = "provider-list-custom-base-url"
    const val CustomAuthType = "provider-list-custom-auth-type"
    const val CustomApiKey = "provider-list-custom-api-key"
    const val CustomAddHeader = "provider-list-custom-add-header"
    const val CustomCreate = "provider-list-custom-create"
    const val CustomError = "provider-list-custom-error"
    const val CustomNotice = "provider-list-custom-notice"
    const val CustomCleartextWarning = "provider-list-custom-cleartext-warning"
    const val CustomCleartextError = "provider-list-custom-cleartext-error"

    fun provider(id: String): String = "provider-list-item-$id"

    fun providerOption(id: String): String = "provider-list-option-$id"

    fun presetOption(id: String): String = "provider-list-preset-option-$id"

    fun authOption(id: String): String = "provider-list-auth-option-$id"

    fun customHeaderName(index: Int): String = "provider-list-custom-header-name-$index"

    fun customHeaderValue(index: Int): String = "provider-list-custom-header-value-$index"

    fun customHeaderRemove(index: Int): String = "provider-list-custom-header-remove-$index"
}

private enum class AddProviderStep { Closed, Choose, Preset, Custom }

/**
 * Provider list.
 *
 * Configured providers are grouped into Connected (a key is stored) and
 * Available (added but not connected). The add-provider flow lives in a bottom
 * sheet so the list stays scannable.
 */
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
) {
    val dimens = LocalDimens.current
    var addStep by remember { mutableStateOf(AddProviderStep.Closed) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val connected = state.providers.filter { it.secretId != null }
    val available = state.providers.filter { it.secretId == null }

    PullToRefreshBox(
        isRefreshing = state.loading && state.providers.isNotEmpty(),
        onRefresh = onRetry,
        modifier = modifier
            .fillMaxSize()
            .testTag(ProviderListTestTags.Refresh),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ProviderListTestTags.Screen),
            contentPadding = PaddingValues(dimens.padding),
            verticalArrangement = Arrangement.spacedBy(dimens.gap),
        ) {
        item {
            ProviderListHeader(onAdd = { addStep = AddProviderStep.Choose })
        }

        when {
            state.loading && state.providers.isEmpty() -> item {
                SkeletonList(
                    modifier = Modifier.testTag(ProviderListTestTags.Loading),
                    count = 4,
                )
            }

            state.error != null -> item {
                ErrorState(
                    title = "Could not load providers",
                    body = state.error,
                    onRetry = onRetry,
                    modifier = Modifier.testTag(ProviderListTestTags.Error),
                )
            }

            state.providers.isEmpty() -> item {
                EmptyState(
                    title = "No providers yet",
                    body = "Connect OpenRouter or add any OpenAI-compatible server to start.",
                    modifier = Modifier.testTag(ProviderListTestTags.Empty),
                )
            }

            else -> {
                if (connected.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = "Connected",
                            modifier = Modifier.testTag(ProviderListTestTags.SectionConnected),
                        )
                    }
                    items(connected, key = { it.id }) { provider ->
                        ProviderRow(
                            provider = provider,
                            onOpen = { onOpenProvider(provider.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                if (available.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = "Available",
                            modifier = Modifier.testTag(ProviderListTestTags.SectionAvailable),
                        )
                    }
                    items(available, key = { it.id }) { provider ->
                        ProviderRow(
                            provider = provider,
                            onOpen = { onOpenProvider(provider.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
        }
    }

    if (addStep != AddProviderStep.Closed) {
        ModalBottomSheet(
            onDismissRequest = { addStep = AddProviderStep.Closed },
            sheetState = sheetState,
        ) {
            when (addStep) {
                AddProviderStep.Choose -> AddProviderChooser(
                    options = state.options,
                    onPreset = { presetId ->
                        onPresetSelected(presetId)
                        addStep = AddProviderStep.Preset
                    },
                    onCustom = { addStep = AddProviderStep.Custom },
                )

                AddProviderStep.Preset -> PresetProviderSheet(
                    state = state,
                    onPresetSelected = onPresetSelected,
                    onLabelChanged = onPresetLabelChanged,
                    onApiKeyChanged = onPresetApiKeyChanged,
                    onCreate = onCreatePresetProvider,
                )

                AddProviderStep.Custom -> CustomProviderSheet(
                    state = state,
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

                AddProviderStep.Closed -> Unit
            }
        }
    }
}

@Composable
private fun ProviderListHeader(onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Providers",
            style = Typography.title1,
            color = LocalChatUiColors.current.fgPrimary,
        )
        IconAction(
            icon = Icons.Rounded.Add,
            contentDescription = "Add provider",
            onClick = onAdd,
            modifier = Modifier.testTag(ProviderListTestTags.AddProvider),
        )
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = Typography.footnote,
        color = LocalChatUiColors.current.fgMuted,
        modifier = modifier.padding(top = LocalDimens.current.space2),
    )
}

@Composable
private fun ProviderRow(
    provider: Provider,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val haptics = rememberHaptics()
    val metadata = getProviderKeyMetadata(provider)
    val keyLabel = metadata?.label ?: if (provider.secretId != null) "Key stored" else "No key"
    val subtitle = "$keyLabel · Usage ${formatUsd(metadata?.usageUsd)}"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ProviderListTestTags.provider(provider.id))
            .clickable {
                haptics.tick()
                onOpen()
            },
        shape = RoundedCornerShape(dimens.radiusMd),
        color = colors.bgSurfaceRaised,
    ) {
        Row(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderAvatar(label = provider.label)
            Spacer(Modifier.width(dimens.space3))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.label,
                        style = Typography.headline,
                        color = colors.fgPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(dimens.space2))
                    StatusDot(enabled = provider.enabled)
                }
                Text(
                    text = subtitle,
                    style = Typography.footnote,
                    color = colors.fgSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.fgMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ProviderAvatar(label: String) {
    val colors = LocalChatUiColors.current
    val initial = label.trim().firstOrNull()?.uppercase() ?: "?"

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(colors.accentSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = Typography.headline,
            color = colors.onAccentSoft,
        )
    }
}

@Composable
private fun StatusDot(enabled: Boolean) {
    val colors = LocalChatUiColors.current
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                if (enabled) colors.success else colors.fgMuted,
                CircleShape,
            ),
    )
}

@Composable
private fun AddProviderChooser(
    options: List<ProviderAvailability>,
    onPreset: (String) -> Unit,
    onCustom: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProviderListTestTags.AddSheet)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.padding)
            .padding(bottom = dimens.padding),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Add provider",
            style = Typography.title2,
            color = colors.fgPrimary,
        )
        Text(
            text = "Choose a preset or connect any OpenAI-compatible server.",
            style = Typography.subhead,
            color = colors.fgSecondary,
        )

        options.forEach { option ->
            ProviderOptionCard(
                title = option.label,
                subtitle = when {
                    option.recommended && option.enabled -> "Recommended"
                    option.enabled -> "Available"
                    else -> option.statusLabel ?: "Coming later"
                },
                enabled = option.enabled,
                modifier = Modifier.testTag(ProviderListTestTags.providerOption(option.id)),
                onClick = { onPreset(option.id) },
            )
        }

        ProviderOptionCard(
            title = "Custom provider",
            subtitle = "Any OpenAI-compatible server",
            enabled = true,
            modifier = Modifier.testTag(ProviderListTestTags.CustomOption),
            onClick = onCustom,
        )
    }
}

@Composable
private fun ProviderOptionCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(dimens.radiusMd),
        color = colors.bgSurfaceRaised,
    ) {
        Row(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = Typography.headline,
                    color = if (enabled) colors.fgPrimary else colors.fgMuted,
                )
                Text(
                    text = subtitle,
                    style = Typography.footnote,
                    color = colors.fgSecondary,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.fgMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PresetProviderSheet(
    state: ProviderListUiState,
    onPresetSelected: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .testTag(ProviderListTestTags.AddSheet),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimens.padding),
        ) {
            PresetProviderForm(
                options = state.options,
                form = state.presetForm,
                creating = state.creatingPreset,
                onPresetSelected = onPresetSelected,
                onLabelChanged = onLabelChanged,
                onApiKeyChanged = onApiKeyChanged,
                onCreate = onCreate,
                showAction = false,
            )
        }
        Box(modifier = Modifier.padding(dimens.padding)) {
            PresetActionButton(creating = state.creatingPreset, onCreate = onCreate)
        }
    }
}

@Composable
private fun CustomProviderSheet(
    state: ProviderListUiState,
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .testTag(ProviderListTestTags.AddSheet),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimens.padding),
        ) {
            CustomProviderForm(
                form = state.customForm,
                creating = state.creatingCustom,
                notice = state.notice,
                onLabelChanged = onLabelChanged,
                onBaseUrlChanged = onBaseUrlChanged,
                onAuthTypeChanged = onAuthTypeChanged,
                onApiKeyChanged = onApiKeyChanged,
                onAddHeader = onAddHeader,
                onHeaderNameChanged = onHeaderNameChanged,
                onHeaderValueChanged = onHeaderValueChanged,
                onRemoveHeader = onRemoveHeader,
                onCreate = onCreate,
                showAction = false,
            )
        }
        Box(modifier = Modifier.padding(dimens.padding)) {
            CustomActionButton(creating = state.creatingCustom, onCreate = onCreate)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetProviderForm(
    options: List<ProviderAvailability>,
    form: PresetProviderFormState,
    creating: Boolean,
    onPresetSelected: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onCreate: () -> Unit,
    showAction: Boolean = true,
) {
    val dimens = LocalDimens.current
    val selectable = options.filter { it.enabled && it.id != "custom" }
    val selected = selectable.firstOrNull { it.id == form.presetId }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProviderListTestTags.PresetForm),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Add a preset provider",
            style = Typography.title2,
            color = LocalChatUiColors.current.fgPrimary,
        )
        Text(
            text = "Choose an enabled OpenAI-compatible preset. The key is stored " +
                "separately from the provider record.",
            style = Typography.footnote,
            color = LocalChatUiColors.current.fgSecondary,
        )

        Text(
            text = "Provider",
            style = Typography.footnote,
            color = LocalChatUiColors.current.fgMuted,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            selectable.forEach { option ->
                FilterChip(
                    selected = option.id == form.presetId,
                    onClick = { onPresetSelected(option.id) },
                    label = { Text(option.label) },
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(
                            ProviderListTestTags.presetOption(option.id),
                        ),
                )
            }
        }

        Text(
            text = "Details",
            style = Typography.footnote,
            color = LocalChatUiColors.current.fgMuted,
        )
        OutlinedTextField(
            value = form.label,
            onValueChange = onLabelChanged,
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProviderListTestTags.PresetLabel),
        )

        if (selected?.requiresKey == true) {
            OutlinedTextField(
                value = form.apiKey,
                onValueChange = onApiKeyChanged,
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProviderListTestTags.PresetApiKey),
            )
        }

        form.error?.let { message ->
            InlineBanner(
                severity = BannerSeverity.Error,
                title = "Could not add provider",
                body = message,
                modifier = Modifier.testTag(ProviderListTestTags.PresetError),
            )
        }

        if (showAction) {
            PresetActionButton(creating = creating, onCreate = onCreate)
        }
    }
}

@Composable
private fun PresetActionButton(creating: Boolean, onCreate: () -> Unit) {
    Button(
        onClick = onCreate,
        enabled = !creating,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalDimens.current.touchTargetMin)
            .testTag(ProviderListTestTags.PresetCreate),
    ) {
        Text(if (creating) "Adding…" else "Add preset provider")
    }
}

@Composable
private fun CustomProviderForm(
    form: CustomProviderFormState,
    creating: Boolean,
    notice: String?,
    onLabelChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onAuthTypeChanged: (ProviderAuthType) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onAddHeader: () -> Unit,
    onHeaderNameChanged: (Int, String) -> Unit,
    onHeaderValueChanged: (Int, String) -> Unit,
    onRemoveHeader: (Int) -> Unit,
    onCreate: () -> Unit,
    showAction: Boolean = true,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProviderListTestTags.CustomForm),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Add a Custom provider",
            style = Typography.title2,
            color = colors.fgPrimary,
        )
        Text(
            text = "Use any OpenAI-compatible server. The key is stored separately " +
                "from the provider record.",
            style = Typography.footnote,
            color = colors.fgSecondary,
        )

        Text(
            text = "Connection",
            style = Typography.footnote,
            color = colors.fgMuted,
        )
        OutlinedTextField(
            value = form.label,
            onValueChange = onLabelChanged,
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProviderListTestTags.CustomLabel),
        )

        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = onBaseUrlChanged,
            label = { Text("Base URL") },
            placeholder = { Text("https://api.example.com/v1") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProviderListTestTags.CustomBaseUrl),
        )

        form.cleartextWarning?.let { message ->
            InlineBanner(
                severity = BannerSeverity.Warning,
                title = "Cleartext connection",
                body = message,
                modifier = Modifier.testTag(ProviderListTestTags.CustomCleartextWarning),
            )
        }

        form.cleartextError?.let { message ->
            InlineBanner(
                severity = BannerSeverity.Error,
                title = "Cleartext connection blocked",
                body = message,
                modifier = Modifier.testTag(ProviderListTestTags.CustomCleartextError),
            )
        }

        Text(
            text = "Authentication",
            style = Typography.footnote,
            color = colors.fgMuted,
        )
        Row(
            modifier = Modifier.testTag(ProviderListTestTags.CustomAuthType),
            horizontalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            AuthChip(
                label = "Bearer",
                value = ProviderAuthType.BEARER,
                selected = form.authType,
                onSelected = onAuthTypeChanged,
                modifier = Modifier.testTag(
                    ProviderListTestTags.authOption(ProviderAuthType.BEARER.name),
                ),
            )
            AuthChip(
                label = "x-api-key",
                value = ProviderAuthType.X_API_KEY,
                selected = form.authType,
                onSelected = onAuthTypeChanged,
                modifier = Modifier.testTag(
                    ProviderListTestTags.authOption(ProviderAuthType.X_API_KEY.name),
                ),
            )
            AuthChip(
                label = "None",
                value = ProviderAuthType.NONE,
                selected = form.authType,
                onSelected = onAuthTypeChanged,
                modifier = Modifier.testTag(
                    ProviderListTestTags.authOption(ProviderAuthType.NONE.name),
                ),
            )
        }

        if (form.authType != ProviderAuthType.NONE) {
            OutlinedTextField(
                value = form.apiKey,
                onValueChange = onApiKeyChanged,
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProviderListTestTags.CustomApiKey),
            )
        }

        Text(
            text = "Non-secret headers",
            style = Typography.footnote,
            color = colors.fgMuted,
        )

        form.headers.forEachIndexed { index, header ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = header.name,
                    onValueChange = { onHeaderNameChanged(index, it) },
                    label = { Text("Header") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ProviderListTestTags.customHeaderName(index)),
                )
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { onHeaderValueChanged(index, it) },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ProviderListTestTags.customHeaderValue(index)),
                )
                TextButton(
                    onClick = { onRemoveHeader(index) },
                    modifier = Modifier.testTag(ProviderListTestTags.customHeaderRemove(index)),
                ) {
                    Text("Remove")
                }
            }
        }

        TextButton(
            onClick = onAddHeader,
            modifier = Modifier.testTag(ProviderListTestTags.CustomAddHeader),
        ) {
            Text("Add header")
        }

        form.error?.let { message ->
            InlineBanner(
                severity = BannerSeverity.Error,
                title = "Could not add provider",
                body = message,
                modifier = Modifier.testTag(ProviderListTestTags.CustomError),
            )
        }

        notice?.let { message ->
            InlineBanner(
                severity = BannerSeverity.Info,
                title = message,
                modifier = Modifier.testTag(ProviderListTestTags.CustomNotice),
            )
        }

        if (showAction) {
            CustomActionButton(creating = creating, onCreate = onCreate)
        }
    }
}

@Composable
private fun CustomActionButton(creating: Boolean, onCreate: () -> Unit) {
    Button(
        onClick = onCreate,
        enabled = !creating,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalDimens.current.touchTargetMin)
            .testTag(ProviderListTestTags.CustomCreate),
    ) {
        Text(if (creating) "Adding…" else "Add Custom provider")
    }
}

@Composable
private fun AuthChip(
    label: String,
    value: ProviderAuthType,
    selected: ProviderAuthType,
    onSelected: (ProviderAuthType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    FilterChip(
        selected = selected == value,
        onClick = { onSelected(value) },
        label = { Text(label) },
        modifier = modifier.heightIn(min = dimens.touchTargetMin),
    )
}
