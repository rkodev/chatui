package dev.chatui.feature.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Model

/** Test tags exposed by the per-conversation model control. */
object ConversationModelTestTags {
    const val Control = "conversation-model-control"
    const val Chip = "conversation-model-chip"
    const val Warning = "conversation-model-warning"
    const val ChangeButton = "conversation-model-change"
    const val Sheet = "conversation-model-sheet"
}

/**
 * Per-conversation model override.
 *
 * Selecting a model updates the conversation record immediately. The next send
 * reads the conversation record, so the new model applies without rewriting
 * past messages or their provider/model snapshots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationModelControl(
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
    var showPicker by remember { mutableStateOf(false) }
    val provider = state.providers.firstOrNull { it.id == state.providerId }
    val label = state.selectedModel?.label
        ?: state.conversation?.modelLabel
        ?: "Choose model"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ConversationModelTestTags.Control),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(dimens.space2),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag(ConversationModelTestTags.Chip),
                    )
                    Text(
                        text = provider?.label ?: "OpenRouter",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { showPicker = true },
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ConversationModelTestTags.ChangeButton),
                ) {
                    Text("Change model")
                }
            }
        }

        if (!state.availability.available) {
            state.availability.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ConversationModelTestTags.Warning),
                )
            }
        }

        state.error?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error)
        }
        state.notice?.let { notice -> Text(notice) }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.testTag(ConversationModelTestTags.Sheet),
        ) {
            ModelPickerContent(
                state = state,
                title = "Choose a model for this conversation",
                onQueryChanged = onQueryChanged,
                onSelect = { model ->
                    onSelect(model)
                    showPicker = false
                },
                onPin = onPin,
                onHide = onHide,
                onManualRemoteIdChanged = onManualRemoteIdChanged,
                onManualLabelChanged = onManualLabelChanged,
                onAddManual = onAddManual,
                onRefresh = onRefresh,
            )
        }
    }
}
