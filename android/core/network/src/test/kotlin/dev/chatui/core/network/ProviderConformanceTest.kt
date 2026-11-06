package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Usage
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Cross-adapter conformance tests for M2 critical cases 11-14.
 *
 * Each case runs through MockWebServer with a chunked response body, so the
 * same test proves both the normalized event mapping and chunk-boundary
 * handling. Per-adapter suites cover the remaining error, retry, cancellation,
 * protected-field, and request-vector cases.
 *
 * Case mapping:
 * - 11: OpenAI-compatible streaming appends text in order and finalizes usage.
 * - 12: Anthropic thinking and text deltas are separated correctly.
 * - 13: Gemini MAX_TOKENS maps to length.
 * - 14: Ollama NDJSON completes and usage is recorded.
 */
class ProviderConformanceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val at: Instant = Instant.parse("2026-11-06T12:34:56Z")

    private val openAIPreset: OpenAICompatiblePreset by lazy {
        OpenAICompatiblePreset.fromPreset(
            ProviderRegistry.load().getPreset("openai")
                ?: error("OpenAI preset is missing from contracts/providers.json"),
        )
    }

    private val anthropicPreset: ProviderPreset by lazy {
        ProviderRegistry.load().getPreset("anthropic")
            ?: error("Anthropic preset is missing from contracts/providers.json")
    }

    private val geminiPreset: ProviderPreset by lazy {
        ProviderRegistry.load().getPreset("google")
            ?: error("Gemini preset is missing from contracts/providers.json")
    }

    private val ollamaPreset: ProviderPreset by lazy {
        ProviderRegistry.load().getPreset("ollama")
            ?: error("Ollama preset is missing from contracts/providers.json")
    }

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
    fun `case 11 openai-compatible chunked SSE appends text in order and finalizes usage`(): Unit =
        runBlocking {
            server.enqueue(
                chunkedSse(
                    readSseTestResource("/contracts/fixtures/streams/openai-text.sse"),
                    chunkSize = 7,
                ),
            )

            val events = openAIAdapter()
                .streamChat(openAIContext(), baseRequest("gpt-4o-mini"))
                .toList()

            assertThat(events)
                .containsExactlyElementsIn(decodeExpectedEvents(expectedEvents("openai-text")))
                .inOrder()
            assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
                .containsExactly("Hello", " from OpenAI.")
                .inOrder()
            assertThat(events.filterIsInstance<StreamEvent.UsageEvent>().single().usage)
                .isEqualTo(
                    Usage(
                        inputTokens = 8,
                        outputTokens = 5,
                        totalTokens = 13,
                        costUsd = null,
                        estimated = false,
                    ),
                )
        }

    @Test
    fun `case 12 anthropic chunked SSE separates thinking and text deltas`(): Unit = runBlocking {
        val case = Json.parseToJsonElement(
            readSseTestResource("/contracts/fixtures/expected/anthropic-thinking.json"),
        ).jsonObject["cases"]!!.jsonArray.single().jsonObject
        val rawEvents = case["rawEvents"]!!.jsonArray

        val body = buildString {
            append("event: message_start\n")
            append(
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_thinking\"," +
                    "\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}\n\n",
            )
            for (raw in rawEvents) {
                append("event: ${raw.jsonObject["type"]!!.jsonPrimitive.content}\n")
                append("data: $raw\n\n")
            }
            append("event: message_delta\n")
            append(
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
                    "\"usage\":{\"output_tokens\":5}}\n\n",
            )
            append("event: message_stop\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }

        server.enqueue(chunkedSse(body, chunkSize = 5))

        val events = anthropicAdapter()
            .streamChat(anthropicContext(), baseRequest("claude-sonnet-4-5"))
            .toList()

        assertThat(events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text })
            .containsExactly("Let me think about this.")
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Here is the answer.")
        assertThat(events.filterIsInstance<StreamEvent.Finish>().single().reason)
            .isEqualTo(FinishReason.STOP)
    }

    @Test
    fun `case 13 gemini MAX_TOKENS maps to length`(): Unit = runBlocking {
        val body =
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]," +
                "\"role\":\"model\"},\"finishReason\":null,\"index\":0}]," +
                "\"usageMetadata\":{\"promptTokenCount\":8,\"candidatesTokenCount\":1," +
                "\"totalTokenCount\":9}}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" truncated\"}]," +
                "\"role\":\"model\"},\"finishReason\":\"MAX_TOKENS\",\"index\":0}]," +
                "\"usageMetadata\":{\"promptTokenCount\":8,\"candidatesTokenCount\":5," +
                "\"totalTokenCount\":13}}\n\n"

        server.enqueue(chunkedSse(body, chunkSize = 9))

        val events = geminiAdapter()
            .streamChat(geminiContext(), baseRequest("gemini-2.5-flash"))
            .toList()

        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " truncated")
            .inOrder()
        assertThat(events.filterIsInstance<StreamEvent.Finish>().single().reason)
            .isEqualTo(FinishReason.LENGTH)
    }

    @Test
    fun `case 14 ollama chunked NDJSON completes and records usage`(): Unit = runBlocking {
        server.enqueue(
            chunkedNdjson(
                readSseTestResource("/contracts/fixtures/streams/ollama-ndjson.txt"),
                chunkSize = 11,
            ),
        )

        val events = ollamaAdapter()
            .streamChat(ollamaContext(), baseRequest("llama3.2:latest"))
            .toList()

        assertThat(events)
            .containsExactlyElementsIn(decodeExpectedEvents(expectedEvents("ollama-ndjson")))
            .inOrder()
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " from Ollama.")
            .inOrder()
        assertThat(events.filterIsInstance<StreamEvent.UsageEvent>().single().usage)
            .isEqualTo(
                Usage(
                    inputTokens = 8,
                    outputTokens = 5,
                    totalTokens = 13,
                    costUsd = null,
                    estimated = false,
                ),
            )
    }

    @Test
    fun `openrouter chunked SSE regression keeps text usage and finish events`(): Unit = runBlocking {
        server.enqueue(
            chunkedSse(
                readSseTestResource("/contracts/fixtures/streams/openrouter-text.sse"),
                chunkSize = 7,
            ),
        )

        val events = openRouterAdapter()
            .streamChat(openRouterContext(), baseRequest("openai/gpt-4o-mini-2024-07-18"))
            .toList()

        assertThat(events)
            .containsExactlyElementsIn(decodeExpectedEvents(expectedEvents("openrouter-text")))
            .inOrder()
    }

    private fun openAIAdapter(): OpenAICompatibleAdapter = OpenAICompatibleAdapter(
        preset = openAIPreset,
        client = client,
        attribution = null,
        cache = MemoryCatalogCache(),
        now = { at.toEpochMilli() },
        retryPolicy = OpenRouterRetryPolicy(),
        inactivityTimeoutMillis = DEFAULT_INACTIVITY_TIMEOUT_MS,
        random = Random(0),
        sleep = { _ -> },
    )

    private fun anthropicAdapter(): AnthropicAdapter = AnthropicAdapter(
        client = client,
        preset = anthropicPreset,
        cache = MemoryCatalogCache(),
        now = { at.toEpochMilli() },
        retryPolicy = OpenRouterRetryPolicy(),
        inactivityTimeoutMillis = DEFAULT_INACTIVITY_TIMEOUT_MS,
        random = Random(0),
        sleep = { _ -> },
    )

    private fun geminiAdapter(): GeminiAdapter = GeminiAdapter(
        client = client,
        preset = geminiPreset,
        cache = MemoryCatalogCache(),
        now = { at.toEpochMilli() },
        retryPolicy = OpenRouterRetryPolicy(),
        inactivityTimeoutMillis = DEFAULT_INACTIVITY_TIMEOUT_MS,
        random = Random(0),
        sleep = { _ -> },
    )

    private fun ollamaAdapter(): OllamaAdapter = OllamaAdapter(
        client = client,
        preset = ollamaPreset,
        cache = MemoryCatalogCache(),
        now = { at.toEpochMilli() },
        retryPolicy = OpenRouterRetryPolicy(),
        inactivityTimeoutMillis = DEFAULT_INACTIVITY_TIMEOUT_MS,
        random = Random(0),
        sleep = { _ -> },
    )

    private fun openRouterAdapter(): OpenRouterAdapter = OpenRouterAdapter(
        client = client,
        attribution = null,
        cache = MemoryCatalogCache(),
        now = { at.toEpochMilli() },
        retryPolicy = OpenRouterRetryPolicy(),
        inactivityTimeoutMillis = DEFAULT_INACTIVITY_TIMEOUT_MS,
        random = Random(0),
        sleep = { _ -> },
    )

    private fun openAIContext(): ProviderContext = context(
        id = "openai",
        presetId = "openai",
        label = "OpenAI",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        authType = ProviderAuthType.BEARER,
        apiKey = "sk-test-key",
        baseUrl = server.url("/v1").toString(),
    )

    private fun anthropicContext(): ProviderContext = context(
        id = "anthropic",
        presetId = "anthropic",
        label = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        authType = ProviderAuthType.X_API_KEY,
        apiKey = "sk-ant-test-key",
        baseUrl = server.url("/v1").toString(),
    )

    private fun geminiContext(): ProviderContext = context(
        id = "google",
        presetId = "google",
        label = "Google Gemini",
        kind = ProviderKind.GOOGLE_GEMINI,
        authType = ProviderAuthType.X_API_KEY,
        apiKey = "AIza-test-key",
        baseUrl = server.url("/v1").toString(),
    )

    private fun ollamaContext(): ProviderContext = context(
        id = "ollama",
        presetId = "ollama",
        label = "Ollama",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        authType = ProviderAuthType.NONE,
        apiKey = null,
        baseUrl = server.url("/v1").toString(),
    )

    private fun openRouterContext(): ProviderContext = context(
        id = "openrouter",
        presetId = "openrouter",
        label = "OpenRouter",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        authType = ProviderAuthType.BEARER,
        apiKey = "sk-test-key",
        baseUrl = server.url("/api/v1").toString(),
    )

    private fun context(
        id: String,
        presetId: String,
        label: String,
        kind: ProviderKind,
        authType: ProviderAuthType,
        apiKey: String?,
        baseUrl: String,
    ): ProviderContext = ProviderContext(
        provider = Provider(
            id = id,
            presetId = presetId,
            label = label,
            kind = kind,
            baseUrl = baseUrl,
            authType = authType,
            secretId = if (apiKey == null) null else "secret-1",
            headers = emptyMap(),
            enabled = true,
            browserCors = BrowserCors.UNKNOWN,
            metadata = null,
            createdAt = at,
            updatedAt = at,
        ),
        apiKey = apiKey,
    )

    private fun baseRequest(model: String): NormalizedChatRequest = NormalizedChatRequest(
        model = model,
        system = null,
        messages = listOf(
            NormalizedMessage(NormalizedRole.USER, listOf(ContentPart.Text("Hello"))),
        ),
        params = ChatParams(
            temperature = null,
            topP = null,
            maxOutputTokens = 256,
            stop = emptyList(),
            reasoningEffort = null,
            streaming = true,
            extra = JsonObject(emptyMap()),
        ),
    )

    private fun chunkedSse(body: String, chunkSize: Int): MockResponse =
        MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .chunkedBody(body, chunkSize)
            .build()

    private fun chunkedNdjson(body: String, chunkSize: Int): MockResponse =
        MockResponse.Builder()
            .addHeader("Content-Type", "application/x-ndjson")
            .chunkedBody(body, chunkSize)
            .build()

    private fun expectedEvents(name: String): JsonArray =
        Json.parseToJsonElement(readSseTestResource("/contracts/fixtures/expected/$name.json"))
            .jsonObject["events"]!!
            .jsonArray

    private fun decodeExpectedEvents(expected: JsonArray): List<StreamEvent> =
        expected.map { element ->
            val obj = element.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "error") {
                val error = obj["error"]!!.jsonObject
                Json.decodeFromJsonElement(
                    StreamEvent.serializer(),
                    JsonObject(obj + ("error" to withAt(error))),
                )
            } else {
                Json.decodeFromJsonElement(StreamEvent.serializer(), element)
            }
        }

    private fun withAt(error: JsonObject): JsonObject =
        JsonObject(error + ("at" to JsonPrimitive(at.toString())))
}
