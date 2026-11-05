package dev.chatui.feature.providers

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.providers.getProviderKeyMetadata

/** Test tags exposed by the provider list. */
object ProviderListTestTags {
    const val Screen = "provider-list-screen"
    const val Loading = "provider-list-loading"
    const val Empty = "provider-list-empty"
    const val Error = "provider-list-error"
    const val AddProvider = "provider-list-add"
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

    fun provider(id: String): String = "provider-list-item-$id"

    fun providerOption(id: String): String = "provider-list-option-$id"

    fun presetOption(id: String): String = "provider-list-preset-option-$id"

    fun customHeaderName(index: Int): String = "provider-list-custom-header-name-$index"

    fun customHeaderValue(index: Int): String = "provider-list-custom-header-value-$index"

    fun customHeaderRemove(index: Int): String = "provider-list-custom-header-remove-$index"
}

/**
 * Provider list.
 *
 * The list shows every stored provider with its non-secret `/key` metadata and
 * CORS status. The add-provider section lists contract presets and offers the
 * Custom provider creation flow.
 */
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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(ProviderListTestTags.Screen),
        contentPadding = PaddingValues(dimens.padding),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        item {
            Text(
                text = "Providers",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        when {
            state.loading && state.providers.isEmpty() -> item {
                Text(
                    text = "Loading providers…",
                    modifier = Modifier.testTag(ProviderListTestTags.Loading),
                )
            }

            state.error != null -> item {
                Column(
                    modifier = Modifier.testTag(ProviderListTestTags.Error),
                    verticalArrangement = Arrangement.spacedBy(dimens.space1),
                ) {
                    Text(state.error)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }

            state.providers.isEmpty() -> item {
                Text(
                    text = "No provider is configured yet. Connect OpenRouter to start.",
                    modifier = Modifier.testTag(ProviderListTestTags.Empty),
                )
            }

            else -> items(state.providers, key = { it.id }) { provider ->
                ProviderRow(provider = provider, onOpen = { onOpenProvider(provider.id) })
            }
        }

        item { AddProviderSection(state.options) }

        item {
            PresetProviderForm(
                options = state.options,
                form = state.presetForm,
                creating = state.creatingPreset,
                onPresetSelected = onPresetSelected,
                onLabelChanged = onPresetLabelChanged,
                onApiKeyChanged = onPresetApiKeyChanged,
                onCreate = onCreatePresetProvider,
            )
        }

        item {
            CustomProviderForm(
                form = state.customForm,
                creating = state.creatingCustom,
                notice = state.notice,
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
}

@Composable
private fun ProviderRow(provider: Provider, onOpen: () -> Unit) {
    val dimens = LocalDimens.current
    val metadata = getProviderKeyMetadata(provider)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProviderListTestTags.provider(provider.id)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(dimens.space3),
            verticalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = provider.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (provider.enabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onOpen,
                    modifier = Modifier.heightIn(min = dimens.touchTargetMin),
                ) {
                    Text("Manage")
                }
            }

            Text(
                text = "Key label: ${metadata?.label ?: "Not available"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Usage: ${formatUsd(metadata?.usageUsd)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Limit: ${metadata?.limitUsd?.let(::formatUsd) ?: "No limit"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Free tier: ${formatFreeTier(metadata?.isFreeTier)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Rate limit: ${formatRateLimit(metadata?.rateLimit) ?: "Not reported"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "CORS: ${formatCors(provider.browserCors)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AddProviderSection(options: List<ProviderAvailability>) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProviderListTestTags.AddProvider),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Text(
            text = "Add provider",
            style = MaterialTheme.typography.titleMedium,
        )

        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProviderListTestTags.providerOption(option.id))
                    .padding(vertical = dimens.space1),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(option.label)
                Text(
                    text = when {
                        option.recommended && option.enabled -> "Recommended"
                        option.enabled -> "Available"
                        else -> option.statusLabel ?: "Coming later"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Choose an enabled OpenAI-compatible preset. The key is stored " +
                "separately from the provider record.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    modifier = Modifier.testTag(
                        ProviderListTestTags.presetOption(option.id),
                    ),
                )
            }
        }

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
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(ProviderListTestTags.PresetError),
            )
        }

        Button(
            onClick = onCreate,
            enabled = !creating,
            modifier = Modifier.testTag(ProviderListTestTags.PresetCreate),
        ) {
            Text(if (creating) "Adding…" else "Add preset provider")
        }
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
) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProviderListTestTags.CustomForm),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Add a Custom provider",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Use any OpenAI-compatible server. The key is stored separately " +
                "from the provider record.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        Text(
            text = "Authentication",
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier.testTag(ProviderListTestTags.CustomAuthType),
            horizontalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            AuthChip("Bearer", ProviderAuthType.BEARER, form.authType, onAuthTypeChanged)
            AuthChip("x-api-key", ProviderAuthType.X_API_KEY, form.authType, onAuthTypeChanged)
            AuthChip("None", ProviderAuthType.NONE, form.authType, onAuthTypeChanged)
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
            style = MaterialTheme.typography.labelMedium,
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
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(ProviderListTestTags.CustomError),
            )
        }

        notice?.let { message ->
            Text(
                text = message,
                modifier = Modifier.testTag(ProviderListTestTags.CustomNotice),
            )
        }

        Button(
            onClick = onCreate,
            enabled = !creating,
            modifier = Modifier.testTag(ProviderListTestTags.CustomCreate),
        ) {
            Text(if (creating) "Adding…" else "Add Custom provider")
        }
    }
}

@Composable
private fun AuthChip(
    label: String,
    value: ProviderAuthType,
    selected: ProviderAuthType,
    onSelected: (ProviderAuthType) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelected(value) },
        label = { Text(label) },
    )
}
