package dev.chatui.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.feature.providers.ProviderManagementService

/** Settings sections reachable from the shell. */
enum class SettingsSection {
    ROOT,
    APPEARANCE,
    CHAT_DEFAULTS,
    DATA,
    BACKUP,
    ABOUT,
}

/**
 * Stateful settings entry point.
 *
 * The root section renders the section list without a ViewModel. Every other
 * section shares one [SettingsViewModel] so appearance and chat-default changes
 * persist through the same typed settings store.
 */
@Composable
fun SettingsRoute(
    section: SettingsSection,
    settingsStore: SettingsDataStore,
    service: ProviderManagementService,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (section == SettingsSection.ROOT) {
        SettingsRootScreen(
            onOpenAppearance = { onOpenSection(SettingsSection.APPEARANCE) },
            onOpenChatDefaults = { onOpenSection(SettingsSection.CHAT_DEFAULTS) },
            onOpenProviders = onOpenProviders,
            onOpenData = { onOpenSection(SettingsSection.DATA) },
            onOpenBackup = { onOpenSection(SettingsSection.BACKUP) },
            onOpenAbout = { onOpenSection(SettingsSection.ABOUT) },
            modifier = modifier,
        )
        return
    }

    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(settingsStore, service),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (section) {
        SettingsSection.APPEARANCE -> AppearanceSettingsScreen(
            settings = state.settings,
            onThemeChanged = viewModel::onThemeChanged,
            onDensityChanged = viewModel::onDensityChanged,
            onChatFontSizeChanged = viewModel::onChatFontSizeChanged,
            modifier = modifier,
        )

        SettingsSection.CHAT_DEFAULTS -> ChatDefaultsSettingsScreen(
            state = state,
            onDefaultProviderChanged = viewModel::onDefaultProviderChanged,
            onDefaultModelChanged = viewModel::onDefaultModelChanged,
            onDefaultSystemPromptChanged = viewModel::onDefaultSystemPromptChanged,
            onTemperatureChanged = viewModel::onTemperatureChanged,
            onTopPChanged = viewModel::onTopPChanged,
            onMaxOutputTokensChanged = viewModel::onMaxOutputTokensChanged,
            onStopChanged = viewModel::onStopChanged,
            onReasoningEffortChanged = viewModel::onReasoningEffortChanged,
            onStreamingChanged = viewModel::onStreamingChanged,
            onSendKeyChanged = viewModel::onSendKeyChanged,
            onShowTokenUsageChanged = viewModel::onShowTokenUsageChanged,
            onLoadRemoteImagesChanged = viewModel::onLoadRemoteImagesChanged,
            onDismissError = viewModel::onDismissError,
            modifier = modifier,
        )

        SettingsSection.DATA -> DataSettingsScreen(modifier = modifier)

        SettingsSection.BACKUP -> BackupSettingsScreen(modifier = modifier)

        SettingsSection.ABOUT -> AboutSettingsScreen(modifier = modifier)

        SettingsSection.ROOT -> Unit
    }
}

/** Entry point used by the shell to obtain the settings store without Hilt VMs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    fun settingsDataStore(): SettingsDataStore
}
