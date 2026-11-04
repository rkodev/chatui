package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenRouterAdapterTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val at: Instant = Instant.parse("2026-11-02T12:34:56Z")
    private val attribution = OpenRouterAttribution(
        origin = "https://chat.example.com",
        title = "ChatUI",
    )

    private val modelsFixtureText: String = readResource(
        "/contracts/fixtures/models/openrouter-models.json",
    )
    private val keyFixtureText: String = readResource(
        "/contracts/fixtures/models/openrouter-key.json",
    )
    private val modelsFixture: JsonElement = Json.parseToJsonElement(modelsFixtureText)

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
    fun `validateKey sends GET key with bearer auth and attribution headers`() = runTest {
        server.enqueue(jsonResponse(keyFixtureText))

        val info = adapter().validateKey(context())

        assertThat(info.label).isEqualTo("Example OpenRouter Key")
        assertThat(info.usageUsd).isEqualTo(12.34)
        assertThat(info.limitUsd).isEqualTo(100.0)
        assertThat(info.limitRemainingUsd).isEqualTo(87.66)
        assertThat(info.isFreeTier).isFalse()
        assertThat(info.rateLimit).isNotNull()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/key")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer sk-test-key")
        assertThat(request.headers["HTTP-Referer"]).isEqualTo("https://chat.example.com")
        assertThat(request.headers["X-Title"]).isEqualTo("ChatUI")
        assertThat(request.url.toString()).doesNotContain("sk-test-key")
    }

    @Test
    fun `validateKey rejects a missing key before making a request`() = runTest {
        val error = runCatching {
            adapter().validateKey(context(apiKey = null))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(OpenRouterRequestException::class.java)
        assertThat((error as OpenRouterRequestException).code).isEqualTo(ChatErrorCode.AUTH)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `validateKey rejects a blank key before making a request`() = runTest {
        val error = runCatching {
            adapter().validateKey(context(apiKey = "   "))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(OpenRouterRequestException::class.java)
        assertThat((error as OpenRouterRequestException).code).isEqualTo(ChatErrorCode.AUTH)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `maps an HTTP 401 and redacts the key from the message`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":{"message":"Invalid key sk-test-key"}}""")
                .build(),
        )

        val error = runCatching {
            adapter().validateKey(context())
        }.exceptionOrNull() as OpenRouterRequestException

        assertThat(error.code).isEqualTo(ChatErrorCode.AUTH)
        assertThat(error.status).isEqualTo(401)
        assertThat(error.retryable).isFalse()
        assertThat(error.message).isEqualTo("Invalid key [redacted]")
    }

    @Test
    fun `maps quota rate limit and provider statuses`() = runTest {
        val cases = listOf(
            402 to ChatErrorCode.QUOTA,
            429 to ChatErrorCode.RATE_LIMIT,
            503 to ChatErrorCode.PROVIDER,
        )

        for ((status, code) in cases) {
            server.enqueue(
                MockResponse.Builder()
                    .code(status)
                    .body("""{"error":{"message":"failure"}}""")
                    .build(),
            )

            val error = runCatching {
                adapter().validateKey(context())
            }.exceptionOrNull() as OpenRouterRequestException

            assertThat(error.code).isEqualTo(code)
            assertThat(error.status).isEqualTo(status)
        }
    }

    @Test
    fun `honors Retry-After on a rate limit`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "2")
                .body("""{"error":{"message":"slow down"}}""")
                .build(),
        )

        val error = runCatching {
            adapter().validateKey(context())
        }.exceptionOrNull() as OpenRouterRequestException

        assertThat(error.retryable).isTrue()
        assertThat(error.retryAfterSeconds).isEqualTo(2.0)
    }

    @Test
    fun `maps a network failure`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .onRequestStart(SocketEffect.CloseSocket())
                .build(),
        )

        val error = runCatching {
            adapter().validateKey(context())
        }.exceptionOrNull() as OpenRouterRequestException

        assertThat(error.code).isEqualTo(ChatErrorCode.NETWORK)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `maps invalid JSON and malformed key payloads to parse errors`() = runTest {
        server.enqueue(MockResponse.Builder().body("not json").build())

        val invalidJson = runCatching {
            adapter().validateKey(context())
        }.exceptionOrNull() as OpenRouterRequestException
        assertThat(invalidJson.code).isEqualTo(ChatErrorCode.PARSE)

        server.enqueue(MockResponse.Builder().body("{}").build())

        val malformed = runCatching {
            adapter().validateKey(context())
        }.exceptionOrNull() as OpenRouterRequestException
        assertThat(malformed.code).isEqualTo(ChatErrorCode.PARSE)
    }

    @Test
    fun `listModels loads the public catalog without sending the key`() = runTest {
        server.enqueue(jsonResponse(modelsFixtureText))

        val models = adapter().listModels(context())

        assertThat(models).hasSize(8)

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/models")
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(request.headers["HTTP-Referer"]).isEqualTo("https://chat.example.com")
        assertThat(request.headers["X-Title"]).isEqualTo("ChatUI")
        assertThat(request.url.toString()).doesNotContain("sk-test-key")
    }

    @Test
    fun `listModels works without a key because the catalog is public`() = runTest {
        server.enqueue(jsonResponse(modelsFixtureText))

        val models = adapter().listModels(context(apiKey = null))

        assertThat(models).hasSize(8)
        assertThat(server.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `returns a fresh cache entry without fetching`() = runTest {
        val cache = MemoryCatalogCache()
        val cachedModel = mapOpenRouterModels(modelsFixture).first()
        cache.write(
            openRouterCatalogCacheKey(),
            CatalogSnapshot(fetchedAt = 1000, models = listOf(cachedModel)),
        )

        val result = adapter(
            cache = cache,
            now = { 1000 + OPENROUTER_CATALOG_TTL_MS - 1 },
        ).loadCatalog(context())

        assertThat(result.fromCache).isTrue()
        assertThat(result.stale).isFalse()
        assertThat(result.refreshError).isNull()
        assertThat(result.models).containsExactly(cachedModel)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `refreshes an expired cache entry and writes the new snapshot`() = runTest {
        val cache = MemoryCatalogCache()
        val cachedModel = mapOpenRouterModels(modelsFixture).first()
        cache.write(
            openRouterCatalogCacheKey(),
            CatalogSnapshot(fetchedAt = 1000, models = listOf(cachedModel)),
        )
        server.enqueue(jsonResponse(modelsFixtureText))

        val now = 1000 + OPENROUTER_CATALOG_TTL_MS
        val result = adapter(cache = cache, now = { now }).loadCatalog(context())

        assertThat(result.fromCache).isFalse()
        assertThat(result.stale).isFalse()
        assertThat(result.models).hasSize(8)
        assertThat(server.requestCount).isEqualTo(1)

        val snapshot = cache.read(openRouterCatalogCacheKey())
        assertThat(snapshot?.fetchedAt).isEqualTo(now)
        assertThat(snapshot?.models).hasSize(8)
    }

    @Test
    fun `forces a manual refresh even when the cache is fresh`() = runTest {
        val cache = MemoryCatalogCache()
        val cachedModel = mapOpenRouterModels(modelsFixture).first()
        cache.write(
            openRouterCatalogCacheKey(),
            CatalogSnapshot(fetchedAt = 1000, models = listOf(cachedModel)),
        )
        server.enqueue(jsonResponse(modelsFixtureText))

        val result = adapter(cache = cache, now = { 1000 }).refreshCatalog(context())

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(result.fromCache).isFalse()
        assertThat(result.models).hasSize(8)
    }

    @Test
    fun `returns the last-known-good catalog when refresh fails`() = runTest {
        val cache = MemoryCatalogCache()
        val cachedModel = mapOpenRouterModels(modelsFixture).first()
        cache.write(
            openRouterCatalogCacheKey(),
            CatalogSnapshot(fetchedAt = 1000, models = listOf(cachedModel)),
        )
        server.enqueue(
            MockResponse.Builder()
                .onRequestStart(SocketEffect.CloseSocket())
                .build(),
        )

        val result = adapter(
            cache = cache,
            now = { 1000 + OPENROUTER_CATALOG_TTL_MS },
        ).loadCatalog(context())

        assertThat(result.models).containsExactly(cachedModel)
        assertThat(result.fromCache).isTrue()
        assertThat(result.stale).isTrue()
        assertThat(result.refreshError).isInstanceOf(OpenRouterRequestException::class.java)
        assertThat((result.refreshError as OpenRouterRequestException).code)
            .isEqualTo(ChatErrorCode.NETWORK)
    }

    @Test
    fun `throws when refresh fails and no snapshot exists`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .onRequestStart(SocketEffect.CloseSocket())
                .build(),
        )

        val error = runCatching {
            adapter(cache = MemoryCatalogCache(), now = { 1000 }).loadCatalog(context())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(OpenRouterRequestException::class.java)
        assertThat((error as OpenRouterRequestException).code).isEqualTo(ChatErrorCode.NETWORK)
    }

    @Test
    fun `keeps a successful refresh when the cache write fails`() = runTest {
        val cache = object : CatalogCache {
            override suspend fun read(key: String): CatalogSnapshot? = null

            override suspend fun write(key: String, snapshot: CatalogSnapshot) {
                throw IllegalStateException("quota exceeded")
            }
        }
        server.enqueue(jsonResponse(modelsFixtureText))

        val result = adapter(cache = cache, now = { 1000 }).loadCatalog(context())

        assertThat(result.models).hasSize(8)
        assertThat(result.fromCache).isFalse()
        assertThat(result.refreshError).isNull()
    }

    @Test
    fun `treats a cache read failure as a miss`() = runTest {
        val cache = object : CatalogCache {
            override suspend fun read(key: String): CatalogSnapshot? {
                throw IllegalStateException("storage unavailable")
            }

            override suspend fun write(key: String, snapshot: CatalogSnapshot) = Unit
        }
        server.enqueue(jsonResponse(modelsFixtureText))

        val result = adapter(cache = cache, now = { 1000 }).loadCatalog(context())

        assertThat(result.models).hasSize(8)
        assertThat(result.fromCache).isFalse()
        assertThat(result.refreshError).isNull()
    }

    @Test
    fun `maps a malformed catalog response to a parse error`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"nope":true}""").build())

        val error = runCatching {
            adapter(cache = MemoryCatalogCache(), now = { 1000 }).listModels(context())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(OpenRouterRequestException::class.java)
        assertThat((error as OpenRouterRequestException).code).isEqualTo(ChatErrorCode.PARSE)
    }

    private fun adapter(
        cache: CatalogCache = MemoryCatalogCache(),
        now: () -> Long = { 1000L },
    ): OpenRouterAdapter = OpenRouterAdapter(
        client = client,
        attribution = attribution,
        cache = cache,
        now = now,
    )

    private fun context(
        apiKey: String? = "sk-test-key",
        baseUrl: String = server.url("/api/v1").toString(),
    ): ProviderContext = ProviderContext(
        provider = Provider(
            id = "openrouter",
            presetId = "openrouter",
            label = "OpenRouter",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = baseUrl,
            authType = ProviderAuthType.BEARER,
            secretId = "secret-1",
            headers = emptyMap(),
            enabled = true,
            browserCors = BrowserCors.SUPPORTED,
            metadata = null,
            createdAt = at,
            updatedAt = at,
        ),
        apiKey = apiKey,
    )

    private fun jsonResponse(body: String): MockResponse =
        MockResponse.Builder()
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
