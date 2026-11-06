package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class AnthropicAdapterTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val at: Instant = Instant.parse("2026-11-05T12:34:56Z")
    private val apiKey = "sk-ant-anthropic-test-key"

    private val preset: ProviderPreset by lazy {
        ProviderRegistry.load().getPreset("anthropic")
            ?: error("Anthropic preset is missing from contracts/providers.json")
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
    fun `serializes the basic request vector exactly`() {
        val vector = requestVector("anthropic-basic")

        val body = buildAnthropicChatRequestBody(
            decodeRequest(vector),
            supportedParameters(vector),
        )

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `serializes images as base64 blocks and text files as text blocks`() {
        val vector = requestVector("anthropic-attachments")
        val request = decodeRequest(vector).copy(
            attachments = Json.decodeFromJsonElement(
                MapSerializer(String.serializer(), ResolvedAttachment.serializer()),
                vector["attachments"]!!,
            ),
        )

        val body = buildAnthropicChatRequestBody(request, supportedParameters(vector))

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `round-trips safe advanced JSON under the anthropic namespace`() {
        val vector = requestVector("anthropic-extras")

        val body = buildAnthropicChatRequestBody(
            decodeRequest(vector),
            supportedParameters(vector),
        )

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `rejects protected fields before sending`(): Unit = runBlocking {
        val vector = requestVector("anthropic-protected")
        val expectedError = vector["expected"]!!.jsonObject["error"]!!.jsonObject

        val events = adapter().streamChat(context(), decodeRequest(vector)).toList()

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(events).hasSize(1)
        val error = (events.single() as StreamEvent.ErrorEvent).error
        assertThat(error).isEqualTo(decodeExpectedError(expectedError))
    }

    @Test
    fun `sends native headers without the web browser header`(): Unit = runBlocking {
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/anthropic-text.sse")))

        adapter().streamChat(context(), baseRequest()).toList()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v1/messages")
        assertThat(request.headers["x-api-key"]).isEqualTo(apiKey)
        assertThat(request.headers["anthropic-version"]).isEqualTo(ANTHROPIC_VERSION)
        assertThat(request.headers["anthropic-dangerous-direct-browser-access"]).isNull()
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(request.headers["Content-Type"]).isEqualTo("application/json")
        assertThat(request.headers["Accept"]).isEqualTo("text/event-stream")
        assertThat(request.url.toString()).doesNotContain(apiKey)
    }

    @Test
    fun `maps the shared Anthropic catalog fixture`() {
        val catalogs = Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/catalogs.json"),
        ).jsonObject["cases"]!!.jsonArray
        val case = catalogs.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "anthropic" }
        val fixture = case["fixture"]!!.jsonPrimitive.content
        val expected = case["expected"]!!.jsonArray

        val models = mapAnthropicModels(
            Json.parseToJsonElement(readResource("/contracts/fixtures/$fixture")),
        )

        assertThat(models).hasSize(expected.size)
        models.forEachIndexed { index, model ->
            val entry = expected[index].jsonObject
            assertThat(model.remoteId).isEqualTo(entry["remoteId"]!!.jsonPrimitive.content)
            assertThat(model.label).isEqualTo(entry["label"]!!.jsonPrimitive.content)
            assertThat(model.contextWindow)
                .isEqualTo(entry["contextWindow"]?.jsonPrimitive?.intOrNull)
            assertThat(model.maxOutputTokens)
                .isEqualTo(entry["maxOutputTokens"]?.jsonPrimitive?.intOrNull)
            assertThat(model.inputModalities.map { it.wireName() })
                .containsExactlyElementsIn(
                    entry["inputModalities"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
                .inOrder()
            assertThat(model.outputModalities.map { it.wireName() })
                .containsExactlyElementsIn(
                    entry["outputModalities"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
                .inOrder()
            assertThat(model.capabilities.map { it.wireName() })
                .containsExactlyElementsIn(
                    entry["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
                .inOrder()
            assertThat(model.supportedParameters)
                .containsExactlyElementsIn(
                    entry["supportedParameters"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
                .inOrder()
            assertThat(model.pricing).isNull()
            assertThat(model.hidden).isEqualTo(entry["hidden"]!!.jsonPrimitive.booleanOrNull)
        }
    }

    @Test
    fun `validates the key with GET models and keeps it out of the URL`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse(
                readResource("/contracts/fixtures/models/anthropic-models.json"),
                200,
            ),
        )

        val info = adapter().validateKey(context())

        assertThat(info).isEqualTo(ProviderKeyInfo())
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/v1/models")
        assertThat(request.headers["x-api-key"]).isEqualTo(apiKey)
        assertThat(request.headers["anthropic-version"]).isEqualTo(ANTHROPIC_VERSION)
        assertThat(request.headers["anthropic-dangerous-direct-browser-access"]).isNull()
        assertThat(request.url.toString()).doesNotContain(apiKey)
    }

    @Test
    fun `emits the expected text usage and finish events`(): Unit = runBlocking {
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/anthropic-text.sse")))

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertThat(events).containsExactlyElementsIn(
            decodeExpectedEvents(expectedEvents("anthropic-text")),
        ).inOrder()
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " from Anthropic.")
    }

    @Test
    fun `keeps thinking deltas separate from answer text`(): Unit = runBlocking {
        val fixture = Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/anthropic-thinking.json"),
        ).jsonObject
        val case = fixture["cases"]!!.jsonArray.single().jsonObject
        val rawEvents = case["rawEvents"]!!.jsonArray
        val expected = case["events"]!!.jsonArray

        val events = mutableListOf<StreamEvent>()
        val mapper = AnthropicStreamMapper(context()) { events += it }

        for (raw in rawEvents) {
            mapper.handleData(raw.toString())
        }
        mapper.finish()

        assertThat(events).containsExactlyElementsIn(decodeExpectedEvents(expected)).inOrder()
        assertThat(events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text })
            .containsExactly("Let me think about this.")
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Here is the answer.")
    }

    @Test
    fun `maps Anthropic HTTP error fixtures`(): Unit = runBlocking {
        for (case in anthropicErrorCases()) {
            val obj = case.jsonObject
            if ((obj["kind"] as? JsonPrimitive)?.content != "http") continue

            val fixture = obj["fixture"]!!.jsonPrimitive.content
            val status = obj["status"]!!.jsonPrimitive.int
            val expectedError = obj["error"]!!.jsonObject
            val fixtureObj = Json.parseToJsonElement(
                readResource("/contracts/fixtures/$fixture"),
            ).jsonObject
            val body = fixtureObj["body"]!!.toString()
            val headers = fixtureObj["headers"]?.jsonObject
                ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                ?: emptyMap()

            server.enqueue(jsonResponse(body, status, headers))

            val events = adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1))
                .streamChat(context(), baseRequest())
                .toList()

            assertThat(events).hasSize(1)
            val error = (events.single() as StreamEvent.ErrorEvent).error
            assertThat(error).isEqualTo(decodeExpectedError(expectedError))
        }
    }

    @Test
    fun `maps an in-stream overloaded error`(): Unit = runBlocking {
        val case = anthropicErrorCases()
            .map { it.jsonObject }
            .single { (it["kind"] as? JsonPrimitive)?.content == "stream" }
        val fixture = case["fixture"]!!.jsonPrimitive.content
        val expectedError = case["error"]!!.jsonObject

        server.enqueue(sseResponse(readResource("/contracts/fixtures/$fixture")))

        val events = adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1))
            .streamChat(context(), baseRequest())
            .toList()

        assertThat(events).hasSize(1)
        val error = (events.single() as StreamEvent.ErrorEvent).error
        assertThat(error).isEqualTo(decodeExpectedError(expectedError))
    }

    @Test
    fun `redacts the API key from provider error messages`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"type":"error","error":{"type":"authentication_error","message":"bad key $apiKey"}}""",
                401,
            ),
        )

        val events = adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1))
            .streamChat(context(), baseRequest())
            .toList()

        assertThat(Json.encodeToString(StreamEvent.serializer(), events.single()))
            .doesNotContain(apiKey)
        assertThat((events.single() as StreamEvent.ErrorEvent).error.message)
            .isEqualTo("bad key [redacted]")
    }

    @Test
    fun `honors Retry-After and retries before any output`(): Unit = runBlocking {
        val delays = mutableListOf<Long>()
        server.enqueue(
            jsonResponse(
                """{"type":"error","error":{"type":"rate_limit_error","message":"Rate limit."}}""",
                429,
                mapOf("Retry-After" to "2", "request-id" to "req_retry"),
            ),
        )
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/anthropic-text.sse")))

        val events = adapter(
            retryPolicy = OpenRouterRetryPolicy(maxAttempts = 2, jitterFraction = 0.0),
            sleep = { delays += it },
        ).streamChat(context(), baseRequest()).toList()

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(delays).containsExactly(2_000L)
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " from Anthropic.")
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
                            "event: message_start\n" +
                                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"," +
                                "\"usage\":{\"input_tokens\":3}}}\n\n" +
                                "event: content_block_delta\n" +
                                "data: {\"type\":\"content_block_delta\",\"index\":0," +
                                "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n",
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

    private fun adapter(
        httpClient: OkHttpClient = client,
        retryPolicy: OpenRouterRetryPolicy = OpenRouterRetryPolicy(),
        inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
        sleep: suspend (Long) -> Unit = { _ -> },
    ): AnthropicAdapter = AnthropicAdapter(
        client = httpClient,
        preset = preset,
        cache = MemoryCatalogCache(),
        now = { at.toEpochMilli() },
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = Random(0),
        sleep = sleep,
    )

    private fun context(
        apiKey: String? = this.apiKey,
        baseUrl: String = server.url("/v1").toString(),
    ): ProviderContext = ProviderContext(
        provider = Provider(
            id = "anthropic",
            presetId = "anthropic",
            label = "Anthropic",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = baseUrl,
            authType = ProviderAuthType.X_API_KEY,
            secretId = "secret-1",
            headers = emptyMap(),
            enabled = true,
            browserCors = BrowserCors.UNKNOWN,
            metadata = null,
            createdAt = at,
            updatedAt = at,
        ),
        apiKey = apiKey,
    )

    private fun baseRequest(
        model: String = "claude-sonnet-4-5",
        system: String? = null,
        messages: List<NormalizedMessage> = listOf(
            NormalizedMessage(NormalizedRole.USER, listOf(ContentPart.Text("Hello"))),
        ),
        params: ChatParams = ChatParams(
            temperature = null,
            topP = null,
            maxOutputTokens = 256,
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

    private fun expectedEvents(name: String): JsonArray =
        Json.parseToJsonElement(readResource("/contracts/fixtures/expected/$name.json"))
            .jsonObject["events"]!!
            .jsonArray

    private fun anthropicErrorCases(): List<JsonElement> =
        Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/provider-errors.json"),
        ).jsonObject["cases"]!!
            .jsonArray
            .filter { (it.jsonObject["providerId"] as? JsonPrimitive)?.content == "anthropic" }

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

    private fun readResource(path: String): String {
        val normalized = path.removePrefix("/")
        val stream = javaClass.classLoader.getResourceAsStream(normalized)
            ?: error("Missing test resource $normalized")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

private fun dev.chatui.core.model.InputModality.wireName(): String = when (this) {
    dev.chatui.core.model.InputModality.TEXT -> "text"
    dev.chatui.core.model.InputModality.IMAGE -> "image"
    dev.chatui.core.model.InputModality.FILE -> "file"
    dev.chatui.core.model.InputModality.AUDIO -> "audio"
    dev.chatui.core.model.InputModality.VIDEO -> "video"
}

private fun dev.chatui.core.model.OutputModality.wireName(): String = when (this) {
    dev.chatui.core.model.OutputModality.TEXT -> "text"
    dev.chatui.core.model.OutputModality.IMAGE -> "image"
    dev.chatui.core.model.OutputModality.AUDIO -> "audio"
}

private fun dev.chatui.core.model.ModelCapability.wireName(): String = when (this) {
    dev.chatui.core.model.ModelCapability.TEXT -> "text"
    dev.chatui.core.model.ModelCapability.VISION -> "vision"
    dev.chatui.core.model.ModelCapability.FILES -> "files"
    dev.chatui.core.model.ModelCapability.TOOLS -> "tools"
    dev.chatui.core.model.ModelCapability.REASONING -> "reasoning"
    dev.chatui.core.model.ModelCapability.STREAMING -> "streaming"
}
