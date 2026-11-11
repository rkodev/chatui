package dev.chatui.feature.providers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import dev.chatui.core.model.Model

/** Test tags exposed by the model picker. */
object ModelPickerTestTags {
    const val Sheet = "model-picker-sheet"
    const val Content = "model-picker-content"
    const val Search = "model-picker-search"
    const val Filters = "model-picker-filters"
    const val Warning = "model-picker-warning"
    const val Error = "model-picker-error"
    const val Notice = "model-picker-notice"
    const val Empty = "model-picker-empty"
    const val ManualId = "model-picker-manual-id"
    const val ManualLabel = "model-picker-manual-label"
    const val AddManual = "model-picker-add-manual"
    const val Refresh = "model-picker-refresh"
    const val PullRefresh = "model-picker-pull-refresh"
    const val Loading = "model-picker-loading"

    fun group(key: String): String = "model-picker-group-$key"

    fun model(id: String): String = "model-picker-model-$id"

    fun freeBadge(id: String): String = "model-picker-free-$id"

    fun pin(id: String): String = "model-picker-pin-$id"

    fun hide(id: String): String = "model-picker-hide-$id"

    fun details(id: String): String = "model-picker-details-$id"

    fun menu(id: String): String = "model-picker-menu-$id"

    fun check(id: String): String = "model-picker-check-$id"

    fun filter(key: String): String = "model-picker-filter-$key"
}

private enum class ModelFilter(val label: String, val key: String) {
    All("All", "all"),
    Free("Free", "free"),
    Pinned("Pinned", "pinned"),
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

    // The sheet is a separate window and normally consumes back itself. This
    // fallback shares the same close function as onDismissRequest.
    BackHandler(enabled = true) { onDismiss() }

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
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    val colors = LocalChatUiColors.current
    var filter by rememberSaveable { mutableStateOf(ModelFilter.All) }
    val groups = remember(state.groups, filter) { filterGroups(state.groups, filter) }

    PullToRefreshBox(
        isRefreshing = state.loading && state.groups.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .testTag(ModelPickerTestTags.PullRefresh),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .testTag(ModelPickerTestTags.Content),
            contentPadding = PaddingValues(dimens.padding),
            verticalArrangement = Arrangement.spacedBy(dimens.gap),
        ) {
        item {
            Text(
                text = title,
                style = Typography.title1,
                color = colors.fgPrimary,
            )
        }

        header?.let { headerContent ->
            item { headerContent() }
        }

        stickyHeader {
            Surface(color = colors.bgCanvas) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = dimens.space2),
                    verticalArrangement = Arrangement.spacedBy(dimens.space2),
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChanged,
                        label = { Text("Search models") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ModelPickerTestTags.Search),
                    )
                    ModelFilterRow(
                        selected = filter,
                        onSelected = { filter = it },
                    )
                }
            }
        }

        state.error?.let { error ->
            item {
                ErrorState(
                    title = "Could not load models",
                    body = error,
                    onRetry = onRefresh,
                    modifier = Modifier.testTag(ModelPickerTestTags.Error),
                )
            }
        }

        state.notice?.let { notice ->
            item {
                InlineBanner(
                    severity = BannerSeverity.Info,
                    title = notice,
                    modifier = Modifier.testTag(ModelPickerTestTags.Notice),
                )
            }
        }

        if (!state.availability.available) {
            state.availability.message?.let { message ->
                item {
                    InlineBanner(
                        severity = BannerSeverity.Warning,
                        title = "Model unavailable",
                        body = message,
                        modifier = Modifier.testTag(ModelPickerTestTags.Warning),
                    )
                }
            }
        }

        if (state.loading) {
            item {
                SkeletonList(
                    modifier = Modifier.testTag(ModelPickerTestTags.Loading),
                    count = 5,
                )
            }
        }

        if (!state.loading && groups.isEmpty()) {
            item {
                EmptyState(
                    title = "No models found",
                    body = "Try a different search or refresh the catalog.",
                    modifier = Modifier.testTag(ModelPickerTestTags.Empty),
                )
            }
        }

        groups.forEach { group ->
            item(key = "group-${group.key}") {
                Text(
                    text = group.label,
                    style = Typography.title3,
                    color = colors.fgPrimary,
                    modifier = Modifier.testTag(ModelPickerTestTags.group(group.key)),
                )
            }
            items(group.models, key = { it.id }) { model ->
                ModelRow(
                    model = model,
                    providerLabel = state.providers
                        .firstOrNull { it.id == model.providerId }
                        ?.label
                        ?: model.providerId,
                    selected = model.id == state.selectedModelId,
                    busy = state.busy,
                    onSelect = { onSelect(model) },
                    onPin = { onPin(model, !model.pinned) },
                    onHide = { onHide(model, !model.hidden) },
                    modifier = Modifier.animateItem(),
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
}

private fun filterGroups(
    groups: List<ModelPickerGroup>,
    filter: ModelFilter,
): List<ModelPickerGroup> = when (filter) {
    ModelFilter.All -> groups
    ModelFilter.Free -> groups.mapNotNull { group ->
        group.models
            .filter(::isFreeModel)
            .takeIf { it.isNotEmpty() }
            ?.let { group.copy(models = it) }
    }

    ModelFilter.Pinned -> groups.filter { it.pinned }
}

@Composable
private fun ModelFilterRow(
    selected: ModelFilter,
    onSelected: (ModelFilter) -> Unit,
) {
    val dimens = LocalDimens.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ModelPickerTestTags.Filters),
        horizontalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        ModelFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelected(filter) },
                label = { Text(filter.label) },
                modifier = Modifier
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ModelPickerTestTags.filter(filter.key)),
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: Model,
    providerLabel: String,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val haptics = rememberHaptics()
    val free = isFreeModel(model)
    var menuExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ModelPickerTestTags.model(model.id))
            .clickable(enabled = !busy) {
                haptics.tick()
                onSelect()
            },
        shape = RoundedCornerShape(dimens.radiusMd),
        color = if (selected) colors.accentSoft else colors.bgSurfaceRaised,
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.label,
                        style = Typography.headline,
                        color = colors.fgPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = providerLabel,
                        style = Typography.footnote,
                        color = colors.fgSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (free) {
                    Text(
                        text = "Free",
                        style = Typography.caption,
                        color = colors.accent,
                        modifier = Modifier
                            .padding(horizontal = dimens.space1)
                            .testTag(ModelPickerTestTags.freeBadge(model.id)),
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = colors.accent,
                        modifier = Modifier
                            .size(20.dp)
                            .testTag(ModelPickerTestTags.check(model.id)),
                    )
                }
                Box {
                    IconAction(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = "Model actions",
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag(ModelPickerTestTags.menu(model.id)),
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (model.pinned) "Unpin" else "Pin") },
                            onClick = {
                                menuExpanded = false
                                haptics.tick()
                                onPin()
                            },
                            modifier = Modifier.testTag(ModelPickerTestTags.pin(model.id)),
                        )
                        DropdownMenuItem(
                            text = { Text(if (model.hidden) "Unhide" else "Hide") },
                            onClick = {
                                menuExpanded = false
                                haptics.tick()
                                onHide()
                            },
                            modifier = Modifier.testTag(ModelPickerTestTags.hide(model.id)),
                        )
                        DropdownMenuItem(
                            text = { Text(if (detailsExpanded) "Hide details" else "Details") },
                            onClick = {
                                menuExpanded = false
                                detailsExpanded = !detailsExpanded
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space1)) {
                ModelChip(formatContextWindow(model.contextWindow))
                ModelChip(formatModelPrice(model))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space1)) {
                modelCapabilityChips(model).forEach { chip ->
                    ModelChip(chip)
                }
            }

            if (detailsExpanded) {
                ModelDetails(model)
            }
        }
    }
}

@Composable
private fun ModelChip(text: String) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current

    Surface(
        shape = RoundedCornerShape(dimens.radiusFull),
        color = colors.bgGrouped,
    ) {
        Text(
            text = text,
            style = Typography.caption,
            color = colors.fgSecondary,
            modifier = Modifier.padding(
                horizontal = dimens.space2,
                vertical = dimens.space1,
            ),
        )
    }
}

@Composable
private fun ModelDetails(model: Model) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ModelPickerTestTags.details(model.id)),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Text(
            text = "Details",
            style = Typography.footnote,
            color = colors.fgMuted,
        )
        Text(
            text = "Canonical slug: ${model.canonicalSlug ?: "Not reported"}",
            style = Typography.footnote,
            color = colors.fgSecondary,
        )
        Text(
            text = "Max output: ${model.maxOutputTokens?.let { "$it tokens" } ?: "Not reported"}",
            style = Typography.footnote,
            color = colors.fgSecondary,
        )
        Text(
            text = "Upstream context: ${upstreamContext(model)}",
            style = Typography.footnote,
            color = colors.fgSecondary,
        )
        Text(
            text = "Supported parameters: " +
                (model.supportedParameters.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: "Not reported"),
            style = Typography.footnote,
            color = colors.fgSecondary,
        )
        Text(
            text = "Moderation: ${moderationLabel(model)}",
            style = Typography.footnote,
            color = colors.fgSecondary,
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
        Text(
            text = "Add a model manually",
            style = Typography.title3,
            color = LocalChatUiColors.current.fgPrimary,
        )
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
