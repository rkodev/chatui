package dev.chatui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentService
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.repository.AttachmentRepository
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.feature.conversations.importer.DataStoreImportSettingsStore
import dev.chatui.feature.conversations.importer.ImportService
import dev.chatui.feature.conversations.importer.toAndroidSettings
import dev.chatui.testing.BaselineFixtures
import dev.chatui.testing.BaselineImages
import dev.chatui.testing.TEST_NOW
import dev.chatui.testing.baselineSha256
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in setup for a DISPOSABLE reference install, not a shipping data entry point.
 * Pass baselineAction=seed|verify|reset and baselineDisposable=true explicitly.
 */
@RunWith(AndroidJUnit4::class)
class BaselineSeedTest {
    @Test
    fun fixturePhase(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val action = arguments.getString("baselineAction")
        assumeTrue(
            "manual reference fixture; pass baselineAction=seed|verify|reset",
            action in setOf("seed", "verify", "reset"),
        )
        check(arguments.getString("baselineDisposable") == "true") {
            "Use only an explicitly acknowledged disposable reference install."
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = ChatUiDatabase.create(context)
        try {
            val clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC)
            val conversations = ConversationRepository(database.conversationDao(), clock)
            val providers = ProviderRepository(database.providerDao(), clock)
            val messages = MessageRepository(database.messageDao(), clock)
            val settings = SettingsDataStore.create(context)
            val storage = AttachmentStorage(context.filesDir)
            val archive = BaselineFixtures.archive(longHistory = true)
            val ids = archive.conversations.map { it.id }.toSet()
            when (action) {
                "seed" -> {
                    check(conversations.all().isEmpty() && providers.all().isEmpty()) {
                        "Seeding refuses a nonempty install. Reset fixture-owned data first; never clear real data."
                    }
                    check(!settings.current().onboardingCompleted) {
                        "Seeding requires a fresh or fixture-reset disposable install."
                    }
                    ImportService(
                        database,
                        storage,
                        DataStoreImportSettingsStore(settings),
                        now = { TEST_NOW.toString() },
                    ).importText(BaselineFixtures.exportText(archive))
                    settings.save(
                        archive.settings.toAndroidSettings()
                            .copy(lastActiveConversationId = BaselineFixtures.LONG_CHAT),
                    )
                }
                "reset" -> {
                    val stored = providers.get(BaselineFixtures.provider.id)
                    check(
                        stored == null ||
                            (stored.baseUrl == BaselineFixtures.provider.baseUrl && stored.secretId == null),
                    ) { "Refusing to reset a modified fixture provider or any stored key." }
                    val attachments = AttachmentService(
                        AttachmentRepository(database.attachmentDao()),
                        messages,
                        storage,
                        clock = clock,
                    )
                    archive.attachments.forEach { attachments.remove(it.id) }
                    ids.forEach { conversations.delete(it) }
                    providers.delete(BaselineFixtures.provider.id)
                    settings.setDrafts(settings.drafts().filterKeys { it !in ids })
                    val empty = conversations.all().isEmpty() && providers.all().isEmpty()
                    settings.update { current ->
                        current.copy(
                            defaultProviderId = current.defaultProviderId
                                .takeUnless { it == BaselineFixtures.provider.id },
                            defaultModelId = current.defaultModelId
                                .takeUnless { id -> archive.models.any { it.id == id } },
                            lastActiveConversationId = current.lastActiveConversationId.takeUnless { it in ids },
                            onboardingCompleted = current.onboardingCompleted && !empty,
                        )
                    }
                }
            }
            if (action == "reset") {
                assertEquals(0, messages.count(BaselineFixtures.LONG_CHAT))
                assertFalse(conversations.all().any { it.id in ids })
                assertFalse(providers.all().any { it.id == BaselineFixtures.provider.id })
                assertFalse(settings.current().lastActiveConversationId in ids)
            } else {
                assertEquals(10_000, messages.count(BaselineFixtures.LONG_CHAT))
                assertEquals(10_000, conversations.get(BaselineFixtures.LONG_CHAT)!!.messageCount)
                assertTrue(
                    "The reference transcript must match the versioned fixture exactly.",
                    messages.allForConversation(BaselineFixtures.LONG_CHAT) == archive.messages,
                )
                assertTrue(settings.current().onboardingCompleted)
                assertEquals(BaselineFixtures.LONG_CHAT, settings.current().lastActiveConversationId)
                BaselineImages.all.forEach { image ->
                    val bytes = storage.read(AttachmentStorage.storageKeyFor(image.sha256))!!
                    assertEquals(image.sha256, baselineSha256(bytes))
                }
            }
        } finally {
            database.close()
        }
    }
}
