package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.ReasoningEffort
import dev.chatui.core.model.StreamEvent
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenRouterStreamingTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val at: Instant = Instant.parse("2026-11-02T12:34:56Z")
    private val attribution = OpenRouterAttribution(
        origin = "https://chat.example.com",
        title = "ChatUI",
    )

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
    fun `emits the expected text events`(): Unit = runBlocking {
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/openrouter-text.sse")))

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertEvents(events, expectedEvents("openrouter-text"))
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `emits the expected keep-alive events`(): Unit = runBlocking {
        server.enqueue(
            sseResponse(readResource("/contracts/fixtures/streams/openrouter-keepalive.sse")),
        )

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertEvents(events, expectedEvents("openrouter-keepalive"))
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `emits the expected reasoning events`(): Unit = runBlocking {
        server.enqueue(
            sseResponse(readResource("/contracts/fixtures/streams/openrouter-reasoning.sse")),
        )

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertEvents(events, expectedEvents("openrouter-reasoning"))
        assertThat(events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text })
            .containsExactly("Let me compare the available models.")
    }

    @Test
    fun `emits the expected usage events`(): Unit = runBlocking {
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/openrouter-usage.sse")))

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertEvents(events, expectedEvents("openrouter-usage"))
    }

    @Test
    fun `preserves partial text and does not retry an in-stream error`(): Unit = runBlocking {
        server.enqueue(
            sseResponse(readResource("/contracts/fixtures/streams/openrouter-error-200.sse")),
        )

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertEvents(events, expectedEvents("openrouter-error-200"))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Partial answer")
    }

    @Test
    fun `concatenates content arrays defensively`(): Unit = runBlocking {
        val sse = listOf(
            "data: {\"id\":\"gen-array\",\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}," +
                "{\"type\":\"text\",\"text\":\" array\"}]},\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"gen-array\",\"choices\":[{\"index\":0," +
                "\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
        ).joinToString("\n")
        server.enqueue(sseResponse(sse))

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello array")
    }

    @Test
    fun `maps HTTP error fixtures`(): Unit = runBlocking {
        for (case in expectedErrorCases()) {
            val obj = case.jsonObject
            val fixture = (obj["fixture"] as? JsonPrimitive)?.content ?: continue
            val status = obj["status"]!!.jsonPrimitive.int
            val expectedError = obj["error"]!!.jsonObject

            server.enqueue(jsonResponse(readResource("/contracts/fixtures/$fixture"), status))

            val events = adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1))
                .streamChat(context(), baseRequest())
                .toList()

            assertThat(events).hasSize(1)
            val error = (events.single() as StreamEvent.ErrorEvent).error
            assertThat(error).isEqualTo(decodeExpectedError(expectedError))
        }
    }

    @Test
    fun `maps synthetic auth model and context errors`(): Unit = runBlocking {
        for (case in expectedErrorCases()) {
            val obj = case.jsonObject
            val synthetic = (obj["synthetic"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!synthetic) continue

            val status = obj["status"]!!.jsonPrimitive.int
            val expectedError = obj["error"]!!.jsonObject
            val message = expectedError["message"]!!.jsonPrimitive.content
            val body = buildJsonObject {
                put(
                    "error",
                    buildJsonObject {
                        put("code", status)
                        put("message", message)
                    },
                )
            }.toString()

            server.enqueue(jsonResponse(body, status))

            val events = adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1))
                .streamChat(context(), baseRequest())
                .toList()

            assertThat(events).hasSize(1)
            val error = (events.single() as StreamEvent.ErrorEvent).error
            assertThat(error).isEqualTo(decodeExpectedError(expectedError))
        }
    }

    @Test
    fun `maps a 403 moderation block to content_filter`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse(
                "{\"error\":{\"code\":403,\"message\":\"Blocked by moderation policy.\"}}",
                403,
            ),
        )

        val error = firstError(adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1)))

        assertThat(error.code).isEqualTo(ChatErrorCode.CONTENT_FILTER)
        assertThat(error.status).isEqualTo(403)
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `maps a non-moderation 403 to permission`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse(
                "{\"error\":{\"code\":403,\"message\":\"This key is not allowed.\"}}",
                403,
            ),
        )

        val error = firstError(adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1)))

        assertThat(error.code).isEqualTo(ChatErrorCode.PERMISSION)
        assertThat(error.status).isEqualTo(403)
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `maps 413 to context_length`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse("{\"error\":{\"code\":413,\"message\":\"Payload too large.\"}}", 413),
        )

        val error = firstError(adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1)))

        assertThat(error.code).isEqualTo(ChatErrorCode.CONTEXT_LENGTH)
        assertThat(error.status).isEqualTo(413)
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `maps a generic 500 to a retryable provider error`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse("{\"error\":{\"code\":500,\"message\":\"Internal error.\"}}", 500),
        )

        val error = firstError(adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1)))

        assertThat(error.code).isEqualTo(ChatErrorCode.PROVIDER)
        assertThat(error.status).isEqualTo(500)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `maps invalid stream JSON to parse`(): Unit = runBlocking {
        server.enqueue(sseResponse("data: not-json\n\n"))

        val events = adapter().streamChat(context(), baseRequest()).toList()

        val error = events.last() as StreamEvent.ErrorEvent
        assertThat(error.error.code).isEqualTo(ChatErrorCode.PARSE)
        assertThat(error.error.retryable).isFalse()
    }

    @Test
    fun `redacts the API key from provider error messages`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse("{\"error\":{\"message\":\"Invalid key sk-test-key\"}}", 401),
        )

        val events = adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1))
            .streamChat(context(), baseRequest())
            .toList()

        assertThat(events.map(::encodeEvent).toString()).doesNotContain("sk-test-key")
        val error = (events.single() as StreamEvent.ErrorEvent).error
        assertThat(error.code).isEqualTo(ChatErrorCode.AUTH)
        assertThat(error.message).isEqualTo("Invalid key [redacted]")
    }

    @Test
    fun `honors Retry-After from the response header`(): Unit = runBlocking {
        val delays = mutableListOf<Long>()
        server.enqueue(
            jsonResponse(
                "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded.\"}}",
                429,
                mapOf("Retry-After" to "2"),
            ),
        )
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/openrouter-text.sse")))

        val events = adapter(
            retryPolicy = OpenRouterRetryPolicy(jitterFraction = 0.0),
            sleep = { delays += it },
        ).streamChat(context(), baseRequest()).toList()

        assertThat(delays).containsExactly(2_000L)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(events.any { it is StreamEvent.TextDelta }).isTrue()
    }

    @Test
    fun `honors Retry-After from fixture metadata`(): Unit = runBlocking {
        val delays = mutableListOf<Long>()
        server.enqueue(jsonResponse(readResource("/contracts/fixtures/errors/openrouter-429.json"), 429))
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/openrouter-text.sse")))

        adapter(
            retryPolicy = OpenRouterRetryPolicy(jitterFraction = 0.0),
            sleep = { delays += it },
        ).streamChat(context(), baseRequest()).toList()

        assertThat(delays).containsExactly(2_000L)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `retries network failures with exponential backoff`(): Unit = runBlocking {
        val delays = mutableListOf<Long>()
        server.enqueue(closeSocket())
        server.enqueue(closeSocket())
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/openrouter-text.sse")))

        val events = adapter(
            retryPolicy = OpenRouterRetryPolicy(jitterFraction = 0.0),
            sleep = { delays += it },
        ).streamChat(context(), baseRequest()).toList()

        assertThat(delays).containsExactly(800L, 1_600L).inOrder()
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(events.any { it is StreamEvent.TextDelta }).isTrue()
    }

    @Test
    fun `stops after three attempts`(): Unit = runBlocking {
        val delays = mutableListOf<Long>()
        repeat(3) { server.enqueue(closeSocket()) }

        val events = adapter(
            retryPolicy = OpenRouterRetryPolicy(jitterFraction = 0.0),
            sleep = { delays += it },
        ).streamChat(context(), baseRequest()).toList()

        assertThat(delays).containsExactly(800L, 1_600L).inOrder()
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(events).hasSize(1)
        val error = (events.single() as StreamEvent.ErrorEvent).error
        assertThat(error.code).isEqualTo(ChatErrorCode.NETWORK)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `cancellation cancels the OkHttp call`(): Unit = runBlocking {
        val release = CountDownLatch(1)
        val canceled = AtomicBoolean(false)
        val cancelClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        canceled.set(true)
                    }
                },
            )
            .build()

        try {
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body(
                        HangingSseBody(
                            "data: {\"id\":\"gen-1\",\"choices\":[{\"index\":0," +
                                "\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n",
                            release,
                        ),
                    )
                    .build(),
            )

            val events = CopyOnWriteArrayList<StreamEvent>()
            val job = launch(Dispatchers.IO) {
                adapter(httpClient = cancelClient)
                    .streamChat(context(), baseRequest())
                    .collect { events += it }
            }

            withTimeout(5_000) {
                while (events.none { it is StreamEvent.TextDelta }) {
                    delay(10)
                }
            }

            withTimeout(5_000) { job.cancelAndJoin() }

            assertThat(canceled.get()).isTrue()
            assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
                .containsExactly("Hi")
        } finally {
            release.countDown()
            cancelClient.dispatcher.executorService.shutdown()
            cancelClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `does not retry a timeout after the first token`(): Unit = runBlocking {
        val release = CountDownLatch(1)

        try {
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body(
                        HangingSseBody(
                            "data: {\"id\":\"gen-1\",\"choices\":[{\"index\":0," +
                                "\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n",
                            release,
                        ),
                    )
                    .build(),
            )

            val events = adapter(inactivityTimeoutMillis = 1_000)
                .streamChat(context(), baseRequest())
                .toList()

            assertThat(server.requestCount).isEqualTo(1)
            assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
                .containsExactly("Hi")
            val error = events.last() as StreamEvent.ErrorEvent
            assertThat(error.error.code).isEqualTo(ChatErrorCode.TIMEOUT)
            assertThat(error.error.retryable).isFalse()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `serializes the basic request vector exactly`() {
        val vector = requestVector("openrouter-basic")

        val body = buildChatRequestBody(decodeRequest(vector), supportedParameters(vector))

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `serializes curated routing exactly`() {
        val vector = requestVector("openrouter-routing")

        val body = buildChatRequestBody(decodeRequest(vector), supportedParameters(vector))

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `omits an unsupported reasoning control`() {
        val vector = requestVector("openrouter-unsupported-parameter")

        val body = buildChatRequestBody(decodeRequest(vector), supportedParameters(vector))

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `serializes sampling parameters only when supported`() {
        val request = baseRequest(
            params = ChatParams(
                temperature = 0.2,
                topP = 0.9,
                maxOutputTokens = 128,
                stop = listOf("END"),
                reasoningEffort = ReasoningEffort.HIGH,
                streaming = true,
                extra = JsonObject(emptyMap()),
            ),
        )

        val body = buildChatRequestBody(request, listOf("temperature", "max_tokens", "reasoning"))

        assertThat(body).isEqualTo(
            buildJsonObject {
                put("model", request.model)
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "Hello")
                            },
                        )
                    },
                )
                put("stream", true)
                put("stream_options", buildJsonObject { put("include_usage", true) })
                put("temperature", 0.2)
                put("max_tokens", 128)
                put("reasoning", buildJsonObject { put("effort", "high") })
            },
        )
    }

    @Test
    fun `concatenates multiple text parts into one content string`() {
        val request = baseRequest(
            messages = listOf(
                NormalizedMessage(
                    NormalizedRole.USER,
                    listOf(ContentPart.Text("Hello"), ContentPart.Text(" world")),
                ),
            ),
        )

        val body = buildChatRequestBody(request)

        assertThat(body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
            .isEqualTo("Hello world")
    }

    @Test
    fun `does not send reasoning parts back to the provider`() {
        val request = baseRequest(
            messages = listOf(
                NormalizedMessage(
                    NormalizedRole.ASSISTANT,
                    listOf(ContentPart.Reasoning("private reasoning"), ContentPart.Text("Answer")),
                ),
            ),
        )

        val body = buildChatRequestBody(request)

        assertThat(body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
            .isEqualTo("Answer")
    }

    @Test
    fun `merges advanced reasoning over the curated effort`() {
        val request = baseRequest(
            params = ChatParams(
                temperature = null,
                topP = null,
                maxOutputTokens = null,
                stop = emptyList(),
                reasoningEffort = ReasoningEffort.MEDIUM,
                streaming = true,
                extra = buildJsonObject {
                    put(
                        "openrouter",
                        buildJsonObject {
                            put(
                                "reasoning",
                                buildJsonObject {
                                    put("max_tokens", 2048)
                                    put("exclude", true)
                                },
                            )
                        },
                    )
                },
            ),
        )

        val body = buildChatRequestBody(request, listOf("reasoning"))

        assertThat(body["reasoning"]!!.jsonObject).isEqualTo(
            buildJsonObject {
                put("effort", "medium")
                put("max_tokens", 2048)
                put("exclude", true)
            },
        )
    }

    @Test
    fun `rejects protected advanced fields before sending`(): Unit = runBlocking {
        val vector = requestVector("openrouter-advanced-protected")
        val request = decodeRequest(vector)
        val expectedError = vector["expected"]!!.jsonObject["error"]!!.jsonObject

        val events = adapter().streamChat(context(), request).toList()

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(events).hasSize(1)
        val error = (events.single() as StreamEvent.ErrorEvent).error
        assertThat(error).isEqualTo(decodeExpectedError(expectedError))
    }

    @Test
    fun `builder throws a normalized invalid request error`() {
        val vector = requestVector("openrouter-advanced-protected")
        val request = decodeRequest(vector)
        val expectedError = vector["expected"]!!.jsonObject["error"]!!.jsonObject

        val thrown = runCatching {
            buildChatRequestBody(request, supportedParameters(vector))
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(OpenRouterRequestException::class.java)
        val error = thrown as OpenRouterRequestException
        assertThat(error.code).isEqualTo(ChatErrorCode.INVALID_REQUEST)
        assertThat(error.message).isEqualTo(expectedError["message"]!!.jsonPrimitive.content)
        assertThat(error.details).isEqualTo(expectedError["details"])
    }

    @Test
    fun `sends attribution auth and stream headers`(): Unit = runBlocking {
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/openrouter-text.sse")))

        adapter().streamChat(context(), baseRequest()).toList()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/chat/completions")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer sk-test-key")
        assertThat(request.headers["HTTP-Referer"]).isEqualTo("https://chat.example.com")
        assertThat(request.headers["X-Title"]).isEqualTo("ChatUI")
        assertThat(request.headers["Content-Type"]).isEqualTo("application/json")
        assertThat(request.headers["Accept"]).isEqualTo("text/event-stream")
        assertThat(request.url.toString()).doesNotContain("sk-test-key")
    }

    private fun adapter(
        httpClient: OkHttpClient = client,
        retryPolicy: OpenRouterRetryPolicy = OpenRouterRetryPolicy(),
        inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
        sleep: suspend (Long) -> Unit = { _ -> },
        random: Random = Random(0),
        supportedParameters: List<String> = DEFAULT_SUPPORTED_PARAMETERS,
    ): OpenRouterAdapter = OpenRouterAdapter(
        client = httpClient,
        attribution = attribution,
        cache = MemoryCatalogCache(),
        now = { at.toEpochMilli() },
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = random,
        sleep = sleep,
        supportedParameters = supportedParameters,
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

    private fun baseRequest(
        model: String = "openai/gpt-4o-mini-2024-07-18",
        system: String? = null,
        messages: List<NormalizedMessage> = listOf(
            NormalizedMessage(NormalizedRole.USER, listOf(ContentPart.Text("Hello"))),
        ),
        params: ChatParams = ChatParams(
            temperature = null,
            topP = null,
            maxOutputTokens = null,
            stop = emptyList(),
            reasoningEffort = null,
            streaming = true,
            extra = JsonObject(emptyMap()),
        ),
    ): NormalizedChatRequest = NormalizedChatRequest(
        model = model,
        system = system,
        messages = messages,
        params = params,
    )

    private suspend fun firstError(adapter: OpenRouterAdapter): ChatError {
        val events = adapter.streamChat(context(), baseRequest()).toList()
        return (events.single() as StreamEvent.ErrorEvent).error
    }

    private fun assertEvents(events: List<StreamEvent>, expected: JsonArray) {
        assertThat(events).containsExactlyElementsIn(decodeExpectedEvents(expected)).inOrder()
    }

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

    private fun decodeExpectedError(expected: JsonObject): ChatError =
        Json.decodeFromJsonElement(ChatError.serializer(), withAt(expected))

    private fun withAt(error: JsonObject): JsonObject =
        JsonObject(error + ("at" to JsonPrimitive(at.toString())))

    private fun encodeEvent(event: StreamEvent): JsonElement =
        Json.encodeToJsonElement(StreamEvent.serializer(), event)

    private fun expectedEvents(name: String): JsonArray =
        Json.parseToJsonElement(readResource("/contracts/fixtures/expected/$name.json"))
            .jsonObject["events"]!!
            .jsonArray

    private fun expectedErrorCases(): JsonArray =
        Json.parseToJsonElement(readResource("/contracts/fixtures/expected/openrouter-errors.json"))
            .jsonObject["cases"]!!
            .jsonArray

    private fun requestVector(name: String): JsonObject =
        Json.parseToJsonElement(readResource("/contracts/fixtures/requests/$name.json")).jsonObject

    private fun decodeRequest(vector: JsonObject): NormalizedChatRequest =
        Json.decodeFromJsonElement(NormalizedChatRequest.serializer(), vector["request"]!!)

    private fun supportedParameters(vector: JsonObject): List<String> =
        vector["supportedParameters"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun sseResponse(body: String): MockResponse =
        MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(body)
            .build()

    private fun jsonResponse(
        body: String,
        status: Int,
        headers: Map<String, String> = emptyMap(),
    ): MockResponse {
        val builder = MockResponse.Builder()
            .code(status)
            .addHeader("Content-Type", "application/json")
            .body(body)

        headers.forEach { (name, value) -> builder.addHeader(name, value) }

        return builder.build()
    }

    private fun closeSocket(): MockResponse =
        MockResponse.Builder()
            .onRequestStart(SocketEffect.CloseSocket())
            .build()

    private fun readResource(path: String): String {
        val normalized = path.removePrefix("/")
        val stream = javaClass.classLoader.getResourceAsStream(normalized)
            ?: error("Missing test resource $normalized")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
