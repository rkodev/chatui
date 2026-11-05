package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class DiscoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val at: Instant = Instant.parse("2026-11-02T12:34:56Z")
    private val registry: ProviderRegistry = ProviderRegistry.load()

    private val openAIModels: String = readResource("/contracts/fixtures/models/openai-models.json")
    private val openRouterModels: String =
        readResource("/contracts/fixtures/models/openrouter-models.json")
    private val anthropicModels: String =
        readResource("/contracts/fixtures/models/anthropic-models.json")
    private val geminiModels: String =
        readResource("/contracts/fixtures/models/gemini-models.json")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `discovers models for every enabled preset`() = runTest {
        for (presetId in M2_ENABLED_PRESET_IDS) {
            val preset = registry.getPreset(presetId)
                ?: error("Missing preset $presetId")
            val payload = when (preset.modelDiscovery.format) {
                ModelDiscoveryFormat.OPENROUTER -> openRouterModels
                ModelDiscoveryFormat.ANTHROPIC -> anthropicModels
                ModelDiscoveryFormat.GEMINI -> geminiModels
                else -> openAIModels
            }
            server.enqueue(jsonResponse(payload))

            val result = catalogAdapter(preset).loadCatalog(context(preset))

            assertThat(result.models).isNotEmpty()
            assertThat(result.fromCache).isFalse()
            assertThat(result.stale).isFalse()

            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/v1/models")

            val sendsKey = !preset.modelDiscovery.public &&
                preset.auth.type != ProviderAuthType.NONE
            if (sendsKey) {
                when (preset.auth.type) {
                    ProviderAuthType.X_API_KEY -> {
                        val header = preset.auth.headerName ?: "x-api-key"
                        assertThat(request.headers[header]).isEqualTo("sk-test-key")
                    }
                    else ->
                        assertThat(request.headers["Authorization"]).isEqualTo("Bearer sk-test-key")
                }
            } else {
                assertThat(request.headers["Authorization"]).isNull()
            }
        }
    }

    @Test
    fun `uses independent cache keys per provider`() {
        assertThat(catalogCacheKey("provider-a")).isNotEqualTo(catalogCacheKey("provider-b"))
        assertThat(catalogCacheKey("provider-a")).contains("provider-a")
    }

    @Test
    fun `serves a fresh cache entry and refreshes after the TTL`() = runTest {
        val preset = registry.getPreset("openai")!!
        val cache = MemoryCatalogCache()
        var now = 1000L
        server.enqueue(jsonResponse(openAIModels))
        server.enqueue(jsonResponse(openAIModels))

        val first = adapter(preset, cache, now = { now }).loadCatalog(context(preset))
        val cached = adapter(preset, cache, now = { now }).loadCatalog(context(preset))

        assertThat(first.fromCache).isFalse()
        assertThat(cached.fromCache).isTrue()
        assertThat(server.requestCount).isEqualTo(1)

        now += preset.modelDiscovery.cacheTtlSeconds * 1_000L
        val expired = adapter(preset, cache, now = { now }).loadCatalog(context(preset))

        assertThat(expired.fromCache).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `keeps the last good snapshot when a forced refresh fails`() = runTest {
        val preset = registry.getPreset("openai")!!
        val cache = MemoryCatalogCache()
        server.enqueue(jsonResponse(openAIModels))

        val first = adapter(preset, cache).loadCatalog(context(preset))
        server.enqueue(jsonResponse("""{"error":{"message":"blocked"}}""", status = 500))
        val stale = adapter(preset, cache).refreshCatalog(context(preset))

        assertThat(first.models).isNotEmpty()
        assertThat(stale.stale).isTrue()
        assertThat(stale.fromCache).isTrue()
        assertThat(stale.refreshError).isNotNull()
        assertThat(stale.models).isEqualTo(first.models)
    }

    @Test
    fun `throws when discovery fails and no snapshot exists`() = runTest {
        val preset = registry.getPreset("openai")!!
        server.enqueue(jsonResponse("""{"error":{"message":"blocked"}}""", status = 500))

        val error = runCatching {
            adapter(preset, MemoryCatalogCache()).loadCatalog(context(preset))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderRequestException::class.java)
    }

    private fun catalogAdapter(
        preset: ProviderPreset,
        cache: CatalogCache = MemoryCatalogCache(),
        now: () -> Long = { 1000L },
    ): CatalogAdapter = when (preset.kind) {
        ProviderKind.ANTHROPIC -> AnthropicAdapter(
            client = client,
            preset = preset,
            cache = cache,
            now = now,
        )
        ProviderKind.GOOGLE_GEMINI -> GeminiAdapter(
            client = client,
            preset = preset,
            cache = cache,
            now = now,
        )
        else -> adapter(preset, cache, now)
    }

    private fun adapter(
        preset: ProviderPreset,
        cache: CatalogCache = MemoryCatalogCache(),
        now: () -> Long = { 1000L },
    ): OpenAICompatibleAdapter {
        val transportPreset = if (preset.id == "openrouter") {
            openRouterTransportPreset(preset)
        } else {
            OpenAICompatiblePreset.fromPreset(preset)
        }

        return OpenAICompatibleAdapter(
            preset = transportPreset,
            client = client,
            cache = cache,
            now = now,
        )
    }

    private fun context(preset: ProviderPreset): ProviderContext = ProviderContext(
        provider = Provider(
            id = preset.id,
            presetId = preset.id,
            label = preset.label,
            kind = preset.kind,
            baseUrl = server.url("/v1").toString(),
            authType = preset.auth.type,
            secretId = null,
            headers = emptyMap(),
            enabled = true,
            browserCors = preset.browserCors,
            metadata = null,
            createdAt = at,
            updatedAt = at,
        ),
        apiKey = if (preset.auth.type == ProviderAuthType.NONE) null else "sk-test-key",
    )

    private fun jsonResponse(body: String, status: Int = 200): MockResponse =
        MockResponse.Builder()
            .code(status)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun readResource(path: String): String {
        val normalized = path.removePrefix("/")
        val stream = javaClass.classLoader.getResourceAsStream(normalized)
            ?: error("Missing test resource $normalized")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
