package dev.chatui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.Density
import dev.chatui.core.model.ReasoningEffort
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Theme

/** Test tags exposed by the settings screens. */
object SettingsTestTags {
    const val Root = "settings-root"
    const val Appearance = "settings-appearance"
    const val ChatDefaults = "settings-chat-defaults"
    const val Providers = "settings-providers"
    const val Data = "settings-data"
    const val Backup = "settings-backup"
    const val About = "settings-about"
    const val Theme = "settings-theme"
    const val Density = "settings-density"
    const val ChatFontSize = "settings-chat-font-size"
    const val SendKey = "settings-send-key"
    const val ShowTokenUsage = "settings-show-token-usage"
    const val LoadRemoteImages = "settings-load-remote-images"
    const val DefaultProvider = "settings-default-provider"
    const val DefaultModel = "settings-default-model"
    const val DefaultSystemPrompt = "settings-default-system-prompt"
    const val DefaultTemperature = "settings-default-temperature"
    const val DefaultTopP = "settings-default-top-p"
    const val DefaultMaxOutput = "settings-default-max-output"
    const val DefaultStop = "settings-default-stop"
    const val DefaultStreaming = "settings-default-streaming"
    const val Error = "settings-error"

    fun theme(value: String): String = "settings-theme-$value"

    fun density(value: String): String = "settings-density-$value"

    fun fontSize(value: String): String = "settings-font-size-$value"

    fun sendKey(value: String): String = "settings-send-key-$value"

    fun reasoning(value: String): String = "settings-reasoning-$value"

    fun provider(id: String): String = "settings-provider-$id"

    fun model(id: String): String = "settings-model-$id"
}

/** Settings root with links to every section. */
@Composable
fun SettingsRootScreen(
    onOpenAppearance: () -> Unit,
    onOpenChatDefaults: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenData: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(SettingsTestTags.Root),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        SettingsLink("Appearance", SettingsTestTags.Appearance, onOpenAppearance)
        SettingsLink("Chat defaults", SettingsTestTags.ChatDefaults, onOpenChatDefaults)
        SettingsLink("Providers", SettingsTestTags.Providers, onOpenProviders)
        SettingsLink("Data", SettingsTestTags.Data, onOpenData)
        SettingsLink("Backup", SettingsTestTags.Backup, onOpenBackup)
        SettingsLink("About and privacy", SettingsTestTags.About, onOpenAbout)
    }
}

@Composable
private fun SettingsLink(label: String, tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag),
    ) {
        Text(label)
    }
}

/** Appearance settings: theme, density, and chat font size. */
@Composable
fun AppearanceSettingsScreen(
    settings: AndroidSettings,
    onThemeChanged: (Theme) -> Unit,
    onDensityChanged: (Density) -> Unit,
    onChatFontSizeChanged: (ChatFontSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(SettingsTestTags.Appearance),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Appearance", style = MaterialTheme.typography.headlineSmall)

        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Theme.entries.forEach { theme ->
                FilterChip(
                    selected = settings.theme == theme,
                    onClick = { onThemeChanged(theme) },
                    label = { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.theme(theme.name.lowercase())),
                )
            }
        }

        Text("Density", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Density.entries.forEach { density ->
                FilterChip(
                    selected = settings.density == density,
                    onClick = { onDensityChanged(density) },
                    label = { Text(density.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.density(density.name.lowercase())),
                )
            }
        }

        Text("Chat font size", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatFontSize.entries.forEach { size ->
                FilterChip(
                    selected = settings.chatFontSize == size,
                    onClick = { onChatFontSizeChanged(size) },
                    label = { Text(size.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.fontSize(size.name.lowercase())),
                )
            }
        }

        Text(
            text = "Reduced motion follows the operating-system preference.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Chat defaults: default provider/model, prompt, parameters, and behavior. */
@Composable
fun ChatDefaultsSettingsScreen(
    state: SettingsUiState,
    onDefaultProviderChanged: (String?) -> Unit,
    onDefaultModelChanged: (String?) -> Unit,
    onDefaultSystemPromptChanged: (String) -> Unit,
    onTemperatureChanged: (String) -> Unit,
    onTopPChanged: (String) -> Unit,
    onMaxOutputTokensChanged: (String) -> Unit,
    onStopChanged: (String) -> Unit,
    onReasoningEffortChanged: (ReasoningEffort?) -> Unit,
    onStreamingChanged: (Boolean) -> Unit,
    onSendKeyChanged: (SendKey) -> Unit,
    onShowTokenUsageChanged: (Boolean) -> Unit,
    onLoadRemoteImagesChanged: (Boolean) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val params = settings.defaultParams
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val temperatureText = rememberNumericText(params.temperature)
    val topPText = rememberNumericText(params.topP)
    val maxOutputText = rememberIntText(params.maxOutputTokens)
    val stopText = rememberStopText(params.stop)
    val providerLabel = state.providers.firstOrNull { it.id == settings.defaultProviderId }?.label
        ?: "First enabled provider"
    val modelLabel = state.models.firstOrNull { it.id == settings.defaultModelId }?.label
        ?: "Provider default"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(SettingsTestTags.ChatDefaults),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Chat defaults", style = MaterialTheme.typography.headlineSmall)

        state.error?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SettingsTestTags.Error),
                )
                TextButton(onClick = onDismissError) { Text("Dismiss") }
            }
        }

        Text("Default provider and model", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "New conversations start from these defaults.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box {
            TextButton(
                onClick = { providerMenu = true },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(SettingsTestTags.DefaultProvider),
            ) {
                Text("Provider: $providerLabel")
            }
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                DropdownMenuItem(
                    text = { Text("First enabled provider") },
                    onClick = {
                        providerMenu = false
                        onDefaultProviderChanged(null)
                    },
                )
                state.providers.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.label) },
                        onClick = {
                            providerMenu = false
                            onDefaultProviderChanged(provider.id)
                        },
                        modifier = Modifier.testTag(SettingsTestTags.provider(provider.id)),
                    )
                }
            }
        }

        Box {
            TextButton(
                onClick = { modelMenu = true },
                enabled = settings.defaultProviderId != null,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(SettingsTestTags.DefaultModel),
            ) {
                Text("Model: $modelLabel")
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Provider default") },
                    onClick = {
                        modelMenu = false
                        onDefaultModelChanged(null)
                    },
                )
                state.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.label) },
                        onClick = {
                            modelMenu = false
                            onDefaultModelChanged(model.id)
                        },
                        modifier = Modifier.testTag(SettingsTestTags.model(model.id)),
                    )
                }
            }
        }

        HorizontalDivider()

        Text("Prompt and parameters", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = settings.defaultSystemPrompt,
            onValueChange = onDefaultSystemPromptChanged,
            label = { Text("System prompt") },
            minLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTestTags.DefaultSystemPrompt),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = temperatureText.value,
                onValueChange = {
                    temperatureText.value = it
                    onTemperatureChanged(it)
                },
                label = { Text("Temperature") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsTestTags.DefaultTemperature),
            )
            OutlinedTextField(
                value = topPText.value,
                onValueChange = {
                    topPText.value = it
                    onTopPChanged(it)
                },
                label = { Text("Top P") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsTestTags.DefaultTopP),
            )
        }

        OutlinedTextField(
            value = maxOutputText.value,
            onValueChange = {
                maxOutputText.value = it
                onMaxOutputTokensChanged(it)
            },
            label = { Text("Max output tokens") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTestTags.DefaultMaxOutput),
        )

        OutlinedTextField(
            value = stopText.value,
            onValueChange = {
                stopText.value = it
                onStopChanged(it)
            },
            label = { Text("Stop sequences") },
            placeholder = { Text("One sequence per line") },
            minLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTestTags.DefaultStop),
        )

        Text("Reasoning effort", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = params.reasoningEffort == null,
                onClick = { onReasoningEffortChanged(null) },
                label = { Text("Default") },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(SettingsTestTags.reasoning("default")),
            )
            ReasoningEffort.entries.forEach { effort ->
                FilterChip(
                    selected = params.reasoningEffort == effort,
                    onClick = { onReasoningEffortChanged(effort) },
                    label = { Text(effort.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.reasoning(effort.name.lowercase())),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Stream responses by default")
            Switch(
                checked = params.streaming,
                onCheckedChange = onStreamingChanged,
                modifier = Modifier.testTag(SettingsTestTags.DefaultStreaming),
            )
        }

        HorizontalDivider()

        Text("Composer and privacy", style = MaterialTheme.typography.titleMedium)

        Text("Send key", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SendKey.entries.forEach { key ->
                FilterChip(
                    selected = settings.sendKey == key,
                    onClick = { onSendKeyChanged(key) },
                    label = {
                        Text(
                            when (key) {
                                SendKey.ENTER -> "Enter sends"
                                SendKey.MOD_ENTER -> "Mod+Enter sends"
                            },
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SettingsTestTags.sendKey(key.name.lowercase())),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show token usage")
            Switch(
                checked = settings.showTokenUsage,
                onCheckedChange = onShowTokenUsageChanged,
                modifier = Modifier.testTag(SettingsTestTags.ShowTokenUsage),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Load remote images")
            Switch(
                checked = settings.loadRemoteImages,
                onCheckedChange = onLoadRemoteImagesChanged,
                modifier = Modifier.testTag(SettingsTestTags.LoadRemoteImages),
            )
        }
        Text(
            text = "Remote images are blocked by default. When enabled, image URLs from " +
                "model output are requested directly from their host.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Data settings entry point reserved for M2-07. */
@Composable
fun DataSettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(SettingsTestTags.Data),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Data", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Storage usage, export, import, and clear-data controls arrive in M2-07.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Conversations and settings stay on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Backup placeholder for the post-M2 backup feature. */
@Composable
fun BackupSettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(SettingsTestTags.Backup),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Backup", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "ChatUI does not back up or sync data in this release.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "When backup arrives it will encrypt data before upload and keep " +
                "provider keys out of backup metadata.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** About and privacy information. */
@Composable
fun AboutSettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(SettingsTestTags.About),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("About", style = MaterialTheme.typography.headlineSmall)
        Text("ChatUI", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Version: development build",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "ChatUI is a local-first chat client. Conversations and settings live " +
                "on this device, and chat requests go directly to the provider you select.",
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Local conversation storage is not encrypted at rest in this phase.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Provider keys are encrypted with a device-bound key and are never " +
                "placed in URLs, logs, or exports.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Chat content is sent only to the selected provider.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Remote images in model output are blocked until you allow them.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "No analytics or telemetry are collected by ChatUI itself.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
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

@Composable
private fun rememberStopText(value: List<String>): MutableState<String> {
    val state = remember { mutableStateOf(value.joinToString("\n")) }

    LaunchedEffect(value) {
        val parsed = state.value
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parsed != value) {
            state.value = value.joinToString("\n")
        }
    }

    return state
}
