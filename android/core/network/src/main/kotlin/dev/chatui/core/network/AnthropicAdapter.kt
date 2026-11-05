package dev.chatui.core.network

import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Usage
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** Anthropic Messages API version sent with every request. */
const val ANTHROPIC_VERSION: String = "2023-06-01"

/** Namespace that owns Anthropic-only advanced JSON. */
const val ANTHROPIC_EXTRA_NAMESPACE: String = "anthropic"

/** Anthropic's supported sampling controls for the phase-1 surface. */
val DEFAULT_ANTHROPIC_SUPPORTED_PARAMETERS: List<String> = listOf(
    "temperature",
    "top_p",
    "max_tokens",
    "stop",
    "stream",
)

private const val ANTHROPIC_PRESET_ID = "anthropic"
private const val BROWSER_ACCESS_HEADER = "anthropic-dangerous-direct-browser-access"

private val ANTHROPIC_PROTECTED_FIELDS: List<String> = listOf(
    "model",
    "messages",
    "stream",
    "stream_options",
)

private val ANTHROPIC_CONTEXT_PATTERN = Regex(
    "prompt is too long|context length|context_length|maximum context|too many tokens",
    RegexOption.IGNORE_CASE,
)

private val ANTHROPIC_MODERATION_PATTERN = Regex(
    "moderation|policy|content|safety",
    RegexOption.IGNORE_CASE,
)

private val ANTHROPIC_CONTENT_FILTER_PATTERN = Regex(
    "content filter|content_filter|content_policy|safety system|moderation",
    RegexOption.IGNORE_CASE,
)

private fun defaultAnthropicPreset(): ProviderPreset =
    ProviderRegistry.load().getPreset(ANTHROPIC_PRESET_ID)
        ?: error("Anthropic preset is missing from contracts/providers.json")

/**
 * Build the Anthropic Messages request headers.
 *
 * The key travels only in `x-api-key`. `anthropic-version` is always present.
 * The web-only `anthropic-dangerous-direct-browser-access` header from the
 * contract defaults is deliberately omitted on Android.
 */
internal fun buildAnthropicHeaders(
    context: ProviderContext,
    preset: ProviderPreset,
    accept: String = "application/json",
): Headers {
    val headers = linkedMapOf<String, String>()
    headers["Accept"] = accept
    headers["content-type"] = "application/json"

    preset.defaultHeaders.forEach { (name, value) ->
        if (!name.equals(BROWSER_ACCESS_HEADER, ignoreCase = true)) {
            headers[name] = value
        }
    }

    context.provider.headers.forEach { (name, value) -> headers[name] = value }

    if (headers.keys.none { it.equals("anthropic-version", ignoreCase = true) }) {
        headers["anthropic-version"] = ANTHROPIC_VERSION
    }

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

/**
 * Build the exact Anthropic `POST /messages` body.
 *
 * The system prompt is a top-level field, never a message. `max_tokens` is
 * required by Anthropic and is always sent when the caller supplied it.
 * Advanced JSON under `params.extra.anthropic` cannot override protected
 * fields.
 */
internal fun buildAnthropicChatRequestBody(
    request: NormalizedChatRequest,
    supportedParameters: List<String> = DEFAULT_ANTHROPIC_SUPPORTED_PARAMETERS,
): JsonObject {
    val supported = supportedParameters.toSet()
    val extra = request.params.extra[ANTHROPIC_EXTRA_NAMESPACE] as? JsonObject
    val protectedFields = ANTHROPIC_PROTECTED_FIELDS
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

    return buildJsonObject {
        put("model", request.model)
        request.system?.let { put("system", it) }

        put(
            "messages",
            buildJsonArray {
                request.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put(
                                "role",
                                if (message.role == NormalizedRole.ASSISTANT) {
                                    "assistant"
                                } else {
                                    "user"
                                },
                            )
                            put(
                                "content",
                                anthropicContentBlocks(message.parts, request.attachments),
                            )
                        },
                    )
                }
            },
        )

        request.params.maxOutputTokens?.let { put("max_tokens", it) }
        put("stream", true)

        request.params.temperature
            ?.takeIf { "temperature" in supported }
            ?.let { put("temperature", it) }

        request.params.topP
            ?.takeIf { "top_p" in supported }
            ?.let { put("top_p", it) }

        request.params.stop
            .takeIf { it.isNotEmpty() && "stop" in supported }
            ?.let { stops ->
                put("stop_sequences", buildJsonArray { stops.forEach { add(JsonPrimitive(it)) } })
            }

        extra?.forEach { (field, value) ->
            if (field !in ANTHROPIC_PROTECTED_FIELDS) {
                put(field, value)
            }
        }
    }
}

private fun anthropicContentBlocks(
    parts: List<ContentPart>,
    attachments: Map<String, ResolvedAttachment>,
): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is ContentPart.Text -> {
                if (part.text.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        },
                    )
                }
            }

            is ContentPart.Image -> {
                val attachment = attachments[part.attachmentId]
                    ?: throw missingAnthropicAttachment(part.attachmentId)
                add(
                    buildJsonObject {
                        put("type", "image")
                        put(
                            "source",
                            buildJsonObject {
                                put("type", "base64")
                                put(
                                    "media_type",
                                    attachment.mimeType.ifEmpty { part.mimeType },
                                )
                                put("data", attachment.dataBase64)
                            },
                        )
                    },
                )
            }

            is ContentPart.File -> {
                val attachment = attachments[part.attachmentId]
                    ?: throw missingAnthropicAttachment(part.attachmentId)
                val name = attachment.name ?: part.name
                val body = if (attachment.mimeType.startsWith("text/", ignoreCase = true)) {
                    String(Base64.getDecoder().decode(attachment.dataBase64), Charsets.UTF_8)
                } else {
                    ""
                }
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "\n\n[File: $name]\n$body")
                    },
                )
            }

            else -> Unit
        }
    }
}

private fun missingAnthropicAttachment(attachmentId: String): ProviderRequestException =
    ProviderRequestException(
        code = ChatErrorCode.INVALID_REQUEST,
        message = "Missing attachment $attachmentId for the Anthropic request.",
    )

/**
 * Map one Anthropic `/models` entry.
 *
 * Anthropic reports `display_name` rather than `name`; missing capability
 * metadata falls back to the shared Anthropic preset defaults.
 */
fun mapAnthropicModel(
    raw: JsonElement?,
    defaults: OpenAICatalogDefaults = catalogDefaultsForPreset(ANTHROPIC_PRESET_ID),
): ModelInfo? {
    val obj = raw as? JsonObject ?: return null
    val remoteId = (obj["id"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        .orEmpty()
    if (remoteId.isEmpty()) return null

    val label = (obj["display_name"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: (obj["name"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: remoteId

    val inferred = inferOpenAIModelMetadata(obj, defaults)

    return ModelInfo(
        remoteId = remoteId,
        label = label,
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
        hidden = false,
    )
}

/** Map an Anthropic `/models` envelope to normalized entries. */
fun mapAnthropicModels(
    payload: JsonElement?,
    defaults: OpenAICatalogDefaults = catalogDefaultsForPreset(ANTHROPIC_PRESET_ID),
): List<ModelInfo> {
    val entries = when {
        payload is JsonArray -> payload
        payload is JsonObject && payload["data"] is JsonArray ->
            payload.getValue("data") as JsonArray
        else -> throw OpenRouterCatalogException(
            "Anthropic /models response must contain a data array",
        )
    }

    return entries.mapNotNull { mapAnthropicModel(it, defaults) }
}

/**
 * Anthropic Messages key validation, model discovery, and chat streaming.
 *
 * The key is sent only in `x-api-key`; it is never placed in a URL or a log
 * line. [streamChat] emits normalized events and never throws for provider,
 * network, or timeout failures. Coroutine cancellation cancels the underlying
 * OkHttp call.
 */
class AnthropicAdapter(
    private val client: OkHttpClient,
    private val preset: ProviderPreset = defaultAnthropicPreset(),
    private val cache: CatalogCache = getDefaultCatalogCache(),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryPolicy: OpenRouterRetryPolicy = OpenRouterRetryPolicy(),
    private val inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val supportedParameters: List<String> = DEFAULT_ANTHROPIC_SUPPORTED_PARAMETERS,
) : CatalogAdapter {
    override val kind: ProviderKind = ProviderKind.ANTHROPIC

    private val streamClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val streamer = AnthropicStreamer(
        preset = preset,
        client = streamClient,
        now = now,
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = random,
        sleep = sleep,
    )

    /**
     * Validate an Anthropic key with `GET /models`.
     *
     * Anthropic has no dedicated key-introspection endpoint, so a successful
     * catalog load is the validation signal. The key stays in a header.
     */
    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo {
        if (context.apiKey.isNullOrBlank()) {
            throw ProviderRequestException(
                code = ChatErrorCode.AUTH,
                message = "An Anthropic API key is required.",
                providerId = context.provider.id,
            )
        }

        val path = preset.keyValidation?.path ?: preset.modelDiscovery.path
        val payload = requestJson(context, path)

        try {
            mapAnthropicModels(payload)
        } catch (error: OpenRouterCatalogException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PARSE,
                message = "Anthropic /models response was not valid.",
                providerId = context.provider.id,
                cause = error,
            )
        }

        return ProviderKeyInfo()
    }

    /**
     * Load the Anthropic catalog with the preset cache TTL and last-known-good
     * fallback.
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
    ): Flow<StreamEvent> = streamer.stream(context, request, supportedParameters)

    private suspend fun fetchModels(context: ProviderContext): List<ModelInfo> {
        val payload = requestJson(context, preset.modelDiscovery.path)

        return try {
            mapAnthropicModels(payload)
        } catch (error: OpenRouterCatalogException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PARSE,
                message = "Anthropic /models response was not valid.",
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
                message = "Could not reach Anthropic. Check your connection and base URL.",
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        }

        return withContext(Dispatchers.IO) {
            response.use { result ->
                val body = result.body.string()

                if (!result.isSuccessful) {
                    throw mapAnthropicHttpError(
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
                        message = "Anthropic returned invalid JSON.",
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

        buildAnthropicHeaders(context, preset, accept = "application/json")
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
 * Stream an Anthropic Messages completion as normalized events.
 *
 * Events follow the shared order: `start`, text/reasoning deltas, `usage`,
 * `finish`, or a single terminal `error`. Thinking deltas stay in
 * `reasoning-delta` and are never mixed into answer text.
 */
internal class AnthropicStreamer(
    private val preset: ProviderPreset,
    client: OkHttpClient,
    private val now: () -> Long,
    private val retryPolicy: OpenRouterRetryPolicy,
    private val inactivityTimeoutMillis: Long,
    private val random: Random,
    private val sleep: suspend (Long) -> Unit,
) {
    private val transport = SseTransport(client)

    fun stream(
        context: ProviderContext,
        request: NormalizedChatRequest,
        supportedParameters: List<String>,
    ): Flow<StreamEvent> = flow {
        val body = try {
            buildAnthropicChatRequestBody(request, supportedParameters)
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
                        cancelledAnthropicError(context).toChatError(nowInstant()),
                    ),
                )
                return@flow
            } catch (thrown: Throwable) {
                val normalized = normalizeAnthropicThrown(thrown, context, receivedOutput)

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
        val request = buildStreamRequest(context, body)
        val mapper = AnthropicStreamMapper(context, emit)

        try {
            collectWithWatchdog(context, transport.stream(request)) { data ->
                mapper.handleData(data)
            }
        } catch (error: SseHttpException) {
            throw mapAnthropicHttpError(
                context = context,
                status = error.code,
                headers = error.headers,
                body = error.body,
                nowMillis = now(),
            )
        } catch (error: SseBufferOverflowException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PROVIDER,
                message = "The Anthropic stream exceeded the buffer limit.",
                providerId = context.provider.id,
                cause = error,
            )
        } catch (error: SseReadException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = "The Anthropic stream was interrupted.",
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        } catch (error: SseTransportException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = "The Anthropic stream was interrupted.",
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
            .url(joinUrl(context.provider.baseUrl, "/messages"))
            .post(body.toString().toRequestBody(null))

        buildAnthropicHeaders(context, preset, accept = "text/event-stream")
            .forEach { (name, value) -> builder.header(name, value) }

        return builder.build()
    }

    private suspend fun collectWithWatchdog(
        context: ProviderContext,
        flow: Flow<String>,
        onData: suspend (String) -> Unit,
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
                            message = "Anthropic stopped responding.",
                            providerId = context.provider.id,
                            retryable = true,
                        )
                    }

                    if (result.isClosed) {
                        result.exceptionOrNull()?.let { throw it }
                        break
                    }

                    onData(result.getOrThrow())
                }
            } finally {
                channel.cancel()
            }
        }
    }

    private fun nowInstant(): Instant = Instant.ofEpochMilli(now())
}

/** Maps raw Anthropic SSE payloads into normalized events. */
internal class AnthropicStreamMapper(
    private val context: ProviderContext,
    private val emit: suspend (StreamEvent) -> Unit,
) {
    var error: ProviderRequestException? = null
        private set

    private var started = false
    private var finished = false
    private var responseId: String? = null
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null
    private var usageEmitted = false
    private var finishReason: FinishReason? = null

    suspend fun handleData(data: String) {
        if (error != null) return

        val payload = try {
            Json.parseToJsonElement(data) as? JsonObject
        } catch (_: Exception) {
            null
        }

        if (payload == null) {
            error = ProviderRequestException(
                code = ChatErrorCode.PARSE,
                message = "The Anthropic stream contained invalid JSON.",
                providerId = context.provider.id,
            )
            return
        }

        when ((payload["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "message_start" -> handleMessageStart(payload)
            "content_block_start" -> handleContentBlockStart(payload)
            "content_block_delta" -> handleContentBlockDelta(payload)
            "content_block_stop" -> Unit
            "message_delta" -> handleMessageDelta(payload)
            "message_stop" -> handleMessageStop()
            "error" -> {
                val envelope = payload["error"] as? JsonObject ?: payload
                error = anthropicStreamError(envelope, context)
            }
            else -> Unit
        }
    }

    suspend fun finish() {
        if (error != null || finished || !started) return
        emitUsage()
        emitFinish(finishReason ?: FinishReason.UNKNOWN)
    }

    private suspend fun handleMessageStart(payload: JsonObject) {
        val message = payload["message"] as? JsonObject

        if (message != null) {
            (message["id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.let { responseId = it }

            val usage = message["usage"] as? JsonObject
            if (usage != null) {
                inputTokens = (usage["input_tokens"] as? JsonPrimitive)?.intOrNull
                outputTokens = (usage["output_tokens"] as? JsonPrimitive)?.intOrNull
            }
        }

        if (!started) {
            started = true
            emit(StreamEvent.Start(responseId))
        }
    }

    private suspend fun handleContentBlockStart(payload: JsonObject) {
        val block = payload["content_block"] as? JsonObject ?: return

        when ((block["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "text" -> {
                val text = (block["text"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    .orEmpty()
                if (text.isNotEmpty()) {
                    emit(StreamEvent.TextDelta(text))
                }
            }
            "thinking" -> {
                val thinking = (block["thinking"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    .orEmpty()
                if (thinking.isNotEmpty()) {
                    emit(StreamEvent.ReasoningDelta(thinking))
                }
            }
        }
    }

    private suspend fun handleContentBlockDelta(payload: JsonObject) {
        val delta = payload["delta"] as? JsonObject ?: return

        when ((delta["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "text_delta" -> {
                val text = (delta["text"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    .orEmpty()
                if (text.isNotEmpty()) {
                    emit(StreamEvent.TextDelta(text))
                }
            }
            "thinking_delta" -> {
                val thinking = (delta["thinking"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    .orEmpty()
                if (thinking.isNotEmpty()) {
                    emit(StreamEvent.ReasoningDelta(thinking))
                }
            }
        }
    }

    private suspend fun handleMessageDelta(payload: JsonObject) {
        val delta = payload["delta"] as? JsonObject
        if (delta != null && delta.containsKey("stop_reason")) {
            finishReason = mapAnthropicFinishReason(delta["stop_reason"])
        }

        val usage = payload["usage"] as? JsonObject
        if (usage != null) {
            (usage["output_tokens"] as? JsonPrimitive)?.intOrNull?.let { outputTokens = it }
            (usage["input_tokens"] as? JsonPrimitive)?.intOrNull?.let { inputTokens = it }
        }

        emitUsage()
    }

    private suspend fun handleMessageStop() {
        if (finished) return
        emitUsage()
        emitFinish(finishReason ?: FinishReason.STOP)
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
        emit(StreamEvent.Finish(reason))
        finished = true
    }
}

private fun mapAnthropicFinishReason(reason: JsonElement?): FinishReason {
    val value = (reason as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (value) {
        "end_turn", "stop_sequence" -> FinishReason.STOP
        "max_tokens" -> FinishReason.LENGTH
        "tool_use" -> FinishReason.TOOL_CALLS
        "refusal" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.UNKNOWN
    }
}

private fun anthropicStatusToCode(status: Int, message: String): ChatErrorCode = when {
    status == 401 -> ChatErrorCode.AUTH
    status == 402 -> ChatErrorCode.QUOTA
    status == 403 -> if (ANTHROPIC_MODERATION_PATTERN.containsMatchIn(message)) {
        ChatErrorCode.CONTENT_FILTER
    } else {
        ChatErrorCode.PERMISSION
    }
    status == 404 -> ChatErrorCode.MODEL_NOT_FOUND
    status == 429 -> ChatErrorCode.RATE_LIMIT
    status == 413 -> ChatErrorCode.CONTEXT_LENGTH
    status == 400 -> when {
        ANTHROPIC_CONTENT_FILTER_PATTERN.containsMatchIn(message) ->
            ChatErrorCode.CONTENT_FILTER
        ANTHROPIC_CONTEXT_PATTERN.containsMatchIn(message) -> ChatErrorCode.CONTEXT_LENGTH
        else -> ChatErrorCode.INVALID_REQUEST
    }
    else -> ChatErrorCode.PROVIDER
}

private fun anthropicStreamStatus(envelope: JsonObject): Int? {
    val type = (envelope["type"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

    return when (type) {
        "overloaded_error" -> 529
        "server_error" -> 500
        "rate_limit_error" -> 429
        "invalid_request_error" -> 400
        "authentication_error" -> 401
        "permission_error" -> 403
        "not_found_error" -> 404
        else -> null
    }
}

private fun anthropicStreamError(
    envelope: JsonObject,
    context: ProviderContext,
): ProviderRequestException {
    val status = anthropicStreamStatus(envelope)
    val rawMessage = (envelope["message"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: "The Anthropic stream returned an error."
    val message = redactSecret(rawMessage, context.apiKey)

    return ProviderRequestException(
        code = if (status != null) {
            anthropicStatusToCode(status, message)
        } else {
            ChatErrorCode.PROVIDER
        },
        message = message,
        providerId = context.provider.id,
        status = status,
        retryable = false,
    )
}

/** Map an Anthropic HTTP error response to a normalized provider failure. */
internal fun mapAnthropicHttpError(
    context: ProviderContext,
    status: Int,
    headers: Headers,
    body: String,
    nowMillis: Long,
): ProviderRequestException {
    val payload = parseAnthropicJsonObject(body)
    val envelope = payload?.get("error") as? JsonObject ?: payload
    val rawMessage = (envelope?.get("message") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: "The Anthropic request failed with HTTP $status"
    val message = redactSecret(rawMessage, context.apiKey)
    val retryAfterSeconds = parseRetryAfterSeconds(
        headers["Retry-After"],
        Instant.ofEpochMilli(nowMillis),
    )
    val requestId = headers["request-id"]
        ?: headers["x-request-id"]
        ?: (envelope?.get("request_id") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    val details = retryAfterSeconds?.let { seconds ->
        buildJsonObject {
            put(
                "retryAfterSeconds",
                if (seconds % 1.0 == 0.0) seconds.toLong() else seconds,
            )
        }
    }

    return ProviderRequestException(
        code = anthropicStatusToCode(status, message),
        message = message,
        providerId = context.provider.id,
        status = status,
        retryable = isRetryableStatus(status),
        retryAfterSeconds = retryAfterSeconds,
        requestId = requestId,
        details = details,
    )
}

private fun parseAnthropicJsonObject(body: String): JsonObject? {
    if (body.isBlank()) return null
    return try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }
}

private fun cancelledAnthropicError(context: ProviderContext): ProviderRequestException =
    ProviderRequestException(
        code = ChatErrorCode.CANCELLED,
        message = "The Anthropic request was cancelled.",
        providerId = context.provider.id,
    )

private fun normalizeAnthropicThrown(
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
        message = "The Anthropic request failed.",
        providerId = context.provider.id,
        cause = error,
    )
}
