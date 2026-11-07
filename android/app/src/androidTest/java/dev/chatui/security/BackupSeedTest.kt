package dev.chatui.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.entity.ConversationEntity
import dev.chatui.core.database.entity.ProviderEntity
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.datastore.settings.SettingsDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual two-target backup-exclusion proof.
 *
 * Seeds the production database, DataStore, and Keystore-backed secret store
 * with synthetic sentinels, then verifies the same state is absent on a second
 * target after a backup/restore attempt.
 *
 * Run with `-e phase seed`, `-e phase verify`, `-e phase verifyAbsent`, or
 * `-e phase clear`. The normal connected suite skips this test.
 *
 * Every value here is synthetic. Never replace it with a real key or personal
 * data.
 */
@RunWith(AndroidJUnit4::class)
class BackupSeedTest {
    @Test
    fun backupPhase() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        assumeTrue(
            "manual backup proof; pass -e phase seed|verify|verifyAbsent|clear",
            phase in setOf("seed", "verify", "verifyAbsent", "clear"),
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = ChatUiDatabase.create(context)
        val settings = SettingsDataStore.create(context)
        try {
            val store = SecretStore.create(context, database.secretDao())
            when (phase) {
                "seed" -> {
                    store.put(SECRET_ID, SENTINEL)
                    database.providerDao().upsert(provider())
                    database.conversationDao().upsert(conversation())
                    settings.setDefaultProvider(PROVIDER_ID, MODEL_ID)
                    settings.setOnboardingCompleted(true)
                    settings.setLastActiveConversation(CONVERSATION_ID)
                    verifyPresent(database, store, settings)
                }
                "verify" -> verifyPresent(database, store, settings)
                "verifyAbsent" -> verifyAbsent(database, store, settings)
                "clear" -> {
                    store.delete(SECRET_ID)
                    database.providerDao().deleteById(PROVIDER_ID)
                    database.conversationDao().deleteWithChildren(CONVERSATION_ID)
                    settings.setOnboardingCompleted(false)
                    settings.setDefaultProvider(null, null)
                    settings.setLastActiveConversation(null)
                }
            }
        } finally {
            database.close()
        }
    }

    private suspend fun verifyPresent(
        database: ChatUiDatabase,
        store: SecretStore,
        settings: SettingsDataStore,
    ) {
        assertEquals(SENTINEL, store.get(SECRET_ID))
        assertNotNull(database.providerDao().getById(PROVIDER_ID))
        assertNotNull(database.conversationDao().getById(CONVERSATION_ID))
        val current = settings.current()
        assertTrue(current.onboardingCompleted)
        assertEquals(PROVIDER_ID, current.defaultProviderId)
        assertEquals(CONVERSATION_ID, current.lastActiveConversationId)
    }

    private suspend fun verifyAbsent(
        database: ChatUiDatabase,
        store: SecretStore,
        settings: SettingsDataStore,
    ) {
        assertNull(store.get(SECRET_ID))
        assertNull(database.providerDao().getById(PROVIDER_ID))
        assertNull(database.conversationDao().getById(CONVERSATION_ID))
        val current = settings.current()
        assertFalse(current.onboardingCompleted)
        assertNull(current.defaultProviderId)
        assertNull(current.lastActiveConversationId)
    }

    private fun provider() = ProviderEntity(
        id = PROVIDER_ID,
        presetId = "openrouter",
        label = "M3 backup sentinel provider",
        kind = "openai-compatible",
        baseUrl = "https://openrouter.ai/api/v1",
        authType = "bearer",
        secretId = SECRET_ID,
        headersJson = "{}",
        enabled = true,
        browserCors = "unknown",
        metadataJson = null,
        createdAt = TIMESTAMP,
        updatedAt = TIMESTAMP,
    )

    private fun conversation() = ConversationEntity(
        id = CONVERSATION_ID,
        title = "M3 backup sentinel conversation",
        systemPrompt = "",
        providerId = PROVIDER_ID,
        modelId = MODEL_ID,
        modelLabel = "Sentinel model",
        paramsJson = "{\"temperature\":null,\"topP\":null,\"maxOutputTokens\":null," +
            "\"stop\":[],\"reasoningEffort\":null,\"streaming\":true,\"extra\":{}}",
        pinned = false,
        archived = false,
        temporary = false,
        createdAt = TIMESTAMP,
        updatedAt = TIMESTAMP,
        lastMessageAt = TIMESTAMP,
        messageCount = 0,
        lastErrorJson = null,
        syncState = null,
    )

    private companion object {
        const val SENTINEL = "m3-backup-sentinel-not-a-real-key"
        const val SECRET_ID = "m3-backup-sentinel"
        const val PROVIDER_ID = "m3-backup-provider"
        const val CONVERSATION_ID = "m3-backup-conversation"
        const val MODEL_ID = "m3-backup-model"
        const val TIMESTAMP = "2026-11-06T00:00:00Z"
    }
}
