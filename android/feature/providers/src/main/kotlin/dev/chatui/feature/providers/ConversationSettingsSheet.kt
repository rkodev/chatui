package dev.chatui.feature.providers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.ReasoningEffort

/** Test tags exposed by the conversation settings sheet. */
object ConversationSettingsTestTags {
    const val Sheet = "conversation-settings-sheet"
    const val Title = "conversation-settings-title"
    const val Model = "conversation-settings-model"
    const val ModelMenu = "conversation-settings-model-menu"
    const val SystemPrompt = "conversation-settings-system-prompt"
    const val Temperature = "conversation-settings-temperature"
    const val TopP = "conversation-settings-top-p"
    const val MaxOutputTokens = "conversation-settings-max-output"
    const val Stop = "conversation-settings-stop"
    const val Streaming = "conversation-settings-streaming"
    const val Extras = "conversation-settings-extras"
    const val ExtrasError = "conversation-settings-extras-error"
    const val UnsupportedNote = "conversation-settings-unsupported-note"
    const val RoutingSummary = "conversation-settings-routing-summary"
    const val Export = "conversation-settings-export"
    const val Delete = "conversation-settings-delete"
    const val ConfirmDelete = "conversation-settings-confirm-delete"
    const val Error = "conversation-settings-error"

    fun model(modelId: String): String = "conversation-settings-model-$modelId"

    fun reasoning(effort: String): String = "conversation-settings-reasoning-$effort"
}

/**
 * Full-height conversation settings sheet.
 *
 * Renders the model, system prompt, generation parameters, provider extras,
 * routing summary, export entry point, and delete action from one immutable
 * state. Every intent is forwarded to the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSettingsSheet(
    state: ConversationSettingsUiState,
    onDismiss: () -> Unit,
    onSystemPromptChanged: (String) -> Unit,
    onTemperatureChanged: (String) -> Unit,
    onTopPChanged: (String) -> Unit,
    onMaxOutputTokensChanged: (String) -> Unit,
    onStopChanged: (String) -> Unit,
    onReasoningEffortChanged: (ReasoningEffort?) -> Unit,
    onStreamingChanged: (Boolean) -> Unit,
    onExtrasChanged: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    var confirmDelete by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val temperatureText = rememberNumericText(state.params.temperature)
    val topPText = rememberNumericText(state.params.topP)
    val maxOutputText = rememberIntText(state.params.maxOutputTokens)

    // The sheet and its delete confirmation are separate windows and normally
    // consume back themselves. These fallbacks share the same close functions
    // as onDismissRequest so back can never close both layers at once.
    BackHandler(enabled = !confirmDelete) { onDismiss() }
    BackHandler(enabled = confirmDelete) { confirmDelete = false }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier.testTag(ConversationSettingsTestTags.Sheet),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimens.padding)
                .padding(bottom = dimens.padding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Conversation settings",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag(ConversationSettingsTestTags.Title),
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = dimens.touchTargetMin),
                ) {
                    Text("Close")
                }
            }

            state.error?.let { error ->
                InlineBanner(
                    severity = BannerSeverity.Error,
                    title = "Could not update conversation settings",
                    body = error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ConversationSettingsTestTags.Error),
                    action = {
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    },
                )
            }

            HorizontalDivider()

            Text("Model", style = MaterialTheme.typography.titleMedium)
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { modelMenuExpanded = true },
                    enabled = !state.busy,
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ConversationSettingsTestTags.Model),
                ) {
                    Text(
                        text = state.model?.label
                            ?: state.conversation?.modelLabel
                            ?: "Choose model",
                    )
                }
                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                    modifier = Modifier.testTag(ConversationSettingsTestTags.ModelMenu),
                ) {
                    state.models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.label) },
                            onClick = {
                                modelMenuExpanded = false
                                onModelSelected(model.id)
                            },
                            modifier = Modifier.testTag(
                                ConversationSettingsTestTags.model(model.id),
                            ),
                        )
                    }
                }
            }
            Text(
                text = "Model changes apply to the next message and never rewrite history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("System prompt and parameters", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = state.systemPrompt,
                onValueChange = onSystemPromptChanged,
                label = { Text("System prompt") },
                placeholder = { Text("Optional instructions sent before the history") },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConversationSettingsTestTags.SystemPrompt),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
            ) {
                OutlinedTextField(
                    value = temperatureText.value,
                    onValueChange = {
                        temperatureText.value = it
                        onTemperatureChanged(it)
                    },
                    label = { Text("Temperature") },
                    placeholder = { Text("Model default") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ConversationSettingsTestTags.Temperature),
                )
                OutlinedTextField(
                    value = topPText.value,
                    onValueChange = {
                        topPText.value = it
                        onTopPChanged(it)
                    },
                    label = { Text("Top P") },
                    placeholder = { Text("Model default") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ConversationSettingsTestTags.TopP),
                )
            }

            OutlinedTextField(
                value = maxOutputText.value,
                onValueChange = {
                    maxOutputText.value = it
                    onMaxOutputTokensChanged(it)
                },
                label = { Text("Max output tokens") },
                placeholder = { Text("Model default") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConversationSettingsTestTags.MaxOutputTokens),
            )

            OutlinedTextField(
                value = state.stopText,
                onValueChange = onStopChanged,
                label = { Text("Stop sequences") },
                placeholder = { Text("One sequence per line") },
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConversationSettingsTestTags.Stop),
            )

            Text("Reasoning effort", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space1)) {
                ReasoningChip(
                    label = "Default",
                    selected = state.params.reasoningEffort == null,
                    tag = ConversationSettingsTestTags.reasoning("default"),
                    onClick = { onReasoningEffortChanged(null) },
                )
                ReasoningEffort.entries.forEach { effort ->
                    ReasoningChip(
                        label = effort.name.lowercase().replaceFirstChar { it.uppercase() },
                        selected = state.params.reasoningEffort == effort,
                        tag = ConversationSettingsTestTags.reasoning(effort.name.lowercase()),
                        onClick = { onReasoningEffortChanged(effort) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Stream responses")
                Switch(
                    checked = state.params.streaming,
                    onCheckedChange = onStreamingChanged,
                    modifier = Modifier.testTag(ConversationSettingsTestTags.Streaming),
                )
            }

            state.unsupportedNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ConversationSettingsTestTags.UnsupportedNote),
                )
            }

            HorizontalDivider()

            Text("Provider extras", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Advanced JSON for the ${state.presetId} namespace. " +
                    "Core fields such as model, messages, and stream cannot be overridden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.extrasText,
                onValueChange = onExtrasChanged,
                label = { Text("Extra JSON") },
                placeholder = { Text("{}") },
                minLines = 3,
                isError = state.extrasError != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConversationSettingsTestTags.Extras),
            )
            state.extrasError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(ConversationSettingsTestTags.ExtrasError),
                )
            }

            state.routingSummary?.let { summary ->
                Text(
                    text = "Routing: $summary",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(ConversationSettingsTestTags.RoutingSummary),
                )
            }

            HorizontalDivider()

            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConversationSettingsTestTags.Export),
            ) {
                Text("Export conversation (coming soon)")
            }

            TextButton(
                onClick = { confirmDelete = true },
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConversationSettingsTestTags.Delete),
            ) {
                Text("Delete conversation", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete conversation?") },
            text = { Text("This permanently removes the conversation and its messages.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    modifier = Modifier.testTag(ConversationSettingsTestTags.ConfirmDelete),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ReasoningChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current

    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier
            .heightIn(min = dimens.touchTargetMin)
            .testTag(tag),
    )
}

/**
 * Editable text for a nullable numeric field.
 *
 * The local text is the source of truth while typing so partial input such as
 * `0.` is not rewritten. It resyncs only when the persisted value changes to
 * something the local text does not already represent.
 */
@Composable
private fun rememberNumericText(value: Double?): MutableState<String> {
    val state = remember { mutableStateOf(value?.toString().orEmpty()) }

    LaunchedEffect(value) {
        val parsed = state.value.trim().toDoubleOrNull()
        if (parsed != value) {
            state.value = value?.toString().orEmpty()
        }
    }

    return state
}

@Composable
private fun rememberIntText(value: Int?): MutableState<String> {
    val state = remember { mutableStateOf(value?.toString().orEmpty()) }

    LaunchedEffect(value) {
        val parsed = state.value.trim().toIntOrNull()
        if (parsed != value) {
            state.value = value?.toString().orEmpty()
        }
    }

    return state
}
