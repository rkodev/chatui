package dev.chatui.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.Density
import dev.chatui.core.model.ReasoningEffort
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Theme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsDataStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsDataStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tempFolder.root, "settings.preferences_pb") },
        )
        store = SettingsDataStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults are returned when nothing is stored`() = runTest {
        assertThat(store.settings.first()).isEqualTo(AndroidSettings())
    }

    @Test
    fun `token usage is shown by default`() = runTest {
        assertThat(store.settings.first().showTokenUsage).isTrue()
    }

    @Test
    fun `save and read typed settings`() = runTest {
        val settings = AndroidSettings(
            theme = Theme.DARK,
            density = Density.COMPACT,
            chatFontSize = ChatFontSize.LG,
            sendKey = SendKey.MOD_ENTER,
            defaultProviderId = "provider-1",
            defaultModelId = "model-1",
            defaultSystemPrompt = "Be concise",
            defaultParams = ChatParams(
                temperature = 0.2,
                topP = 0.9,
                maxOutputTokens = 512,
                stop = listOf("END"),
                reasoningEffort = ReasoningEffort.HIGH,
                streaming = false,
                extra = JsonObject(mapOf("openrouter" to JsonPrimitive("route"))),
            ),
            showTokenUsage = true,
            loadRemoteImages = false,
            lastActiveConversationId = "conversation-1",
            onboardingCompleted = true,
        )

        store.save(settings)

        assertThat(store.settings.first()).isEqualTo(settings)
    }

    @Test
    fun `update preserves unrelated fields`() = runTest {
        store.save(AndroidSettings(theme = Theme.DARK, onboardingCompleted = true))

        store.update { it.copy(theme = Theme.LIGHT) }

        val loaded = store.settings.first()
        assertThat(loaded.theme).isEqualTo(Theme.LIGHT)
        assertThat(loaded.onboardingCompleted).isTrue()
    }

    @Test
    fun `nullable fields can be cleared`() = runTest {
        store.save(
            AndroidSettings(
                defaultProviderId = "provider-1",
                defaultModelId = "model-1",
                lastActiveConversationId = "conversation-1",
            ),
        )

        store.save(AndroidSettings())

        val loaded = store.settings.first()
        assertThat(loaded.defaultProviderId).isNull()
        assertThat(loaded.defaultModelId).isNull()
        assertThat(loaded.lastActiveConversationId).isNull()
    }

    @Test
    fun `settings persist across data store instances`() = runTest {
        val file = File(tempFolder.root, "persisted.preferences_pb")
        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = SettingsDataStore(
            PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }),
        )
        first.save(AndroidSettings(theme = Theme.DARK, onboardingCompleted = true))
        firstScope.cancel()
        firstScope.coroutineContext[Job]?.join()

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val second = SettingsDataStore(
            PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file }),
        )
        val loaded = second.settings.first()
        secondScope.cancel()

        assertThat(loaded.theme).isEqualTo(Theme.DARK)
        assertThat(loaded.onboardingCompleted).isTrue()
    }

    @Test
    fun `corrupt enum and params fall back to defaults`() = runTest {
        dataStore.edit { preferences ->
            preferences[SettingsKeys.theme] = "NOT_A_THEME"
            preferences[SettingsKeys.defaultParams] = "{not-json"
        }

        val loaded = store.settings.first()

        assertThat(loaded.theme).isEqualTo(Theme.SYSTEM)
        assertThat(loaded.defaultParams).isEqualTo(AndroidSettings.DEFAULT_CHAT_PARAMS)
    }

    @Test
    fun `settings keys contain no secret material`() = runTest {
        store.save(AndroidSettings())

        val keys = dataStore.data.first().asMap().keys.map { it.name }

        assertThat(keys.none { key ->
            key.contains("secret") || key.contains("api_key") || key.contains("password")
        }).isTrue()
    }
}
