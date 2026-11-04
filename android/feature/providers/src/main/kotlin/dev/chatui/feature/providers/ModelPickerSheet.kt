package dev.chatui.feature.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Model

/** Test tags exposed by the model picker. */
object ModelPickerTestTags {
    const val Sheet = "model-picker-sheet"
    const val Content = "model-picker-content"
    const val Search = "model-picker-search"
    const val Warning = "model-picker-warning"
    const val Error = "model-picker-error"
    const val Notice = "model-picker-notice"
    const val Empty = "model-picker-empty"
    const val ManualId = "model-picker-manual-id"
    const val ManualLabel = "model-picker-manual-label"
    const val AddManual = "model-picker-add-manual"
    const val Refresh = "model-picker-refresh"
    const val Loading = "model-picker-loading"

    fun group(key: String): String = "model-picker-group-$key"

    fun model(id: String): String = "model-picker-model-$id"

    fun freeBadge(id: String): String = "model-picker-free-$id"

    fun pin(id: String): String = "model-picker-pin-$id"

    fun hide(id: String): String = "model-picker-hide-$id"

    fun details(id: String): String = "model-picker-details-$id"
}

/** Bottom-sheet wrapper around [ModelPickerContent]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    state: ModelPickerUiState,
    onDismiss: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSelect: (Model) -> Unit,
    onPin: (Model, Boolean) -> Unit,
    onHide: (Model, Boolean) -> Unit,
    onManualRemoteIdChanged: (String) -> Unit,
    onManualLabelChanged: (String) -> Unit,
    onAddManual: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Choose a model",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.testTag(ModelPickerTestTags.Sheet),
    ) {
        ModelPickerContent(
            state = state,
            title = title,
            onQueryChanged = onQueryChanged,
            onSelect = onSelect,
            onPin = onPin,
            onHide = onHide,
            onManualRemoteIdChanged = onManualRemoteIdChanged,
            onManualLabelChanged = onManualLabelChanged,
            onAddManual = onAddManual,
            onRefresh = onRefresh,
        )
    }
}

/**
 * Stateless model picker content.
 *
 * Search, grouping, pinned-first ordering, and availability warnings are pure
 * selectors. Pin/hide/manual-add/refresh actions are delegated to the caller so
 * the same content works for global defaults, per-conversation overrides, and
 * model management.
 */
@Composable
fun ModelPickerContent(
    state: ModelPickerUiState,
    onQueryChanged: (String) -> Unit,
    onSelect: (Model) -> Unit,
    onPin: (Model, Boolean) -> Unit,
    onHide: (Model, Boolean) -> Unit,
    onManualRemoteIdChanged: (String) -> Unit,
    onManualLabelChanged: (String) -> Unit,
    onAddManual: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Choose a model",
    header: (@Composable () -> Unit)? = null,
) {
    val dimens = LocalDimens.current

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ModelPickerTestTags.Content),
        contentPadding = PaddingValues(dimens.padding),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        item {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
        }

        header?.let { headerContent ->
            item { headerContent() }
        }

        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                label = { Text("Search models") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ModelPickerTestTags.Search),
            )
        }

        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ModelPickerTestTags.Error),
                )
            }
        }

        state.notice?.let { notice ->
            item {
                Text(
                    text = notice,
                    modifier = Modifier.testTag(ModelPickerTestTags.Notice),
                )
            }
        }

        if (!state.availability.available) {
            state.availability.message?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(ModelPickerTestTags.Warning),
                    )
                }
            }
        }

        if (state.loading) {
            item {
                Text(
                    text = "Loading models…",
                    modifier = Modifier.testTag(ModelPickerTestTags.Loading),
                )
            }
        }

        if (!state.loading && state.groups.isEmpty()) {
            item {
                Text(
                    text = "No models match your search.",
                    modifier = Modifier.testTag(ModelPickerTestTags.Empty),
                )
            }
        }

        state.groups.forEach { group ->
            item(key = "group-${group.key}") {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(ModelPickerTestTags.group(group.key)),
                )
            }
            items(group.models, key = { it.id }) { model ->
                ModelRow(
                    model = model,
                    selected = model.id == state.selectedModelId,
                    busy = state.busy,
                    onSelect = { onSelect(model) },
                    onPin = { onPin(model, !model.pinned) },
                    onHide = { onHide(model, !model.hidden) },
                )
            }
        }

        item {
            ManualAddSection(
                state = state,
                onManualRemoteIdChanged = onManualRemoteIdChanged,
                onManualLabelChanged = onManualLabelChanged,
                onAddManual = onAddManual,
            )
        }

        item {
            Button(
                onClick = onRefresh,
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ModelPickerTestTags.Refresh),
            ) {
                Text("Refresh models")
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: Model,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
) {
    val dimens = LocalDimens.current
    val free = isFreeModel(model)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ModelPickerTestTags.model(model.id)),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
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
                    Text(text = model.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = model.remoteId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (free) {
                    Text(
                        text = "Free",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(ModelPickerTestTags.freeBadge(model.id)),
                    )
                }
            }

            Text(
                text = "${formatContextWindow(model.contextWindow)} · " +
                    formatModalities(model),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = formatModelPrice(model),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space1)) {
                modelCapabilityChips(model).forEach { chip ->
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
                TextButton(
                    onClick = onSelect,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = dimens.touchTargetMin),
                ) {
                    Text(if (selected) "Selected" else "Select")
                }
                TextButton(
                    onClick = onPin,
                    enabled = !busy,
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ModelPickerTestTags.pin(model.id)),
                ) {
                    Text(if (model.pinned) "Unpin" else "Pin")
                }
                TextButton(
                    onClick = onHide,
                    enabled = !busy,
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ModelPickerTestTags.hide(model.id)),
                ) {
                    Text(if (model.hidden) "Unhide" else "Hide")
                }
            }

            ModelDetails(model)
        }
    }
}

@Composable
private fun ModelDetails(model: Model) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ModelPickerTestTags.details(model.id)),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Text("Details", style = MaterialTheme.typography.labelLarge)
        Text(
            text = "Canonical slug: ${model.canonicalSlug ?: "Not reported"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Max output: ${model.maxOutputTokens?.let { "$it tokens" } ?: "Not reported"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Upstream context: ${upstreamContext(model)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Supported parameters: " +
                (model.supportedParameters.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: "Not reported"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Moderation: ${moderationLabel(model)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ManualAddSection(
    state: ModelPickerUiState,
    onManualRemoteIdChanged: (String) -> Unit,
    onManualLabelChanged: (String) -> Unit,
    onAddManual: () -> Unit,
) {
    val dimens = LocalDimens.current

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        Text("Add a model manually", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.manualRemoteId,
            onValueChange = onManualRemoteIdChanged,
            label = { Text("Model id") },
            placeholder = { Text("author/model") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ModelPickerTestTags.ManualId),
        )
        OutlinedTextField(
            value = state.manualLabel,
            onValueChange = onManualLabelChanged,
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ModelPickerTestTags.ManualLabel),
        )
        Button(
            onClick = onAddManual,
            enabled = !state.busy,
            modifier = Modifier
                .heightIn(min = dimens.touchTargetMin)
                .testTag(ModelPickerTestTags.AddManual),
        ) {
            Text("Add model")
        }
    }
}
