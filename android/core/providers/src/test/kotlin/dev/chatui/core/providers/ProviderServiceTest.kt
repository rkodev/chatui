package dev.chatui.core.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.dao.ProviderDao
import dev.chatui.core.database.entity.ProviderEntity
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretCipher
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.network.ProviderRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderServiceTest {
    private lateinit var database: ChatUiDatabase
    private lateinit var secretStore: SecretStore
    private lateinit var providerRepository: ProviderRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var gateway: FakeProviderGateway
    private lateinit var registry: ProviderRegistry
    private lateinit var service: ProviderService
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
        service = ProviderService(
            providers = providerRepository,
            secrets = secretStore,
            gateway = gateway,
            registry = registry,
            clock = clock,
            generateId = { "id-${nextId++}" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `createOpenRouterProvider stores the key only in the secret store`() = runTest {
        val apiKey = "sk-test-secret-key"

        val provider = service.createOpenRouterProvider(apiKey, label = "Work")

        assertThat(provider.presetId).isEqualTo("openrouter")
        assertThat(provider.label).isEqualTo("Work")
        assertThat(provider.enabled).isTrue()
        assertThat(provider.metadata).isNull()
        assertThat(provider.secretId).isNotNull()
        assertThat(secretStore.get(provider.secretId!!)).isEqualTo(apiKey)

        val entity = database.providerDao().getById(provider.id)!!
        assertThat(entity.secretId).isEqualTo(provider.secretId)
        assertThat(entity.metadataJson).isNull()
        assertThat(entity.headersJson).doesNotContain(apiKey)

        val secret = database.secretDao().getById(provider.secretId!!)!!
        assertThat(secret.ciphertext).isNotEqualTo(apiKey)
        assertThat(secret.ciphertext).doesNotContain(apiKey)
    }

    @Test
    fun `createOpenRouterProvider rejects a blank key`() = runTest {
        val error = runCatching { service.createOpenRouterProvider("   ") }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderServiceException::class.java)
        assertThat((error as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.INVALID_INPUT)
    }

    @Test
    fun `validateProviderKey persists non-secret key metadata`() = runTest {
        val apiKey = "sk-test-secret-key"
        val provider = service.createOpenRouterProvider(apiKey)

        val updated = service.validateProviderKey(provider.id)

        val metadata = getProviderKeyMetadata(updated)
        assertThat(metadata).isNotNull()
        assertThat(metadata!!.label).isEqualTo("Test key")
        assertThat(metadata.usageUsd).isEqualTo(1.5)
        assertThat(metadata.limitUsd).isEqualTo(10.0)
        assertThat(metadata.limitRemainingUsd).isEqualTo(8.5)
        assertThat(metadata.isFreeTier).isFalse()
        assertThat(metadata.validatedAt).isEqualTo(clock.instant())
        assertThat(updated.metadata.toString()).doesNotContain(apiKey)
        assertThat(secretStore.get(provider.secretId!!)).isEqualTo(apiKey)
    }

    @Test
    fun `validateProviderKey preserves unrelated provider metadata`() = runTest {
        val provider = service.createOpenRouterProvider("sk-test-secret-key")
        providerRepository.upsert(
            provider.copy(
                metadata = JsonObject(mapOf("custom" to JsonPrimitive("value"))),
            ),
        )

        val updated = service.validateProviderKey(provider.id)

        assertThat(updated.metadata?.get("custom")).isEqualTo(JsonPrimitive("value"))
        assertThat(getProviderKeyMetadata(updated)).isNotNull()
    }

    @Test
    fun `validateProviderKey reports a missing key`() = runTest {
        val provider = service.createOpenRouterProvider("sk-test-secret-key")
        service.removeKey(provider.id)

        val error = runCatching { service.validateProviderKey(provider.id) }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderServiceException::class.java)
        assertThat((error as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.MISSING_KEY)
    }

    @Test
    fun `rotateKey replaces the secret and clears key metadata`() = runTest {
        val provider = service.createOpenRouterProvider("sk-old-key")
        service.validateProviderKey(provider.id)
        val oldSecretId = provider.secretId!!

        val rotated = service.rotateKey(provider.id, "sk-new-key")

        assertThat(rotated.secretId).isNotEqualTo(oldSecretId)
        assertThat(secretStore.get(rotated.secretId!!)).isEqualTo("sk-new-key")
        assertThat(secretStore.get(oldSecretId)).isNull()
        assertThat(getProviderKeyMetadata(rotated)).isNull()
    }

    @Test
    fun `removeKey deletes the secret and clears key metadata`() = runTest {
        val provider = service.createOpenRouterProvider("sk-test-secret-key")
        service.validateProviderKey(provider.id)
        val secretId = provider.secretId!!

        val removed = service.removeKey(provider.id)

        assertThat(removed.secretId).isNull()
        assertThat(secretStore.get(secretId)).isNull()
        assertThat(getProviderKeyMetadata(removed)).isNull()
    }

    @Test
    fun `setProviderEnabled toggles the enabled flag`() = runTest {
        val provider = service.createOpenRouterProvider("sk-test-secret-key")

        val disabled = service.setProviderEnabled(provider.id, false)
        assertThat(disabled.enabled).isFalse()

        val enabled = service.setProviderEnabled(provider.id, true)
        assertThat(enabled.enabled).isTrue()
    }

    @Test
    fun `deleteProvider removes the provider, models, and secret`() = runTest {
        val provider = service.createOpenRouterProvider("sk-test-secret-key")
        val secretId = provider.secretId!!
        modelRepository.insert(
            model(
                id = "model-1",
                providerId = provider.id,
                remoteId = "openrouter/auto",
            ),
        )

        service.deleteProvider(provider.id)

        assertThat(providerRepository.get(provider.id)).isNull()
        assertThat(modelRepository.forProvider(provider.id)).isEmpty()
        assertThat(secretStore.get(secretId)).isNull()
    }

    @Test
    fun `unknown provider operations report not found`() = runTest {
        val error = runCatching { service.validateProviderKey("missing") }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderServiceException::class.java)
        assertThat((error as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.NOT_FOUND)
    }

    @Test
    fun `createPresetProvider uses contract metadata and stores the key separately`() = runTest {
        val provider = service.createPresetProvider(
            CreatePresetProviderInput(
                presetId = "openai",
                apiKey = "sk-openai-secret",
                label = "OpenAI work",
            ),
        )

        assertThat(provider.presetId).isEqualTo("openai")
        assertThat(provider.label).isEqualTo("OpenAI work")
        assertThat(provider.baseUrl).isEqualTo("https://api.openai.com/v1")
        assertThat(provider.authType).isEqualTo(ProviderAuthType.BEARER)
        assertThat(provider.headers).isEmpty()
        assertThat(provider.secretId).isNotNull()
        assertThat(secretStore.get(provider.secretId!!)).isEqualTo("sk-openai-secret")

        val entity = database.providerDao().getById(provider.id)!!
        assertThat(entity.headersJson).doesNotContain("sk-openai-secret")
    }

    @Test
    fun `createPresetProvider supports a no-key local preset`() = runTest {
        val provider = service.createPresetProvider(
            CreatePresetProviderInput(presetId = "lmstudio"),
        )

        assertThat(provider.presetId).isEqualTo("lmstudio")
        assertThat(provider.authType).isEqualTo(ProviderAuthType.NONE)
        assertThat(provider.secretId).isNull()
        assertThat(database.secretDao().getAll()).isEmpty()
    }

    @Test
    fun `createPresetProvider accepts Anthropic and rejects unknown presets`() = runTest {
        val anthropic = service.createPresetProvider(
            CreatePresetProviderInput(presetId = "anthropic", apiKey = "sk-ant"),
        )
        assertThat(anthropic.presetId).isEqualTo("anthropic")
        assertThat(anthropic.kind).isEqualTo(ProviderKind.ANTHROPIC)
        assertThat(anthropic.authType).isEqualTo(ProviderAuthType.X_API_KEY)

        val unknown = runCatching {
            service.createPresetProvider(
                CreatePresetProviderInput(presetId = "missing", apiKey = "sk"),
            )
        }.exceptionOrNull()
        assertThat(unknown).isInstanceOf(ProviderServiceException::class.java)
        assertThat((unknown as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.INVALID_INPUT)
    }

    @Test
    fun `createCustomProvider stores base url auth and non-secret headers`() = runTest {
        val provider = service.createCustomProvider(
            CreateCustomProviderInput(
                baseUrl = "https://api.example.com/v1",
                authType = ProviderAuthType.X_API_KEY,
                apiKey = "custom-secret",
                label = "My server",
                headers = mapOf("X-Org" to "team-a"),
            ),
        )

        assertThat(provider.presetId).isEqualTo("custom")
        assertThat(provider.label).isEqualTo("My server")
        assertThat(provider.baseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(provider.authType).isEqualTo(ProviderAuthType.X_API_KEY)
        assertThat(provider.headers).containsExactly("X-Org", "team-a")
        assertThat(provider.secretId).isNotNull()
        assertThat(secretStore.get(provider.secretId!!)).isEqualTo("custom-secret")

        val entity = database.providerDao().getById(provider.id)!!
        assertThat(entity.headersJson).contains("X-Org")
        assertThat(entity.headersJson).doesNotContain("custom-secret")
    }

    @Test
    fun `createCustomProvider rejects invalid base urls`() = runTest {
        val cases = listOf(
            "",
            "not a url",
            "ftp://example.com/v1",
            "https://user:pass@example.com/v1",
            "https://example.com/v1?key=value",
            "https://example.com/v1#fragment",
        )

        for (baseUrl in cases) {
            val error = runCatching {
                service.createCustomProvider(
                    CreateCustomProviderInput(
                        baseUrl = baseUrl,
                        authType = ProviderAuthType.BEARER,
                        apiKey = "sk",
                    ),
                )
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(ProviderServiceException::class.java)
            assertThat((error as ProviderServiceException).code)
                .isEqualTo(ProviderServiceErrorCode.INVALID_INPUT)
        }

        assertThat(providerRepository.observeAll().first()).isEmpty()
    }

    @Test
    fun `createCustomProvider refuses public cleartext with actionable copy`() = runTest {
        val error = runCatching {
            service.createCustomProvider(
                CreateCustomProviderInput(
                    baseUrl = "http://api.example.com/v1",
                    authType = ProviderAuthType.BEARER,
                    apiKey = "sk",
                ),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderServiceException::class.java)
        assertThat((error as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.INVALID_INPUT)
        assertThat(error.message).isEqualTo(CLEARTEXT_REFUSED_ERROR)
        assertThat(providerRepository.observeAll().first()).isEmpty()
    }

    @Test
    fun `createCustomProvider accepts approved local cleartext`() = runTest {
        val provider = service.createCustomProvider(
            CreateCustomProviderInput(
                baseUrl = "http://10.0.2.2:11434/v1",
                authType = ProviderAuthType.NONE,
            ),
        )

        assertThat(provider.baseUrl).isEqualTo("http://10.0.2.2:11434/v1")
    }

    @Test
    fun `createCustomProvider rejects query-key auth and missing keys`() = runTest {
        val queryKey = runCatching {
            service.createCustomProvider(
                CreateCustomProviderInput(
                    baseUrl = "https://api.example.com/v1",
                    authType = ProviderAuthType.QUERY_KEY,
                    apiKey = "sk",
                ),
            )
        }.exceptionOrNull()
        assertThat((queryKey as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.INVALID_INPUT)

        val missingKey = runCatching {
            service.createCustomProvider(
                CreateCustomProviderInput(
                    baseUrl = "https://api.example.com/v1",
                    authType = ProviderAuthType.BEARER,
                ),
            )
        }.exceptionOrNull()
        assertThat((missingKey as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.INVALID_INPUT)

        val unexpectedKey = runCatching {
            service.createCustomProvider(
                CreateCustomProviderInput(
                    baseUrl = "https://api.example.com/v1",
                    authType = ProviderAuthType.NONE,
                    apiKey = "sk",
                ),
            )
        }.exceptionOrNull()
        assertThat((unexpectedKey as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.INVALID_INPUT)
    }

    @Test
    fun `createCustomProvider rejects authentication and malformed headers`() = runTest {
        val cases = listOf(
            mapOf("Authorization" to "Bearer sk"),
            mapOf("x-api-key" to "sk"),
            mapOf("Cookie" to "session=1"),
            mapOf("Bad Header" to "value"),
            mapOf("X-Org" to ""),
            mapOf("X-Org" to "line\nbreak"),
        )

        for (headers in cases) {
            val error = runCatching {
                service.createCustomProvider(
                    CreateCustomProviderInput(
                        baseUrl = "https://api.example.com/v1",
                        authType = ProviderAuthType.BEARER,
                        apiKey = "sk",
                        headers = headers,
                    ),
                )
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(ProviderServiceException::class.java)
            assertThat((error as ProviderServiceException).code)
                .isEqualTo(ProviderServiceErrorCode.INVALID_INPUT)
        }

        assertThat(providerRepository.observeAll().first()).isEmpty()
    }

    @Test
    fun `createCustomProvider rejects duplicate labels`() = runTest {
        service.createCustomProvider(
            CreateCustomProviderInput(
                baseUrl = "https://api.example.com/v1",
                authType = ProviderAuthType.BEARER,
                apiKey = "sk",
                label = "My server",
            ),
        )

        val error = runCatching {
            service.createCustomProvider(
                CreateCustomProviderInput(
                    baseUrl = "https://api.example.com/v2",
                    authType = ProviderAuthType.BEARER,
                    apiKey = "sk",
                    label = "my server",
                ),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderServiceException::class.java)
        assertThat((error as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.INVALID_INPUT)
    }

    @Test
    fun `failed provider write removes the newly written secret`() = runTest {
        val failingService = ProviderService(
            providers = ProviderRepository(FailingProviderDao(), clock),
            secrets = secretStore,
            gateway = gateway,
            registry = registry,
            clock = clock,
            generateId = { "id-${nextId++}" },
        )

        val error = runCatching {
            failingService.createCustomProvider(
                CreateCustomProviderInput(
                    baseUrl = "https://api.example.com/v1",
                    authType = ProviderAuthType.BEARER,
                    apiKey = "sk-orphan",
                ),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderServiceException::class.java)
        assertThat((error as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.STORAGE)
        assertThat(database.secretDao().getAll()).isEmpty()
    }

    @Test
    fun `failed secret write leaves no provider record`() = runTest {
        val failingSecrets = SecretStore(
            dao = database.secretDao(),
            cipher = FailingSecretCipher(),
            clock = { clock.instant() },
        )
        val failingService = ProviderService(
            providers = providerRepository,
            secrets = failingSecrets,
            gateway = gateway,
            registry = registry,
            clock = clock,
            generateId = { "id-${nextId++}" },
        )

        val error = runCatching {
            failingService.createCustomProvider(
                CreateCustomProviderInput(
                    baseUrl = "https://api.example.com/v1",
                    authType = ProviderAuthType.BEARER,
                    apiKey = "sk",
                ),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderServiceException::class.java)
        assertThat((error as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.STORAGE)
        assertThat(providerRepository.observeAll().first()).isEmpty()
    }
}

private class FailingProviderDao : ProviderDao() {
    override suspend fun upsert(provider: ProviderEntity) {
        throw IllegalStateException("provider write failed")
    }

    override suspend fun update(provider: ProviderEntity) {
        throw IllegalStateException("provider write failed")
    }

    override fun observeAll(): Flow<List<ProviderEntity>> = flowOf(emptyList())

    override suspend fun getById(id: String): ProviderEntity? = null

    override suspend fun deleteById(id: String) = Unit

    override suspend fun deleteModelsForProvider(providerId: String) = Unit

    override suspend fun deleteSecretsForProvider(providerId: String) = Unit
}

private class FailingSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String =
        throw IllegalStateException("secret write failed")

    override fun decrypt(ciphertext: String): String =
        throw IllegalStateException("secret read failed")
}
