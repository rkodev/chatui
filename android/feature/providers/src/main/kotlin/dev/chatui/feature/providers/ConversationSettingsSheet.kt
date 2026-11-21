package dev.chatui.feature.providers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.ReasoningEffort

object ConversationSettingsTestTags {
    const val Sheet = "conversation-settings-sheet"
    const val Title = "conversation-settings-title"
    const val Model = "conversation-settings-model"
    const val SystemPrompt = "conversation-settings-system-prompt"
    const val Generation = "conversation-settings-generation"
    const val GenerationSummary = "conversation-settings-generation-summary"
    const val Temperature = "conversation-settings-temperature"
    const val TopP = "conversation-settings-top-p"
    const val MaxOutputTokens = "conversation-settings-max-output"
    const val Stop = "conversation-settings-stop"
    const val Streaming = "conversation-settings-streaming"
    const val Advanced = "conversation-settings-advanced"
    const val AdvancedSummary = "conversation-settings-advanced-summary"
    const val Extras = "conversation-settings-extras"
    const val ExtrasError = "conversation-settings-extras-error"
    const val UnsupportedNote = "conversation-settings-unsupported-note"
    const val RoutingSummary = "conversation-settings-routing-summary"
    const val Export = "conversation-settings-export"
    const val Delete = "conversation-settings-delete"
    const val ConfirmDelete = "conversation-settings-confirm-delete"
    const val Error = "conversation-settings-error"
    const val Retry = "conversation-settings-retry"

    fun reset(field: String): String = "conversation-settings-reset-$field"
    fun reasoning(effort: String): String = "conversation-settings-reasoning-$effort"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onOpenModelPicker: () -> Unit,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    routingControls: (@Composable () -> Unit)? = null,
    pickerOpen: Boolean = false,
    pickerReturnVersion: Int = 0,
    modelPickerAvailable: Boolean = true,
) {
    val dimens = LocalDimens.current
    var confirmDelete by remember { mutableStateOf(false) }
    var generationExpanded by rememberSaveable(state.conversation?.id) { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable(state.conversation?.id) { mutableStateOf(false) }
    val modelFocus = remember { FocusRequester() }
    var handledPickerReturn by rememberSaveable(state.conversation?.id) { mutableStateOf(0) }
    val suspended by rememberUpdatedState(pickerOpen)
    val enabled = state.conversation != null && !state.loading && !state.busy && !pickerOpen

    LaunchedEffect(pickerReturnVersion, state.loading, pickerOpen) {
        if (pickerReturnVersion > handledPickerReturn && !state.loading && !pickerOpen) {
            withFrameNanos { }
            modelFocus.requestFocus()
            handledPickerReturn = pickerReturnVersion
        }
    }

    BackHandler(enabled = !confirmDelete && !pickerOpen) { onDismiss() }
    BackHandler(enabled = confirmDelete && !pickerOpen) { confirmDelete = false }

    ModalBottomSheet(
        onDismissRequest = { if (!pickerOpen) onDismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !suspended },
        ),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !pickerOpen),
        modifier = modifier.testTag(ConversationSettingsTestTags.Sheet),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(dimens.padding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
            ) {
                Text(
                    "Conversation settings",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag(ConversationSettingsTestTags.Title),
                )
                TextButton(onClick = onDismiss, enabled = !pickerOpen) { Text("Close") }
            }
            Text(
                "Changes apply immediately to this conversation.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.error?.let { error ->
                InlineBanner(
                    severity = BannerSeverity.Error,
                    title = if (state.hasPendingChanges) "Changes have not been saved" else "Could not update conversation settings",
                    body = error,
                    modifier = Modifier.testTag(ConversationSettingsTestTags.Error),
                    action = {
                        FlowRow {
                            TextButton(
                                onClick = onRetry,
                                modifier = Modifier.testTag(ConversationSettingsTestTags.Retry),
                            ) { Text("Retry") }
                            if (!state.hasPendingChanges) {
                                TextButton(onClick = onDismissError) { Text("Dismiss") }
                            }
                        }
                    },
                )
            }
            if (state.saving) Text("Saving changes...", style = MaterialTheme.typography.bodySmall)

            Text("Model", style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = onOpenModelPicker,
                enabled = enabled && modelPickerAvailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(modelFocus)
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(ConversationSettingsTestTags.Model),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(state.model?.label ?: state.conversation?.modelLabel ?: "Choose model")
                    Text("Change model for this conversation", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Model changes apply to the next message and never rewrite history.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = state.systemPrompt,
                onValueChange = onSystemPromptChanged,
                label = { Text("System prompt") },
                placeholder = { Text("Optional instructions sent before the history") },
                enabled = enabled,
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag(ConversationSettingsTestTags.SystemPrompt),
            )

            HorizontalDivider()
            ProviderSectionToggle(
                title = "Generation",
                expanded = generationExpanded,
                onClick = { generationExpanded = !generationExpanded },
                modifier = Modifier.testTag(ConversationSettingsTestTags.Generation),
            )
            Text(
                state.generationSummary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ConversationSettingsTestTags.GenerationSummary),
            )
            if (generationExpanded) {
                GenerationNumber(
                    label = "Temperature",
                    text = state.temperatureText,
                    error = state.parameterErrors[CoreParameterKey.TEMPERATURE],
                    onChange = onTemperatureChanged,
                    keyboardType = KeyboardType.Decimal,
                    enabled = enabled,
                    tag = ConversationSettingsTestTags.Temperature,
                    resetTag = ConversationSettingsTestTags.reset("temperature"),
                )
                GenerationNumber(
                    label = "Top P",
                    text = state.topPText,
                    error = state.parameterErrors[CoreParameterKey.TOP_P],
                    onChange = onTopPChanged,
                    keyboardType = KeyboardType.Decimal,
                    enabled = enabled,
                    tag = ConversationSettingsTestTags.TopP,
                    resetTag = ConversationSettingsTestTags.reset("top-p"),
                )
                GenerationNumber(
                    label = "Max output tokens",
                    text = state.maxOutputText,
                    error = state.parameterErrors[CoreParameterKey.MAX_OUTPUT_TOKENS],
                    onChange = onMaxOutputTokensChanged,
                    keyboardType = KeyboardType.Number,
                    enabled = enabled,
                    tag = ConversationSettingsTestTags.MaxOutputTokens,
                    resetTag = ConversationSettingsTestTags.reset("max-output"),
                )
                OutlinedTextField(
                    value = state.stopText,
                    onValueChange = onStopChanged,
                    label = { Text("Stop sequences") },
                    placeholder = { Text("One sequence per line; empty uses model default") },
                    enabled = enabled,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag(ConversationSettingsTestTags.Stop),
                )
                Text("Reasoning effort", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.space1)) {
                    ReasoningChip(
                        label = "Default",
                        selected = state.params.reasoningEffort == null,
                        enabled = enabled,
                        tag = ConversationSettingsTestTags.reasoning("default"),
                        onClick = { onReasoningEffortChanged(null) },
                    )
                    ReasoningEffort.entries.forEach { effort ->
                        ReasoningChip(
                            label = effort.name.lowercase().replaceFirstChar { it.uppercase() },
                            selected = state.params.reasoningEffort == effort,
                            enabled = enabled,
                            tag = ConversationSettingsTestTags.reasoning(effort.name.lowercase()),
                            onClick = { onReasoningEffortChanged(effort) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stream responses", modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.params.streaming,
                        onCheckedChange = onStreamingChanged,
                        enabled = enabled,
                        modifier = Modifier.testTag(ConversationSettingsTestTags.Streaming)
                            .semantics { contentDescription = "Stream responses" },
                    )
                }
            }
            state.unsupportedNote?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(ConversationSettingsTestTags.UnsupportedNote),
                )
            }

            HorizontalDivider()
            ProviderSectionToggle(
                title = "Advanced",
                expanded = advancedExpanded,
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier.testTag(ConversationSettingsTestTags.Advanced),
            )
            Text(
                state.advancedSummary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ConversationSettingsTestTags.AdvancedSummary),
            )
            if (advancedExpanded) {
                Text(
                    "Full provider extras JSON for ${state.presetId}. Core fields such as " +
                        "model, messages, stream, and stream_options cannot be overridden.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = state.extrasText,
                    onValueChange = onExtrasChanged,
                    label = { Text("Extra JSON") },
                    placeholder = { Text("{}") },
                    enabled = enabled,
                    minLines = 3,
                    isError = state.extrasError != null,
                    modifier = Modifier.fillMaxWidth().testTag(ConversationSettingsTestTags.Extras),
                )
                state.extrasError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(ConversationSettingsTestTags.ExtrasError),
                    )
                }
                routingControls?.invoke()
                state.routingSummary?.let { summary ->
                    Text(
                        "Routing: $summary",
                        modifier = Modifier.testTag(ConversationSettingsTestTags.RoutingSummary),
                    )
                }
            }

            HorizontalDivider()
            Text(
                "File export is not available yet. Share text or Markdown from More.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ConversationSettingsTestTags.Export),
            )
            TextButton(
                onClick = { confirmDelete = true },
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag(ConversationSettingsTestTags.Delete),
            ) {
                Text("Delete conversation")
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
                    onClick = { confirmDelete = false; onDelete() },
                    modifier = Modifier.testTag(ConversationSettingsTestTags.ConfirmDelete),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun GenerationNumber(
    label: String,
    text: String,
    error: String?,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType,
    enabled: Boolean,
    tag: String,
    resetTag: String,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("Model default") },
        supportingText = { Text(error ?: "Empty uses model default.") },
        isError = error != null,
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
    TextButton(
        onClick = { onChange("") },
        enabled = enabled && text.isNotEmpty(),
        modifier = Modifier.testTag(resetTag)
            .semantics { contentDescription = "Use model default for $label" },
    ) {
        Text("Use model default")
    }
}

@Composable
private fun ReasoningChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = LocalDimens.current.touchTargetMin).testTag(tag),
    )
}
