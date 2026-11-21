package dev.chatui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.GroupedList
import dev.chatui.core.designsystem.GroupedSection
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LabeledSlider
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.SettingsRow
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.Density
import dev.chatui.core.model.ReasoningEffort
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Theme
import java.util.Locale
import kotlinx.coroutines.delay

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
    const val HapticFeedback = "settings-haptic-feedback"
    const val DynamicColor = "settings-dynamic-color"
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
    const val Saved = "settings-saved"
    const val Preview = "settings-preview"
    const val Generation = "settings-generation"
    const val Composer = "settings-composer"
    const val Privacy = "settings-privacy"
    const val ModelWarning = "settings-model-warning"

    fun reset(field: String): String = "settings-reset-$field"
    fun theme(value: String): String = "settings-theme-$value"

    fun density(value: String): String = "settings-density-$value"

    fun fontSize(value: String): String = "settings-font-size-$value"

    fun sendKey(value: String): String = "settings-send-key-$value"

    fun reasoning(value: String): String = "settings-reasoning-$value"

    fun provider(id: String): String = "settings-provider-$id"

    fun model(id: String): String = "settings-model-$id"
}

/** Settings root with grouped links to every section. */
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
    val dimens = LocalDimens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = dimens.sectionGap)
            .testTag(SettingsTestTags.Root),
    ) {
        GroupedList {
            GroupedSection(title = "Preferences") {
                SettingsRow(
                    label = "Appearance",
                    value = "Theme, text size, and feedback",
                    icon = Icons.Rounded.Palette,
                    showChevron = true,
                    onClick = onOpenAppearance,
                    modifier = Modifier.testTag(SettingsTestTags.Appearance),
                )
                SettingsRow(
                    label = "Chat defaults",
                    value = "Model, prompt, and behavior",
                    icon = Icons.AutoMirrored.Rounded.Chat,
                    showChevron = true,
                    onClick = onOpenChatDefaults,
                    modifier = Modifier.testTag(SettingsTestTags.ChatDefaults),
                )
                SettingsRow(
                    label = "Providers",
                    icon = Icons.Rounded.Extension,
                    showChevron = true,
                    onClick = onOpenProviders,
                    modifier = Modifier.testTag(SettingsTestTags.Providers),
                )
            }
            Spacer(Modifier.height(dimens.sectionGap))
            GroupedSection(title = "Data") {
                SettingsRow(
                    label = "Data & storage",
                    icon = Icons.Rounded.Storage,
                    showChevron = true,
                    onClick = onOpenData,
                    modifier = Modifier.testTag(SettingsTestTags.Data),
                )
                SettingsRow(
                    label = "Encrypted backup",
                    icon = Icons.Rounded.Backup,
                    showChevron = true,
                    onClick = onOpenBackup,
                    modifier = Modifier.testTag(SettingsTestTags.Backup),
                )
            }
            Spacer(Modifier.height(dimens.sectionGap))
            GroupedSection(title = "About") {
                SettingsRow(
                    label = "About and privacy",
                    icon = Icons.Rounded.Info,
                    showChevron = true,
                    onClick = onOpenAbout,
                    modifier = Modifier.testTag(SettingsTestTags.About),
                )
            }
        }
    }
}

/** Appearance settings: theme, density, font size, haptics, and dynamic color. */
@Composable
fun AppearanceSettingsScreen(
    settings: AndroidSettings,
    onThemeChanged: (Theme) -> Unit,
    onDensityChanged: (Density) -> Unit,
    onChatFontSizeChanged: (ChatFontSize) -> Unit,
    onHapticFeedbackChanged: (Boolean) -> Unit = {},
    onDynamicColorChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    feedback: SettingsUiState = SettingsUiState(loading = false),
    onRetry: () -> Unit = {},
    onDismissError: () -> Unit = {},
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = dimens.sectionGap)
            .testTag(SettingsTestTags.Appearance),
    ) {
        SettingsFeedback(feedback, onRetry)
        GroupedList {
            GroupedSection(
                title = "Display",
                footer = "Changes apply immediately. Reduced motion follows the system.",
            ) {
                Column(
                    modifier = Modifier.padding(dimens.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(dimens.space3),
                ) {
                    Text("Theme", style = Typography.body, color = colors.fgPrimary)
                    SettingsSegmentedControl(
                        options = Theme.entries.map { it.label() },
                        selectedIndex = Theme.entries.indexOf(settings.theme),
                        onSelected = { onThemeChanged(Theme.entries[it]) },
                        tags = Theme.entries.map { SettingsTestTags.theme(it.name.lowercase()) },
                        modifier = Modifier.testTag(SettingsTestTags.Theme),
                        swatches = Theme.entries.map {
                            when (it) {
                                Theme.LIGHT -> Color.White
                                Theme.DARK -> Color(0xFF1C1C1E)
                                Theme.SYSTEM -> colors.accent
                            }
                        },
                    )
                    Text("Density", style = Typography.body, color = colors.fgPrimary)
                    SettingsSegmentedControl(
                        options = Density.entries.map { it.label() },
                        selectedIndex = Density.entries.indexOf(settings.density),
                        onSelected = { onDensityChanged(Density.entries[it]) },
                        tags = Density.entries.map { SettingsTestTags.density(it.name.lowercase()) },
                        modifier = Modifier.testTag(SettingsTestTags.Density),
                    )
                    Text("Chat font size", style = Typography.body, color = colors.fgPrimary)
                    SettingsSegmentedControl(
                        options = ChatFontSize.entries.map { it.label() },
                        selectedIndex = ChatFontSize.entries.indexOf(settings.chatFontSize),
                        onSelected = { onChatFontSizeChanged(ChatFontSize.entries[it]) },
                        tags = ChatFontSize.entries.map { SettingsTestTags.fontSize(it.name.lowercase()) },
                        modifier = Modifier.testTag(SettingsTestTags.ChatFontSize),
                    )
                    ThemePreview(settings)
                }
            }
            Spacer(Modifier.height(dimens.sectionGap))
            GroupedSection(title = "Feedback") {
                SettingsToggleRow(
                    label = "Haptic feedback",
                    description = "Allow app haptics when system vibration is enabled.",
                    icon = Icons.Rounded.Vibration,
                    checked = settings.hapticFeedback,
                    onCheckedChange = onHapticFeedbackChanged,
                    tag = SettingsTestTags.HapticFeedback,
                )
                SettingsToggleRow(
                    label = "Dynamic color",
                    description = "Use the system wallpaper palette on Android 12 and later.",
                    icon = Icons.Rounded.ColorLens,
                    checked = settings.dynamicColor,
                    onCheckedChange = onDynamicColorChanged,
                    tag = SettingsTestTags.DynamicColor,
                    showDivider = false,
                )
            }
        }
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
    onOpenModelPicker: () -> Unit = {},
    pickerReturnVersion: Int = 0,
    onRetry: () -> Unit = {},
) {
    val settings = state.settings
    val params = settings.defaultParams
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    var providerMenu by remember { mutableStateOf(false) }
    var generationExpanded by rememberSaveable { mutableStateOf(false) }
    var composerExpanded by rememberSaveable { mutableStateOf(false) }
    var privacyExpanded by rememberSaveable { mutableStateOf(false) }
    val modelFocus = remember { FocusRequester() }
    val temperatureText = rememberNumberText(params.temperature)
    val topPText = rememberNumberText(params.topP)
    val maxOutputText = rememberIntText(params.maxOutputTokens)
    val stopText = rememberStopText(params.stop)
    val providerLabel = state.providers.firstOrNull { it.id == settings.defaultProviderId }?.label
        ?: settings.defaultProviderId ?: "First enabled provider"
    val modelLabel = state.models.firstOrNull { it.id == settings.defaultModelId }?.label
        ?: settings.defaultModelId ?: "Provider default"

    LaunchedEffect(pickerReturnVersion) {
        if (pickerReturnVersion > 0) {
            withFrameNanos { }
            modelFocus.requestFocus()
        }
    }

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = dimens.sectionGap)
                .testTag(SettingsTestTags.ChatDefaults),
        ) {
            SettingsFeedback(state, onRetry)

            GroupedList {
                GroupedSection(
                    title = "Model",
                    footer = "Provider, model, prompt, and generation defaults affect new " +
                        "conversations, not existing chats. Composer and privacy apply app-wide.",
                ) {
                    Box {
                        SettingsRow(
                            label = "Default provider",
                            value = providerLabel,
                            showChevron = true,
                            onClick = { providerMenu = true },
                            modifier = Modifier.testTag(SettingsTestTags.DefaultProvider),
                        )
                        DropdownMenu(
                            expanded = providerMenu,
                            onDismissRequest = { providerMenu = false },
                        ) {
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
                                    modifier = Modifier.testTag(
                                        SettingsTestTags.provider(provider.id),
                                    ),
                                )
                            }
                        }
                    }
                        SettingsRow(
                            label = "Default model",
                            value = modelLabel,
                            showChevron = true,
                            onClick = onOpenModelPicker,
                            modifier = Modifier.focusRequester(modelFocus)
                                .testTag(SettingsTestTags.DefaultModel),
                            showDivider = false,
                        )
                        if (settings.defaultModelId != null) {
                            TextButton(
                                onClick = { onDefaultModelChanged(null) },
                                modifier = Modifier.testTag(SettingsTestTags.reset("model")),
                            ) { Text("Use provider default") }
                        }
                    state.warning?.let {
                        InlineBanner(
                            severity = BannerSeverity.Warning,
                            title = "Default model unavailable",
                            body = it,
                            modifier = Modifier.padding(dimens.cardPadding)
                                .testTag(SettingsTestTags.ModelWarning),
                        )
                    }
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space3),
                    ) {
                        OutlinedTextField(
                            value = settings.defaultSystemPrompt,
                            onValueChange = onDefaultSystemPromptChanged,
                            label = { Text("System prompt") },
                            minLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SettingsTestTags.DefaultSystemPrompt),
                        )
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                SettingsDisclosure(
                    title = "Generation",
                    summary = generationSummary(params),
                    tag = SettingsTestTags.Generation,
                    expanded = generationExpanded,
                    onExpandedChange = { generationExpanded = it },
                ) {
                        SettingsNumber(
                            label = "Temperature",
                            text = temperatureText.value,
                            error = state.fieldErrors["temperature"],
                            tag = SettingsTestTags.DefaultTemperature,
                            field = "temperature",
                            onChange = { temperatureText.value = it; onTemperatureChanged(it) },
                        )
                        LabeledSlider(
                            label = "Temperature",
                            value = params.temperature?.toFloat() ?: 0.7f,
                            onValueChange = {
                                temperatureText.value = it.toString()
                                onTemperatureChanged(it.toString())
                            },
                            valueRange = 0f..2f,
                            valueText = params.temperature?.let {
                                String.format(Locale.US, "%.2f", it)
                            } ?: "Default",
                        )
                        SettingsNumber(
                            label = "Top P",
                            text = topPText.value,
                            error = state.fieldErrors["top-p"],
                            tag = SettingsTestTags.DefaultTopP,
                            field = "top-p",
                            onChange = { topPText.value = it; onTopPChanged(it) },
                        )
                        LabeledSlider(
                            label = "Top P",
                            value = params.topP?.toFloat() ?: 1f,
                            onValueChange = {
                                topPText.value = it.toString()
                                onTopPChanged(it.toString())
                            },
                            valueRange = 0f..1f,
                            valueText = params.topP?.let {
                                String.format(Locale.US, "%.2f", it)
                            } ?: "Default",
                        )
                        SettingsNumber(
                            text = maxOutputText.value,
                            onChange = {
                                maxOutputText.value = it
                                onMaxOutputTokensChanged(it)
                            },
                            label = "Max output tokens",
                            error = state.fieldErrors["max-output"],
                            keyboardType = KeyboardType.Number,
                            field = "max-output",
                            tag = SettingsTestTags.DefaultMaxOutput,
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
                        Text(
                            text = "Reasoning effort",
                            style = Typography.body,
                            color = colors.fgPrimary,
                        )
                        SettingsSegmentedControl(
                            options = listOf("Default") +
                                ReasoningEffort.entries.map { it.label() },
                            selectedIndex = params.reasoningEffort?.let {
                                ReasoningEffort.entries.indexOf(it) + 1
                            } ?: 0,
                            onSelected = { index ->
                                onReasoningEffortChanged(
                                    if (index == 0) {
                                        null
                                    } else {
                                        ReasoningEffort.entries[index - 1]
                                    },
                                )
                            },
                            tags = listOf(SettingsTestTags.reasoning("default")) +
                                ReasoningEffort.entries.map {
                                    SettingsTestTags.reasoning(it.name.lowercase())
                                },
                        )
                        SettingsToggleRow(
                            label = "Stream responses by default",
                            checked = params.streaming,
                            onCheckedChange = onStreamingChanged,
                            tag = SettingsTestTags.DefaultStreaming,
                            showDivider = false,
                        )
                }
                Spacer(Modifier.height(dimens.sectionGap))
                SettingsDisclosure(
                    title = "Composer",
                    summary = settings.sendKey.label(),
                    tag = SettingsTestTags.Composer,
                    expanded = composerExpanded,
                    onExpandedChange = { composerExpanded = it },
                ) {
                        Text(
                            text = "Send key",
                            style = Typography.body,
                            color = colors.fgPrimary,
                        )
                        SettingsSegmentedControl(
                            options = SendKey.entries.map { it.label() },
                            selectedIndex = SendKey.entries.indexOf(settings.sendKey),
                            onSelected = { onSendKeyChanged(SendKey.entries[it]) },
                            tags = SendKey.entries.map {
                                SettingsTestTags.sendKey(it.name.lowercase())
                            },
                            modifier = Modifier.testTag(SettingsTestTags.SendKey),
                        )
                        Text(
                            "Shift+Enter inserts a new line. Mod means Ctrl on most " +
                                "keyboards or Command on macOS. The Send button is always available.",
                            style = Typography.footnote,
                            color = colors.fgSecondary,
                        )
                }
                Spacer(Modifier.height(dimens.sectionGap))
                SettingsDisclosure(
                    title = "Privacy",
                    summary = "Token usage ${if (settings.showTokenUsage) "shown" else "hidden"}; " +
                        "remote images ${if (settings.loadRemoteImages) "on" else "off"}",
                    tag = SettingsTestTags.Privacy,
                    expanded = privacyExpanded,
                    onExpandedChange = { privacyExpanded = it },
                ) {
                    SettingsToggleRow(
                        label = "Show token usage",
                        description = "Show reported prompt and completion token counts.",
                        checked = settings.showTokenUsage,
                        onCheckedChange = onShowTokenUsageChanged,
                        tag = SettingsTestTags.ShowTokenUsage,
                    )
                    SettingsToggleRow(
                        label = "Load remote images",
                        description = "When on, images in responses contact their hosts directly. " +
                            "Turn off to block those requests. Enabled for new installs.",
                        checked = settings.loadRemoteImages,
                        onCheckedChange = onLoadRemoteImagesChanged,
                        tag = SettingsTestTags.LoadRemoteImages,
                        showDivider = false,
                    )
                }
            }
        }
}

/** About and privacy information. */
@Composable
fun AboutSettingsScreen(modifier: Modifier = Modifier) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = dimens.sectionGap)
            .testTag(SettingsTestTags.About),
    ) {
        GroupedList {
            GroupedSection {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dimens.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(dimens.space2),
                ) {
                    Text(
                        text = "ChatUI",
                        style = Typography.title3,
                        color = colors.fgPrimary,
                    )
                    Text(
                        text = "Version: development build",
                        style = Typography.footnote,
                        color = colors.fgMuted,
                    )
                }
            }
            Spacer(Modifier.height(dimens.sectionGap))
            GroupedSection(
                title = "Privacy",
                footer = "ChatUI is a local-first chat client. Conversations and settings " +
                    "live on this device, and chat requests go directly to the provider you " +
                    "select.",
            ) {
                PrivacyRow("Local conversation storage is not encrypted at rest in this phase.")
                PrivacyRow(
                    "Provider keys are encrypted with a device-bound key and are never " +
                        "placed in URLs, logs, or exports.",
                )
                PrivacyRow("Chat requests go directly to your configured provider.")
                PrivacyRow(
                    "When Load remote images is on, response images also contact their hosts. " +
                        "You can turn this off in Chat defaults.",
                )
                PrivacyRow(
                    "Optional encrypted backup uploads to the storage destination you configure. " +
                        "Plaintext exports are readable by anyone with the file.",
                )
                PrivacyRow(
                    "No analytics or telemetry are collected by ChatUI itself.",
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
private fun PrivacyRow(text: String, showDivider: Boolean = true) {
    SettingsRow(label = text, showDivider = showDivider)
}

@Composable
private fun ThemePreview(settings: AndroidSettings) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val background = when (settings.theme) {
        Theme.LIGHT -> Color.White
        Theme.DARK -> Color(0xFF1C1C1E)
        Theme.SYSTEM -> colors.bgGrouped
    }
    val foreground = when (settings.theme) {
        Theme.LIGHT -> Color(0xFF1C1C1E)
        Theme.DARK -> Color.White
        Theme.SYSTEM -> colors.fgPrimary
    }
    Surface(
        color = background,
        shape = RoundedCornerShape(dimens.radiusMd),
        modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.Preview),
    ) {
        Column(
            modifier = Modifier.padding(
                if (settings.density == Density.COMPACT) dimens.space2 else dimens.cardPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Text(
                text = "Preview",
                style = Typography.footnote,
                color = foreground.copy(alpha = 0.7f),
            )
            Text(
                text = "Hello from ChatUI",
                style = Typography.chatStyle(settings.chatFontSize),
                color = foreground,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    tags: List<String>,
    modifier: Modifier = Modifier,
    swatches: List<Color>? = null,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val haptics = rememberHaptics()
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            FilterChip(
                selected = selected,
                onClick = { haptics.tick(); onSelected(index) },
                label = { Text(option) },
                leadingIcon = if (swatches == null) null else ({
                    Box(Modifier.size(14.dp).clip(CircleShape).background(swatches[index]))
                }),
                modifier = Modifier.heightIn(min = dimens.touchTargetMin).testTag(tags[index]),
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    showDivider: Boolean = true,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val haptics = rememberHaptics()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.rowMinHeight)
                .padding(horizontal = dimens.cardPadding, vertical = dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = dimens.space3),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = Typography.body,
                    color = colors.fgPrimary,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = Typography.footnote,
                        color = colors.fgMuted,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = {
                    haptics.tick()
                    onCheckedChange(it)
                },
                modifier = Modifier.testTag(tag).semantics { contentDescription = label },
            )
        }
        if (showDivider) {
            HorizontalDivider(
                color = colors.separator,
                thickness = 0.5.dp,
                modifier = Modifier.padding(
                    start = if (icon != null) {
                        dimens.cardPadding + 24.dp + dimens.space3
                    } else {
                        dimens.cardPadding
                    },
                ),
            )
        }
    }
}

private fun Theme.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun Density.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun ChatFontSize.label(): String = when (this) {
    ChatFontSize.SM -> "Small (14 sp)"
    ChatFontSize.MD -> "Medium (16 sp)"
    ChatFontSize.LG -> "Large (18 sp)"
}

private fun ReasoningEffort.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun SendKey.label(): String = when (this) {
    SendKey.ENTER -> "Enter sends"
    SendKey.MOD_ENTER -> "Mod+Enter sends"
}

@Composable
private fun rememberNumberText(value: Double?): MutableState<String> {
    val state = remember { mutableStateOf(value?.toString().orEmpty()) }
    LaunchedEffect(value) {
        if (state.value.trim().toDoubleOrNull() != value) {
            state.value = value?.toString().orEmpty()
        }
    }
    return state
}

@Composable
private fun SettingsNumber(
    label: String,
    text: String,
    error: String?,
    tag: String,
    field: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Decimal,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(error ?: "Empty uses the model default.") },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
    TextButton(
        onClick = { onChange("") },
        enabled = text.isNotEmpty(),
        modifier = Modifier.testTag(SettingsTestTags.reset(field))
            .semantics { contentDescription = "Use model default for $label" },
    ) { Text("Use model default") }
}

@Composable
private fun SettingsDisclosure(
    title: String,
    summary: String,
    tag: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    GroupedSection {
        SettingsRow(
            label = title,
            value = summary,
            showChevron = true,
            onClick = { onExpandedChange(!expanded) },
            showDivider = expanded,
            modifier = Modifier.testTag(tag).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        )
        if (expanded) Column(
            modifier = Modifier.padding(LocalDimens.current.cardPadding),
            verticalArrangement = Arrangement.spacedBy(LocalDimens.current.space3),
        ) { content() }
    }
}

private fun generationSummary(params: ChatParams): String = listOf(
    "Temperature ${params.temperature ?: "default"}",
    "Top P ${params.topP ?: "default"}",
    "output ${params.maxOutputTokens ?: "default"}",
    "${params.stop.size} stop sequences",
    "reasoning ${params.reasoningEffort?.label() ?: "default"}",
    "streaming ${if (params.streaming) "on" else "off"}",
).joinToString("; ")

@Composable
private fun SettingsFeedback(state: SettingsUiState, onRetry: () -> Unit) {
    val dimens = LocalDimens.current
    val initialRevision = remember { state.savedRevision }
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(state.savedRevision, state.pendingSaves, state.error) {
        showSaved = false
        if (state.savedRevision > initialRevision && state.pendingSaves == 0 && state.error == null) {
            delay(450)
            showSaved = true
        }
    }
    state.error?.let { error ->
        InlineBanner(
            severity = BannerSeverity.Error,
            title = "Settings need attention",
            body = error,
            action = { TextButton(onClick = onRetry) { Text("Retry") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.screenPadding)
                .testTag(SettingsTestTags.Error),
        )
        Spacer(Modifier.height(dimens.space3))
    }
    if (showSaved || state.pendingSaves > 0) {
        Text(
            text = if (showSaved) "Changes saved" else "Saving changes...",
            style = Typography.footnote,
            color = LocalChatUiColors.current.fgSecondary,
            modifier = Modifier.padding(horizontal = dimens.screenPadding)
                .testTag(SettingsTestTags.Saved)
                .semantics { if (showSaved) liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(dimens.space2))
    }
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
