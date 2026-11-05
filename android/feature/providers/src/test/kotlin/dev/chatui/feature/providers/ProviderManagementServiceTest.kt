package dev.chatui.feature.providers

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.providers.CreateCustomProviderInput
import dev.chatui.core.providers.CreatePresetProviderInput
import dev.chatui.core.providers.ModelService
import dev.chatui.core.providers.ProviderService
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class ProviderManagementServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: ChatUiDatabase
    private lateinit var providerRepository: ProviderRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var dataStoreScope: TestScope
    private lateinit var service: DefaultProviderManagementService
    private lateinit var providerService: ProviderService
    private var nextId = 0

    private val clock: Clock = Clock.fixed(
        Instant.parse("2026-11-03T00:00:00Z"),
        ZoneOffset.UTC,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = ChatUiDatabase.inMemory(context)
        providerRepository = ProviderRepository(database.providerDao(), clock)
        modelRepository = ModelRepository(database.modelDao())
        conversationRepository = ConversationRepository(database.conversationDao(), clock)
        messageRepository = MessageRepository(database.messageDao(), clock)
        val secretStore = SecretStore(
            dao = database.secretDao(),
            cipher = FakeSecretCipher(),
            clock = { clock.instant() },
        )
        val gateway = FakeProviderGateway()
        val registry = ProviderRegistry.load()
        providerService = ProviderService(
            providers = providerRepository,
            secrets = secretStore,
            gateway = gateway,
            registry = registry,
            clock = clock,
            generateId = { "id-${nextId++}" },
        )
        val modelService = ModelService(
            providers = providerRepository,
            models = modelRepository,
            gateway = gateway,
            registry = registry,
            clock = clock,
            generateId = { "model-${nextId++}" },
        )
        dataStoreScope = TestScope(UnconfinedTestDispatcher())
        settingsDataStore = SettingsDataStore(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = {
                    File(temporaryFolder.newFolder(), "settings.preferences_pb")
                },
            ),
        )
        service = DefaultProviderManagementService(
            providers = providerRepository,
            models = modelRepository,
            conversations = conversationRepository,
            providerService = providerService,
            modelService = modelService,
            settings = settingsDataStore,
            registry = registry,
            clock = clock,
        )
        service.generateId = { "manual-${nextId++}" }
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        database.close()
    }

    @Test
    fun `provider options enable M2 OpenAI-compatible presets`() {
        val options = service.providerOptions()

        assertThat(options.first { it.id == "openrouter" }.enabled).isTrue()
        assertThat(options.first { it.id == "openrouter" }.recommended).isTrue()
        assertThat(options.first { it.id == "openai" }.enabled).isTrue()
        assertThat(options.first { it.id == "custom" }.enabled).isTrue()
        assertThat(options.first { it.id == "anthropic" }.enabled).isTrue()
        assertThat(options.first { it.id == "google" }.enabled).isTrue()
        assertThat(options.first { it.id == "ollama" }.enabled).isFalse()
        assertThat(options.first { it.id == "ollama" }.statusLabel)
            .isEqualTo("Coming later")
        assertThat(service.docsUrl("openrouter")).isNotNull()
    }

    @Test
    fun `createPresetProvider persists an enabled preset`() = runTest {
        val provider = service.createPresetProvider(
            CreatePresetProviderInput(
                presetId = "groq",
                apiKey = "gsk-test",
                label = "Groq",
            ),
        )

        assertThat(provider.presetId).isEqualTo("groq")
        assertThat(provider.baseUrl).isEqualTo("https://api.groq.com/openai/v1")
        assertThat(providerRepository.get(provider.id)).isNotNull()
    }

    @Test
    fun `createCustomProvider persists base url auth and headers`() = runTest {
        val provider = service.createCustomProvider(
            CreateCustomProviderInput(
                baseUrl = "https://api.example.com/v1",
                authType = ProviderAuthType.BEARER,
                apiKey = "sk-custom",
                label = "Custom server",
                headers = mapOf("X-Org" to "team-a"),
            ),
        )

        assertThat(provider.presetId).isEqualTo("custom")
        assertThat(provider.baseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(provider.authType).isEqualTo(ProviderAuthType.BEARER)
        assertThat(provider.headers).containsExactly("X-Org", "team-a")
        assertThat(providerRepository.get(provider.id)!!.headers)
            .containsExactly("X-Org", "team-a")
    }

    @Test
    fun `createCustomProvider rejects invalid input`() = runTest {
        val error = runCatching {
            service.createCustomProvider(
                CreateCustomProviderInput(
                    baseUrl = "ftp://example.com",
                    authType = ProviderAuthType.BEARER,
                    apiKey = "sk",
                ),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(dev.chatui.core.providers.ProviderServiceException::class.java)
        assertThat(providerRepository.observeAll().first()).isEmpty()
    }

    @Test
    fun `pin and hide persist through the model repository`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")
        val model = modelRepository.insert(testModel(providerId = provider.id))

        service.setModelPinned(model.id, true)
        service.setModelHidden(model.id, true)

        val stored = modelRepository.get(model.id)!!
        assertThat(stored.pinned).isTrue()
        assertThat(stored.hidden).isTrue()
    }

    @Test
    fun `manual model add persists with manual source`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")

        val model = service.addManualModel(
            provider.id,
            ManualModelInput(remoteId = "author/custom", label = "Custom"),
        )

        assertThat(model.source).isEqualTo(ModelSource.MANUAL)
        assertThat(model.remoteId).isEqualTo("author/custom")
        assertThat(model.label).isEqualTo("Custom")
        assertThat(modelRepository.get(model.id)).isNotNull()

        val duplicate = runCatching {
            service.addManualModel(provider.id, ManualModelInput(remoteId = "author/custom"))
        }.exceptionOrNull()
        assertThat(duplicate).isInstanceOf(ProviderManagementException::class.java)
    }

    @Test
    fun `defaults validate that the model belongs to the provider`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")
        val model = modelRepository.insert(testModel(providerId = provider.id))

        val defaults = service.setDefaults(provider.id, model.id)
        assertThat(defaults.providerId).isEqualTo(provider.id)
        assertThat(defaults.modelId).isEqualTo(model.id)

        val mismatch = runCatching {
            service.setDefaults("other-provider", model.id)
        }.exceptionOrNull()
        assertThat(mismatch).isInstanceOf(ProviderManagementException::class.java)

        val missingProvider = runCatching {
            service.setDefaults(null, model.id)
        }.exceptionOrNull()
        assertThat(missingProvider).isInstanceOf(ProviderManagementException::class.java)
    }

    @Test
    fun `switching conversation model persists immediately`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")
        val first = modelRepository.insert(
            testModel(id = "model-1", providerId = provider.id, remoteId = "author/first"),
        )
        val second = modelRepository.insert(
            testModel(id = "model-2", providerId = provider.id, remoteId = "author/second"),
        )
        val conversation = conversationRepository.create(
            testConversation(
                providerId = provider.id,
                modelId = first.id,
                modelLabel = first.label,
            ),
        )

        val updated = service.setConversationModel(conversation.id, second.id)

        assertThat(updated.modelId).isEqualTo(second.id)
        assertThat(updated.modelLabel).isEqualTo(second.label)
        assertThat(conversationRepository.get(conversation.id)!!.modelId).isEqualTo(second.id)
    }

    @Test
    fun `switching models does not rewrite assistant message snapshots`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")
        val first = modelRepository.insert(
            testModel(id = "model-1", providerId = provider.id, remoteId = "author/first"),
        )
        val second = modelRepository.insert(
            testModel(id = "model-2", providerId = provider.id, remoteId = "author/second"),
        )
        val conversation = conversationRepository.create(
            testConversation(providerId = provider.id, modelId = first.id),
        )
        val message = messageRepository.appendMessage(
            testMessage(
                providerId = provider.id,
                modelId = first.id,
                modelLabel = first.label,
            ).copy(conversationId = conversation.id),
        ).message

        service.setConversationModel(conversation.id, second.id)

        val stored = messageRepository.get(message.id)!!
        assertThat(stored.modelId).isEqualTo(first.id)
        assertThat(stored.modelLabel).isEqualTo(first.label)
    }

    @Test
    fun `routing persists in conversation params`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")
        val model = modelRepository.insert(testModel(providerId = provider.id))
        val conversation = conversationRepository.create(
            testConversation(providerId = provider.id, modelId = model.id),
        )

        service.setConversationRouting(
            conversation.id,
            RoutingPreferences(
                enabled = true,
                allowFallbacks = false,
                sort = RoutingSort.PRICE,
                denyDataCollection = true,
            ),
        )

        val stored = conversationRepository.get(conversation.id)!!
        val providerObject = stored.params.extra["openrouter"]!!
            .jsonObject["provider"]!!
            .jsonObject
        assertThat(providerObject["allow_fallbacks"]!!.jsonPrimitive.content)
            .isEqualTo("false")
        assertThat(providerObject["sort"]!!.jsonPrimitive.content).isEqualTo("price")
        assertThat(providerObject["data_collection"]!!.jsonPrimitive.content).isEqualTo("deny")
        assertThat(service.getConversationRouting(conversation.id).enabled).isTrue()
    }

    @Test
    fun `default model warning reports missing and disabled providers`() = runTest {
        assertThat(service.getDefaultModelWarning()).isEqualTo("No default provider is configured.")

        val provider = providerService.createOpenRouterProvider("sk-test")
        val model = modelRepository.insert(testModel(providerId = provider.id))
        service.setDefaults(provider.id, model.id)
        assertThat(service.getDefaultModelWarning()).isNull()

        service.setProviderEnabled(provider.id, false)
        assertThat(service.getDefaultModelWarning())
            .isEqualTo("The default provider OpenRouter is disabled.")
    }

    @Test
    fun `resolve conversation model never substitutes another model`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")
        val model = modelRepository.insert(testModel(providerId = provider.id))
        val conversation = conversationRepository.create(
            testConversation(providerId = provider.id, modelId = model.id),
        )

        val available = service.resolveConversationModel(conversation)
        assertThat(available).isInstanceOf(ResolvedConversationModel.Available::class.java)

        val missing = service.resolveConversationModel(
            conversation.copy(modelId = "missing-model"),
        )
        assertThat(missing).isInstanceOf(ResolvedConversationModel.Unavailable::class.java)
    }

    @Test
    fun `key rotation and removal update the provider record`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")
        val originalSecret = provider.secretId

        val rotated = service.rotateKey(provider.id, "sk-rotated")
        assertThat(rotated.secretId).isNotEqualTo(originalSecret)

        val removed = service.removeKey(provider.id)
        assertThat(removed.secretId).isNull()
    }

    @Test
    fun `enabled toggle persists`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")

        val disabled = service.setProviderEnabled(provider.id, false)
        assertThat(disabled.enabled).isFalse()
        assertThat(providerRepository.get(provider.id)!!.enabled).isFalse()
    }

    @Test
    fun `provider key metadata is cached without the secret`() = runTest {
        val provider = providerService.createOpenRouterProvider("sk-test")

        val validated = service.validateProviderKey(provider.id)

        assertThat(validated.metadata).isNotNull()
        assertThat(validated.metadata.toString()).doesNotContain("sk-test")
        assertThat(validated.metadata!!["key"]).isInstanceOf(JsonObject::class.java)
    }
}
