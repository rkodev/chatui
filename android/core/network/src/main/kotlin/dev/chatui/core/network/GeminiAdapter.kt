package dev.chatui.core.network

import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Usage
import java.io.IOException
import java.net.URLEncoder
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

/** Namespace that owns Gemini-only advanced JSON. */
const val GEMINI_EXTRA_NAMESPACE: String = "google"

/** Gemini's supported sampling controls for the phase-1 surface. */
val DEFAULT_GEMINI_SUPPORTED_PARAMETERS: List<String> = listOf(
    "temperature",
    "top_p",
    "max_tokens",
    "stop",
    "stream",
)

private const val GEMINI_PRESET_ID = "google"
private const val GEMINI_DEFAULT_AUTH_HEADER = "x-goog-api-key"

private val GEMINI_PROTECTED_FIELDS: List<String> = listOf(
    "model",
    "messages",
    "stream",
    "stream_options",
)

private val GEMINI_CONTEXT_PATTERN = Regex(
    "context length|context_length|token count|maximum number of tokens|too many tokens|too long",
    RegexOption.IGNORE_CASE,
)

private val GEMINI_CONTENT_FILTER_PATTERN = Regex(
    "content filter|content_filter|safety|policy|blocked|moderation",
    RegexOption.IGNORE_CASE,
)

private val GEMINI_API_KEY_PATTERN = Regex(
    "api[ _-]?key|api_key_invalid|credential",
    RegexOption.IGNORE_CASE,
)

private fun defaultGeminiPreset(): ProviderPreset =
    ProviderRegistry.load().getPreset(GEMINI_PRESET_ID)
        ?: error("Gemini preset is missing from contracts/providers.json")

/**
 * Build the Gemini request headers.
 *
 * The key travels only in `x-goog-api-key`. Gemini's query-string key form is
 * never used because URLs end up in logs and error reports.
 */
internal fun buildGeminiHeaders(
    context: ProviderContext,
    preset: ProviderPreset,
    accept: String = "application/json",
): Headers {
    val headers = linkedMapOf<String, String>()
    headers["Accept"] = accept
    headers["content-type"] = "application/json"

    preset.defaultHeaders.forEach { (name, value) -> headers[name] = value }
    context.provider.headers.forEach { (name, value) -> headers[name] = value }

    val apiKey = context.apiKey?.takeIf { it.isNotBlank() }
    if (apiKey != null) {
        when (preset.auth.type) {
            ProviderAuthType.BEARER -> headers[preset.auth.headerName ?: "Authorization"] =
                "${preset.auth.scheme ?: "Bearer"} $apiKey"
            ProviderAuthType.X_API_KEY -> headers[
                preset.auth.headerName ?: GEMINI_DEFAULT_AUTH_HEADER,
            ] = apiKey
            ProviderAuthType.QUERY_KEY -> throw ProviderRequestException(
                code = ChatErrorCode.INVALID_REQUEST,
                message = "API keys are never placed in URLs. Use a header-based auth style.",
            )
            ProviderAuthType.NONE -> Unit
        }
    }

    return headers.toHeaders()
}

/** Normalize a model id for the Gemini path segment. */
fun geminiModelPath(model: String): String {
    val trimmed = model.trim().removePrefix("models/")
    return URLEncoder.encode(trimmed, Charsets.UTF_8.name()).replace("+", "%20")
}

/** Build `POST /models/{model}:streamGenerateContent?alt=sse`. */
fun buildGeminiChatUrl(baseUrl: String, model: String): String =
    joinUrl(baseUrl, "models/${geminiModelPath(model)}:streamGenerateContent?alt=sse")

private fun decodeBase64Utf8(dataBase64: String): String =
    String(Base64.getDecoder().decode(dataBase64), Charsets.UTF_8)

private fun geminiContentParts(
    parts: List<ContentPart>,
    attachments: Map<String, ResolvedAttachment>,
): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is ContentPart.Text -> {
                if (part.text.isNotEmpty()) {
                    add(buildJsonObject { put("text", part.text) })
                }
            }

            is ContentPart.Image -> {
                val attachment = attachments[part.attachmentId]
                    ?: throw missingGeminiAttachment(part.attachmentId)
                add(
                    buildJsonObject {
                        put(
                            "inlineData",
                            buildJsonObject {
                                put(
                                    "mimeType",
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
                    ?: throw missingGeminiAttachment(part.attachmentId)
                val name = attachment.name ?: part.name
                val mimeType = attachment.mimeType.ifEmpty { part.mimeType }
                val body = if (mimeType.startsWith("text/", ignoreCase = true)) {
                    decodeBase64Utf8(attachment.dataBase64)
                } else {
                    ""
                }
                add(buildJsonObject { put("text", "\n\n[File: $name]\n$body") })
            }

            else -> Unit
        }
    }
}

private fun missingGeminiAttachment(attachmentId: String): ProviderRequestException =
    ProviderRequestException(
        code = ChatErrorCode.INVALID_REQUEST,
        message = "Missing attachment $attachmentId for the Gemini request.",
    )

/**
 * Build the exact Gemini `streamGenerateContent` body.
 *
 * `contents` never carries the system prompt; Gemini has a dedicated
 * `systemInstruction` field. Advanced JSON under `params.extra.google` cannot
 * override protected fields.
 */
internal fun buildGeminiChatRequestBody(
    request: NormalizedChatRequest,
    supportedParameters: List<String> = DEFAULT_GEMINI_SUPPORTED_PARAMETERS,
): JsonObject {
    val supported = supportedParameters.toSet()
    val extra = request.params.extra[GEMINI_EXTRA_NAMESPACE] as? JsonObject
    val protectedFields = GEMINI_PROTECTED_FIELDS
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
        put(
            "contents",
            buildJsonArray {
                request.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put(
                                "role",
                                if (message.role == NormalizedRole.ASSISTANT) "model" else "user",
                            )
                            put(
                                "parts",
                                geminiContentParts(message.parts, request.attachments),
                            )
                        },
                    )
                }
            },
        )

        request.system?.let { system ->
            put(
                "systemInstruction",
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray { add(buildJsonObject { put("text", system) }) },
                    )
                },
            )
        }

        val generationConfig = buildJsonObject {
            request.params.maxOutputTokens?.let { put("maxOutputTokens", it) }
            request.params.temperature
                ?.takeIf { "temperature" in supported }
                ?.let { put("temperature", it) }
            request.params.topP
                ?.takeIf { "top_p" in supported }
                ?.let { put("topP", it) }
            request.params.stop
                .takeIf { it.isNotEmpty() && "stop" in supported }
                ?.let { stops ->
                    put(
                        "stopSequences",
                        buildJsonArray { stops.forEach { add(JsonPrimitive(it)) } },
                    )
                }
        }
        if (generationConfig.isNotEmpty()) {
            put("generationConfig", generationConfig)
        }

        extra?.forEach { (field, value) ->
            if (field !in GEMINI_PROTECTED_FIELDS) {
                put(field, value)
            }
        }
    }
}

/**
 * Map one Gemini `/models` entry.
 *
 * Gemini reports `name` (`models/<id>`), `baseModelId`, `displayName`, and
 * token limits but no normalized capability metadata. Missing fields fall back
 * to the shared Google preset defaults.
 */
fun mapGeminiModel(
    raw: JsonElement?,
    defaults: OpenAICatalogDefaults = catalogDefaultsForPreset(GEMINI_PRESET_ID),
): ModelInfo? {
    val obj = raw as? JsonObject ?: return null

    val name = (obj["name"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        .orEmpty()
    val baseModelId = (obj["baseModelId"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        .orEmpty()
    val remoteId = if (baseModelId.isNotEmpty()) {
        baseModelId
    } else {
        name.removePrefix("models/")
    }
    if (remoteId.isEmpty()) return null

    val label = (obj["displayName"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: remoteId

    val limits = buildJsonObject {
        (obj["inputTokenLimit"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.let { put("contextWindow", it) }
        (obj["outputTokenLimit"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.let { put("maxOutputTokens", it) }
    }
    val inferred = inferOpenAIModelMetadata(limits, defaults)

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

/** Map a Gemini `/models` envelope to normalized entries. */
fun mapGeminiModels(
    payload: JsonElement?,
    defaults: OpenAICatalogDefaults = catalogDefaultsForPreset(GEMINI_PRESET_ID),
): List<ModelInfo> {
    val entries = when {
        payload is JsonArray -> payload
        payload is JsonObject && payload["models"] is JsonArray ->
            payload.getValue("models") as JsonArray
        else -> throw OpenRouterCatalogException(
            "Gemini /models response must contain a models array",
        )
    }

    return entries.mapNotNull { mapGeminiModel(it, defaults) }
}

/** Map a Gemini `finishReason` to the normalized reason. */
fun mapGeminiFinishReason(reason: JsonElement?): FinishReason {
    val value = (reason as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (value) {
        "STOP" -> FinishReason.STOP
        "MAX_TOKENS" -> FinishReason.LENGTH
        "SAFETY", "RECITATION" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.UNKNOWN
    }
}

/**
 * Gemini key validation, model discovery, and chat streaming.
 *
 * The key is sent only in `x-goog-api-key`; it is never placed in a URL or a
 * log line. [streamChat] emits normalized events and never throws for provider,
 * network, or timeout failures. Coroutine cancellation cancels the underlying
 * OkHttp call.
 */
class GeminiAdapter(
    private val client: OkHttpClient,
    private val preset: ProviderPreset = defaultGeminiPreset(),
    private val cache: CatalogCache = getDefaultCatalogCache(),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryPolicy: OpenRouterRetryPolicy = OpenRouterRetryPolicy(),
    private val inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val supportedParameters: List<String> = DEFAULT_GEMINI_SUPPORTED_PARAMETERS,
) : CatalogAdapter {
    override val kind: ProviderKind = ProviderKind.GOOGLE_GEMINI

    private val streamClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val streamer = GeminiStreamer(
        preset = preset,
        client = streamClient,
        now = now,
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = random,
        sleep = sleep,
    )

    /**
     * Validate a Gemini key with `GET /models`.
     *
     * Gemini has no dedicated key-introspection endpoint, so a successful
     * catalog load is the validation signal. The key stays in a header.
     */
    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo {
        if (context.apiKey.isNullOrBlank()) {
            throw ProviderRequestException(
                code = ChatErrorCode.AUTH,
                message = "A Google Gemini API key is required.",
                providerId = context.provider.id,
            )
        }

        val path = preset.keyValidation?.path ?: preset.modelDiscovery.path
        val payload = requestJson(context, path)

        try {
            mapGeminiModels(payload)
        } catch (error: OpenRouterCatalogException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PARSE,
                message = "Gemini /models response was not valid.",
                providerId = context.provider.id,
                cause = error,
            )
        }

        return ProviderKeyInfo()
    }

    /**
     * Load the Gemini catalog with the preset cache TTL and last-known-good
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
            mapGeminiModels(payload)
        } catch (error: OpenRouterCatalogException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PARSE,
                message = "Gemini /models response was not valid.",
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
                message = "Could not reach Gemini. Check your connection and base URL.",
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        }

        return withContext(Dispatchers.IO) {
            response.use { result ->
                val body = result.body.string()

                if (!result.isSuccessful) {
                    throw mapGeminiHttpError(
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
                        message = "Gemini returned invalid JSON.",
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

        buildGeminiHeaders(context, preset, accept = "application/json")
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
 * Stream a Gemini `streamGenerateContent` completion as normalized events.
 *
 * Events follow the shared order: `start`, text/reasoning deltas, `finish`,
 * `usage`, or a single terminal `error`. The contract fixture puts `finish`
 * before `usage`, so this adapter flushes usage at finish time.
 */
internal class GeminiStreamer(
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
            buildGeminiChatRequestBody(request, supportedParameters)
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
                val error = attemptStream(context, request, body, ::emitEvent)
                if (error != null) {
                    emit(StreamEvent.ErrorEvent(error.toChatError(nowInstant())))
                }
                return@flow
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                emit(
                    StreamEvent.ErrorEvent(
                        cancelledGeminiError(context).toChatError(nowInstant()),
                    ),
                )
                return@flow
            } catch (thrown: Throwable) {
                val normalized = normalizeGeminiThrown(thrown, context, receivedOutput)

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
        request: NormalizedChatRequest,
        body: JsonObject,
        emit: suspend (StreamEvent) -> Unit,
    ): ProviderRequestException? {
        val httpRequest = buildStreamRequest(context, request, body)
        val mapper = GeminiStreamMapper(context, emit)

        try {
            collectWithWatchdog(context, transport.stream(httpRequest)) { data ->
                mapper.handleData(data)
            }
        } catch (error: SseHttpException) {
            throw mapGeminiHttpError(
                context = context,
                status = error.code,
                headers = error.headers,
                body = error.body,
                nowMillis = now(),
            )
        } catch (error: SseBufferOverflowException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PROVIDER,
                message = "The Gemini stream exceeded the buffer limit.",
                providerId = context.provider.id,
                cause = error,
            )
        } catch (error: SseReadException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = "The Gemini stream was interrupted.",
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        } catch (error: SseTransportException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = "The Gemini stream was interrupted.",
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
        request: NormalizedChatRequest,
        body: JsonObject,
    ): Request {
        val builder = Request.Builder()
            .url(buildGeminiChatUrl(context.provider.baseUrl, request.model))
            .post(body.toString().toRequestBody(null))

        buildGeminiHeaders(context, preset, accept = "text/event-stream")
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
                            message = "Gemini stopped responding.",
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

/** Maps raw Gemini SSE payloads into normalized events. */
internal class GeminiStreamMapper(
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
    private var totalTokens: Int? = null
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
                message = "The Gemini stream contained invalid JSON.",
                providerId = context.provider.id,
            )
            return
        }

        val envelope = payload["error"] as? JsonObject
        if (envelope != null) {
            error = geminiStreamError(envelope, context)
            return
        }

        (payload["responseId"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.let { responseId = it }

        if (!started) {
            started = true
            emit(StreamEvent.Start(responseId))
        }

        handleUsageMetadata(payload["usageMetadata"])

        val candidates = payload["candidates"] as? JsonArray ?: return
        candidates.forEach { candidate ->
            (candidate as? JsonObject)?.let { handleCandidate(it) }
        }
    }

    suspend fun finish() {
        if (error != null || finished || !started) return
        emitFinish(finishReason ?: FinishReason.UNKNOWN)
        emitUsage()
    }

    private suspend fun handleCandidate(candidate: JsonObject) {
        val content = candidate["content"] as? JsonObject
        val parts = content?.get("parts") as? JsonArray

        parts?.forEach { part ->
            val obj = part as? JsonObject ?: return@forEach
            val text = (obj["text"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                .orEmpty()
            if (text.isEmpty()) return@forEach

            val thought = (obj["thought"] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.content
                ?.toBooleanStrictOrNull() == true
            if (thought) {
                emit(StreamEvent.ReasoningDelta(text))
            } else {
                emit(StreamEvent.TextDelta(text))
            }
        }

        val reason = candidate["finishReason"]
        val reasonValue = (reason as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!finished && !reasonValue.isNullOrEmpty()) {
            finishReason = mapGeminiFinishReason(reason)
            emitFinish(finishReason ?: FinishReason.UNKNOWN)
            emitUsage()
        }
    }

    private fun handleUsageMetadata(value: JsonElement?) {
        val usage = value as? JsonObject ?: return

        (usage["promptTokenCount"] as? JsonPrimitive)?.intOrNull?.let { inputTokens = it }
        (usage["candidatesTokenCount"] as? JsonPrimitive)?.intOrNull?.let { outputTokens = it }
        (usage["totalTokenCount"] as? JsonPrimitive)?.intOrNull?.let { totalTokens = it }
    }

    private fun usage(): Usage? {
        if (inputTokens == null && outputTokens == null) return null

        val total = totalTokens ?: if (inputTokens != null && outputTokens != null) {
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

private fun geminiStatusToCode(status: Int, message: String): ChatErrorCode = when {
    status == 401 -> ChatErrorCode.AUTH
    status == 402 -> ChatErrorCode.QUOTA
    status == 403 -> if (GEMINI_CONTENT_FILTER_PATTERN.containsMatchIn(message)) {
        ChatErrorCode.CONTENT_FILTER
    } else {
        ChatErrorCode.PERMISSION
    }
    status == 404 -> ChatErrorCode.MODEL_NOT_FOUND
    status == 413 -> ChatErrorCode.CONTEXT_LENGTH
    status == 429 -> ChatErrorCode.RATE_LIMIT
    status == 400 -> when {
        GEMINI_API_KEY_PATTERN.containsMatchIn(message) -> ChatErrorCode.AUTH
        GEMINI_CONTENT_FILTER_PATTERN.containsMatchIn(message) -> ChatErrorCode.CONTENT_FILTER
        GEMINI_CONTEXT_PATTERN.containsMatchIn(message) -> ChatErrorCode.CONTEXT_LENGTH
        else -> ChatErrorCode.INVALID_REQUEST
    }
    else -> ChatErrorCode.PROVIDER
}

private fun geminiStreamStatus(envelope: JsonObject): Int? {
    (envelope["code"] as? JsonPrimitive)?.intOrNull?.let { return it }

    val status = (envelope["status"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

    return when (status) {
        "RESOURCE_EXHAUSTED" -> 429
        "INTERNAL", "UNAVAILABLE" -> 500
        "INVALID_ARGUMENT" -> 400
        "UNAUTHENTICATED" -> 401
        "PERMISSION_DENIED" -> 403
        "NOT_FOUND" -> 404
        else -> null
    }
}

private fun geminiStreamError(
    envelope: JsonObject,
    context: ProviderContext,
): ProviderRequestException {
    val status = geminiStreamStatus(envelope)
    val rawMessage = (envelope["message"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: "The Gemini stream returned an error."
    val message = redactSecret(rawMessage, context.apiKey)

    return ProviderRequestException(
        code = if (status != null) {
            geminiStatusToCode(status, message)
        } else {
            ChatErrorCode.PROVIDER
        },
        message = message,
        providerId = context.provider.id,
        status = status,
        retryable = false,
    )
}

/** Map a Gemini HTTP error response to a normalized provider failure. */
internal fun mapGeminiHttpError(
    context: ProviderContext,
    status: Int,
    headers: Headers,
    body: String,
    nowMillis: Long,
): ProviderRequestException {
    val payload = parseGeminiJsonObject(body)
    val envelope = payload?.get("error") as? JsonObject ?: payload
    val rawMessage = (envelope?.get("message") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: "The Gemini request failed with HTTP $status"
    val message = redactSecret(rawMessage, context.apiKey)
    val retryAfterSeconds = parseRetryAfterSeconds(
        headers["Retry-After"],
        Instant.ofEpochMilli(nowMillis),
    )
    val requestId = headers["x-goog-request-id"]
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
        code = geminiStatusToCode(status, message),
        message = message,
        providerId = context.provider.id,
        status = status,
        retryable = isRetryableStatus(status),
        retryAfterSeconds = retryAfterSeconds,
        requestId = requestId,
        details = details,
    )
}

private fun parseGeminiJsonObject(body: String): JsonObject? {
    if (body.isBlank()) return null
    return try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }
}

private fun cancelledGeminiError(context: ProviderContext): ProviderRequestException =
    ProviderRequestException(
        code = ChatErrorCode.CANCELLED,
        message = "The Gemini request was cancelled.",
        providerId = context.provider.id,
    )

private fun normalizeGeminiThrown(
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
        message = "The Gemini request failed.",
        providerId = context.provider.id,
        cause = error,
    )
}
