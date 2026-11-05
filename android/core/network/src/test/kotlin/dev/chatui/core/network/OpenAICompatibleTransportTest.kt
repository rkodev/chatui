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

class OpenAICompatibleTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val at: Instant = Instant.parse("2026-11-02T12:34:56Z")

    private val openAIPreset: OpenAICompatiblePreset by lazy {
        val preset = ProviderRegistry.load().getPreset("openai")
            ?: error("OpenAI preset is missing from contracts/providers.json")
        OpenAICompatiblePreset.fromPreset(preset)
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
    fun `openai text stream matches the shared expected events`(): Unit = runBlocking {
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/openai-text.sse")))

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertEvents(events, expectedEvents("openai-text"))
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " from OpenAI.")
    }

    @Test
    fun `openai reasoning stream matches the shared expected events`(): Unit = runBlocking {
        server.enqueue(
            sseResponse(readResource("/contracts/fixtures/streams/openai-reasoning.sse")),
        )

        val events = adapter().streamChat(context(), baseRequest()).toList()

        assertEvents(events, expectedEvents("openai-reasoning"))
        assertThat(events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text })
            .containsExactly("I should answer briefly.")
    }

    @Test
    fun `openai HTTP error fixtures match the shared expected errors`(): Unit = runBlocking {
        for (case in openAIErrorCases()) {
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
    fun `openai stream error fixture matches the shared expected error`(): Unit = runBlocking {
        val case = openAIErrorCases()
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
    fun `retries a retryable HTTP error before output`(): Unit = runBlocking {
        val delays = mutableListOf<Long>()
        server.enqueue(
            jsonResponse(
                """{"error":{"message":"The server had an error.","type":"server_error"}}""",
                500,
            ),
        )
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/openai-text.sse")))

        val events = adapter(
            retryPolicy = OpenRouterRetryPolicy(maxAttempts = 2, jitterFraction = 0.0),
            sleep = { delays += it },
        ).streamChat(context(), baseRequest()).toList()

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(delays).hasSize(1)
        assertThat(events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " from OpenAI.")
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
                            "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0," +
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
    fun `serializes the openai basic request vector exactly`() {
        val vector = requestVector("openai-basic")

        val body = buildOpenAICompatibleChatRequestBody(
            decodeRequest(vector),
            openAIPreset,
            supportedParameters(vector),
        )

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `serializes openai extras exactly`() {
        val vector = requestVector("openai-extras")

        val body = buildOpenAICompatibleChatRequestBody(
            decodeRequest(vector),
            openAIPreset,
            supportedParameters(vector),
        )

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `serializes openai attachments exactly`() {
        val vector = requestVector("openai-attachments")
        val request = decodeRequest(vector).copy(
            attachments = Json.decodeFromJsonElement(
                MapSerializer(String.serializer(), ResolvedAttachment.serializer()),
                vector["attachments"]!!,
            ),
        )

        val body = buildOpenAICompatibleChatRequestBody(
            request,
            openAIPreset,
            supportedParameters(vector),
        )

        assertThat(body).isEqualTo(vector["expected"]!!.jsonObject["body"])
    }

    @Test
    fun `rejects protected openai fields before sending`(): Unit = runBlocking {
        val vector = requestVector("openai-protected")
        val request = decodeRequest(vector)
        val expectedError = vector["expected"]!!.jsonObject["error"]!!.jsonObject

        val events = adapter().streamChat(context(), request).toList()

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(events).hasSize(1)
        val error = (events.single() as StreamEvent.ErrorEvent).error
        assertThat(error).isEqualTo(decodeExpectedError(expectedError))
    }

    @Test
    fun `maps the openai catalog fixture with inferred capabilities`() {
        val catalogs = Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/catalogs.json"),
        ).jsonObject["cases"]!!.jsonArray
        val case = catalogs.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "openai" }
        val fixture = case["fixture"]!!.jsonPrimitive.content
        val expected = case["expected"]!!.jsonArray

        val models = mapOpenAIModels(
            Json.parseToJsonElement(readResource("/contracts/fixtures/$fixture")),
            catalogDefaultsForPreset("openai"),
        )

        assertThat(models).hasSize(expected.size)
        models.forEachIndexed { index, model ->
            val entry = expected[index].jsonObject
            assertThat(model.remoteId).isEqualTo(entry["remoteId"]!!.jsonPrimitive.content)
            assertThat(model.label).isEqualTo(entry["label"]!!.jsonPrimitive.content)
            assertThat(model.contextWindow).isEqualTo(entry["contextWindow"].intOrNull())
            assertThat(model.maxOutputTokens).isEqualTo(entry["maxOutputTokens"].intOrNull())
            assertThat(model.inputModalities.map { it.wireName() })
                .containsExactlyElementsIn(entry["inputModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
                .inOrder()
            assertThat(model.outputModalities.map { it.wireName() })
                .containsExactlyElementsIn(entry["outputModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
                .inOrder()
            assertThat(model.capabilities.map { it.wireName() })
                .containsExactlyElementsIn(entry["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
                .inOrder()
            assertThat(model.supportedParameters)
                .containsExactlyElementsIn(entry["supportedParameters"]!!.jsonArray.map { it.jsonPrimitive.content })
                .inOrder()
            assertThat(model.pricing).isNull()
            assertThat(model.hidden).isEqualTo(entry["hidden"]!!.jsonPrimitive.booleanOrNull)
        }
    }

    @Test
    fun `preset drives auth headers and attribution`(): Unit = runBlocking {
        server.enqueue(sseResponse(readResource("/contracts/fixtures/streams/openai-text.sse")))

        adapter().streamChat(context(), baseRequest()).toList()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v1/chat/completions")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer sk-test-key")
        assertThat(request.headers["HTTP-Referer"]).isNull()
        assertThat(request.headers["X-Title"]).isNull()
        assertThat(request.headers["Content-Type"]).isEqualTo("application/json")
        assertThat(request.headers["Accept"]).isEqualTo("text/event-stream")
        assertThat(request.url.toString()).doesNotContain("sk-test-key")
    }

    @Test
    fun `openrouter preset keeps curated extras and M1 messages`() {
        val preset = openRouterTransportPreset()

        assertThat(preset.id).isEqualTo("openrouter")
        assertThat(preset.extraNamespace).isEqualTo("openrouter")
        assertThat(preset.extraMode).isEqualTo(ExtraMode.CURATED)
        assertThat(preset.extraPassthroughFields)
            .containsExactly("provider", "models", "route", "reasoning")
        assertThat(preset.reasoningStyle).isEqualTo(ReasoningStyle.OPENROUTER)
        assertThat(preset.multimodal).isFalse()
        assertThat(preset.messages.missingKey).isEqualTo("An OpenRouter API key is required.")
        assertThat(preset.attributionHeaders)
            .containsExactly("HTTP-Referer", "app.origin", "X-Title", "app.name")
    }

    @Test
    fun `openai preset uses the generic extras and reasoning style`() {
        assertThat(openAIPreset.id).isEqualTo("openai")
        assertThat(openAIPreset.extraNamespace).isEqualTo("openai")
        assertThat(openAIPreset.extraMode).isEqualTo(ExtraMode.ALL)
        assertThat(openAIPreset.reasoningStyle).isEqualTo(ReasoningStyle.OPENAI)
        assertThat(openAIPreset.multimodal).isTrue()
        assertThat(openAIPreset.attributionHeaders).isEmpty()
    }

    private fun adapter(
        httpClient: OkHttpClient = client,
        retryPolicy: OpenRouterRetryPolicy = OpenRouterRetryPolicy(),
        inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
        sleep: suspend (Long) -> Unit = { _ -> },
    ): OpenAICompatibleAdapter = OpenAICompatibleAdapter(
        preset = openAIPreset,
        client = httpClient,
        attribution = null,
        cache = MemoryCatalogCache(),
        now = { at.toEpochMilli() },
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = Random(0),
        sleep = sleep,
    )

    private fun context(
        apiKey: String? = "sk-test-key",
        baseUrl: String = server.url("/v1").toString(),
    ): ProviderContext = ProviderContext(
        provider = Provider(
            id = "openai",
            presetId = "openai",
            label = "OpenAI",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = baseUrl,
            authType = ProviderAuthType.BEARER,
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
        model: String = "gpt-4o-mini",
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

    private fun expectedEvents(name: String): JsonArray =
        Json.parseToJsonElement(readResource("/contracts/fixtures/expected/$name.json"))
            .jsonObject["events"]!!
            .jsonArray

    private fun openAIErrorCases(): List<JsonElement> =
        Json.parseToJsonElement(
            readResource("/contracts/fixtures/expected/provider-errors.json"),
        ).jsonObject["cases"]!!
            .jsonArray
            .filter { (it.jsonObject["providerId"] as? JsonPrimitive)?.content == "openai" }

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

private fun JsonElement?.intOrNull(): Int? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
