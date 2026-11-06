package dev.chatui.feature.conversations

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.settings.SettingsDataStore
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: ChatUiDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var messages: MessageRepository
    private lateinit var providers: ProviderRepository
    private lateinit var models: ModelRepository
    private lateinit var settings: SettingsDataStore
    private lateinit var dataStoreScope: TestScope
    private lateinit var service: DefaultConversationService

    private val clock: Clock = Clock.fixed(
        Instant.parse("2026-11-03T12:00:00Z"),
        ZoneOffset.UTC,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = ChatUiDatabase.inMemory(context)
        conversations = ConversationRepository(database.conversationDao(), clock)
        messages = MessageRepository(database.messageDao(), clock)
        providers = ProviderRepository(database.providerDao(), clock)
        models = ModelRepository(database.modelDao())
        dataStoreScope = TestScope(UnconfinedTestDispatcher())
        settings = SettingsDataStore(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = {
                    File(temporaryFolder.newFolder(), "settings.preferences_pb")
                },
            ),
        )
        service = DefaultConversationService(
            conversations = conversations,
            messages = messages,
            providers = providers,
            models = models,
            settings = settings,
            clock = clock,
        )
        service.generateId = { "conversation-1" }
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        database.close()
    }

    @Test
    fun `create uses configured defaults and records last active`() = runTest {
        val provider = providers.upsert(testProvider())
        val model = models.insert(testModel(providerId = provider.id))
        settings.setDefaultProvider(provider.id, model.id)
        settings.setDefaultSystemPrompt("Be concise")

        val result = service.createConversation()

        assertThat(result).isInstanceOf(CreateConversationResult.Created::class.java)
        val conversation = (result as CreateConversationResult.Created).conversation
        assertThat(conversation.id).isEqualTo("conversation-1")
        assertThat(conversation.title).isEqualTo(DEFAULT_CONVERSATION_TITLE)
        assertThat(conversation.providerId).isEqualTo(provider.id)
        assertThat(conversation.modelId).isEqualTo(model.id)
        assertThat(conversation.modelLabel).isEqualTo(model.label)
        assertThat(conversation.systemPrompt).isEqualTo("Be concise")
        assertThat(conversation.createdAt).isEqualTo(clock.instant())
        assertThat(conversations.get(conversation.id)).isEqualTo(conversation)
        assertThat(settings.current().lastActiveConversationId).isEqualTo(conversation.id)
    }

    @Test
    fun `create falls back to the first enabled provider and text model`() = runTest {
        val disabled = providers.upsert(testProvider(id = "provider-disabled", enabled = false))
        val enabled = providers.upsert(testProvider(id = "provider-1"))
        val model = models.insert(testModel(providerId = enabled.id))
        settings.setDefaultProvider(disabled.id, "missing-model")

        val result = service.createConversation()

        val conversation = (result as CreateConversationResult.Created).conversation
        assertThat(conversation.providerId).isEqualTo(enabled.id)
        assertThat(conversation.modelId).isEqualTo(model.id)
    }

    @Test
    fun `create returns not configured without a provider`() = runTest {
        val result = service.createConversation()

        assertThat(result).isInstanceOf(CreateConversationResult.NotConfigured::class.java)
        assertThat((result as CreateConversationResult.NotConfigured).message)
            .contains("No provider is configured")
    }

    @Test
    fun `create returns not configured without a text model`() = runTest {
        val provider = providers.upsert(testProvider())
        models.insert(testModel(providerId = provider.id, hidden = true))
        settings.setDefaultProvider(provider.id, null)

        val result = service.createConversation()

        assertThat(result).isInstanceOf(CreateConversationResult.NotConfigured::class.java)
        assertThat((result as CreateConversationResult.NotConfigured).message)
            .contains("No model is available")
    }

    @Test
    fun `rename trims and persists the title`() = runTest {
        val conversation = conversations.create(testConversation())

        val updated = service.renameConversation(conversation.id, "  New title  ")

        assertThat(updated.title).isEqualTo("New title")
        assertThat(conversations.get(conversation.id)!!.title).isEqualTo("New title")
    }

    @Test
    fun `rename rejects blank titles and missing conversations`() = runTest {
        val conversation = conversations.create(testConversation())

        val blank = runCatching {
            service.renameConversation(conversation.id, "   ")
        }.exceptionOrNull()
        assertThat(blank).isInstanceOf(ConversationServiceException::class.java)
        assertThat((blank as ConversationServiceException).code)
            .isEqualTo(ConversationServiceErrorCode.INVALID_INPUT)

        val missing = runCatching {
            service.renameConversation("missing", "Title")
        }.exceptionOrNull()
        assertThat(missing).isInstanceOf(ConversationServiceException::class.java)
        assertThat((missing as ConversationServiceException).code)
            .isEqualTo(ConversationServiceErrorCode.NOT_FOUND)
    }

    @Test
    fun `delete removes the conversation and clears last active`() = runTest {
        val conversation = conversations.create(testConversation())
        settings.setLastActiveConversation(conversation.id)

        service.deleteConversation(conversation.id)

        assertThat(conversations.get(conversation.id)).isNull()
        assertThat(settings.current().lastActiveConversationId).isNull()
    }

    @Test
    fun `last active restore clears a stale pointer`() = runTest {
        settings.setLastActiveConversation("missing")

        val restored = service.getLastActiveConversation()

        assertThat(restored).isNull()
        assertThat(settings.current().lastActiveConversationId).isNull()
    }

    @Test
    fun `last active restore returns the stored conversation`() = runTest {
        val conversation = conversations.create(testConversation())
        service.setLastActiveConversation(conversation.id)

        assertThat(service.getLastActiveConversation()).isEqualTo(conversation)
    }

    @Test
    fun `setting an unknown last active conversation is rejected`() = runTest {
        val error = runCatching {
            service.setLastActiveConversation("missing")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ConversationServiceException::class.java)
        assertThat((error as ConversationServiceException).code)
            .isEqualTo(ConversationServiceErrorCode.NOT_FOUND)
    }

    @Test
    fun `observe conversations excludes archived rows`() = runTest {
        conversations.create(testConversation(id = "active"))
        conversations.create(testConversation(id = "archived", archived = true))

        val observed = service.observeConversations().first()

        assertThat(observed.map { it.id }).containsExactly("active")
    }

    @Test
    fun `pin and archive persist and filter the active and archive views`() = runTest {
        conversations.create(testConversation(id = "active"))
        conversations.create(testConversation(id = "archived", archived = true))

        service.setPinned("active", true)
        service.setArchived("active", true)

        assertThat(conversations.get("active")!!.pinned).isTrue()
        assertThat(conversations.get("active")!!.archived).isTrue()
        assertThat(service.observeConversations().first()).isEmpty()
        assertThat(service.observeArchivedConversations().first().map { it.id })
            .containsExactly("active", "archived")
    }

    @Test
    fun `pinned conversations sort first in the active list`() = runTest {
        conversations.create(
            testConversation(
                id = "older",
                lastMessageAt = Instant.parse("2026-11-01T12:00:00Z"),
            ),
        )
        conversations.create(
            testConversation(
                id = "newer",
                lastMessageAt = Instant.parse("2026-11-02T12:00:00Z"),
            ),
        )

        service.setPinned("older", true)

        assertThat(service.observeConversations().first().map { it.id })
            .containsExactly("older", "newer")
            .inOrder()
    }

    @Test
    fun `pin and archive reject missing conversations`() = runTest {
        val pinError = runCatching { service.setPinned("missing", true) }.exceptionOrNull()
        assertThat(pinError).isInstanceOf(ConversationServiceException::class.java)
        assertThat((pinError as ConversationServiceException).code)
            .isEqualTo(ConversationServiceErrorCode.NOT_FOUND)

        val archiveError = runCatching {
            service.setArchived("missing", true)
        }.exceptionOrNull()
        assertThat(archiveError).isInstanceOf(ConversationServiceException::class.java)
        assertThat((archiveError as ConversationServiceException).code)
            .isEqualTo(ConversationServiceErrorCode.NOT_FOUND)
    }

    @Test
    fun `search groups title and message matches by conversation`() = runTest {
        conversations.create(testConversation(id = "title", title = "Kotlin notes"))
        conversations.create(testConversation(id = "message", title = "Other"))
        messages.appendMessage(
            testMessage(
                id = "message-1",
                conversationId = "message",
                sequence = 1,
                text = "I love Kotlin",
            ),
        )
        messages.appendMessage(
            testMessage(
                id = "message-2",
                conversationId = "message",
                sequence = 2,
                text = "Kotlin coroutines",
            ),
        )

        val results = service.searchConversations("Kotlin")

        assertThat(results.map { it.conversation.id })
            .containsExactly("title", "message")

        val titleResult = results.first { it.conversation.id == "title" }
        assertThat(titleResult.titleMatched).isTrue()
        assertThat(titleResult.messageMatches).isEmpty()

        val messageResult = results.first { it.conversation.id == "message" }
        assertThat(messageResult.titleMatched).isFalse()
        assertThat(messageResult.messageMatches.map { it.messageId })
            .containsExactly("message-2", "message-1")
            .inOrder()
        assertThat(messageResult.messageMatches.first().snippet).contains("Kotlin")
    }

    @Test
    fun `search excludes archived conversations`() = runTest {
        conversations.create(testConversation(id = "active", title = "Kotlin"))
        conversations.create(
            testConversation(id = "archived", title = "Kotlin", archived = true),
        )

        val results = service.searchConversations("Kotlin")

        assertThat(results.map { it.conversation.id }).containsExactly("active")
    }

    @Test
    fun `search returns nothing for a blank query`() = runTest {
        conversations.create(testConversation(id = "conversation-1", title = "Kotlin"))

        assertThat(service.searchConversations("   ")).isEmpty()
    }

    @Test
    fun `search sanitizes fts operators`() = runTest {
        conversations.create(testConversation(id = "conversation-1", title = "Notes"))
        messages.appendMessage(
            testMessage(
                id = "message-1",
                conversationId = "conversation-1",
                text = "hello world",
            ),
        )

        val results = service.searchConversations("\"hello\" (world)")

        assertThat(results.map { it.conversation.id }).containsExactly("conversation-1")
        assertThat(results.single().messageMatches.single().messageId)
            .isEqualTo("message-1")
    }

    @Test
    fun `title search escapes like wildcards`() = runTest {
        conversations.create(testConversation(id = "percent", title = "100% done"))
        conversations.create(testConversation(id = "other", title = "100 done"))

        val results = service.searchConversations("100%")

        assertThat(results.map { it.conversation.id }).containsExactly("percent")
    }

    @Test
    fun `search orders pinned conversations first`() = runTest {
        conversations.create(
            testConversation(
                id = "older",
                title = "Kotlin",
                lastMessageAt = Instant.parse("2026-11-01T12:00:00Z"),
            ),
        )
        conversations.create(
            testConversation(
                id = "pinned",
                title = "Kotlin",
                lastMessageAt = Instant.parse("2026-11-02T12:00:00Z"),
            ),
        )
        conversations.setPinned("pinned", true)

        val results = service.searchConversations("Kotlin")

        assertThat(results.map { it.conversation.id })
            .containsExactly("pinned", "older")
            .inOrder()
    }
}
