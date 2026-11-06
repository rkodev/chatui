package dev.chatui.core.network

import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Usage
import java.io.IOException
import java.io.Reader
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer

/** Namespace that owns Ollama-only advanced JSON. */
const val OLLAMA_EXTRA_NAMESPACE: String = "ollama"

/** Ollama's supported sampling controls for the phase-1 surface. */
val DEFAULT_OLLAMA_SUPPORTED_PARAMETERS: List<String> = listOf(
    "temperature",
    "top_p",
    "max_tokens",
    "stop",
    "stream",
)

/**
 * Android cleartext guidance for local Ollama servers.
 *
 * Android blocks cleartext HTTP by default. A local `http://localhost:11434`
 * endpoint therefore needs a network security config that permits cleartext
 * only for `localhost` and private-range hosts, plus an explicit user warning.
 * The adapter never weakens TLS validation globally.
 */
const val OLLAMA_CLEARTEXT_GUIDANCE: String =
    "Ollama uses a local http:// endpoint. Android blocks cleartext HTTP by " +
        "default; allow localhost and private-range hosts in a network security " +
        "config and warn the user before enabling it."

/** Default maximum number of characters retained for an incomplete NDJSON line. */
const val DEFAULT_MAX_NDJSON_BUFFER_CHARS: Int = 1024 * 1024

private const val OLLAMA_PRESET_ID = "ollama"
private const val DEFAULT_MAX_ERROR_BODY_CHARS = 64 * 1024

private val OLLAMA_PROTECTED_FIELDS: List<String> = listOf(
    "model",
    "messages",
    "stream",
    "stream_options",
)

private val OLLAMA_CONTROL_FIELDS: List<String> = listOf("transport", "mode")

private val OLLAMA_CONTEXT_PATTERN = Regex(
    "context length|context_length|too many tokens|too long",
    RegexOption.IGNORE_CASE,
)

private val OLLAMA_CONTENT_FILTER_PATTERN = Regex(
    "moderation|policy|content|safety",
    RegexOption.IGNORE_CASE,
)

private fun defaultOllamaPreset(): ProviderPreset =
    ProviderRegistry.load().getPreset(OLLAMA_PRESET_ID)
        ?: error("Ollama preset is missing from contracts/providers.json")

/**
 * Ollama's native API lives at the server root, while the contract preset's
 * base URL points at the OpenAI-compatible `/v1` prefix.
 */
fun ollamaNativeBaseUrl(baseUrl: String): String =
    baseUrl.trimEnd('/').replace(Regex("/v1$", RegexOption.IGNORE_CASE), "")

/** Build the native `POST /api/chat` URL. */
fun buildOllamaChatUrl(baseUrl: String): String =
    joinUrl(ollamaNativeBaseUrl(baseUrl), "api/chat")

/** Build the native `GET /api/tags` URL. */
fun buildOllamaTagsUrl(baseUrl: String): String =
    joinUrl(ollamaNativeBaseUrl(baseUrl), "api/tags")

/** Build native Ollama request headers. */
internal fun buildOllamaHeaders(
    context: ProviderContext,
    preset: ProviderPreset,
    accept: String = "application/x-ndjson",
): Headers {
    val headers = linkedMapOf<String, String>()
    headers["Accept"] = accept
    headers["Content-Type"] = "application/json"

    preset.defaultHeaders.forEach { (name, value) -> headers[name] = value }
    context.provider.headers.forEach { (name, value) -> headers[name] = value }

    val apiKey = context.apiKey?.takeIf { it.isNotBlank() }
    if (apiKey != null) {
        when (preset.auth.type) {
            ProviderAuthType.BEARER -> headers[preset.auth.headerName ?: "Authorization"] =
                "${preset.auth.scheme ?: "Bearer"} $apiKey"
            ProviderAuthType.X_API_KEY -> headers[preset.auth.headerName ?: "x-api-key"] = apiKey
            ProviderAuthType.QUERY_KEY -> throw ProviderRequestException(
                code = ChatErrorCode.INVALID_REQUEST,
                message = "API keys are never placed in URLs. Use a header-based auth style.",
            )
            ProviderAuthType.NONE -> Unit
        }
    }

    return headers.toHeaders()
}

private fun decodeBase64Utf8(dataBase64: String): String =
    String(Base64.getDecoder().decode(dataBase64), Charsets.UTF_8)

private fun ollamaRoleName(role: NormalizedRole): String = when (role) {
    NormalizedRole.USER -> "user"
    NormalizedRole.ASSISTANT -> "assistant"
    NormalizedRole.TOOL -> "tool"
}

private fun missingOllamaAttachment(attachmentId: String): ProviderRequestException =
    ProviderRequestException(
        code = ChatErrorCode.INVALID_REQUEST,
        message = "Missing attachment $attachmentId for the Ollama request.",
    )

private fun ollamaMessageContent(
    request: NormalizedChatRequest,
    message: NormalizedMessage,
): Pair<String, List<String>> {
    val textParts = mutableListOf<String>()
    val images = mutableListOf<String>()

    message.parts.forEach { part ->
        when (part) {
            is ContentPart.Text -> {
                if (part.text.isNotEmpty()) {
                    textParts += part.text
                }
            }

            is ContentPart.Image -> {
                val attachment = request.attachments[part.attachmentId]
                    ?: throw missingOllamaAttachment(part.attachmentId)
                images += attachment.dataBase64
            }

            is ContentPart.File -> {
                val attachment = request.attachments[part.attachmentId]
                    ?: throw missingOllamaAttachment(part.attachmentId)
                val name = attachment.name ?: part.name
                val mimeType = attachment.mimeType.ifEmpty { part.mimeType }
                val body = if (mimeType.startsWith("text/", ignoreCase = true)) {
                    decodeBase64Utf8(attachment.dataBase64)
                } else {
                    ""
                }
                textParts += "\n\n[File: $name]\n$body"
            }

            else -> Unit
        }
    }

    return textParts.joinToString("") to images
}

/**
 * Build the exact native Ollama `POST /api/chat` body.
 *
 * The system prompt is the first message. Text and text-file parts become the
 * message `content`; images become the message `images` array. Generation
 * controls live under `options`, and advanced JSON under `params.extra.ollama`
 * may not override protected fields or the transport selector.
 */
internal fun buildOllamaChatRequestBody(
    request: NormalizedChatRequest,
    supportedParameters: List<String> = DEFAULT_OLLAMA_SUPPORTED_PARAMETERS,
): JsonObject {
    val supported = supportedParameters.toSet()
    val extra = request.params.extra[OLLAMA_EXTRA_NAMESPACE] as? JsonObject
    val protectedFields = OLLAMA_PROTECTED_FIELDS
        .filter { extra?.containsKey(it) == true }
        .sorted()

    if (protectedFields.isNotEmpty()) {
        throw ProviderRequestException(
            code = ChatErrorCode.INVALID_REQUEST,
            message = "Advanced JSON cannot override protected fields: " +
                "${protectedFields.joinToString(", ")}.",
            details = buildJsonObject {
                put(
                    "protectedFields",
                    buildJsonArray { protectedFields.forEach { add(JsonPrimitive(it)) } },
                )
            },
        )
    }

    val messages = buildJsonArray {
        request.system?.let { system ->
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", system)
                },
            )
        }

        request.messages.forEach { message ->
            val (content, images) = ollamaMessageContent(request, message)
            add(
                buildJsonObject {
                    put("role", ollamaRoleName(message.role))
                    put("content", content)
                    if (images.isNotEmpty()) {
                        put(
                            "images",
                            buildJsonArray { images.forEach { add(JsonPrimitive(it)) } },
                        )
                    }
                },
            )
        }
    }

    val options = buildJsonObject {
        request.params.maxOutputTokens
            ?.takeIf { "max_tokens" in supported }
            ?.let { put("num_predict", it) }
        request.params.temperature
            ?.takeIf { "temperature" in supported }
            ?.let { put("temperature", it) }
        request.params.topP
            ?.takeIf { "top_p" in supported }
            ?.let { put("top_p", it) }
        request.params.stop
            .takeIf { it.isNotEmpty() && "stop" in supported }
            ?.let { stops ->
                put("stop", buildJsonArray { stops.forEach { add(JsonPrimitive(it)) } })
            }
    }

    return buildJsonObject {
        put("model", request.model)
        put("messages", messages)
        put("stream", true)
        if (options.isNotEmpty()) {
            put("options", options)
        }

        extra?.forEach { (field, value) ->
            if (field !in OLLAMA_PROTECTED_FIELDS && field !in OLLAMA_CONTROL_FIELDS) {
                put(field, value)
            }
        }
    }
}

/** Transport selected for one Ollama chat request. */
enum class OllamaTransport {
    NATIVE,
    OPENAI_COMPATIBLE,
}

/**
 * Native mode is the default for the Ollama preset. The OpenAI-compatible
 * fallback is selected deterministically when the preset is not Ollama (for
 * example a Custom local server) or when the request sets
 * `params.extra.ollama.transport` to `openai-compatible`.
 */
fun selectOllamaTransport(
    presetId: String,
    request: NormalizedChatRequest,
): OllamaTransport {
    val extra = request.params.extra[OLLAMA_EXTRA_NAMESPACE] as? JsonObject
    val requested = (extra?.get("transport") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: (extra?.get("mode") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    return when (requested) {
        "native" -> OllamaTransport.NATIVE
        "openai-compatible" -> OllamaTransport.OPENAI_COMPATIBLE
        else -> if (presetId == OLLAMA_PRESET_ID) {
            OllamaTransport.NATIVE
        } else {
            OllamaTransport.OPENAI_COMPATIBLE
        }
    }
}

private fun withoutTransportControl(request: NormalizedChatRequest): NormalizedChatRequest {
    val extra = request.params.extra
    val ollama = extra[OLLAMA_EXTRA_NAMESPACE] as? JsonObject ?: return request

    if (OLLAMA_CONTROL_FIELDS.none { it in ollama }) {
        return request
    }

    val nextOllama = JsonObject(ollama.filterKeys { it !in OLLAMA_CONTROL_FIELDS })
    return request.copy(
        params = request.params.copy(
            extra = JsonObject(extra + (OLLAMA_EXTRA_NAMESPACE to nextOllama)),
        ),
    )
}

/** Map an Ollama `done_reason` to the normalized reason. */
fun mapOllamaFinishReason(reason: JsonElement?): FinishReason {
    val value = (reason as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (value) {
        "stop" -> FinishReason.STOP
        "length" -> FinishReason.LENGTH
        "content_filter" -> FinishReason.CONTENT_FILTER
        "tool_calls" -> FinishReason.TOOL_CALLS
        else -> FinishReason.UNKNOWN
    }
}

/**
 * Map one Ollama `/api/tags` entry.
 *
 * Ollama reports `name` and `model` but no normalized capability metadata.
 * Missing fields fall back to the shared Ollama preset defaults. Embedding
 * models are marked hidden so they never become the default chat model.
 */
fun mapOllamaModel(
    raw: JsonElement?,
    defaults: OpenAICatalogDefaults = catalogDefaultsForPreset(OLLAMA_PRESET_ID),
): ModelInfo? {
    val obj = raw as? JsonObject ?: return null

    val model = (obj["model"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        .orEmpty()
    val name = (obj["name"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        .orEmpty()
    val remoteId = if (model.isNotEmpty()) model else name
    if (remoteId.isEmpty()) return null

    val inferred = inferOpenAIModelMetadata(obj, defaults)

    return ModelInfo(
        remoteId = remoteId,
        label = remoteId,
        contextWindow = inferred.contextWindow,
        maxOutputTokens = inferred.maxOutputTokens,
        capabilities = inferred.capabilities,
        pricing = null,
        canonicalSlug = null,
        inputModalities = inferred.inputModalities,
        outputModalities = inferred.outputModalities,
        supportedParameters = inferred.supportedParameters,
        metadata = null,
        isFree = false,
        hidden = remoteId.contains("embed", ignoreCase = true),
    )
}

/** Map an Ollama `/api/tags` envelope to normalized entries. */
fun mapOllamaModels(
    payload: JsonElement?,
    defaults: OpenAICatalogDefaults = catalogDefaultsForPreset(OLLAMA_PRESET_ID),
): List<ModelInfo> {
    val entries = when {
        payload is JsonArray -> payload
        payload is JsonObject && payload["models"] is JsonArray ->
            payload.getValue("models") as JsonArray
        else -> throw OpenRouterCatalogException(
            "Ollama /api/tags response must contain a models array",
        )
    }

    return entries.mapNotNull { mapOllamaModel(it, defaults) }
}

/**
 * Ollama native chat, `/api/tags` discovery, and OpenAI-compatible fallback.
 *
 * Native mode uses `POST /api/chat` with NDJSON lines and `GET /api/tags` for
 * discovery. The OpenAI-compatible path at `/v1/chat/completions` remains
 * available for custom local servers and for requests that explicitly opt in.
 *
 * [streamChat] emits normalized events and never throws for provider, network,
 * or timeout failures. Coroutine cancellation cancels the underlying OkHttp
 * call.
 */
class OllamaAdapter(
    private val client: OkHttpClient,
    private val preset: ProviderPreset = defaultOllamaPreset(),
    private val cache: CatalogCache = getDefaultCatalogCache(),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryPolicy: OpenRouterRetryPolicy = OpenRouterRetryPolicy(),
    private val inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val supportedParameters: List<String> = DEFAULT_OLLAMA_SUPPORTED_PARAMETERS,
) : CatalogAdapter {
    override val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE

    private val streamClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val streamer = OllamaStreamer(
        preset = preset,
        client = streamClient,
        now = now,
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = random,
        sleep = sleep,
    )

    private val fallback = OpenAICompatibleAdapter(
        preset = OpenAICompatiblePreset.fromPreset(preset),
        client = client,
        cache = cache,
        now = now,
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = random,
        sleep = sleep,
        supportedParameters = supportedParameters,
    )

    /**
     * Ollama has no key-introspection endpoint and the preset uses no auth, so
     * validation is a no-op. Discovery is the real reachability check.
     */
    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo =
        ProviderKeyInfo()

    /**
     * Load the Ollama catalog from `GET /api/tags` with the preset cache TTL and
     * last-known-good fallback.
     */
    override suspend fun loadCatalog(
        context: ProviderContext,
        forceRefresh: Boolean,
    ): CatalogResult {
        val cacheKey = catalogCacheKey(context.provider.id)

        val cached = try {
            cache.read(cacheKey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

        val ttlMs = preset.modelDiscovery.cacheTtlSeconds * 1_000L

        if (cached != null && !forceRefresh && isCatalogFresh(cached, now(), ttlMs)) {
            return CatalogResult(
                models = cached.models,
                fetchedAt = cached.fetchedAt,
                fromCache = true,
                stale = false,
                refreshError = null,
            )
        }

        try {
            val models = fetchModels(context)
            val snapshot = CatalogSnapshot(fetchedAt = now(), models = models)

            try {
                cache.write(cacheKey, snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A cache write failure must not discard a successful refresh.
            }

            return CatalogResult(
                models = models,
                fetchedAt = snapshot.fetchedAt,
                fromCache = false,
                stale = false,
                refreshError = null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (cached != null) {
                return CatalogResult(
                    models = cached.models,
                    fetchedAt = cached.fetchedAt,
                    fromCache = true,
                    stale = true,
                    refreshError = error,
                )
            }

            throw error
        }
    }

    override suspend fun listModels(context: ProviderContext): List<ModelInfo> =
        loadCatalog(context).models

    override suspend fun refreshCatalog(context: ProviderContext): CatalogResult =
        loadCatalog(context, forceRefresh = true)

    override fun streamChat(
        context: ProviderContext,
        request: NormalizedChatRequest,
    ): Flow<StreamEvent> = streamChat(context, request, supportedParameters)

    /** Stream with the model's catalog-supported parameters. */
    fun streamChat(
        context: ProviderContext,
        request: NormalizedChatRequest,
        supportedParameters: List<String>,
    ): Flow<StreamEvent> = when (
        selectOllamaTransport(context.provider.presetId, request)
    ) {
        OllamaTransport.NATIVE ->
            streamer.stream(context, request, supportedParameters)
        OllamaTransport.OPENAI_COMPATIBLE ->
            fallback.streamChat(context, withoutTransportControl(request), supportedParameters)
    }

    private suspend fun fetchModels(context: ProviderContext): List<ModelInfo> {
        val payload = requestJson(context, buildOllamaTagsUrl(context.provider.baseUrl))

        return try {
            mapOllamaModels(payload)
        } catch (error: OpenRouterCatalogException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PARSE,
                message = "Ollama /api/tags response was not valid.",
                providerId = context.provider.id,
                cause = error,
            )
        }
    }

    private suspend fun requestJson(
        context: ProviderContext,
        path: String,
    ): JsonElement {
        val request = buildJsonRequest(context, path)
        val call = client.newCall(request)

        val response = try {
            execute(call)
        } catch (error: IOException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = "Could not reach Ollama. Check that the server is running and the base URL is correct.",
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        }

        return withContext(Dispatchers.IO) {
            response.use { result ->
                val body = result.body.string()

                if (!result.isSuccessful) {
                    throw mapOllamaHttpError(
                        context = context,
                        status = result.code,
                        headers = result.headers,
                        body = body,
                        nowMillis = now(),
                    )
                }

                try {
                    Json.parseToJsonElement(body)
                } catch (error: Exception) {
                    throw ProviderRequestException(
                        code = ChatErrorCode.PARSE,
                        message = "Ollama returned invalid JSON.",
                        providerId = context.provider.id,
                        status = result.code,
                        cause = error,
                    )
                }
            }
        }
    }

    private fun buildJsonRequest(context: ProviderContext, path: String): Request {
        val builder = Request.Builder()
            .url(joinUrl(context.provider.baseUrl, path))
            .get()

        buildOllamaHeaders(context, preset, accept = "application/json")
            .forEach { (name, value) -> builder.header(name, value) }

        return builder.build()
    }

    private suspend fun execute(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) {
                            return
                        }
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isCancelled) {
                            response.close()
                            return
                        }
                        continuation.resume(response)
                    }
                },
            )
        }
}

/**
 * Stream a native Ollama `/api/chat` completion as normalized events.
 *
 * Events follow the shared order: `start`, text/reasoning deltas, `finish`,
 * `usage`, or a single terminal `error`. Retries happen only before any text or
 * reasoning delta has been emitted.
 */
internal class OllamaStreamer(
    private val preset: ProviderPreset,
    client: OkHttpClient,
    private val now: () -> Long,
    private val retryPolicy: OpenRouterRetryPolicy,
    private val inactivityTimeoutMillis: Long,
    private val random: Random,
    private val sleep: suspend (Long) -> Unit,
) {
    private val transport = NdjsonTransport(client)

    fun stream(
        context: ProviderContext,
        request: NormalizedChatRequest,
        supportedParameters: List<String>,
    ): Flow<StreamEvent> = flow {
        val body = try {
            buildOllamaChatRequestBody(request, supportedParameters)
        } catch (error: ProviderRequestException) {
            val normalized = if (error.providerId == null) {
                error.withProviderId(context.provider.id)
            } else {
                error
            }
            emit(StreamEvent.ErrorEvent(normalized.toChatError(nowInstant())))
            return@flow
        }

        var startEmitted = false
        var receivedOutput = false

        suspend fun emitEvent(event: StreamEvent) {
            if (event is StreamEvent.Start) {
                if (startEmitted) return
                startEmitted = true
            }

            if (event is StreamEvent.TextDelta || event is StreamEvent.ReasoningDelta) {
                receivedOutput = true
            }

            emit(event)
        }

        for (attempt in 1..retryPolicy.maxAttempts) {
            currentCoroutineContext().ensureActive()

            try {
                val error = attemptStream(context, body, ::emitEvent)
                if (error != null) {
                    emit(StreamEvent.ErrorEvent(error.toChatError(nowInstant())))
                }
                return@flow
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                emit(
                    StreamEvent.ErrorEvent(
                        cancelledOllamaError(context).toChatError(nowInstant()),
                    ),
                )
                return@flow
            } catch (thrown: Throwable) {
                val normalized = normalizeOllamaThrown(thrown, context, receivedOutput)

                if (normalized.code == ChatErrorCode.CANCELLED) {
                    emit(StreamEvent.ErrorEvent(normalized.toChatError(nowInstant())))
                    return@flow
                }

                val canRetry = normalized.retryable &&
                    !receivedOutput &&
                    attempt < retryPolicy.maxAttempts

                if (!canRetry) {
                    emit(StreamEvent.ErrorEvent(normalized.toChatError(nowInstant())))
                    return@flow
                }

                val delayMillis = retryPolicy.nextDelayMillis(
                    attemptsMade = attempt,
                    retryAfterSeconds = normalized.retryAfterSeconds?.toLong(),
                    random = random,
                ) ?: 0L
                sleep(delayMillis)
            }
        }
    }

    private suspend fun attemptStream(
        context: ProviderContext,
        body: JsonObject,
        emit: suspend (StreamEvent) -> Unit,
    ): ProviderRequestException? {
        val httpRequest = buildStreamRequest(context, body)
        val mapper = OllamaStreamMapper(context, emit)

        try {
            collectWithWatchdog(context, transport.stream(httpRequest)) { line ->
                mapper.handleLine(line)
            }
        } catch (error: NdjsonHttpException) {
            throw mapOllamaHttpError(
                context = context,
                status = error.code,
                headers = error.headers,
                body = error.body,
                nowMillis = now(),
            )
        } catch (error: NdjsonBufferOverflowException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PROVIDER,
                message = "The Ollama stream exceeded the buffer limit.",
                providerId = context.provider.id,
                cause = error,
            )
        } catch (error: NdjsonReadException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = "The Ollama stream was interrupted.",
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        } catch (error: NdjsonTransportException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = "The Ollama stream was interrupted.",
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        }

        mapper.finish()
        return mapper.error
    }

    private fun buildStreamRequest(
        context: ProviderContext,
        body: JsonObject,
    ): Request {
        val builder = Request.Builder()
            .url(buildOllamaChatUrl(context.provider.baseUrl))
            .post(body.toString().toRequestBody(null))

        buildOllamaHeaders(context, preset, accept = "application/x-ndjson")
            .forEach { (name, value) -> builder.header(name, value) }

        return builder.build()
    }

    private suspend fun collectWithWatchdog(
        context: ProviderContext,
        flow: Flow<String>,
        onLine: suspend (String) -> Unit,
    ) {
        coroutineScope {
            val channel = flow.produceIn(this)

            try {
                while (true) {
                    val result = try {
                        withTimeout(inactivityTimeoutMillis) { channel.receiveCatching() }
                    } catch (timeout: TimeoutCancellationException) {
                        channel.cancel()
                        throw ProviderRequestException(
                            code = ChatErrorCode.TIMEOUT,
                            message = "Ollama stopped responding.",
                            providerId = context.provider.id,
                            retryable = true,
                        )
                    }

                    if (result.isClosed) {
                        result.exceptionOrNull()?.let { throw it }
                        break
                    }

                    onLine(result.getOrThrow())
                }
            } finally {
                channel.cancel()
            }
        }
    }

    private fun nowInstant(): java.time.Instant =
        java.time.Instant.ofEpochMilli(now())
}

/** Maps raw Ollama NDJSON lines into normalized events. */
internal class OllamaStreamMapper(
    private val context: ProviderContext,
    private val emit: suspend (StreamEvent) -> Unit,
) {
    var error: ProviderRequestException? = null
        private set

    private var started = false
    private var finished = false
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null
    private var usageEmitted = false

    suspend fun handleLine(line: String) {
        if (error != null || line.isBlank()) return

        val element = try {
            Json.parseToJsonElement(line)
        } catch (_: Exception) {
            null
        }

        if (element == null) {
            error = ProviderRequestException(
                code = ChatErrorCode.PARSE,
                message = "The Ollama stream contained invalid JSON.",
                providerId = context.provider.id,
            )
            return
        }

        val payload = element as? JsonObject ?: return

        val errorElement = payload["error"]
        if (errorElement != null && errorElement !is JsonNull) {
            error = ollamaStreamError(payload, context)
            return
        }

        if (!started) {
            started = true
            emit(StreamEvent.Start(null))
        }

        val message = payload["message"] as? JsonObject
        if (message != null) {
            val content = (message["content"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                .orEmpty()
            if (content.isNotEmpty()) {
                emit(StreamEvent.TextDelta(content))
            }

            val thinking = (message["thinking"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                .orEmpty()
            if (thinking.isNotEmpty()) {
                emit(StreamEvent.ReasoningDelta(thinking))
            }
        }

        (payload["prompt_eval_count"] as? JsonPrimitive)?.intOrNull?.let { inputTokens = it }
        (payload["eval_count"] as? JsonPrimitive)?.intOrNull?.let { outputTokens = it }

        val done = (payload["done"] as? JsonPrimitive)?.booleanOrNull == true
        if (done) {
            val reasonElement = payload["done_reason"]
            val reason = if ((reasonElement as? JsonPrimitive)?.isString == true) {
                mapOllamaFinishReason(reasonElement)
            } else {
                FinishReason.STOP
            }
            emitFinish(reason)
            emitUsage()
        }
    }

    suspend fun finish() {
        if (error != null || !started || finished) return
        emitFinish(FinishReason.UNKNOWN)
        emitUsage()
    }

    private fun usage(): Usage? {
        if (inputTokens == null && outputTokens == null) return null

        val total = if (inputTokens != null && outputTokens != null) {
            inputTokens!! + outputTokens!!
        } else {
            null
        }

        return Usage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = total,
            costUsd = null,
            estimated = false,
        )
    }

    private suspend fun emitUsage() {
        if (usageEmitted) return
        val usage = usage() ?: return
        usageEmitted = true
        emit(StreamEvent.UsageEvent(usage))
    }

    private suspend fun emitFinish(reason: FinishReason) {
        if (finished) return
        emit(StreamEvent.Finish(reason))
        finished = true
    }
}

private fun ollamaStatusToCode(status: Int, message: String): ChatErrorCode = when {
    status == 401 -> ChatErrorCode.AUTH
    status == 402 -> ChatErrorCode.QUOTA
    status == 403 -> if (OLLAMA_CONTENT_FILTER_PATTERN.containsMatchIn(message)) {
        ChatErrorCode.CONTENT_FILTER
    } else {
        ChatErrorCode.PERMISSION
    }
    status == 404 -> ChatErrorCode.MODEL_NOT_FOUND
    status == 413 -> ChatErrorCode.CONTEXT_LENGTH
    status == 429 -> ChatErrorCode.RATE_LIMIT
    status == 400 -> if (OLLAMA_CONTEXT_PATTERN.containsMatchIn(message)) {
        ChatErrorCode.CONTEXT_LENGTH
    } else {
        ChatErrorCode.INVALID_REQUEST
    }
    else -> ChatErrorCode.PROVIDER
}

private fun ollamaErrorMessage(payload: JsonObject?): String? {
    if (payload == null) return null

    val error = payload["error"]
    if (error is JsonPrimitive && error.isString && error.content.trim().isNotEmpty()) {
        return error.content
    }
    if (error is JsonObject) {
        val message = (error["message"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        if (!message.isNullOrBlank()) return message
    }

    val message = (payload["message"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
    return message?.takeIf { it.trim().isNotEmpty() }
}

/** Map an Ollama HTTP error response to a normalized provider failure. */
internal fun mapOllamaHttpError(
    context: ProviderContext,
    status: Int,
    headers: Headers,
    body: String,
    nowMillis: Long,
): ProviderRequestException {
    val payload = parseOllamaJsonObject(body)
    val rawMessage = ollamaErrorMessage(payload)
        ?: "The Ollama request failed with HTTP $status"
    val message = redactSecret(rawMessage, context.apiKey)
    val retryAfterSeconds = parseRetryAfterSeconds(
        headers["Retry-After"],
        java.time.Instant.ofEpochMilli(nowMillis),
    )
    val requestId = headers["x-request-id"]

    val details = retryAfterSeconds?.let { seconds ->
        buildJsonObject {
            put(
                "retryAfterSeconds",
                if (seconds % 1.0 == 0.0) seconds.toLong() else seconds,
            )
        }
    }

    return ProviderRequestException(
        code = ollamaStatusToCode(status, message),
        message = message,
        providerId = context.provider.id,
        status = status,
        retryable = isRetryableStatus(status),
        retryAfterSeconds = retryAfterSeconds,
        requestId = requestId,
        details = details,
    )
}

private fun ollamaStreamError(
    payload: JsonObject,
    context: ProviderContext,
): ProviderRequestException {
    val rawMessage = ollamaErrorMessage(payload) ?: "The Ollama stream returned an error."
    val message = redactSecret(rawMessage, context.apiKey)
    val status = (payload["status"] as? JsonPrimitive)?.intOrNull ?: 500

    return ProviderRequestException(
        code = ollamaStatusToCode(status, message),
        message = message,
        providerId = context.provider.id,
        status = status,
        retryable = false,
    )
}

private fun parseOllamaJsonObject(body: String): JsonObject? {
    if (body.isBlank()) return null
    return try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }
}

private fun cancelledOllamaError(context: ProviderContext): ProviderRequestException =
    ProviderRequestException(
        code = ChatErrorCode.CANCELLED,
        message = "The Ollama request was cancelled.",
        providerId = context.provider.id,
    )

private fun normalizeOllamaThrown(
    error: Throwable,
    context: ProviderContext,
    receivedOutput: Boolean,
): ProviderRequestException {
    if (error is ProviderRequestException) {
        if (receivedOutput &&
            (error.code == ChatErrorCode.NETWORK || error.code == ChatErrorCode.TIMEOUT)
        ) {
            return error.withRetryable(false)
        }
        return error
    }

    return ProviderRequestException(
        code = ChatErrorCode.UNKNOWN,
        message = "The Ollama request failed.",
        providerId = context.provider.id,
        cause = error,
    )
}

// ---------------------------------------------------------------------------
// NDJSON transport
// ---------------------------------------------------------------------------

/** Base class for normalized NDJSON transport failures. */
sealed class NdjsonTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** An incomplete line grew past the configured buffer limit. */
class NdjsonBufferOverflowException(
    val maxBufferChars: Int,
) : NdjsonTransportException("NDJSON buffer exceeded the $maxBufferChars character limit")

/** The server returned a non-success HTTP status. */
class NdjsonHttpException(
    val code: Int,
    val headers: Headers,
    val body: String,
) : NdjsonTransportException("NDJSON request failed with HTTP $code")

/** The response could not be opened or read. */
class NdjsonReadException(
    message: String,
    cause: Throwable,
) : NdjsonTransportException(message, cause)

/**
 * Provider-agnostic NDJSON transport built on OkHttp.
 *
 * The transport only understands line framing. Every complete non-empty line is
 * emitted as a raw string; adapters interpret the JSON. The final partial line
 * is flushed when the stream ends without a trailing newline.
 */
class NdjsonTransport(
    private val client: OkHttpClient,
    private val maxBufferChars: Int = DEFAULT_MAX_NDJSON_BUFFER_CHARS,
) {
    init {
        require(maxBufferChars > 0) { "maxBufferChars must be positive" }
    }

    fun stream(request: Request): Flow<String> = callbackFlow {
        val call = client.newCall(request)
        val completed = AtomicBoolean(false)

        val readerJob = launch(Dispatchers.IO) {
            try {
                val response = try {
                    call.execute()
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw NdjsonReadException("Failed to open the NDJSON stream", error)
                }

                response.use {
                    if (!it.isSuccessful) {
                        throw NdjsonHttpException(
                            code = it.code,
                            headers = it.headers,
                            body = readBoundedOllamaBody(it.body, DEFAULT_MAX_ERROR_BODY_CHARS),
                        )
                    }

                    try {
                        NdjsonParser(maxBufferChars).read(it.body.charStream()) { line ->
                            send(line)
                        }
                    } catch (error: NdjsonTransportException) {
                        throw error
                    } catch (error: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw NdjsonReadException("Failed to read the NDJSON stream", error)
                    }
                }

                completed.set(true)
                close()
            } catch (error: Throwable) {
                close(error)
            }
        }

        awaitClose {
            if (!completed.get()) {
                call.cancel()
            }
            readerJob.cancel()
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * NDJSON line parser.
 *
 * Line endings are normalized before splitting, so CRLF, LF, and lone CR all
 * work even when a CRLF pair is split across reads. Blank lines are ignored and
 * the final partial line is flushed when the stream ends.
 */
internal class NdjsonParser(
    private val maxBufferChars: Int = DEFAULT_MAX_NDJSON_BUFFER_CHARS,
) {
    init {
        require(maxBufferChars > 0) { "maxBufferChars must be positive" }
    }

    suspend fun read(reader: Reader, onLine: suspend (String) -> Unit) {
        val lineReader = BoundedLineReader(reader, maxBufferChars)

        while (true) {
            val line = try {
                lineReader.readLine()
            } catch (error: SseBufferOverflowException) {
                throw NdjsonBufferOverflowException(maxBufferChars)
            } ?: break

            if (line.isNotBlank()) {
                onLine(line)
            }
        }
    }
}

private fun readBoundedOllamaBody(body: ResponseBody, maxChars: Int): String {
    val source = body.source()
    val buffer = Buffer()
    val builder = StringBuilder()

    while (builder.length < maxChars) {
        val remaining = (maxChars - builder.length).toLong()
        val read = source.read(buffer, minOf(8192L, remaining))
        if (read == -1L) {
            break
        }
        builder.append(buffer.readByteArray().toString(Charsets.UTF_8))
    }

    return builder.toString()
}
