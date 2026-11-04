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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Provider
import dev.chatui.core.providers.getProviderKeyMetadata

/** Test tags exposed by the provider list. */
object ProviderListTestTags {
    const val Screen = "provider-list-screen"
    const val Loading = "provider-list-loading"
    const val Empty = "provider-list-empty"
    const val Error = "provider-list-error"
    const val AddProvider = "provider-list-add"
    const val NoCustomProvider = "provider-list-no-custom"

    fun provider(id: String): String = "provider-list-item-$id"

    fun providerOption(id: String): String = "provider-list-option-$id"
}

/**
 * Provider list.
 *
 * The list shows every stored provider with its non-secret `/key` metadata and
 * CORS status. The add-provider section lists contract presets but only
 * OpenRouter is selectable; custom creation is not offered.
 */
@Composable
fun ProviderListScreen(
    state: ProviderListUiState,
    onOpenProvider: (String) -> Unit,
    onRetry: () -> Unit,
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

        Text(
            text = "Custom providers are not available in this preview.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ProviderListTestTags.NoCustomProvider),
        )
    }
}
