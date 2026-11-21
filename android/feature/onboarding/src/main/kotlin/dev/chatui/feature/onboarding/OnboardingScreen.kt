package dev.chatui.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.BrandMark
import dev.chatui.core.designsystem.EmptyState
import dev.chatui.core.designsystem.ErrorState
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.SecureScreen
import dev.chatui.core.designsystem.SkeletonList
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
import dev.chatui.core.model.Theme
import dev.chatui.feature.backup.BackupSettingsServiceLike
import dev.chatui.feature.backup.BackupSetupRoute

object OnboardingTestTags {
    const val Screen = "onboarding-screen"
    const val Welcome = "onboarding-welcome"
    const val StepIndicator = "onboarding-step-indicator"
    const val ProviderPicker = "onboarding-provider-picker"
    const val ContinueButton = "onboarding-continue"
    const val KeyEntry = "onboarding-key-entry"
    const val ApiKeyField = "onboarding-api-key"
    const val ShowKeyToggle = "onboarding-show-key"
    const val PasteKey = "onboarding-paste-key"
    const val HelpLink = "onboarding-help"
    const val LabelDisclosure = "onboarding-label-disclosure"
    const val LabelField = "onboarding-label"
    const val ConnectButton = "onboarding-connect"
    const val TestingStatus = "onboarding-testing"
    const val ConnectedStatus = "onboarding-connected"
    const val Notice = "onboarding-notice"
    const val ErrorCard = "onboarding-error"
    const val ErrorDetailsToggle = "onboarding-error-details-toggle"
    const val ErrorDetails = "onboarding-error-details"
    const val Catalog = "onboarding-catalog"
    const val CatalogLoading = "onboarding-catalog-loading"
    const val CatalogError = "onboarding-catalog-error"
    const val CatalogEmpty = "onboarding-catalog-empty"
    const val CatalogList = "onboarding-catalog-list"
    const val CatalogDisclosure = "onboarding-catalog-disclosure"
    const val RetryCatalog = "onboarding-catalog-retry"
    const val Completion = "onboarding-completion"
    const val StartChattingButton = "onboarding-start-chatting"
    const val BackButton = "onboarding-back"
    const val ThemeSelector = "onboarding-theme-selector"
    const val BackupStep = "onboarding-backup-step"
    const val BackupSetup = "onboarding-backup-setup"
    const val BackupSkip = "onboarding-backup-skip"
    const val BackupUnavailable = "onboarding-backup-unavailable"

    fun providerOption(id: String): String = "onboarding-provider-$id"
    fun themeOption(theme: Theme): String = "onboarding-theme-${theme.name.lowercase()}"
    fun catalogModel(id: String): String = "onboarding-catalog-$id"
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onProviderSelected: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRetryCatalog: () -> Unit,
    onThemeSelected: (Theme) -> Unit = {},
    onStartChatting: () -> Unit = {},
    backupService: BackupSettingsServiceLike? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = state.step == OnboardingStep.KEY_ENTRY) {
        if (!state.testing && !state.connected) onBack()
    }
    val content: @Composable () -> Unit = {
        Box(
            modifier = modifier.fillMaxSize().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides LocalChatUiColors.current.fgPrimary,
            ) {
                OnboardingContent(
                    state, onApiKeyChanged, onLabelChanged, onProviderSelected, onThemeSelected,
                    onContinue, onBack, onConnect, onRetryCatalog, onStartChatting, backupService,
                    Modifier.widthIn(max = 560.dp).fillMaxSize(),
                )
            }
        }
    }
    if (state.step == OnboardingStep.WELCOME) content() else SecureScreen(content = content)
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onProviderSelected: (String) -> Unit,
    onThemeSelected: (Theme) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRetryCatalog: () -> Unit,
    onStartChatting: () -> Unit,
    backupService: BackupSettingsServiceLike?,
    modifier: Modifier,
) {
    if (state.step == OnboardingStep.BACKUP) {
        OnboardingBackupStep(state, backupService, onStartChatting, modifier)
        return
    }
    val dimens = LocalDimens.current
    var catalogExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(OnboardingTestTags.Screen),
            contentPadding = PaddingValues(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            item(key = "steps") { StepIndicator(state.step) }
            if (state.step == OnboardingStep.WELCOME) {
                item(key = "welcome") { WelcomeHero() }
                item(key = "provider") { ProviderPicker(state, onProviderSelected) }
                item(key = "theme") { ThemeSelector(state.theme, onThemeSelected) }
            } else {
                item(key = "connection-header") { KeyEntryHeader(state) }
                item(key = "key-form") {
                    KeyEntryForm(state, onApiKeyChanged, onLabelChanged, onConnect, onBack)
                }
                item(key = "catalog-disclosure") {
                    OnboardingDisclosure(
                        title = "View available models",
                        expanded = catalogExpanded,
                        onClick = { catalogExpanded = !catalogExpanded },
                        modifier = Modifier.testTag(OnboardingTestTags.CatalogDisclosure),
                    )
                }
                if (catalogExpanded) catalogItems(state, onRetryCatalog)
            }
        }
        if (state.step == OnboardingStep.WELCOME) {
            HorizontalDivider()
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .padding(horizontal = dimens.screenPadding, vertical = dimens.space3)
                    .fillMaxWidth()
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(OnboardingTestTags.ContinueButton),
            ) { Text("Get started") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepIndicator(step: OnboardingStep) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.space3),
        modifier = Modifier.testTag(OnboardingTestTags.StepIndicator)
            .semantics { stateDescription = "Step ${step.ordinal + 1} of 3" },
    ) {
        listOf("Welcome", "Connect", "Backup").forEachIndexed { index, label ->
            Text(
                label,
                style = Typography.footnote,
                color = if (index == step.ordinal) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WelcomeHero() {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(OnboardingTestTags.Welcome),
        verticalArrangement = Arrangement.spacedBy(LocalDimens.current.space2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.space2),
        ) {
            BrandMark(size = 28.dp)
            Text("ChatUI", style = Typography.title2)
        }
        Text(
            "Bring your own API key. Chats and keys are stored on your device; " +
                "requests go directly to your provider, never a ChatUI server.",
            style = Typography.body,
        )
    }
}

@Composable
private fun ProviderPicker(state: OnboardingUiState, onProviderSelected: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(LocalDimens.current.space1)) {
        OnboardingDisclosure(
            title = "Provider: ${state.selectedProvider?.label ?: "OpenRouter"}",
            expanded = expanded,
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag(OnboardingTestTags.ProviderPicker),
        )
        if (expanded) {
            Column(Modifier.selectableGroup()) {
                state.providers.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LocalDimens.current.touchTargetMin)
                            .selectable(
                                selected = option.id == state.selectedProviderId,
                                enabled = option.enabled,
                                role = Role.RadioButton,
                                onClick = { haptics.tick(); onProviderSelected(option.id) },
                            )
                            .semantics(mergeDescendants = true) {}
                            .padding(vertical = LocalDimens.current.space2)
                            .testTag(OnboardingTestTags.providerOption(option.id)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(option.label)
                            if (option.recommended) Text("Recommended", style = Typography.footnote)
                            option.statusLabel?.let { Text(it, style = Typography.footnote) }
                        }
                        RadioButton(
                            selected = option.id == state.selectedProviderId,
                            enabled = option.enabled,
                            onClick = null,
                        )
                    }
                }
            }
        }
        Text(
            "You can add other providers from Providers after setup.",
            style = Typography.footnote,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.selectedProviderId != "openrouter") OpenRouterSetupNotice(state)
    }
}

@Composable
private fun KeyEntryHeader(state: OnboardingUiState) {
    Column(
        modifier = Modifier.testTag(OnboardingTestTags.KeyEntry),
        verticalArrangement = Arrangement.spacedBy(LocalDimens.current.space2),
    ) {
        Text("Connect OpenRouter", style = Typography.title2)
        Text(
            "Your key is stored on this device and is never sent to a ChatUI server.",
            style = Typography.footnote,
        )
        if (state.selectedProviderId != "openrouter") OpenRouterSetupNotice(state)
    }
}

@Composable
private fun OpenRouterSetupNotice(state: OnboardingUiState) {
    Text(
        "${state.selectedProvider?.label ?: "This provider"} can be added from Providers after setup. " +
            "First-run connection currently validates an OpenRouter key.",
        style = Typography.footnote,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyEntryForm(
    state: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    val dimens = LocalDimens.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val enabled = !state.testing && !state.connected
    var keyVisible by remember { mutableStateOf(false) }
    var labelExpanded by rememberSaveable { mutableStateOf(state.label.isNotBlank()) }
    Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChanged,
            label = { Text("OpenRouter API key") },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = state.error != null,
            supportingText = {
                if (state.apiKey.isBlank()) Text("Paste your OpenRouter key to continue.")
                else if (state.error != null) Text("Check the key and try again.")
            },
            trailingIcon = {
                IconButton(
                    onClick = { keyVisible = !keyVisible },
                    enabled = enabled,
                    modifier = Modifier.size(dimens.touchTargetMin).testTag(OnboardingTestTags.ShowKeyToggle),
                ) {
                    Icon(
                        if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (keyVisible) "Hide key" else "Show key",
                    )
                }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(OnboardingTestTags.ApiKeyField),
        )
        state.error?.let { ErrorCard(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            TextButton(
                onClick = { clipboard.getText()?.text?.let(onApiKeyChanged) },
                enabled = enabled,
                modifier = Modifier.testTag(OnboardingTestTags.PasteKey),
            ) { Text("Paste") }
            TextButton(
                onClick = { uriHandler.openUri("https://openrouter.ai/keys") },
                modifier = Modifier.testTag(OnboardingTestTags.HelpLink),
            ) { Text("Where do I find this?") }
        }
        Button(
            onClick = onConnect,
            enabled = state.canConnect,
            modifier = Modifier.fillMaxWidth().heightIn(min = dimens.touchTargetMin)
                .testTag(OnboardingTestTags.ConnectButton)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            if (state.testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).testTag(OnboardingTestTags.TestingStatus),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(dimens.space2))
            }
            Text(when {
                state.testing -> "Connecting..."
                state.connected -> "Connected"
                else -> "Connect"
            })
        }
        OnboardingDisclosure(
            title = "Optional label",
            expanded = labelExpanded,
            onClick = { labelExpanded = !labelExpanded },
            modifier = Modifier.testTag(OnboardingTestTags.LabelDisclosure),
        )
        if (labelExpanded) {
            OutlinedTextField(
                value = state.label,
                onValueChange = onLabelChanged,
                label = { Text("Label (optional)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(OnboardingTestTags.LabelField),
            )
        }
        state.notice?.let { Text(it, modifier = Modifier.testTag(OnboardingTestTags.Notice)) }
        TextButton(
            onClick = onBack,
            enabled = enabled,
            modifier = Modifier.testTag(OnboardingTestTags.BackButton),
        ) { Text("Back") }
    }
}

@Composable
private fun ErrorCard(error: OnboardingErrorCopy) {
    var showDetails by remember { mutableStateOf(false) }
    InlineBanner(
        severity = BannerSeverity.Error,
        title = error.title,
        body = error.message,
        modifier = Modifier.fillMaxWidth().testTag(OnboardingTestTags.ErrorCard),
        action = error.details?.let { details ->
            {
                Column {
                    TextButton(
                        onClick = { showDetails = !showDetails },
                        modifier = Modifier.testTag(OnboardingTestTags.ErrorDetailsToggle),
                    ) { Text(if (showDetails) "Hide details" else "Details") }
                    if (showDetails) {
                        Text(
                            details,
                            style = Typography.footnote,
                            modifier = Modifier.testTag(OnboardingTestTags.ErrorDetails),
                        )
                    }
                }
            }
        },
    )
}

private fun LazyListScope.catalogItems(state: OnboardingUiState, onRetry: () -> Unit) {
    item(key = "catalog-header") {
        Column(Modifier.testTag(OnboardingTestTags.Catalog)) {
            Text("Available models", style = Typography.headline)
            Text("Public OpenRouter catalog. Browsing does not validate your key.", style = Typography.footnote)
        }
    }
    val models = when (val catalog = state.catalog) {
        is CatalogUiState.Ready -> catalog.models
        else -> state.cachedCatalog
    }
    when (val catalog = state.catalog) {
        CatalogUiState.Loading -> item(key = "catalog-loading") {
            if (models.isEmpty()) {
                SkeletonList(count = 3, modifier = Modifier.testTag(OnboardingTestTags.CatalogLoading))
            } else {
                Text("Refreshing models...", modifier = Modifier.testTag(OnboardingTestTags.CatalogLoading))
            }
        }
        is CatalogUiState.Error -> item(key = "catalog-error") {
            ErrorState(
                title = "Could not load models",
                body = catalog.message,
                onRetry = onRetry,
                retryModifier = Modifier.testTag(OnboardingTestTags.RetryCatalog),
                modifier = Modifier.testTag(OnboardingTestTags.CatalogError),
            )
        }
        is CatalogUiState.Ready -> if (models.isEmpty()) item(key = "catalog-empty") {
            EmptyState(
                title = "No models returned",
                body = "You can still connect; models will load after validation.",
                modifier = Modifier.testTag(OnboardingTestTags.CatalogEmpty),
            )
        }
    }
    if (models.isNotEmpty()) {
        item(key = "catalog-count") {
            Text("${models.size} models", modifier = Modifier.testTag(OnboardingTestTags.CatalogList))
        }
        items(models, key = { "catalog-model-${it.remoteId}" }) { model ->
            Column(
                modifier = Modifier.fillMaxWidth().testTag(OnboardingTestTags.catalogModel(model.remoteId)),
                verticalArrangement = Arrangement.spacedBy(LocalDimens.current.space1),
            ) {
                Text(model.label, style = Typography.body)
                Text(model.remoteId, style = Typography.footnote)
                Text(
                    listOfNotNull(
                        model.contextWindow?.let { "$it context tokens" },
                        "Free".takeIf { model.isFree },
                    ).joinToString(" · "),
                    style = Typography.footnote,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSelector(theme: Theme, onThemeSelected: (Theme) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        OnboardingDisclosure(
            title = "Appearance: ${theme.name.lowercase().replaceFirstChar { it.uppercase() }}",
            expanded = expanded,
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag(OnboardingTestTags.ThemeSelector),
        )
        if (expanded) {
            FlowRow(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.space2),
            ) {
                listOf(Theme.LIGHT, Theme.DARK, Theme.SYSTEM).forEach { option ->
                    FilterChip(
                        selected = theme == option,
                        onClick = { onThemeSelected(option) },
                        label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.heightIn(min = LocalDimens.current.touchTargetMin)
                            .testTag(OnboardingTestTags.themeOption(option)),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingDisclosure(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalDimens.current.touchTargetMin)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
        )
    }
}

@Composable
private fun OnboardingBackupStep(
    state: OnboardingUiState,
    backupService: BackupSettingsServiceLike?,
    onStartChatting: () -> Unit,
    modifier: Modifier,
) {
    var configureBackup by rememberSaveable { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val setupFocus = remember { FocusRequester() }
    val choiceListState = rememberLazyListState()
    LaunchedEffect(configureBackup, restoreFocus) {
        if (!configureBackup && restoreFocus) {
            choiceListState.scrollToItem(3)
            withFrameNanos { }
            setupFocus.requestFocus()
            restoreFocus = false
        }
    }
    if (configureBackup && backupService != null) {
        BackupSetupRoute(
            service = backupService,
            onCancel = { configureBackup = false; restoreFocus = true },
            onComplete = onStartChatting,
            modifier = modifier,
        )
        return
    }
    val dimens = LocalDimens.current
    LazyColumn(
        state = choiceListState,
        modifier = modifier.testTag(OnboardingTestTags.BackupStep),
        contentPadding = PaddingValues(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.space3),
    ) {
        item { StepIndicator(state.step) }
        item {
            Text("Connected", style = Typography.footnote, modifier = Modifier.testTag(OnboardingTestTags.ConnectedStatus))
            Text("Backup is optional", style = Typography.title2)
            Text("Add encrypted backup now, or later in Settings > Backup.", style = Typography.body)
        }
        item {
            Button(
                onClick = onStartChatting,
                enabled = state.connected,
                modifier = Modifier.fillMaxWidth().heightIn(min = dimens.touchTargetMin)
                    .testTag(OnboardingTestTags.StartChattingButton),
            ) { Text("Start chatting") }
        }
        item {
            TextButton(
                onClick = { configureBackup = true },
                enabled = state.connected && backupService != null,
                modifier = Modifier.fillMaxWidth().focusRequester(setupFocus)
                    .heightIn(min = dimens.touchTargetMin).testTag(OnboardingTestTags.BackupSetup),
            ) { Text("Set up backup") }
        }
        item {
            TextButton(
                onClick = onStartChatting,
                enabled = state.connected,
                modifier = Modifier.fillMaxWidth().heightIn(min = dimens.touchTargetMin)
                    .testTag(OnboardingTestTags.BackupSkip),
            ) { Text("Skip for now") }
        }
        state.notice?.let { notice ->
            item { Text(notice, modifier = Modifier.testTag(OnboardingTestTags.Notice)) }
        }
        if (backupService == null) item {
            Text(
                "Backup setup is unavailable right now. You can configure it later in Settings > Backup.",
                style = Typography.footnote,
                modifier = Modifier.testTag(OnboardingTestTags.BackupUnavailable),
            )
        }
    }
}
