package dev.chatui.core.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.network.ProviderRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
}
