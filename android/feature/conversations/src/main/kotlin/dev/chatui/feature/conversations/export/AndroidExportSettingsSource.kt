package dev.chatui.feature.conversations.export

import dev.chatui.core.database.export.ExportSettingsSource
import dev.chatui.core.database.repository.BackupConfigRepository
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.model.Settings

/**
 * Maps Android's non-secret settings store and Room backup placeholder into the
 * shared export `Settings` shape.
 *
 * `rememberKeysOnWeb` is a web-only preference and is always false on Android.
 * Backup credentials are referenced by id; no secret material is read.
 */
class AndroidExportSettingsSource(
    private val settings: SettingsDataStore,
    private val backupConfigs: BackupConfigRepository,
) : ExportSettingsSource {
    override suspend fun current(): Settings {
        val android = settings.current()

        return Settings(
            theme = android.theme,
            density = android.density,
            chatFontSize = android.chatFontSize,
            sendKey = android.sendKey,
            defaultProviderId = android.defaultProviderId,
            defaultModelId = android.defaultModelId,
            defaultSystemPrompt = android.defaultSystemPrompt,
            defaultParams = android.defaultParams,
            rememberKeysOnWeb = false,
            showTokenUsage = android.showTokenUsage,
            loadRemoteImages = android.loadRemoteImages,
            lastActiveConversationId = android.lastActiveConversationId,
            backup = backupConfigs.get(),
            onboardingCompleted = android.onboardingCompleted,
        )
    }
}
