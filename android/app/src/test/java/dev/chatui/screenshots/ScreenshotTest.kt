package dev.chatui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Density
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.Theme
import dev.chatui.feature.chat.ChatAvailability
import dev.chatui.feature.chat.ChatScreen
import dev.chatui.feature.chat.ChatUiState
import dev.chatui.feature.chat.StreamingState
import dev.chatui.feature.conversations.ConversationGroup
import dev.chatui.feature.conversations.ConversationGroupKey
import dev.chatui.feature.conversations.ConversationListScreen
import dev.chatui.feature.conversations.ConversationListUiState
import dev.chatui.feature.conversations.ConversationListView
import dev.chatui.feature.conversations.ConversationSearchMessageMatch
import dev.chatui.feature.conversations.ConversationSearchResult
import dev.chatui.feature.onboarding.CatalogModel
import dev.chatui.feature.onboarding.CatalogUiState
import dev.chatui.feature.onboarding.OnboardingScreen
import dev.chatui.feature.onboarding.OnboardingStep
import dev.chatui.feature.onboarding.OnboardingUiState
import dev.chatui.feature.onboarding.ProviderOption
import dev.chatui.feature.providers.ModelPickerContent
import dev.chatui.feature.providers.ModelPickerTarget
import dev.chatui.feature.providers.ModelPickerTestTags
import dev.chatui.feature.providers.ModelPickerUiState
import dev.chatui.feature.providers.ProviderAvailability
import dev.chatui.feature.providers.ProviderDetailScreen
import dev.chatui.feature.providers.ProviderDetailUiState
import dev.chatui.feature.providers.ProviderListScreen
import dev.chatui.feature.providers.ProviderListTestTags
import dev.chatui.feature.providers.ProviderListUiState
import dev.chatui.feature.settings.AboutSettingsScreen
import dev.chatui.feature.settings.AppearanceSettingsScreen
import dev.chatui.feature.settings.ChatDefaultsSettingsScreen
import dev.chatui.feature.settings.DataSettingsScreen
import dev.chatui.feature.settings.DataSettingsUiState
import dev.chatui.feature.settings.ProviderKeySummary
import dev.chatui.feature.settings.SettingsRootScreen
import dev.chatui.feature.settings.SettingsUiState
import dev.chatui.feature.settings.StorageUsage
import dev.chatui.feature.settings.StorageUsageEntry
import dev.chatui.shell.AdaptiveShell
import dev.chatui.shell.ConversationsPlaceholder
import dev.chatui.shell.ShellSidebarDestination
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the key M3 screens.
 *
 * Every screen is captured in light and dark. Each test method pins the
 * phone or tablet qualifiers so the same screen produces four goldens:
 * phone/light, phone/dark, tablet/light, and tablet/dark.
 *
 * All data is fake. No API key, prompt, conversation, or personal data is
 * rendered by these fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun captureBoth(
        name: String,
        interaction: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        var theme by mutableStateOf(Theme.LIGHT)
        composeRule.setContent {
            ChatUiTheme(themeMode = theme, reducedMotion = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
        interaction?.invoke()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("${name}_light.png")

        theme = Theme.DARK
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("${name}_dark.png")
    }

    // Onboarding -----------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onboardingPhone() = captureBoth("onboarding_phone") {
        OnboardingScreen(
            state = onboardingState(),
            onApiKeyChanged = {},
            onLabelChanged = {},
            onProviderSelected = {},
            onContinue = {},
            onBack = {},
            onConnect = {},
            onRetryCatalog = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun onboardingTablet() = captureBoth("onboarding_tablet") {
        OnboardingScreen(
            state = onboardingState(),
            onApiKeyChanged = {},
            onLabelChanged = {},
            onProviderSelected = {},
            onContinue = {},
            onBack = {},
            onConnect = {},
            onRetryCatalog = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onboardingKeyEntryPhone() = captureBoth("onboarding_key_entry_phone") {
        OnboardingScreen(
            state = onboardingState(
                step = OnboardingStep.KEY_ENTRY,
                catalog = CatalogUiState.Loading,
            ),
            onApiKeyChanged = {},
            onLabelChanged = {},
            onProviderSelected = {},
            onContinue = {},
            onBack = {},
            onConnect = {},
            onRetryCatalog = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun onboardingKeyEntryTablet() = captureBoth("onboarding_key_entry_tablet") {
        OnboardingScreen(
            state = onboardingState(
                step = OnboardingStep.KEY_ENTRY,
                catalog = CatalogUiState.Loading,
            ),
            onApiKeyChanged = {},
            onLabelChanged = {},
            onProviderSelected = {},
            onContinue = {},
            onBack = {},
            onConnect = {},
            onRetryCatalog = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onboardingCatalogPhone() = captureBoth("onboarding_catalog_phone") {
        OnboardingScreen(
            state = onboardingState(
                step = OnboardingStep.KEY_ENTRY,
                apiKey = "sk-or-v1-example-not-a-real-key",
                catalog = CatalogUiState.Ready(
                    listOf(
                        CatalogModel("openrouter/auto", "Auto Router", 128_000, false),
                        CatalogModel("openrouter/free", "Free Model", 32_000, true),
                    ),
                ),
            ),
            onApiKeyChanged = {},
            onLabelChanged = {},
            onProviderSelected = {},
            onContinue = {},
            onBack = {},
            onConnect = {},
            onRetryCatalog = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun onboardingCatalogTablet() = captureBoth("onboarding_catalog_tablet") {
        OnboardingScreen(
            state = onboardingState(
                step = OnboardingStep.KEY_ENTRY,
                apiKey = "sk-or-v1-example-not-a-real-key",
                catalog = CatalogUiState.Ready(
                    listOf(
                        CatalogModel("openrouter/auto", "Auto Router", 128_000, false),
                        CatalogModel("openrouter/free", "Free Model", 32_000, true),
                    ),
                ),
            ),
            onApiKeyChanged = {},
            onLabelChanged = {},
            onProviderSelected = {},
            onContinue = {},
            onBack = {},
            onConnect = {},
            onRetryCatalog = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onboardingCompletionPhone() = captureBoth("onboarding_completion_phone") {
        OnboardingScreen(
            state = onboardingState(
                step = OnboardingStep.KEY_ENTRY,
                apiKey = "sk-or-v1-example-not-a-real-key",
                connected = true,
                catalog = CatalogUiState.Ready(
                    listOf(CatalogModel("openrouter/auto", "Auto Router", 128_000, false)),
                ),
            ),
            onApiKeyChanged = {},
            onLabelChanged = {},
            onProviderSelected = {},
            onContinue = {},
            onBack = {},
            onConnect = {},
            onRetryCatalog = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun onboardingCompletionTablet() = captureBoth("onboarding_completion_tablet") {
        OnboardingScreen(
            state = onboardingState(
                step = OnboardingStep.KEY_ENTRY,
                apiKey = "sk-or-v1-example-not-a-real-key",
                connected = true,
                catalog = CatalogUiState.Ready(
                    listOf(CatalogModel("openrouter/auto", "Auto Router", 128_000, false)),
                ),
            ),
            onApiKeyChanged = {},
            onLabelChanged = {},
            onProviderSelected = {},
            onContinue = {},
            onBack = {},
            onConnect = {},
            onRetryCatalog = {},
        )
    }

    // Chat -----------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun chatEmptyPhone() = captureBoth("chat_empty_phone") {
        ChatScreen(
            state = chatState(),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onLoadOlder = {},
            onRetryMessage = {},
            onDismissError = {},
            zoneId = ZoneOffset.UTC,
            imeInsets = WindowInsets(0, 0, 0, 0),
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun chatEmptyTablet() = captureBoth("chat_empty_tablet") {
        ChatScreen(
            state = chatState(),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onLoadOlder = {},
            onRetryMessage = {},
            onDismissError = {},
            zoneId = ZoneOffset.UTC,
            imeInsets = WindowInsets(0, 0, 0, 0),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun chatPopulatedPhone() = captureBoth("chat_populated_phone") {
        ChatScreen(
            state = chatState(messages = listOf(userMessage(), assistantMessage())),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onLoadOlder = {},
            onRetryMessage = {},
            onDismissError = {},
            zoneId = ZoneOffset.UTC,
            imeInsets = WindowInsets(0, 0, 0, 0),
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun chatPopulatedTablet() = captureBoth("chat_populated_tablet") {
        ChatScreen(
            state = chatState(messages = listOf(userMessage(), assistantMessage())),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onLoadOlder = {},
            onRetryMessage = {},
            onDismissError = {},
            zoneId = ZoneOffset.UTC,
            imeInsets = WindowInsets(0, 0, 0, 0),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun chatStreamingPhone() = captureBoth("chat_streaming_phone") {        ChatScreen(
            state = chatState(
                messages = listOf(userMessage()),
                streaming = StreamingState(
                    conversationId = CONVERSATION_ID,
                    messageId = "message-2",
                    started = true,
                    text = "Here is a short streamed answer that is still arriving.",
                    startedAt = FIXED_NOW,
                    firstTokenAt = FIXED_NOW,
                    updatedAt = FIXED_NOW,
                ),
            ),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onLoadOlder = {},
            onRetryMessage = {},
            onDismissError = {},
            zoneId = ZoneOffset.UTC,
            imeInsets = WindowInsets(0, 0, 0, 0),
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun chatStreamingTablet() = captureBoth("chat_streaming_tablet") {
        ChatScreen(
            state = chatState(
                messages = listOf(userMessage()),
                streaming = StreamingState(
                    conversationId = CONVERSATION_ID,
                    messageId = "message-2",
                    started = true,
                    text = "Here is a short streamed answer that is still arriving.",
                    startedAt = FIXED_NOW,
                    firstTokenAt = FIXED_NOW,
                    updatedAt = FIXED_NOW,
                ),
            ),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onLoadOlder = {},
            onRetryMessage = {},
            onDismissError = {},
            zoneId = ZoneOffset.UTC,
            imeInsets = WindowInsets(0, 0, 0, 0),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun chatErrorPhone() = captureBoth("chat_error_phone") {
        ChatScreen(
            state = chatState(
                messages = listOf(userMessage()),
                error = ChatError(
                    code = ChatErrorCode.NETWORK,
                    message = "Network error. Check your connection and try again.",
                    providerId = "provider-1",
                    status = null,
                    retryable = true,
                    retryAfterSeconds = null,
                    requestId = null,
                    details = null,
                    at = FIXED_NOW,
                ),
            ),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onLoadOlder = {},
            onRetryMessage = {},
            onDismissError = {},
            zoneId = ZoneOffset.UTC,
            imeInsets = WindowInsets(0, 0, 0, 0),
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun chatErrorTablet() = captureBoth("chat_error_tablet") {
        ChatScreen(
            state = chatState(
                messages = listOf(userMessage()),
                error = ChatError(
                    code = ChatErrorCode.NETWORK,
                    message = "Network error. Check your connection and try again.",
                    providerId = "provider-1",
                    status = null,
                    retryable = true,
                    retryAfterSeconds = null,
                    requestId = null,
                    details = null,
                    at = FIXED_NOW,
                ),
            ),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onLoadOlder = {},
            onRetryMessage = {},
            onDismissError = {},
            zoneId = ZoneOffset.UTC,
            imeInsets = WindowInsets(0, 0, 0, 0),
        )
    }

    // Model picker ---------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun modelPickerPhone() = captureBoth("model_picker_phone") {
        ModelPickerContent(
            state = modelPickerState(),
            onQueryChanged = {},
            onSelect = {},
            onPin = { _, _ -> },
            onHide = { _, _ -> },
            onManualRemoteIdChanged = {},
            onManualLabelChanged = {},
            onAddManual = {},
            onRefresh = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun modelPickerTablet() = captureBoth("model_picker_tablet") {
        ModelPickerContent(
            state = modelPickerState(),
            onQueryChanged = {},
            onSelect = {},
            onPin = { _, _ -> },
            onHide = { _, _ -> },
            onManualRemoteIdChanged = {},
            onManualLabelChanged = {},
            onAddManual = {},
            onRefresh = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun modelPickerFiltersPhone() = captureBoth(
        name = "model_picker_filters_phone",
        interaction = {
            composeRule.onNodeWithTag(ModelPickerTestTags.filter("pinned")).performClick()
        },
    ) {
        ModelPickerContent(
            state = modelPickerState(),
            onQueryChanged = {},
            onSelect = {},
            onPin = { _, _ -> },
            onHide = { _, _ -> },
            onManualRemoteIdChanged = {},
            onManualLabelChanged = {},
            onAddManual = {},
            onRefresh = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun modelPickerFiltersTablet() = captureBoth(
        name = "model_picker_filters_tablet",
        interaction = {
            composeRule.onNodeWithTag(ModelPickerTestTags.filter("pinned")).performClick()
        },
    ) {
        ModelPickerContent(
            state = modelPickerState(),
            onQueryChanged = {},
            onSelect = {},
            onPin = { _, _ -> },
            onHide = { _, _ -> },
            onManualRemoteIdChanged = {},
            onManualLabelChanged = {},
            onAddManual = {},
            onRefresh = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun modelPickerEmptyPhone() = captureBoth("model_picker_empty_phone") {
        ModelPickerContent(
            state = modelPickerState(models = emptyList()),
            onQueryChanged = {},
            onSelect = {},
            onPin = { _, _ -> },
            onHide = { _, _ -> },
            onManualRemoteIdChanged = {},
            onManualLabelChanged = {},
            onAddManual = {},
            onRefresh = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun modelPickerEmptyTablet() = captureBoth("model_picker_empty_tablet") {
        ModelPickerContent(
            state = modelPickerState(models = emptyList()),
            onQueryChanged = {},
            onSelect = {},
            onPin = { _, _ -> },
            onHide = { _, _ -> },
            onManualRemoteIdChanged = {},
            onManualLabelChanged = {},
            onAddManual = {},
            onRefresh = {},
        )
    }

    // Providers ------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun providerListPhone() = captureBoth("provider_list_phone") {
        ProviderListScreen(
            state = providerListState(),
            onOpenProvider = {},
            onRetry = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun providerListTablet() = captureBoth("provider_list_tablet") {
        ProviderListScreen(
            state = providerListState(),
            onOpenProvider = {},
            onRetry = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun providerAddSheetPhone() = captureBoth(
        name = "provider_add_sheet_phone",
        interaction = {
            composeRule.onNodeWithTag(ProviderListTestTags.AddProvider).performClick()
        },
    ) {
        ProviderListScreen(
            state = providerListState(),
            onOpenProvider = {},
            onRetry = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun providerAddSheetTablet() = captureBoth(
        name = "provider_add_sheet_tablet",
        interaction = {
            composeRule.onNodeWithTag(ProviderListTestTags.AddProvider).performClick()
        },
    ) {
        ProviderListScreen(
            state = providerListState(),
            onOpenProvider = {},
            onRetry = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun providerDetailPhone() = captureBoth("provider_detail_phone") {
        ProviderDetailScreen(
            state = ProviderDetailUiState(
                provider = provider(),
                modelCount = 3,
                loading = false,
            ),
            onNewKeyChanged = {},
            onRotateKey = {},
            onRemoveKey = {},
            onCancelRemove = {},
            onToggleEnabled = {},
            onValidate = {},
            onRefreshModels = {},
            onRetry = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun providerDetailTablet() = captureBoth("provider_detail_tablet") {
        ProviderDetailScreen(
            state = ProviderDetailUiState(
                provider = provider(),
                modelCount = 3,
                loading = false,
            ),
            onNewKeyChanged = {},
            onRotateKey = {},
            onRemoveKey = {},
            onCancelRemove = {},
            onToggleEnabled = {},
            onValidate = {},
            onRefreshModels = {},
            onRetry = {},
        )
    }

    // Settings -------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun settingsRootPhone() = captureBoth("settings_root_phone") {
        SettingsRootScreen(
            onOpenAppearance = {},
            onOpenChatDefaults = {},
            onOpenProviders = {},
            onOpenData = {},
            onOpenBackup = {},
            onOpenAbout = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun settingsRootTablet() = captureBoth("settings_root_tablet") {
        SettingsRootScreen(
            onOpenAppearance = {},
            onOpenChatDefaults = {},
            onOpenProviders = {},
            onOpenData = {},
            onOpenBackup = {},
            onOpenAbout = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun settingsAppearancePhone() = captureBoth("settings_appearance_phone") {
        AppearanceSettingsScreen(
            settings = AndroidSettings(
                theme = Theme.SYSTEM,
                density = Density.COMFORTABLE,
                chatFontSize = ChatFontSize.MD,
                hapticFeedback = true,
                dynamicColor = false,
            ),
            onThemeChanged = {},
            onDensityChanged = {},
            onChatFontSizeChanged = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun settingsAppearanceTablet() = captureBoth("settings_appearance_tablet") {
        AppearanceSettingsScreen(
            settings = AndroidSettings(
                theme = Theme.SYSTEM,
                density = Density.COMFORTABLE,
                chatFontSize = ChatFontSize.MD,
                hapticFeedback = true,
                dynamicColor = false,
            ),
            onThemeChanged = {},
            onDensityChanged = {},
            onChatFontSizeChanged = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun settingsChatDefaultsPhone() = captureBoth("settings_chat_defaults_phone") {
        ChatDefaultsSettingsScreen(
            state = settingsState(),
            onDefaultProviderChanged = {},
            onDefaultModelChanged = {},
            onDefaultSystemPromptChanged = {},
            onTemperatureChanged = {},
            onTopPChanged = {},
            onMaxOutputTokensChanged = {},
            onStopChanged = {},
            onReasoningEffortChanged = {},
            onStreamingChanged = {},
            onSendKeyChanged = {},
            onShowTokenUsageChanged = {},
            onLoadRemoteImagesChanged = {},
            onDismissError = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun settingsChatDefaultsTablet() = captureBoth("settings_chat_defaults_tablet") {
        ChatDefaultsSettingsScreen(
            state = settingsState(),
            onDefaultProviderChanged = {},
            onDefaultModelChanged = {},
            onDefaultSystemPromptChanged = {},
            onTemperatureChanged = {},
            onTopPChanged = {},
            onMaxOutputTokensChanged = {},
            onStopChanged = {},
            onReasoningEffortChanged = {},
            onStreamingChanged = {},
            onSendKeyChanged = {},
            onShowTokenUsageChanged = {},
            onLoadRemoteImagesChanged = {},
            onDismissError = {},
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun settingsAboutPhone() = captureBoth("settings_about_phone") {
        AboutSettingsScreen()
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun settingsAboutTablet() = captureBoth("settings_about_tablet") {
        AboutSettingsScreen()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun dataSettingsPhone() = captureBoth("data_settings_phone") {
        DataSettingsScreen(
            state = dataSettingsState(),
            onConfirmationPhraseChanged = {},
            onClearAll = {},
            onRequestRemoveKey = {},
            onCancelRemoveKey = {},
            onConfirmRemoveKey = {},
            onDismissError = {},
            onOpenProviders = {},
            onOpenBackup = {},
            onExportAll = {},
            onImportMerge = {},
            onImportReplace = {},
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun dataSettingsTablet() = captureBoth("data_settings_tablet") {
        DataSettingsScreen(
            state = dataSettingsState(),
            onConfirmationPhraseChanged = {},
            onClearAll = {},
            onRequestRemoveKey = {},
            onCancelRemoveKey = {},
            onConfirmRemoveKey = {},
            onDismissError = {},
            onOpenProviders = {},
            onOpenBackup = {},
            onExportAll = {},
            onImportMerge = {},
            onImportReplace = {},
        )
    }

    // Conversations and shell ----------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun conversationListPhone() = captureBoth("conversation_list_phone") {
        ConversationListScreen(
            state = conversationListState(),
            onNewChat = {},
            onSelect = {},
            onRename = { _, _ -> },
            onDeleteRequested = {},
            onDeleteDismissed = {},
            onDeleteConfirmed = {},
            onRetry = {},
            onSearchQueryChanged = {},
            onSearchRetry = {},
            now = FIXED_NOW,
            zoneId = ZoneOffset.UTC,
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun conversationListTablet() = captureBoth("conversation_list_tablet") {
        ConversationListScreen(
            state = conversationListState(),
            onNewChat = {},
            onSelect = {},
            onRename = { _, _ -> },
            onDeleteRequested = {},
            onDeleteDismissed = {},
            onDeleteConfirmed = {},
            onRetry = {},
            onSearchQueryChanged = {},
            onSearchRetry = {},
            now = FIXED_NOW,
            zoneId = ZoneOffset.UTC,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun conversationListArchivedPhone() = captureBoth("conversation_list_archived_phone") {
        ConversationListScreen(
            state = conversationListState(
                view = ConversationListView.ARCHIVED,
                archivedGroups = conversationListState().groups,
            ),
            onNewChat = {},
            onSelect = {},
            onRename = { _, _ -> },
            onDeleteRequested = {},
            onDeleteDismissed = {},
            onDeleteConfirmed = {},
            onRetry = {},
            onSearchQueryChanged = {},
            onSearchRetry = {},
            now = FIXED_NOW,
            zoneId = ZoneOffset.UTC,
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun conversationListArchivedTablet() = captureBoth("conversation_list_archived_tablet") {
        ConversationListScreen(
            state = conversationListState(
                view = ConversationListView.ARCHIVED,
                archivedGroups = conversationListState().groups,
            ),
            onNewChat = {},
            onSelect = {},
            onRename = { _, _ -> },
            onDeleteRequested = {},
            onDeleteDismissed = {},
            onDeleteConfirmed = {},
            onRetry = {},
            onSearchQueryChanged = {},
            onSearchRetry = {},
            now = FIXED_NOW,
            zoneId = ZoneOffset.UTC,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun conversationListSearchPhone() = captureBoth("conversation_list_search_phone") {
        ConversationListScreen(
            state = conversationListState(
                searchQuery = "release",
                searchResults = listOf(
                    ConversationSearchResult(
                        conversation = conversation().copy(
                            id = "conversation-1",
                            title = "Release notes",
                        ),
                        titleMatched = true,
                        messageMatches = listOf(
                            ConversationSearchMessageMatch(
                                messageId = "message-1",
                                sequence = 1,
                                role = MessageRole.USER,
                                snippet = "Summarize the release notes in one sentence.",
                                createdAt = FIXED_NOW,
                            ),
                        ),
                    ),
                ),
            ),
            onNewChat = {},
            onSelect = {},
            onRename = { _, _ -> },
            onDeleteRequested = {},
            onDeleteDismissed = {},
            onDeleteConfirmed = {},
            onRetry = {},
            onSearchQueryChanged = {},
            onSearchRetry = {},
            now = FIXED_NOW,
            zoneId = ZoneOffset.UTC,
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun conversationListSearchTablet() = captureBoth("conversation_list_search_tablet") {
        ConversationListScreen(
            state = conversationListState(
                searchQuery = "release",
                searchResults = listOf(
                    ConversationSearchResult(
                        conversation = conversation().copy(
                            id = "conversation-1",
                            title = "Release notes",
                        ),
                        titleMatched = true,
                        messageMatches = listOf(
                            ConversationSearchMessageMatch(
                                messageId = "message-1",
                                sequence = 1,
                                role = MessageRole.USER,
                                snippet = "Summarize the release notes in one sentence.",
                                createdAt = FIXED_NOW,
                            ),
                        ),
                    ),
                ),
            ),
            onNewChat = {},
            onSelect = {},
            onRename = { _, _ -> },
            onDeleteRequested = {},
            onDeleteDismissed = {},
            onDeleteConfirmed = {},
            onRetry = {},
            onSearchQueryChanged = {},
            onSearchRetry = {},
            now = FIXED_NOW,
            zoneId = ZoneOffset.UTC,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun conversationListEmptyPhone() = captureBoth("conversation_list_empty_phone") {
        ConversationListScreen(
            state = conversationListState(groups = emptyList()),
            onNewChat = {},
            onSelect = {},
            onRename = { _, _ -> },
            onDeleteRequested = {},
            onDeleteDismissed = {},
            onDeleteConfirmed = {},
            onRetry = {},
            onSearchQueryChanged = {},
            onSearchRetry = {},
            now = FIXED_NOW,
            zoneId = ZoneOffset.UTC,
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun conversationListEmptyTablet() = captureBoth("conversation_list_empty_tablet") {
        ConversationListScreen(
            state = conversationListState(groups = emptyList()),
            onNewChat = {},
            onSelect = {},
            onRename = { _, _ -> },
            onDeleteRequested = {},
            onDeleteDismissed = {},
            onDeleteConfirmed = {},
            onRetry = {},
            onSearchQueryChanged = {},
            onSearchRetry = {},
            now = FIXED_NOW,
            zoneId = ZoneOffset.UTC,
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun expandedShellTablet() = captureBoth("expanded_shell_tablet") {
        AdaptiveShell(
            expanded = true,
            sidebar = { ScreenshotSidebar() },
            mainContent = { ConversationsPlaceholder() },
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun expandedShellPhone() = captureBoth("expanded_shell_phone") {
        AdaptiveShell(
            expanded = false,
            sidebar = { ScreenshotSidebar() },
            mainContent = { ConversationsPlaceholder() },
            drawerState = rememberDrawerState(DrawerValue.Open),
        )
    }

    // Fixtures -------------------------------------------------------------------

    private fun onboardingState(
        step: OnboardingStep = OnboardingStep.WELCOME,
        apiKey: String = "",
        connected: Boolean = false,
        catalog: CatalogUiState = CatalogUiState.Ready(
            listOf(
                CatalogModel("openrouter/auto", "Auto Router", 128_000, false),
                CatalogModel("openrouter/free", "Free Model", 32_000, true),
            ),
        ),
    ): OnboardingUiState = OnboardingUiState(
        step = step,
        providers = listOf(
            ProviderOption("openrouter", "OpenRouter", true, true, "Recommended"),
            ProviderOption("openai", "OpenAI", true, false, null),
            ProviderOption("anthropic", "Anthropic", true, false, null),
            ProviderOption("google", "Google Gemini", false, false, "Coming later"),
        ),
        selectedProviderId = "openrouter",
        apiKey = apiKey,
        connected = connected,
        catalog = catalog,
    )

    private fun chatState(
        messages: List<Message> = emptyList(),
        streaming: StreamingState? = null,
        error: ChatError? = null,
    ): ChatUiState = ChatUiState(
        conversationId = CONVERSATION_ID,
        conversation = conversation(),
        messages = messages,
        streaming = streaming,
        loading = false,
        availability = ChatAvailability(available = true),
        error = error,
    )

    private fun conversation(): Conversation = Conversation(
        id = CONVERSATION_ID,
        title = "Sample conversation",
        systemPrompt = "",
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model One",
        params = chatParams(),
        pinned = false,
        archived = false,
        temporary = false,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW,
        lastMessageAt = FIXED_NOW,
        messageCount = 1,
        lastError = null,
        sync = null,
    )

    private fun userMessage(): Message = Message(
        id = "message-1",
        conversationId = CONVERSATION_ID,
        sequence = 1,
        role = MessageRole.USER,
        parts = listOf(ContentPart.Text("Summarize the release notes in one sentence.")),
        status = MessageStatus.COMPLETE,
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model One",
        usage = null,
        finishReason = null,
        error = null,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW,
        deletedAt = null,
    )

    private fun assistantMessage(): Message = Message(
        id = "message-2",
        conversationId = CONVERSATION_ID,
        sequence = 2,
        role = MessageRole.ASSISTANT,
        parts = listOf(
            ContentPart.Text(
                "The release adds grouped chat bubbles, a floating composer, and " +
                    "reviewed screenshot goldens.",
            ),
        ),
        status = MessageStatus.COMPLETE,
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model One",
        usage = null,
        finishReason = null,
        error = null,
        latencyMs = 640,
        firstTokenMs = 210,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW,
        deletedAt = null,
    )

    private fun settingsState(): SettingsUiState = SettingsUiState(
        settings = AndroidSettings(
            theme = Theme.SYSTEM,
            density = Density.COMFORTABLE,
            chatFontSize = ChatFontSize.MD,
            defaultProviderId = "provider-1",
            defaultModelId = "model-1",
            defaultSystemPrompt = "You are a concise assistant.",
            showTokenUsage = true,
            loadRemoteImages = true,
            hapticFeedback = true,
            dynamicColor = false,
        ),
        providers = listOf(provider()),
        models = listOf(model("model-1", "Model One")),
        loading = false,
    )

    private fun modelPickerState(
        models: List<Model> = listOf(
            model("model-1", "Model One", pinned = true),
            model("model-2", "Model Two"),
            model("model-3", "Model Three"),
        ),
    ): ModelPickerUiState = ModelPickerUiState(
        target = ModelPickerTarget.Defaults,
        models = models,
        providers = listOf(provider()),
        loading = false,
        selectedModelId = models.firstOrNull()?.id,
    )

    private fun providerListState(): ProviderListUiState = ProviderListUiState(
        providers = listOf(provider()),
        options = listOf(
            ProviderAvailability("openrouter", "OpenRouter", true, true, null),
            ProviderAvailability("openai", "OpenAI", true, false, null),
            ProviderAvailability("anthropic", "Anthropic", true, false, null),
            ProviderAvailability("google", "Google Gemini", false, false, "Coming later"),
        ),
        loading = false,
    )

    private fun dataSettingsState(): DataSettingsUiState = DataSettingsUiState(
        usage = StorageUsage(
            conversations = StorageUsageEntry(2, 1_024),
            messages = StorageUsageEntry(8, 4_096),
            attachments = StorageUsageEntry(1, 2_048),
            secrets = StorageUsageEntry(1, 512),
            providers = StorageUsageEntry(1, 256),
            models = StorageUsageEntry(3, 768),
        ),
        keys = listOf(
            ProviderKeySummary(
                id = "provider-1",
                label = "OpenRouter",
                presetId = "openrouter",
                hasKey = true,
                keyLabel = "Work key",
            ),
        ),
        loading = false,
    )

    private fun conversationListState(
        groups: List<ConversationGroup> = listOf(
            ConversationGroup(
                key = ConversationGroupKey.TODAY,
                label = "Today",
                conversations = listOf(
                    conversation().copy(id = "conversation-1", title = "First chat"),
                ),
            ),
            ConversationGroup(
                key = ConversationGroupKey.YESTERDAY,
                label = "Yesterday",
                conversations = listOf(
                    conversation().copy(id = "conversation-2", title = "Second chat"),
                ),
            ),
        ),
        view: ConversationListView = ConversationListView.ACTIVE,
        archivedGroups: List<ConversationGroup> = emptyList(),
        searchQuery: String = "",
        searchResults: List<ConversationSearchResult> = emptyList(),
    ): ConversationListUiState = ConversationListUiState(
        groups = groups,
        archivedGroups = archivedGroups,
        view = view,
        searchQuery = searchQuery,
        searchResults = searchResults,
        loading = false,
    )

    private fun provider(): Provider = Provider(
        id = "provider-1",
        presetId = "openrouter",
        label = "OpenRouter",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://openrouter.ai/api/v1",
        authType = ProviderAuthType.BEARER,
        secretId = "secret-1",
        headers = emptyMap(),
        enabled = true,
        browserCors = BrowserCors.SUPPORTED,
        metadata = buildJsonObject {
            put(
                "key",
                buildJsonObject {
                    put("label", "Work key")
                    put("usageUsd", 1.5)
                    put("limitUsd", 10.0)
                    put("isFreeTier", false)
                },
            )
        },
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW,
    )

    private fun model(
        id: String,
        label: String,
        pinned: Boolean = false,
    ): Model = Model(
        id = id,
        providerId = "provider-1",
        remoteId = "openrouter/$id",
        label = label,
        contextWindow = 128_000,
        maxOutputTokens = 4_096,
        capabilities = listOf(ModelCapability.TEXT),
        pricing = ModelPricing(inputPerMTok = 1.0, outputPerMTok = 2.0, currency = "USD"),
        canonicalSlug = null,
        inputModalities = listOf(InputModality.TEXT),
        outputModalities = listOf(OutputModality.TEXT),
        supportedParameters = emptyList(),
        metadata = null,
        hidden = false,
        pinned = pinned,
        source = ModelSource.DISCOVERED,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW,
    )

    private fun chatParams(): ChatParams = ChatParams(
        temperature = null,
        topP = null,
        maxOutputTokens = null,
        stop = emptyList(),
        reasoningEffort = null,
        streaming = true,
        extra = kotlinx.serialization.json.JsonObject(emptyMap()),
    )

    @Composable
    private fun ScreenshotSidebar() {
        Column(modifier = Modifier.fillMaxSize()) {
            ConversationListScreen(
                state = conversationListState(),
                onNewChat = {},
                onSelect = {},
                onRename = { _, _ -> },
                onDeleteRequested = {},
                onDeleteDismissed = {},
                onDeleteConfirmed = {},
                onRetry = {},
                onSearchQueryChanged = {},
                onSearchRetry = {},
                modifier = Modifier.weight(1f),
                now = FIXED_NOW,
                zoneId = ZoneOffset.UTC,
            )
            HorizontalDivider()
            ShellSidebarDestination.entries.forEach { destination ->
                NavigationDrawerItem(
                    label = { Text(destination.label) },
                    selected = destination == ShellSidebarDestination.Conversations,
                    onClick = {},
                )
            }
        }
    }

    private companion object {
        const val CONVERSATION_ID = "conversation-1"
        val FIXED_NOW: Instant = Instant.parse("2026-11-03T12:00:00Z")
    }
}
