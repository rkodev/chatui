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

class GeminiAdapterTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val at: Instant = Instant.parse("2026-11-05T12:34:56Z")
    private val apiKey = "AIza-gemini-test-key"

    private val preset: ProviderPreset by lazy {
        ProviderRegistry.load().getPreset("google")
            ?: error("Gemini preset is missing from contracts/providers.json")
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
        val vector = requestVector("gemini-basic")

        val body = buildGeminiChatRequestBody(
            decodeRequest(vector),
            supportedParameters(vector),
        )

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `serializes inline data and text files`() {
        val vector = requestVector("gemini-attachments")
        val request = decodeRequest(vector).copy(
            attachments = Json.decodeFromJsonElement(
                MapSerializer(String.serializer(), ResolvedAttachment.serializer()),
                vector["attachments"]!!,
            ),
        )

        val body = buildGeminiChatRequestBody(request, supportedParameters(vector))

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `round-trips safe advanced JSON under the google namespace`() {
        val vector = requestVector("gemini-extras")

        val body = buildGeminiChatRequestBody(
            decodeRequest(vector),
            supportedParameters(vector),
        )

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `rejects protected fields before sending`(): Unit = runBlocking {
        val vector = requestVector("gemini-protected")
        val expectedError = vector["expected"]!!.jsonObject["error"]!!.jsonObject

        val events = adapter().streamChat(context(), decodeRequest(vector)).toList()

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(events).hasSize(1)
        val error = (events.single() as StreamEvent.ErrorEvent).error
        assertThat(error).isEqualTo(decodeExpectedError(expectedError))
    }

    @Test
    fun `sends the key in the header and alt sse in the query`(): Unit = runBlocking {
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/gemini-text.sse")))

        adapter().streamChat(context(), baseRequest()).toList()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath)
            .isEqualTo("/v1/models/gemini-2.5-flash:streamGenerateContent")
        assertThat(request.url.queryParameter("alt")).isEqualTo("sse")
        assertThat(request.headers["x-goog-api-key"]).isEqualTo(apiKey)
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(request.headers["Content-Type"]).isEqualTo("application/json")
        assertThat(request.headers["Accept"]).isEqualTo("text/event-stream")
        assertThat(request.url.toString()).doesNotContain(apiKey)
    }

    @Test
    fun `maps the shared Gemini catalog fixture`() {
        val catalogs = Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/catalogs.json"),
        ).jsonObject["cases"]!!.jsonArray
        val case = catalogs.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "google" }
        val fixture = case["fixture"]!!.jsonPrimitive.content
        val expected = case["expected"]!!.jsonArray

        val models = mapGeminiModels(
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
                readResource("/contracts/fixtures/models/gemini-models.json"),
                200,
            ),
        )

        val info = adapter().validateKey(context())

        assertThat(info).isEqualTo(ProviderKeyInfo())
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/v1/models")
        assertThat(request.headers["x-goog-api-key"]).isEqualTo(apiKey)
        assertThat(request.url.toString()).doesNotContain(apiKey)
    }

    @Test
    fun `emits the expected text usage and finish events`(): Unit = runBlocking {
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/gemini-text.sse")))

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertThat(events).containsExactlyElementsIn(
            decodeExpectedEvents(expectedEvents("gemini-text")),
        ).inOrder()
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " from Gemini.")
    }

    @Test
    fun `maps every Gemini finish reason`() {
        val cases = Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/gemini-finish-reasons.json"),
        ).jsonObject["cases"]!!.jsonArray

        for (case in cases) {
            val obj = case.jsonObject
            val reason = obj["finishReason"]!!.jsonPrimitive.content
            val expected = obj["expected"]!!.jsonPrimitive.content

            assertThat(mapGeminiFinishReason(JsonPrimitive(reason)).wireName())
                .isEqualTo(expected)
        }
    }

    @Test
    fun `maps Gemini HTTP error fixtures`(): Unit = runBlocking {
        for (case in geminiErrorCases()) {
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
    fun `maps an in-stream Gemini error`(): Unit = runBlocking {
        val case = geminiErrorCases()
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
                """{"error":{"code":401,"message":"bad key $apiKey","status":"UNAUTHENTICATED"}}""",
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
                """{"error":{"code":429,"message":"Resource exhausted.","status":"RESOURCE_EXHAUSTED"}}""",
                429,
                mapOf("Retry-After" to "2", "x-goog-request-id" to "req_retry"),
            ),
        )
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/gemini-text.sse")))

        val events = adapter(
            retryPolicy = OpenRouterRetryPolicy(maxAttempts = 2, jitterFraction = 0.0),
            sleep = { delays += it },
        ).streamChat(context(), baseRequest()).toList()

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(delays).containsExactly(2_000L)
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " from Gemini.")
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
                            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]," +
                                "\"role\":\"model\"},\"index\":0}]}\n\n",
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
    ): GeminiAdapter = GeminiAdapter(
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
            id = "google",
            presetId = "google",
            label = "Google Gemini",
            kind = ProviderKind.GOOGLE_GEMINI,
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
        model: String = "gemini-2.5-flash",
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

    private fun geminiErrorCases(): List<JsonElement> =
        Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/provider-errors.json"),
        ).jsonObject["cases"]!!
            .jsonArray
            .filter { (it.jsonObject["providerId"] as? JsonPrimitive)?.content == "google" }

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

private fun dev.chatui.core.model.FinishReason.wireName(): String = when (this) {
    dev.chatui.core.model.FinishReason.STOP -> "stop"
    dev.chatui.core.model.FinishReason.LENGTH -> "length"
    dev.chatui.core.model.FinishReason.CONTENT_FILTER -> "content_filter"
    dev.chatui.core.model.FinishReason.TOOL_CALLS -> "tool_calls"
    dev.chatui.core.model.FinishReason.CANCELLED -> "cancelled"
    dev.chatui.core.model.FinishReason.ERROR -> "error"
    dev.chatui.core.model.FinishReason.UNKNOWN -> "unknown"
}
