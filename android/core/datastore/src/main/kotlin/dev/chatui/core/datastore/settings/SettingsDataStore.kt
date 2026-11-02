package dev.chatui.core.datastore.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ChatUiJson
import dev.chatui.core.model.Density
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Theme
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

/** Preference keys used by [SettingsDataStore]. */
internal object SettingsKeys {
    val theme = stringPreferencesKey("theme")
    val density = stringPreferencesKey("density")
    val chatFontSize = stringPreferencesKey("chat_font_size")
    val sendKey = stringPreferencesKey("send_key")
    val defaultProviderId = stringPreferencesKey("default_provider_id")
    val defaultModelId = stringPreferencesKey("default_model_id")
    val defaultSystemPrompt = stringPreferencesKey("default_system_prompt")
    val defaultParams = stringPreferencesKey("default_params_json")
    val showTokenUsage = booleanPreferencesKey("show_token_usage")
    val loadRemoteImages = booleanPreferencesKey("load_remote_images")
    val lastActiveConversationId = stringPreferencesKey("last_active_conversation_id")
    val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
}

/**
 * Preferences DataStore persistence for non-secret Android settings.
 *
 * Secrets, tokens, and provider keys must never be written here. The store
 * exposes a typed [AndroidSettings] model and falls back to defaults when a
 * value is absent or corrupt.
 */
class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** Emits the current settings, or defaults when the backing file is unreadable. */
    val settings: Flow<AndroidSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences -> preferences.toAndroidSettings() }

    /** Reads the current settings once. */
    suspend fun current(): AndroidSettings = settings.first()

    /** Replaces every persisted setting. */
    suspend fun save(settings: AndroidSettings) {
        dataStore.edit { preferences -> preferences.write(settings) }
    }

    /** Atomically updates settings from the current persisted value. */
    suspend fun update(transform: (AndroidSettings) -> AndroidSettings) {
        dataStore.edit { preferences ->
            preferences.write(transform(preferences.toAndroidSettings()))
        }
    }

    suspend fun setTheme(theme: Theme) = update { it.copy(theme = theme) }

    suspend fun setDensity(density: Density) = update { it.copy(density = density) }

    suspend fun setChatFontSize(chatFontSize: ChatFontSize) =
        update { it.copy(chatFontSize = chatFontSize) }

    suspend fun setSendKey(sendKey: SendKey) = update { it.copy(sendKey = sendKey) }

    suspend fun setDefaultProvider(providerId: String?, modelId: String?) =
        update { it.copy(defaultProviderId = providerId, defaultModelId = modelId) }

    suspend fun setDefaultSystemPrompt(prompt: String) =
        update { it.copy(defaultSystemPrompt = prompt) }

    suspend fun setDefaultParams(params: ChatParams) = update { it.copy(defaultParams = params) }

    suspend fun setShowTokenUsage(show: Boolean) = update { it.copy(showTokenUsage = show) }

    suspend fun setLoadRemoteImages(load: Boolean) = update { it.copy(loadRemoteImages = load) }

    suspend fun setLastActiveConversation(conversationId: String?) =
        update { it.copy(lastActiveConversationId = conversationId) }

    suspend fun setOnboardingCompleted(completed: Boolean) =
        update { it.copy(onboardingCompleted = completed) }

    companion object {
        /** DataStore file name; the file lives under `files/datastore/`. */
        const val DATASTORE_FILE_NAME: String = "settings"

        /** Directory under `filesDir` that holds DataStore files. */
        const val DATASTORE_DIRECTORY: String = "datastore"

        /** Creates a store backed by the application's settings DataStore. */
        fun create(context: Context): SettingsDataStore =
            SettingsDataStore(context.settingsDataStore)
    }
}

/** Application-scoped settings DataStore. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsDataStore.DATASTORE_FILE_NAME,
)

private fun Preferences.toAndroidSettings(): AndroidSettings {
    val defaults = AndroidSettings()
    return AndroidSettings(
        theme = parseEnum(this[SettingsKeys.theme], defaults.theme),
        density = parseEnum(this[SettingsKeys.density], defaults.density),
        chatFontSize = parseEnum(this[SettingsKeys.chatFontSize], defaults.chatFontSize),
        sendKey = parseEnum(this[SettingsKeys.sendKey], defaults.sendKey),
        defaultProviderId = this[SettingsKeys.defaultProviderId],
        defaultModelId = this[SettingsKeys.defaultModelId],
        defaultSystemPrompt = this[SettingsKeys.defaultSystemPrompt] ?: defaults.defaultSystemPrompt,
        defaultParams = parseParams(this[SettingsKeys.defaultParams], defaults.defaultParams),
        showTokenUsage = this[SettingsKeys.showTokenUsage] ?: defaults.showTokenUsage,
        loadRemoteImages = this[SettingsKeys.loadRemoteImages] ?: defaults.loadRemoteImages,
        lastActiveConversationId = this[SettingsKeys.lastActiveConversationId],
        onboardingCompleted = this[SettingsKeys.onboardingCompleted] ?: defaults.onboardingCompleted,
    )
}

private fun MutablePreferences.write(settings: AndroidSettings) {
    this[SettingsKeys.theme] = settings.theme.name
    this[SettingsKeys.density] = settings.density.name
    this[SettingsKeys.chatFontSize] = settings.chatFontSize.name
    this[SettingsKeys.sendKey] = settings.sendKey.name
    writeNullable(SettingsKeys.defaultProviderId, settings.defaultProviderId)
    writeNullable(SettingsKeys.defaultModelId, settings.defaultModelId)
    this[SettingsKeys.defaultSystemPrompt] = settings.defaultSystemPrompt
    this[SettingsKeys.defaultParams] =
        ChatUiJson.encodeToString(ChatParams.serializer(), settings.defaultParams)
    this[SettingsKeys.showTokenUsage] = settings.showTokenUsage
    this[SettingsKeys.loadRemoteImages] = settings.loadRemoteImages
    writeNullable(SettingsKeys.lastActiveConversationId, settings.lastActiveConversationId)
    this[SettingsKeys.onboardingCompleted] = settings.onboardingCompleted
}

private fun MutablePreferences.writeNullable(
    key: Preferences.Key<String>,
    value: String?,
) {
    if (value == null) {
        remove(key)
    } else {
        this[key] = value
    }
}

private inline fun <reified T : Enum<T>> parseEnum(value: String?, default: T): T =
    value?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: default

private fun parseParams(value: String?, default: ChatParams): ChatParams {
    if (value == null) return default
    return try {
        ChatUiJson.decodeFromString(ChatParams.serializer(), value)
    } catch (_: SerializationException) {
        default
    } catch (_: IllegalArgumentException) {
        default
    }
}
