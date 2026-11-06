package dev.chatui.feature.conversations.importer

import dev.chatui.core.database.import.ImportMode
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.model.Settings

/** Maximum accepted archive size before parsing. */
const val MAX_IMPORT_BYTES: Int = 50 * 1024 * 1024

/** Maximum decoded size of one attachment restored from an archive. */
const val MAX_IMPORT_ATTACHMENT_BYTES: Int = 20 * 1024 * 1024

/** Maximum attachment file name length, matching the picker rules. */
const val MAX_IMPORT_ATTACHMENT_NAME_LENGTH: Int = 255

/** Maximum identifier length accepted from an untrusted archive. */
const val MAX_IMPORT_ID_LENGTH: Int = 256

/** Shown before an explicit replace import. */
const val IMPORT_REPLACE_CONFIRMATION: String =
    "Replace all local conversations, messages, attachments, providers, models, and settings " +
        "with this archive? This cannot be undone."

/** Stable error codes for import validation failures. */
enum class ImportErrorCode {
    TOO_LARGE,
    INVALID_JSON,
    SCHEMA,
    INVALID_ID,
    DUPLICATE_ID,
    INVALID_REFERENCE,
    UNSAFE_ATTACHMENT_NAME,
    INVALID_ATTACHMENT,
    INVALID_MODE,
}

/** Thrown before any write when an archive is not safe to import. */
class ImportValidationError(
    val code: ImportErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Import options. Merge is the default; replace must be explicit. */
data class ImportOptions(
    val mode: ImportMode = ImportMode.MERGE,
)

/** Counts returned after a successful import. */
data class ImportResult(
    val mode: ImportMode,
    val conversations: Int,
    val messages: Int,
    val attachments: Int,
    val providers: Int,
    val models: Int,
    val attachmentBytes: Int,
)

/**
 * Non-secret settings persistence used by replace imports.
 *
 * Merge imports leave local settings untouched. Replace imports write the
 * archive settings and restore the previous values if the Room transaction
 * fails.
 */
interface ImportSettingsStore {
    suspend fun current(): AndroidSettings

    suspend fun save(settings: AndroidSettings)
}

/** Production adapter over the Android preferences DataStore. */
class DataStoreImportSettingsStore(
    private val dataStore: SettingsDataStore,
) : ImportSettingsStore {
    override suspend fun current(): AndroidSettings = dataStore.current()

    override suspend fun save(settings: AndroidSettings) = dataStore.save(settings)
}

/**
 * Map the shared settings shape onto Android's non-secret settings.
 *
 * `rememberKeysOnWeb` is web-only and `backup` lives in Room, so neither is
 * written to DataStore. Secrets are never part of an archive.
 */
fun Settings.toAndroidSettings(): AndroidSettings = AndroidSettings(
    theme = theme,
    density = density,
    chatFontSize = chatFontSize,
    sendKey = sendKey,
    defaultProviderId = defaultProviderId,
    defaultModelId = defaultModelId,
    defaultSystemPrompt = defaultSystemPrompt,
    defaultParams = defaultParams,
    showTokenUsage = showTokenUsage,
    loadRemoteImages = loadRemoteImages,
    lastActiveConversationId = lastActiveConversationId,
    onboardingCompleted = onboardingCompleted,
)
