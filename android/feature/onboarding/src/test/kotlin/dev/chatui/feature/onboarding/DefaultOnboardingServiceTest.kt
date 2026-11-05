package dev.chatui.feature.onboarding

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretCipher
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.OutputModality
import dev.chatui.core.network.ModelInfo
import dev.chatui.core.network.OpenRouterRequestException
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderKeyInfo
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.providers.ModelService
import dev.chatui.core.providers.ProviderGateway
import dev.chatui.core.providers.ProviderService
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
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
class DefaultOnboardingServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: ChatUiDatabase
    private lateinit var secretStore: SecretStore
    private lateinit var providerRepository: ProviderRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var gateway: FakeProviderGateway
    private lateinit var registry: ProviderRegistry
    private lateinit var providerService: ProviderService
    private lateinit var modelService: ModelService
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var dataStoreScope: TestScope
    private lateinit var service: DefaultOnboardingService
    private var nextId = 0

    private val clock: Clock = Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"),
        ZoneOffset.UTC,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = ChatUiDatabase.inMemory(context)
        secretStore = SecretStore(
            dao = database.secretDao(),
            cipher = FakeSecretCipher(),
            clock = { clock.instant() },
        )
        providerRepository = ProviderRepository(database.providerDao(), clock)
        modelRepository = ModelRepository(database.modelDao())
        gateway = FakeProviderGateway()
        registry = ProviderRegistry.load()
        providerService = ProviderService(
            providers = providerRepository,
            secrets = secretStore,
            gateway = gateway,
            registry = registry,
            clock = clock,
            generateId = { "id-${nextId++}" },
        )
        modelService = ModelService(
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
        service = DefaultOnboardingService(
            providers = providerService,
            models = modelService,
            gateway = gateway,
            registry = registry,
            settings = settingsDataStore,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        database.close()
    }

    @Test
    fun `provider options reflect M2 availability`() {
        val options = service.providerOptions()

        assertThat(options.first { it.id == "openrouter" }.enabled).isTrue()
        assertThat(options.first { it.id == "openrouter" }.recommended).isTrue()
        assertThat(options.first { it.id == "openai" }.enabled).isTrue()
        assertThat(options.first { it.id == "anthropic" }.enabled).isTrue()
        assertThat(options.first { it.id == "google" }.enabled).isTrue()
        assertThat(options.first { it.id == "ollama" }.enabled).isFalse()
    }

    @Test
    fun `loadCatalog uses the public catalog without a key`() = runTest {
        gateway.models = listOf(modelInfo("openrouter/auto"))

        val models = service.loadCatalog()

        assertThat(models.map { it.remoteId }).containsExactly("openrouter/auto")
        assertThat(gateway.lastContext?.apiKey).isNull()
        assertThat(gateway.lastForceRefresh).isFalse()
    }

    @Test
    fun `invalid key stores nothing and restores defaults`() = runTest {
        settingsDataStore.setDefaultProvider("previous-provider", "previous-model")
        gateway.validateError = OpenRouterRequestException(
            code = ChatErrorCode.AUTH,
            message = "Invalid key",
        )

        val error = runCatching { service.connect("sk-bad", "Work") }.exceptionOrNull()

        assertThat(error).isInstanceOf(OpenRouterRequestException::class.java)
        assertThat(providerRepository.observeAll().first()).isEmpty()
        val settings = settingsDataStore.current()
        assertThat(settings.onboardingCompleted).isFalse()
        assertThat(settings.defaultProviderId).isEqualTo("previous-provider")
        assertThat(settings.defaultModelId).isEqualTo("previous-model")
    }

    @Test
    fun `valid key creates the provider loads models selects a default and completes onboarding`() =
        runTest {
            gateway.models = listOf(
                modelInfo("openrouter/auto"),
                modelInfo("openrouter/free", isFree = true),
            )

            val result = service.connect("sk-good", "Work")

            val provider = providerRepository.get(result.providerId)
            assertThat(provider).isNotNull()
            assertThat(provider!!.label).isEqualTo("Work")
            assertThat(provider.secretId).isNotNull()
            assertThat(secretStore.get(provider.secretId!!)).isEqualTo("sk-good")
            assertThat(modelRepository.forProvider(provider.id).map { it.remoteId })
                .containsExactly("openrouter/auto", "openrouter/free")

            val settings = settingsDataStore.current()
            assertThat(settings.defaultProviderId).isEqualTo(provider.id)
            assertThat(settings.defaultModelId).isEqualTo(result.modelId)
            assertThat(settings.onboardingCompleted).isTrue()
            assertThat(result.notice).isNull()
        }

    @Test
    fun `no text model rolls back the provider and onboarding`() = runTest {
        gateway.models = emptyList()

        val error = runCatching { service.connect("sk-good", null) }.exceptionOrNull()

        assertThat(error).isInstanceOf(OnboardingException::class.java)
        assertThat((error as OnboardingException).code)
            .isEqualTo(OnboardingErrorCode.NO_MODEL)
        assertThat(providerRepository.observeAll().first()).isEmpty()
        assertThat(settingsDataStore.current().onboardingCompleted).isFalse()
    }
}

private class FakeProviderGateway : ProviderGateway {
    var keyInfo: ProviderKeyInfo = ProviderKeyInfo(label = "Test key")
    var models: List<ModelInfo> = emptyList()
    var validateError: Throwable? = null
    var listError: Throwable? = null
    var lastForceRefresh: Boolean? = null
    var lastContext: ProviderContext? = null

    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo {
        lastContext = context
        validateError?.let { throw it }
        return keyInfo
    }

    override suspend fun listModels(
        context: ProviderContext,
        forceRefresh: Boolean,
    ): List<ModelInfo> {
        lastContext = context
        lastForceRefresh = forceRefresh
        listError?.let { throw it }
        return models
    }
}

private class FakeSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String =
        Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

    override fun decrypt(ciphertext: String): String =
        String(Base64.getDecoder().decode(ciphertext), Charsets.UTF_8)
}

private fun modelInfo(
    remoteId: String,
    label: String = remoteId,
    isFree: Boolean = false,
): ModelInfo = ModelInfo(
    remoteId = remoteId,
    label = label,
    contextWindow = 128_000,
    maxOutputTokens = 4_096,
    capabilities = listOf(ModelCapability.TEXT),
    inputModalities = listOf(InputModality.TEXT),
    outputModalities = listOf(OutputModality.TEXT),
    isFree = isFree,
)
