package dev.chatui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.BrandMark
import dev.chatui.core.designsystem.ChatUiSnackbarHost
import dev.chatui.core.designsystem.GroupedList
import dev.chatui.core.designsystem.GroupedSection
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LabeledSlider
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.LocalSnackbarHostState
import dev.chatui.core.designsystem.SettingsRow
import dev.chatui.core.designsystem.Stepper
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.Density
import dev.chatui.core.model.ReasoningEffort
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Theme
import java.util.Locale
import kotlinx.coroutines.launch

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
                    icon = Icons.Rounded.Palette,
                    showChevron = true,
                    onClick = onOpenAppearance,
                    modifier = Modifier.testTag(SettingsTestTags.Appearance),
                )
                SettingsRow(
                    label = "Chat defaults",
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
                    label = "Data",
                    icon = Icons.Rounded.Storage,
                    showChevron = true,
                    onClick = onOpenData,
                    modifier = Modifier.testTag(SettingsTestTags.Data),
                )
                SettingsRow(
                    label = "Backup",
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
    snackbarHostState: SnackbarHostState? = LocalSnackbarHostState.current,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()
    val host = snackbarHostState ?: remember { SnackbarHostState() }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        if (loaded) {
            scope.launch { host.showSnackbar("Appearance saved") }
        }
        loaded = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = dimens.sectionGap)
                .testTag(SettingsTestTags.Appearance),
        ) {
            GroupedList {
                GroupedSection(
                    title = "Theme",
                    footer = "Live preview updates as you choose.",
                ) {
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space3),
                    ) {
                        SettingsSegmentedControl(
                            options = Theme.entries.map { it.label() },
                            selectedIndex = Theme.entries.indexOf(settings.theme),
                            onSelected = { onThemeChanged(Theme.entries[it]) },
                            tags = Theme.entries.map {
                                SettingsTestTags.theme(it.name.lowercase())
                            },
                            modifier = Modifier.testTag(SettingsTestTags.Theme),
                            swatches = listOf(
                                Color.White,
                                Color(0xFF1C1C1E),
                                colors.accent,
                            ),
                        )
                        ThemePreview(theme = settings.theme)
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Density",
                    footer = "Preview text reflows with the selected density.",
                ) {
                    SettingsStepperRow(
                        label = "Density",
                        value = Density.entries.indexOf(settings.density),
                        onValueChange = { onDensityChanged(Density.entries[it]) },
                        min = 0,
                        max = Density.entries.lastIndex,
                        valueTag = SettingsTestTags.density(settings.density.name.lowercase()),
                        modifier = Modifier.testTag(SettingsTestTags.Density),
                        preview = {
                            Text(
                                text = "Preview text at ${settings.density.label()} spacing",
                                style = Typography.subhead,
                                color = colors.fgSecondary,
                            )
                        },
                    )
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Chat font size",
                    footer = "Preview text uses the selected chat font size.",
                ) {
                    SettingsStepperRow(
                        label = "Chat font size",
                        value = ChatFontSize.entries.indexOf(settings.chatFontSize),
                        onValueChange = { onChatFontSizeChanged(ChatFontSize.entries[it]) },
                        min = 0,
                        max = ChatFontSize.entries.lastIndex,
                        valueTag = SettingsTestTags.fontSize(
                            settings.chatFontSize.name.lowercase(),
                        ),
                        modifier = Modifier.testTag(SettingsTestTags.ChatFontSize),
                        preview = {
                            Text(
                                text = "The quick brown fox jumps over the lazy dog.",
                                style = Typography.chatStyle(settings.chatFontSize),
                                color = colors.fgPrimary,
                            )
                        },
                    )
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(title = "Feedback") {
                    SettingsToggleRow(
                        label = "Haptic feedback",
                        description = "Vibrate on selection and confirmation. Follows the " +
                            "system setting when off.",
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
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Motion",
                    footer = "Reduced motion follows the operating-system preference.",
                ) {
                    SettingsRow(
                        label = "Reduced motion",
                        value = "System",
                        showDivider = false,
                    )
                }
            }
        }
        if (snackbarHostState == null) {
            ChatUiSnackbarHost(
                hostState = host,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
    snackbarHostState: SnackbarHostState? = LocalSnackbarHostState.current,
) {
    val settings = state.settings
    val params = settings.defaultParams
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()
    val host = snackbarHostState ?: remember { SnackbarHostState() }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val maxOutputText = rememberIntText(params.maxOutputTokens)
    val stopText = rememberStopText(params.stop)
    val providerLabel = state.providers.firstOrNull { it.id == settings.defaultProviderId }?.label
        ?: "First enabled provider"
    val modelLabel = state.models.firstOrNull { it.id == settings.defaultModelId }?.label
        ?: "Provider default"

    LaunchedEffect(settings) {
        if (loaded) {
            scope.launch { host.showSnackbar("Chat defaults saved") }
        }
        loaded = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = dimens.sectionGap)
                .testTag(SettingsTestTags.ChatDefaults),
        ) {
            state.error?.let { error ->
                InlineBanner(
                    severity = BannerSeverity.Error,
                    title = "Could not save settings",
                    body = error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimens.screenPadding)
                        .testTag(SettingsTestTags.Error),
                    action = {
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    },
                )
                Spacer(Modifier.height(dimens.space3))
            }

            GroupedList {
                GroupedSection(
                    title = "Model",
                    footer = "New conversations start from these defaults.",
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
                    Box {
                        SettingsRow(
                            label = "Default model",
                            value = modelLabel,
                            showChevron = true,
                            onClick = {
                                if (settings.defaultProviderId != null) modelMenu = true
                            },
                            modifier = Modifier.testTag(SettingsTestTags.DefaultModel),
                            showDivider = false,
                        )
                        DropdownMenu(
                            expanded = modelMenu,
                            onDismissRequest = { modelMenu = false },
                        ) {
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
                                    modifier = Modifier.testTag(
                                        SettingsTestTags.model(model.id),
                                    ),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(title = "Generation") {
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
                        LabeledSlider(
                            label = "Temperature",
                            value = params.temperature?.toFloat() ?: 0.7f,
                            onValueChange = { onTemperatureChanged(it.toString()) },
                            valueRange = 0f..2f,
                            valueText = params.temperature?.let {
                                String.format(Locale.US, "%.2f", it)
                            } ?: "Default",
                            modifier = Modifier.testTag(SettingsTestTags.DefaultTemperature),
                        )
                        LabeledSlider(
                            label = "Top P",
                            value = params.topP?.toFloat() ?: 1f,
                            onValueChange = { onTopPChanged(it.toString()) },
                            valueRange = 0f..1f,
                            valueText = params.topP?.let {
                                String.format(Locale.US, "%.2f", it)
                            } ?: "Default",
                            modifier = Modifier.testTag(SettingsTestTags.DefaultTopP),
                        )
                        OutlinedTextField(
                            value = maxOutputText.value,
                            onValueChange = {
                                maxOutputText.value = it
                                onMaxOutputTokensChanged(it)
                            },
                            label = { Text("Max output tokens") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
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
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(title = "Composer") {
                    Column(
                        modifier = Modifier.padding(dimens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimens.space3),
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
                        SettingsToggleRow(
                            label = "Show token usage",
                            description = "Show prompt and completion token counts under each " +
                                "response.",
                            checked = settings.showTokenUsage,
                            onCheckedChange = onShowTokenUsageChanged,
                            tag = SettingsTestTags.ShowTokenUsage,
                            showDivider = false,
                        )
                    }
                }
                Spacer(Modifier.height(dimens.sectionGap))
                GroupedSection(
                    title = "Privacy",
                    footer = "Remote images are blocked by default. When enabled, image URLs " +
                        "from model output are requested directly from their host.",
                ) {
                    SettingsToggleRow(
                        label = "Load remote images",
                        checked = settings.loadRemoteImages,
                        onCheckedChange = onLoadRemoteImagesChanged,
                        tag = SettingsTestTags.LoadRemoteImages,
                        showDivider = false,
                    )
                }
            }
        }
        if (snackbarHostState == null) {
            ChatUiSnackbarHost(
                hostState = host,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Backup placeholder for the post-M2 backup feature. */
@Composable
fun BackupSettingsScreen(modifier: Modifier = Modifier) {
    val dimens = LocalDimens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = dimens.sectionGap)
            .testTag(SettingsTestTags.Backup),
    ) {
        GroupedList {
            GroupedSection(
                title = "Backup",
                footer = "When backup arrives it will encrypt data before upload and keep " +
                    "provider keys out of backup metadata.",
            ) {
                SettingsRow(
                    label = "ChatUI does not back up or sync data in this release.",
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
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(dimens.space2),
                ) {
                    BrandMark(size = 72.dp)
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
                PrivacyRow("Chat content is sent only to the selected provider.")
                PrivacyRow("Remote images in model output are blocked until you allow them.")
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
private fun ThemePreview(theme: Theme) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val background = when (theme) {
        Theme.LIGHT -> Color.White
        Theme.DARK -> Color(0xFF1C1C1E)
        Theme.SYSTEM -> colors.bgGrouped
    }
    val foreground = when (theme) {
        Theme.LIGHT -> Color(0xFF1C1C1E)
        Theme.DARK -> Color.White
        Theme.SYSTEM -> colors.fgPrimary
    }
    Surface(
        color = background,
        shape = RoundedCornerShape(dimens.radiusMd),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            Text(
                text = "Preview",
                style = Typography.footnote,
                color = foreground.copy(alpha = 0.7f),
            )
            Text(
                text = "Hello from ChatUI",
                style = Typography.body,
                color = foreground,
            )
        }
    }
}

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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgGrouped, RoundedCornerShape(dimens.radiusXs))
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(dimens.radiusXs - 2.dp))
                    .background(if (selected) colors.bgSurfaceRaised else Color.Transparent)
                    .clickable { onSelected(index) }
                    .padding(vertical = dimens.space2, horizontal = dimens.space1)
                    .testTag(tags[index]),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (swatches != null) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(swatches[index]),
                    )
                    Spacer(Modifier.width(dimens.space1))
                }
                Text(
                    text = option,
                    style = Typography.subhead,
                    color = if (selected) colors.fgPrimary else colors.fgSecondary,
                )
            }
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
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(tag),
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

@Composable
private fun SettingsStepperRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    valueTag: String,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.rowMinHeight)
                .padding(horizontal = dimens.cardPadding, vertical = dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = Typography.body,
                    color = colors.fgPrimary,
                )
                Box(modifier = Modifier.testTag(valueTag)) {
                    preview()
                }
            }
            Stepper(
                value = value,
                onValueChange = onValueChange,
                min = min,
                max = max,
            )
        }
        HorizontalDivider(
            color = colors.separator,
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = dimens.cardPadding),
        )
    }
}

private fun Theme.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun Density.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun ChatFontSize.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun ReasoningEffort.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun SendKey.label(): String = when (this) {
    SendKey.ENTER -> "Enter sends"
    SendKey.MOD_ENTER -> "Mod+Enter sends"
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
