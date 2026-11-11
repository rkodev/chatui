package dev.chatui.feature.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalDimens

/** Test tags exposed by the conversation routing controls. */
object ConversationRoutingTestTags {
    const val Controls = "conversation-routing-controls"
    const val Enabled = "conversation-routing-enabled"
    const val AllowFallbacks = "conversation-routing-fallbacks"
    const val DenyDataCollection = "conversation-routing-deny"
    const val Warning = "conversation-routing-warning"
    const val Summary = "conversation-routing-summary"
    const val Error = "conversation-routing-error"

    fun sort(value: String): String = "conversation-routing-sort-$value"
}

/**
 * Curated OpenRouter routing controls for one conversation.
 *
 * Values are stored in `conversation.params.extra.openrouter.provider` and
 * persisted immediately. The summary is shown only when routing is active.
 */
@Composable
fun ConversationRoutingControls(
    state: ConversationRoutingUiState,
    onEnabledChanged: (Boolean) -> Unit,
    onAllowFallbacksChanged: (Boolean) -> Unit,
    onSortChanged: (RoutingSort?) -> Unit,
    onDenyDataCollectionChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val preferences = state.preferences
    val enabled = preferences.enabled && !state.busy

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ConversationRoutingTestTags.Controls),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text("OpenRouter routing", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Use custom routing preferences")
            Switch(
                checked = preferences.enabled,
                onCheckedChange = onEnabledChanged,
                enabled = !state.busy,
                modifier = Modifier.testTag(ConversationRoutingTestTags.Enabled),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Allow provider fallbacks")
            Switch(
                checked = preferences.allowFallbacks,
                onCheckedChange = onAllowFallbacksChanged,
                enabled = enabled,
                modifier = Modifier.testTag(ConversationRoutingTestTags.AllowFallbacks),
            )
        }

        Text("Sort providers by", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space1)) {
            SortChip(
                label = "Default",
                selected = preferences.sort == null,
                enabled = enabled,
                tag = ConversationRoutingTestTags.sort("default"),
                onClick = { onSortChanged(null) },
            )
            RoutingSort.entries.forEach { sort ->
                SortChip(
                    label = sort.wireValue.replaceFirstChar { it.uppercase() },
                    selected = preferences.sort == sort,
                    enabled = enabled,
                    tag = ConversationRoutingTestTags.sort(sort.wireValue),
                    onClick = { onSortChanged(sort) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Do not use providers that may train on prompts")
            Switch(
                checked = preferences.denyDataCollection,
                onCheckedChange = onDenyDataCollectionChanged,
                enabled = enabled,
                modifier = Modifier.testTag(ConversationRoutingTestTags.DenyDataCollection),
            )
        }

        Text(
            text = "Denying data collection can make some models unavailable and may " +
                "exclude free endpoints.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ConversationRoutingTestTags.Warning),
        )

        state.summary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(ConversationRoutingTestTags.Summary),
            )
        }

        state.error?.let { error ->
            InlineBanner(
                severity = BannerSeverity.Error,
                title = "Could not update routing",
                body = error,
                modifier = Modifier.testTag(ConversationRoutingTestTags.Error),
            )
        }
    }
}

@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current

    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        modifier = Modifier
            .heightIn(min = dimens.touchTargetMin)
            .testTag(tag)
            .padding(end = dimens.space1),
    )
}
