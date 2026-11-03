package dev.chatui.feature.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Model

/** Test tags exposed by the manage-models screen. */
object ManageModelsTestTags {
    const val Screen = "manage-models-screen"
    const val Summary = "manage-models-summary"
}

/**
 * Manage-models screen.
 *
 * Hidden models stay visible here so they can be unhidden. Pin/hide/manual-add
 * and refresh persist through the model repository.
 */
@Composable
fun ManageModelsScreen(
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
) {
    val dimens = LocalDimens.current

    ModelPickerContent(
        state = state,
        title = "Manage models",
        modifier = modifier.testTag(ManageModelsTestTags.Screen),
        onQueryChanged = onQueryChanged,
        onSelect = onSelect,
        onPin = onPin,
        onHide = onHide,
        onManualRemoteIdChanged = onManualRemoteIdChanged,
        onManualLabelChanged = onManualLabelChanged,
        onAddManual = onAddManual,
        onRefresh = onRefresh,
        header = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(dimens.space1),
            ) {
                Text(
                    text = "${state.models.size} models cached. " +
                        "Pinned models appear first; hidden models stay visible here.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(ManageModelsTestTags.Summary),
                )
            }
        },
    )
}
