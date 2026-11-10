package dev.chatui.core.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
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
class ModelServiceTest {
    private lateinit var database: ChatUiDatabase
    private lateinit var providerRepository: ProviderRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var gateway: FakeProviderGateway
    private lateinit var registry: ProviderRegistry
    private lateinit var providerService: ProviderService
    private lateinit var service: ModelService
    private var nextId = 0

    private val clock: Clock = Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"),
        ZoneOffset.UTC,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = ChatUiDatabase.inMemory(context)
        val secretStore = SecretStore(
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
        service = ModelService(
            providers = providerRepository,
            models = modelRepository,
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
    fun `refreshModels creates discovered models and forces discovery`() = runTest {
        val provider = createProvider()
        gateway.models = listOf(
            modelInfo("openai/gpt-4o-mini", label = "GPT-4o mini"),
            modelInfo("google/gemini-2.5-flash", label = "Gemini 2.5 Flash"),
        )

        val result = service.refreshModels(provider.id)

        assertThat(gateway.lastForceRefresh).isTrue()
        assertThat(result.created).isEqualTo(2)
        assertThat(result.updated).isEqualTo(0)
        assertThat(result.models.map { it.remoteId })
            .containsExactly("openai/gpt-4o-mini", "google/gemini-2.5-flash")

        val stored = modelRepository.forProvider(provider.id)
        assertThat(stored).hasSize(2)
        assertThat(stored.map { it.source }.toSet()).containsExactly(ModelSource.DISCOVERED)
        assertThat(stored.map { it.hidden }.toSet()).containsExactly(false)
        assertThat(stored.map { it.pinned }.toSet()).containsExactly(false)
    }

    @Test
    fun `refreshModels passes the stored key for a key-protected catalog`() = runTest {
        val provider = createPresetProvider("anthropic")
        gateway.models = listOf(modelInfo("claude-sonnet-4-5"))

        service.refreshModels(provider.id)

        assertThat(gateway.lastContext?.apiKey).isEqualTo("sk-test-secret-key")
        assertThat(gateway.lastContext?.provider?.id).isEqualTo(provider.id)
    }

    @Test
    fun `refreshModels keeps public OpenRouter discovery keyless`() = runTest {
        val provider = createProvider()
        gateway.models = listOf(modelInfo("openrouter/auto"))

        service.refreshModels(provider.id)

        assertThat(gateway.lastContext?.apiKey).isNull()
    }

    @Test
    fun `refreshModels degrades when a key-protected catalog has no stored key`() = runTest {
        val provider = createPresetProvider("anthropic")
        providerService.removeKey(provider.id)
        gateway.listError = IllegalStateException("unauthorized")

        val result = service.refreshModels(provider.id)

        assertThat(gateway.lastContext?.apiKey).isNull()
        assertThat(result.degraded).isTrue()
        assertThat(result.refreshError).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `refreshModels preserves user flags and manual model fields`() = runTest {
        val provider = createProvider()
        val manualMetadata = JsonObject(mapOf("custom" to JsonPrimitive("keep")))
        modelRepository.insert(
            model(
                id = "existing-a",
                providerId = provider.id,
                remoteId = "a/model",
                label = "Old A",
                hidden = true,
                pinned = true,
            ),
        )
        modelRepository.insert(
            model(
                id = "existing-b",
                providerId = provider.id,
                remoteId = "b/manual",
                label = "My Manual Model",
                source = ModelSource.MANUAL,
                metadata = manualMetadata,
            ),
        )
        modelRepository.insert(
            model(
                id = "existing-d",
                providerId = provider.id,
                remoteId = "d/not-in-catalog",
                label = "Cached D",
            ),
        )
        gateway.models = listOf(
            modelInfo("a/model", label = "New A"),
            modelInfo("b/manual", label = "Catalog B"),
            modelInfo("c/new", label = "New C"),
        )

        val result = service.refreshModels(provider.id)

        assertThat(result.created).isEqualTo(1)
        assertThat(result.updated).isEqualTo(2)

        val stored = modelRepository.forProvider(provider.id).associateBy { it.remoteId }
        assertThat(stored.keys).containsExactly(
            "a/model",
            "b/manual",
            "c/new",
            "d/not-in-catalog",
        )

        val a = stored.getValue("a/model")
        assertThat(a.id).isEqualTo("existing-a")
        assertThat(a.label).isEqualTo("New A")
        assertThat(a.hidden).isTrue()
        assertThat(a.pinned).isTrue()

        val b = stored.getValue("b/manual")
        assertThat(b.id).isEqualTo("existing-b")
        assertThat(b.label).isEqualTo("My Manual Model")
        assertThat(b.source).isEqualTo(ModelSource.MANUAL)
        assertThat(b.metadata).isEqualTo(manualMetadata)

        assertThat(stored.getValue("c/new").source).isEqualTo(ModelSource.DISCOVERED)
        assertThat(stored.getValue("d/not-in-catalog").label).isEqualTo("Cached D")
    }

    @Test
    fun `selectDefaultModel uses the preset default when present`() = runTest {
        val provider = createProvider()
        val presetDefault = model(
            id = "auto",
            providerId = provider.id,
            remoteId = "openrouter/auto",
            hidden = true,
        )
        modelRepository.insert(presetDefault)
        modelRepository.insert(
            model(
                id = "other",
                providerId = provider.id,
                remoteId = "aaa/other",
            ),
        )

        val selection = service.selectDefaultModel(provider.id)

        assertThat(selection.modelId).isEqualTo("auto")
        assertThat(selection.remoteId).isEqualTo("openrouter/auto")
        assertThat(selection.notice).isNull()
    }

    @Test
    fun `selectDefaultModel falls back to the first non-hidden text model`() = runTest {
        val provider = createProvider()
        modelRepository.insert(
            model(
                id = "hidden",
                providerId = provider.id,
                remoteId = "aaa/hidden-text",
                hidden = true,
            ),
        )
        modelRepository.insert(
            model(
                id = "gemini",
                providerId = provider.id,
                remoteId = "google/gemini-2.5-flash",
            ),
        )
        modelRepository.insert(
            model(
                id = "llama",
                providerId = provider.id,
                remoteId = "meta-llama/llama-3.2-3b-instruct:free",
            ),
        )
        modelRepository.insert(
            model(
                id = "gpt",
                providerId = provider.id,
                remoteId = "openai/gpt-4o-mini-2024-07-18",
            ),
        )

        val selection = service.selectDefaultModel(provider.id)

        assertThat(selection.modelId).isEqualTo("gemini")
        assertThat(selection.remoteId).isEqualTo("google/gemini-2.5-flash")
        assertThat(selection.notice).isEqualTo(
            "Default model openrouter/auto is unavailable; using google/gemini-2.5-flash.",
        )
    }

    @Test
    fun `selectDefaultModel returns null when no text model is available`() = runTest {
        val provider = createProvider()
        modelRepository.insert(
            model(
                id = "hidden",
                providerId = provider.id,
                remoteId = "aaa/hidden-text",
                hidden = true,
            ),
        )
        modelRepository.insert(
            model(
                id = "image",
                providerId = provider.id,
                remoteId = "example/image-only",
                outputModalities = listOf(OutputModality.IMAGE),
            ),
        )

        val selection = service.selectDefaultModel(provider.id)

        assertThat(selection.modelId).isNull()
        assertThat(selection.remoteId).isNull()
        assertThat(selection.notice).isEqualTo(
            "Default model openrouter/auto is unavailable; no text model is available.",
        )
    }

    @Test
    fun `refreshModels propagates discovery failures`() = runTest {
        val provider = createProvider()
        gateway.listError = IllegalStateException("catalog unavailable")

        val error = runCatching { service.refreshModels(provider.id) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().isEqualTo("catalog unavailable")
    }

    @Test
    fun `refreshModels returns cached models with a degraded notice when discovery fails`() = runTest {
        val provider = createProvider()
        modelRepository.insert(
            model(
                id = "cached",
                providerId = provider.id,
                remoteId = "openrouter/auto",
            ),
        )
        gateway.listError = IllegalStateException("catalog unavailable")

        val result = service.refreshModels(provider.id)

        assertThat(result.degraded).isTrue()
        assertThat(result.models.map { it.remoteId }).containsExactly("openrouter/auto")
        assertThat(result.notice).isEqualTo("Discovery unavailable; using 1 cached model.")
        assertThat(result.refreshError).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `refreshModels persists fallback models when discovery fails and no cache exists`() = runTest {
        val provider = createPresetProvider("openai")
        gateway.listError = IllegalStateException("catalog unavailable")

        val result = service.refreshModels(provider.id)

        assertThat(result.degraded).isTrue()
        assertThat(result.models.map { it.remoteId })
            .containsExactly("gpt-4o-mini", "gpt-4o")
            .inOrder()
        assertThat(result.notice).isEqualTo("Discovery unavailable; using 2 fallback models.")
        assertThat(result.refreshError).isInstanceOf(IllegalStateException::class.java)

        val stored = modelRepository.forProvider(provider.id)
        assertThat(stored).hasSize(2)
        assertThat(stored.map { it.metadata?.get("fallback") })
            .containsExactly(JsonPrimitive(true), JsonPrimitive(true))
    }

    @Test
    fun `selectDefaultModel creates fallback rows when the catalog is empty`() = runTest {
        val provider = createPresetProvider("openai")

        val selection = service.selectDefaultModel(provider.id)

        assertThat(selection.remoteId).isEqualTo("gpt-4o-mini")
        assertThat(selection.notice)
            .isEqualTo("Discovery unavailable; using fallback model gpt-4o-mini.")
        assertThat(modelRepository.forProvider(provider.id)).hasSize(2)
    }

    @Test
    fun `selectDefaultModel uses a fallback id present in the catalog`() = runTest {
        val provider = createPresetProvider("openai")
        modelRepository.insert(
            model(
                id = "gpt-4o",
                providerId = provider.id,
                remoteId = "gpt-4o",
            ),
        )

        val selection = service.selectDefaultModel(provider.id)

        assertThat(selection.remoteId).isEqualTo("gpt-4o")
        assertThat(selection.notice)
            .isEqualTo("Default model gpt-4o-mini is unavailable; using gpt-4o.")
    }

    @Test
    fun `selectDefaultModel reports no fallback for openrouter with an empty catalog`() = runTest {
        val provider = createProvider()

        val selection = service.selectDefaultModel(provider.id)

        assertThat(selection.modelId).isNull()
        assertThat(selection.remoteId).isNull()
        assertThat(selection.notice)
            .isEqualTo("Discovery unavailable; no fallback model is available.")
    }

    @Test
    fun `refreshModels reports an unknown provider`() = runTest {
        val error = runCatching { service.refreshModels("missing") }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderServiceException::class.java)
        assertThat((error as ProviderServiceException).code)
            .isEqualTo(ProviderServiceErrorCode.NOT_FOUND)
    }

    private suspend fun createProvider() =
        providerService.createOpenRouterProvider("sk-test-secret-key")

    private suspend fun createPresetProvider(presetId: String) =
        providerService.createPresetProvider(
            CreatePresetProviderInput(
                presetId = presetId,
                apiKey = "sk-test-secret-key",
            ),
        )
}
