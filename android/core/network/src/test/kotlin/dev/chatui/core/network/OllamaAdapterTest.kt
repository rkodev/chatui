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
import java.io.StringReader
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

class OllamaAdapterTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val at: Instant = Instant.parse("2026-11-05T12:34:56Z")

    private val preset: ProviderPreset by lazy {
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
    fun `serializes the basic request vector exactly`() {
        val vector = requestVector("ollama-basic")

        val body = buildOllamaChatRequestBody(
            decodeRequest(vector),
            supportedParameters(vector),
        )

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `serializes images into the message images array and files into content`() {
        val vector = requestVector("ollama-attachments")
        val request = decodeRequest(vector).copy(
            attachments = Json.decodeFromJsonElement(
                MapSerializer(String.serializer(), ResolvedAttachment.serializer()),
                vector["attachments"]!!,
            ),
        )

        val body = buildOllamaChatRequestBody(request, supportedParameters(vector))

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `round-trips safe advanced JSON under the ollama namespace`() {
        val vector = requestVector("ollama-extras")

        val body = buildOllamaChatRequestBody(
            decodeRequest(vector),
            supportedParameters(vector),
        )

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `rejects protected fields before sending`(): Unit = runBlocking {
        val vector = requestVector("ollama-protected")
        val expectedError = vector["expected"]!!.jsonObject["error"]!!.jsonObject

        val events = adapter().streamChat(context(), decodeRequest(vector)).toList()

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(events).hasSize(1)
        val error = (events.single() as StreamEvent.ErrorEvent).error
        assertThat(error).isEqualTo(decodeExpectedError(expectedError))
    }

    @Test
    fun `does not leak the transport control field into either body`() {
        val request = baseRequest(
            params = baseRequest().params.copy(
                extra = Json.parseToJsonElement(
                    """{"ollama":{"transport":"openai-compatible","keep_alive":"5m"}}""",
                ).jsonObject,
            ),
        )

        val body = buildOllamaChatRequestBody(request, listOf("stream"))

        assertThat(body.containsKey("transport")).isFalse()
        assertThat(body["keep_alive"]!!.jsonPrimitive.content).isEqualTo("5m")
    }

    @Test
    fun `uses native NDJSON for the Ollama preset by default`() {
        assertThat(selectOllamaTransport("ollama", baseRequest()))
            .isEqualTo(OllamaTransport.NATIVE)
    }

    @Test
    fun `falls back to OpenAI-compatible for a Custom local server preset`() {
        assertThat(selectOllamaTransport("custom", baseRequest()))
            .isEqualTo(OllamaTransport.OPENAI_COMPATIBLE)
    }

    @Test
    fun `honors an explicit per-request transport override`() {
        val fallback = baseRequest(
            params = baseRequest().params.copy(
                extra = Json.parseToJsonElement(
                    """{"ollama":{"transport":"openai-compatible"}}""",
                ).jsonObject,
            ),
        )
        val native = baseRequest(
            params = baseRequest().params.copy(
                extra = Json.parseToJsonElement(
                    """{"ollama":{"transport":"native"}}""",
                ).jsonObject,
            ),
        )

        assertThat(selectOllamaTransport("ollama", fallback))
            .isEqualTo(OllamaTransport.OPENAI_COMPATIBLE)
        assertThat(selectOllamaTransport("custom", native))
            .isEqualTo(OllamaTransport.NATIVE)
    }

    @Test
    fun `builds the native URL at the server root, not the v1 prefix`() {
        assertThat(buildOllamaChatUrl("http://localhost:11434/v1"))
            .isEqualTo("http://localhost:11434/api/chat")
        assertThat(buildOllamaChatUrl("http://localhost:11434/v1/"))
            .isEqualTo("http://localhost:11434/api/chat")
        assertThat(buildOllamaChatUrl("http://localhost:11434"))
            .isEqualTo("http://localhost:11434/api/chat")
        assertThat(buildOllamaTagsUrl("http://localhost:11434/v1"))
            .isEqualTo("http://localhost:11434/api/tags")
    }

    @Test
    fun `sends NDJSON headers without an auth header for the no-auth preset`() {
        val headers = buildOllamaHeaders(context(), preset)

        assertThat(headers["Accept"]).isEqualTo("application/x-ndjson")
        assertThat(headers["Content-Type"]).isEqualTo("application/json")
        assertThat(headers["Authorization"]).isNull()
        assertThat(headers["x-api-key"]).isNull()
    }

    @Test
    fun `maps the shared Ollama tags fixture`() {
        val catalogs = Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/catalogs.json"),
        ).jsonObject["cases"]!!.jsonArray
        val case = catalogs.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "ollama" }
        val fixture = case["fixture"]!!.jsonPrimitive.content
        val expected = case["expected"]!!.jsonArray

        val models = mapOllamaModels(
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
    fun `validates a keyless endpoint without a network request`(): Unit = runBlocking {
        val info = adapter().validateKey(context())

        assertThat(info).isEqualTo(ProviderKeyInfo())
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `NDJSON parser joins partial lines and flushes the final line`(): Unit = runBlocking {
        val lines = mutableListOf<String>()

        NdjsonParser().read(
            StringReader("{\"a\":1}\n{\"b\":2}\r\n{\"c\":3}"),
        ) { lines += it }

        assertThat(lines).containsExactly("{\"a\":1}", "{\"b\":2}", "{\"c\":3}").inOrder()
    }

    @Test
    fun `NDJSON parser rejects an over-limit incomplete line`(): Unit = runBlocking {
        val error = runCatching {
            NdjsonParser(maxBufferChars = 4).read(StringReader("1234567890")) {}
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(NdjsonBufferOverflowException::class.java)
    }

    @Test
    fun `emits text finish and usage from the native NDJSON fixture`(): Unit = runBlocking {
        val expected = expectedEvents("ollama-ndjson")
        server.enqueue(ndjsonResponse(readResource("/contracts/fixtures/streams/ollama-ndjson.txt")))

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertThat(events).containsExactlyElementsIn(decodeExpectedEvents(expected)).inOrder()
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " from Ollama.")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/api/chat")
        assertThat(request.headers["Accept"]).isEqualTo("application/x-ndjson")
        assertThat(request.headers["Authorization"]).isNull()
    }

    @Test
    fun `keeps thinking content separate from answer text`(): Unit = runBlocking {
        val events = mutableListOf<StreamEvent>()
        val mapper = OllamaStreamMapper(context()) { events += it }

        mapper.handleLine(
            """{"message":{"role":"assistant","content":"Answer.","thinking":"Thinking."},"done":false}""",
        )
        mapper.handleLine(
            """{"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":2,"eval_count":1}""",
        )

        assertThat(events).containsExactly(
            StreamEvent.Start(null),
            StreamEvent.TextDelta("Answer."),
            StreamEvent.ReasoningDelta("Thinking."),
            StreamEvent.Finish(dev.chatui.core.model.FinishReason.STOP),
            StreamEvent.UsageEvent(
                dev.chatui.core.model.Usage(
                    inputTokens = 2,
                    outputTokens = 1,
                    totalTokens = 3,
                    costUsd = null,
                    estimated = false,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `maps an in-stream error line`(): Unit = runBlocking {
        val case = ollamaErrorCases()
            .map { it.jsonObject }
            .single { (it["kind"] as? JsonPrimitive)?.content == "stream" }
        val fixture = case["fixture"]!!.jsonPrimitive.content
        val expectedError = case["error"]!!.jsonObject

        server.enqueue(ndjsonResponse(readResource("/contracts/fixtures/$fixture")))

        val events = adapter(retryPolicy = OpenRouterRetryPolicy(maxAttempts = 1))
            .streamChat(context(), baseRequest())
            .toList()

        assertThat(events).hasSize(1)
        val error = (events.single() as StreamEvent.ErrorEvent).error
        assertThat(error).isEqualTo(decodeExpectedError(expectedError))
    }

    @Test
    fun `maps Ollama HTTP error fixtures`(): Unit = runBlocking {
        for (case in ollamaErrorCases()) {
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
    fun `retries a 500 before any output`(): Unit = runBlocking {
        val delays = mutableListOf<Long>()
        server.enqueue(
            jsonResponse(
                """{"error":"internal server error"}""",
                500,
            ),
        )
        server.enqueue(ndjsonResponse(readResource("/contracts/fixtures/streams/ollama-ndjson.txt")))

        val events = adapter(
            retryPolicy = OpenRouterRetryPolicy(maxAttempts = 2, jitterFraction = 0.0),
            sleep = { delays += it },
        ).streamChat(context(), baseRequest()).toList()

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(delays).containsExactly(800L)
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " from Ollama.")
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
                    .addHeader("Content-Type", "application/x-ndjson")
                    .body(
                        HangingSseBody(
                            """{"message":{"role":"assistant","content":"Hi"},"done":false}""" + "\n",
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
    fun `uses the OpenAI-compatible fallback when the request opts in`(): Unit = runBlocking {
        val sse = listOf(
            "data: {\"id\":\"chat-1\",\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
            "",
        ).joinToString("\n")
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(sse)
                .build(),
        )
        val request = baseRequest(
            params = baseRequest().params.copy(
                extra = Json.parseToJsonElement(
                    """{"ollama":{"transport":"openai-compatible"}}""",
                ).jsonObject,
            ),
        )

        val events = adapter().streamChat(context(), request).toList()

        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hi")

        val httpRequest = server.takeRequest()
        assertThat(httpRequest.url.encodedPath).isEqualTo("/v1/chat/completions")
        assertThat(httpRequest.body!!.utf8()).doesNotContain("transport")
    }

    @Test
    fun `registry routes the Ollama preset to the native adapter`() {
        val registry = ProviderAdapterRegistry.create(client)

        assertThat(registry.adapterFor(context())).isInstanceOf(OllamaAdapter::class.java)
        assertThat(registry.requireCatalogAdapter(context()))
            .isInstanceOf(OllamaAdapter::class.java)
    }

    private fun adapter(
        httpClient: OkHttpClient = client,
        retryPolicy: OpenRouterRetryPolicy = OpenRouterRetryPolicy(),
        inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
        sleep: suspend (Long) -> Unit = { _ -> },
    ): OllamaAdapter = OllamaAdapter(
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
        baseUrl: String = server.url("/v1").toString(),
    ): ProviderContext = ProviderContext(
        provider = Provider(
            id = "ollama",
            presetId = "ollama",
            label = "Ollama",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = baseUrl,
            authType = ProviderAuthType.NONE,
            secretId = null,
            headers = emptyMap(),
            enabled = true,
            browserCors = BrowserCors.UNKNOWN,
            metadata = null,
            createdAt = at,
            updatedAt = at,
        ),
        apiKey = null,
    )

    private fun baseRequest(
        model: String = "llama3.2:latest",
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

    private fun ollamaErrorCases(): List<JsonElement> =
        Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/provider-errors.json"),
        ).jsonObject["cases"]!!
            .jsonArray
            .filter { (it.jsonObject["providerId"] as? JsonPrimitive)?.content == "ollama" }

    private fun requestVector(name: String): JsonObject =
        Json.parseToJsonElement(readResource("/contracts/fixtures/requests/$name.json")).jsonObject

    private fun decodeRequest(vector: JsonObject): NormalizedChatRequest =
        Json.decodeFromJsonElement(NormalizedChatRequest.serializer(), vector["request"]!!)

    private fun supportedParameters(vector: JsonObject): List<String> =
        vector["supportedParameters"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun ndjsonResponse(body: String): MockResponse =
        MockResponse.Builder()
            .addHeader("Content-Type", "application/x-ndjson")
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
