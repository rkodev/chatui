package dev.chatui.core.network

import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.ReasoningEffort
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Usage
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Attribution values resolved from runtime config. Never personal data. */
data class Attribution(
    val origin: String,
    val title: String,
)

/** Backwards-compatible alias for the OpenRouter attribution shape. */
typealias OpenRouterAttribution = Attribution

/**
 * Normalized provider request failure.
 *
 * [message] is safe to show to a user: the API key is redacted before the
 * exception is created. The key is never placed in a URL or a log line.
 */
class ProviderRequestException(
    val code: ChatErrorCode,
    override val message: String,
    val providerId: String? = null,
    val status: Int? = null,
    val retryable: Boolean = false,
    val retryAfterSeconds: Double? = null,
    val requestId: String? = null,
    val details: JsonObject? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Return a copy with a different retryability flag. */
    fun withRetryable(retryable: Boolean): ProviderRequestException =
        ProviderRequestException(
            code = code,
            message = message,
            providerId = providerId,
            status = status,
            retryable = retryable,
            retryAfterSeconds = retryAfterSeconds,
            requestId = requestId,
            details = details,
            cause = cause,
        )

    /** Return a copy with a provider ID filled in. */
    fun withProviderId(providerId: String): ProviderRequestException =
        ProviderRequestException(
            code = code,
            message = message,
            providerId = providerId,
            status = status,
            retryable = retryable,
            retryAfterSeconds = retryAfterSeconds,
            requestId = requestId,
            details = details,
            cause = cause,
        )
}

/** Backwards-compatible alias for the OpenRouter exception shape. */
typealias OpenRouterRequestException = ProviderRequestException

/** Provider-specific copy used by the transport. */
data class ProviderMessages(
    val missingKey: String = "An API key is required.",
    val cancelled: String = "The provider request was cancelled.",
    val network: String = "Could not reach the provider. Check your connection and base URL.",
    val invalidJson: String = "The provider returned invalid JSON.",
    val invalidModels: String = "The provider /models response was not valid.",
    val invalidKey: String = "The provider key response was not valid.",
    val emptyStream: String = "The provider returned an empty stream.",
    val invalidStreamJson: String = "The provider stream contained invalid JSON.",
    val streamError: String = "The provider stream returned an error.",
    val bufferOverflow: String = "The provider stream exceeded the buffer limit.",
    val interrupted: String = "The provider stream was interrupted.",
    val unknown: String = "The provider request failed.",
    val invalidRequest: String = "The chat request was invalid.",
    val timeout: String = "The provider stopped responding.",
    val defaultHttp: (Int) -> String = { status ->
        "The provider request failed with HTTP $status"
    },
    val status: Map<Int, String> = DEFAULT_STATUS_MESSAGES,
)

private val DEFAULT_STATUS_MESSAGES: Map<Int, String> = mapOf(
    401 to "Authentication failed. Check your API key.",
    402 to "The provider reports insufficient credits or an exhausted quota.",
    403 to "The provider blocked the request.",
    404 to "The requested model was not found.",
    429 to "Rate limit exceeded. Please retry later.",
    503 to "The provider is temporarily unavailable.",
)

/** How provider-specific JSON under `params.extra` is forwarded. */
enum class ExtraMode {
    /** Only forward the declared passthrough fields. */
    CURATED,

    /** Forward every non-protected key. */
    ALL,
}

/** How reasoning controls and deltas are shaped for a provider. */
enum class ReasoningStyle {
    OPENROUTER,
    OPENAI,
}

/**
 * Everything the OpenAI-compatible transport needs to talk to one provider.
 *
 * The preset is data, not code. OpenRouter is one preset; every other
 * OpenAI-compatible provider is another. The transport never branches on a
 * provider name.
 */
data class OpenAICompatiblePreset(
    val id: String,
    val baseUrl: String,
    val auth: ProviderAuth,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val attributionHeaders: Map<String, String> = emptyMap(),
    val modelDiscovery: ModelDiscovery,
    val keyValidation: KeyValidation? = null,
    /** Namespace under `params.extra` that belongs to this preset. */
    val extraNamespace: String = id,
    val extraMode: ExtraMode = ExtraMode.ALL,
    val extraPassthroughFields: List<String> = emptyList(),
    val reasoningStyle: ReasoningStyle = ReasoningStyle.OPENAI,
    val messages: ProviderMessages = ProviderMessages(),
    /** When false, message content is always a plain text string. */
    val multimodal: Boolean = true,
    /** OpenAI-compatible presets map a 400 safety rejection to `content_filter`. */
    val contentFilterOn400: Boolean = true,
    /** OpenRouter emits `start` before an in-stream error envelope. */
    val startBeforeStreamError: Boolean = false,
) {
    companion object {
        /** Build a transport preset from the checked-in provider contract. */
        fun fromPreset(preset: ProviderPreset): OpenAICompatiblePreset =
            OpenAICompatiblePreset(
                id = preset.id,
                baseUrl = preset.baseUrl,
                auth = preset.auth,
                defaultHeaders = preset.defaultHeaders,
                attributionHeaders = preset.attributionHeaders,
                modelDiscovery = preset.modelDiscovery,
                keyValidation = preset.keyValidation,
            )
    }
}

/** OpenRouter keeps its M1 messages and curated extras byte-for-byte. */
internal val OPENROUTER_MESSAGES: ProviderMessages = ProviderMessages(
    missingKey = "An OpenRouter API key is required.",
    cancelled = "OpenRouter request was cancelled.",
    network = "Could not reach OpenRouter.",
    invalidJson = "OpenRouter returned invalid JSON.",
    invalidModels = "OpenRouter /models response was not valid.",
    invalidKey = "OpenRouter /key response was not valid.",
    emptyStream = "OpenRouter returned an empty stream.",
    invalidStreamJson = "OpenRouter stream contained invalid JSON.",
    streamError = "OpenRouter stream returned an error.",
    bufferOverflow = "The OpenRouter stream exceeded the buffer limit.",
    interrupted = "The OpenRouter stream was interrupted.",
    unknown = "OpenRouter request failed.",
    invalidRequest = "The chat request was invalid.",
    timeout = "OpenRouter stopped responding.",
    defaultHttp = { status -> "OpenRouter request failed with HTTP $status" },
    status = mapOf(
        401 to "Authentication failed. Check your OpenRouter API key.",
        402 to "Insufficient credits. Add more credits to continue.",
        403 to "OpenRouter blocked the request.",
        404 to "The requested model was not found.",
        429 to "Rate limit exceeded. Please retry later.",
        503 to "No available upstream provider could serve the request.",
    ),
)

private val defaultOpenRouterTransportPreset: OpenAICompatiblePreset by lazy {
    val preset = ProviderRegistry.load().getPreset("openrouter")
        ?: error("OpenRouter preset is missing from contracts/providers.json")
    openRouterTransportPreset(preset)
}

/** The OpenRouter transport preset, loaded from the checked-in contract. */
fun openRouterTransportPreset(): OpenAICompatiblePreset = defaultOpenRouterTransportPreset

/** Add OpenRouter-specific transport behavior to a contract preset. */
fun openRouterTransportPreset(preset: ProviderPreset): OpenAICompatiblePreset =
    OpenAICompatiblePreset.fromPreset(preset).copy(
        extraNamespace = "openrouter",
        extraMode = ExtraMode.CURATED,
        extraPassthroughFields = listOf("provider", "models", "route", "reasoning"),
        reasoningStyle = ReasoningStyle.OPENROUTER,
        messages = OPENROUTER_MESSAGES,
        multimodal = false,
        contentFilterOn400 = false,
        startBeforeStreamError = true,
    )

/** Stable cache key for one provider's catalog. */
fun catalogCacheKey(providerId: String): String = "chatui.catalog.$providerId.v1"

private val PROTECTED_ADVANCED_FIELDS: List<String> = listOf(
    "model",
    "messages",
    "stream",
    "stream_options",
)

private val MODERATION_PATTERN = Regex("moderation|policy|content", RegexOption.IGNORE_CASE)

private val CONTENT_FILTER_PATTERN = Regex(
    "content filter|content_filter|content_policy|safety system|moderation",
    RegexOption.IGNORE_CASE,
)

private val CONTEXT_PATTERN = Regex(
    "context length|context_length|maximum context|too many tokens",
    RegexOption.IGNORE_CASE,
)

/**
 * Build the exact OpenAI-compatible `POST /chat/completions` body.
 *
 * The function is pure so tests can assert the serialized JSON against the
 * shared request vectors. It throws an `invalid_request` error before any
 * network call when advanced JSON tries to override a protected field.
 */
internal fun buildOpenAICompatibleChatRequestBody(
    request: NormalizedChatRequest,
    preset: OpenAICompatiblePreset,
    supportedParameters: List<String> = DEFAULT_SUPPORTED_PARAMETERS,
): JsonObject {
    val supported = supportedParameters.toSet()
    val extra = request.params.extra[preset.extraNamespace] as? JsonObject
    val protectedFields = protectedAdvancedFields(extra)

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
            add(
                buildJsonObject {
                    put("role", message.role.wireName())
                    put(
                        "content",
                        messageContent(message.parts, request.attachments, preset.multimodal),
                    )
                },
            )
        }
    }

    val reasoningObject = mutableMapOf<String, JsonElement>()
    var reasoningEffort: String? = null

    request.params.reasoningEffort
        ?.takeIf { "reasoning" in supported }
        ?.let { effort ->
            if (preset.reasoningStyle == ReasoningStyle.OPENROUTER) {
                reasoningObject["effort"] = JsonPrimitive(effort.wireName())
            } else {
                reasoningEffort = effort.wireName()
            }
        }

    val passthroughFields = mutableMapOf<String, JsonElement>()

    if (extra != null) {
        when (preset.extraMode) {
            ExtraMode.CURATED -> {
                preset.extraPassthroughFields.forEach { field ->
                    if (field == "reasoning") {
                        val advancedReasoning = extra["reasoning"]
                        if ("reasoning" in supported && advancedReasoning is JsonObject) {
                            advancedReasoning.forEach { (key, value) ->
                                reasoningObject[key] = value
                            }
                        }
                    } else {
                        extra[field]?.let { passthroughFields[field] = it }
                    }
                }
            }

            ExtraMode.ALL -> {
                extra.forEach { (field, value) ->
                    if (field !in PROTECTED_ADVANCED_FIELDS) {
                        passthroughFields[field] = value
                    }
                }
            }
        }
    }

    return buildJsonObject {
        put("model", request.model)
        put("messages", messages)
        put("stream", true)
        put("stream_options", buildJsonObject { put("include_usage", true) })

        request.params.temperature
            ?.takeIf { "temperature" in supported }
            ?.let { put("temperature", it) }

        request.params.topP
            ?.takeIf { "top_p" in supported }
            ?.let { put("top_p", it) }

        request.params.maxOutputTokens
            ?.takeIf { "max_tokens" in supported }
            ?.let { put("max_tokens", it) }

        request.params.stop
            .takeIf { it.isNotEmpty() && "stop" in supported }
            ?.let { stops ->
                put("stop", buildJsonArray { stops.forEach { add(JsonPrimitive(it)) } })
            }

        reasoningEffort?.let { put("reasoning_effort", it) }

        if (reasoningObject.isNotEmpty()) {
            put("reasoning", JsonObject(reasoningObject))
        }

        passthroughFields.forEach { (field, value) -> put(field, value) }
    }
}

/** Build the streaming request headers, including preset auth and attribution. */
internal fun buildOpenAICompatibleStreamRequest(
    context: ProviderContext,
    preset: OpenAICompatiblePreset,
    body: JsonObject,
    attribution: Attribution?,
): Request {
    val builder = Request.Builder()
        .url(joinUrl(context.provider.baseUrl, "/chat/completions"))
        .post(body.toString().toRequestBody(null))
        .header("Accept", "text/event-stream")
        .header("Content-Type", "application/json")

    applyDefaultHeaders(builder, context, preset)
    context.apiKey?.takeIf { it.isNotBlank() }?.let { applyAuth(builder, preset.auth, it) }
    applyAttribution(builder, preset, attribution)

    return builder.build()
}

/**
 * Preset-driven OpenAI-compatible key validation, model discovery, and chat
 * streaming.
 *
 * The key and catalog methods use the same mapping and cache semantics as the
 * web client. [streamChat] emits normalized events and never throws for
 * provider, network, or timeout failures; coroutine cancellation cancels the
 * underlying OkHttp call.
 */
class OpenAICompatibleAdapter(
    val preset: OpenAICompatiblePreset,
    private val client: OkHttpClient,
    private val attribution: Attribution? = null,
    private val cache: CatalogCache = getDefaultCatalogCache(),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryPolicy: OpenRouterRetryPolicy = OpenRouterRetryPolicy(),
    private val inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val supportedParameters: List<String> = DEFAULT_SUPPORTED_PARAMETERS,
) : ProviderAdapter {
    override val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE

    /**
     * Streaming uses a dedicated client so a long-lived response is not cut
     * off by the JSON request timeouts. The inactivity watchdog in
     * [OpenAICompatibleStreamer] bounds a stalled stream instead.
     */
    private val streamClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val streamer = OpenAICompatibleStreamer(
        preset = preset,
        client = streamClient,
        attribution = attribution,
        now = now,
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = random,
        sleep = sleep,
    )

    /**
     * Validate a provider key.
     *
     * OpenRouter uses `GET /key` and maps the rich key metadata. Other
     * OpenAI-compatible presets validate by loading their discovery endpoint
     * and return a generic key result. The key is sent only in a header, never
     * in the URL.
     */
    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo {
        val keyValidation = preset.keyValidation ?: return emptyKeyInfo()

        if (requiresKey(preset.auth) && context.apiKey.isNullOrBlank()) {
            throw ProviderRequestException(
                code = ChatErrorCode.AUTH,
                message = preset.messages.missingKey,
                providerId = context.provider.id,
            )
        }

        val payload = requestJson(context, keyValidation.path, includeAuth = true)

        return try {
            if (preset.modelDiscovery.format == ModelDiscoveryFormat.OPENROUTER) {
                mapOpenRouterKey(payload)
            } else {
                mapModels(payload, preset)
                emptyKeyInfo()
            }
        } catch (error: OpenRouterCatalogException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PARSE,
                message = preset.messages.invalidKey,
                providerId = context.provider.id,
                cause = error,
            )
        }
    }

    /**
     * Load the provider catalog with the preset's cache TTL and last-known-good
     * fallback.
     *
     * A fresh cache entry is returned without a network request.
     * [forceRefresh] bypasses the TTL. When a refresh fails, the last
     * successful snapshot is returned with `stale: true` and `refreshError`
     * set. If no snapshot exists, the refresh error is thrown.
     */
    suspend fun loadCatalog(
        context: ProviderContext,
        forceRefresh: Boolean = false,
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

    /**
     * Adapter-compatible catalog method.
     *
     * Returns the normalized models. Use [loadCatalog] when the caller needs
     * the cache, staleness, and refresh-error flags.
     */
    override suspend fun listModels(context: ProviderContext): List<ModelInfo> =
        loadCatalog(context).models

    /** Force a catalog refresh while preserving last-known-good behavior. */
    suspend fun refreshCatalog(context: ProviderContext): CatalogResult =
        loadCatalog(context, forceRefresh = true)

    /**
     * Stream a normalized chat response.
     *
     * The returned flow is cold: the request starts when collection starts and
     * is cancelled when collection is cancelled.
     */
    override fun streamChat(
        context: ProviderContext,
        request: NormalizedChatRequest,
    ): Flow<StreamEvent> = streamer.stream(context, request, supportedParameters)

    /**
     * Stream with the model's catalog-supported parameters.
     *
     * Callers that have catalog metadata should use this overload so optional
     * sampling and reasoning controls are only serialized when supported.
     */
    fun streamChat(
        context: ProviderContext,
        request: NormalizedChatRequest,
        supportedParameters: List<String>,
    ): Flow<StreamEvent> = streamer.stream(context, request, supportedParameters)

    private suspend fun fetchModels(context: ProviderContext): List<ModelInfo> {
        val payload = requestJson(
            context,
            preset.modelDiscovery.path,
            includeAuth = !preset.modelDiscovery.public,
        )

        return try {
            mapModels(payload, preset)
        } catch (error: OpenRouterCatalogException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PARSE,
                message = preset.messages.invalidModels,
                providerId = context.provider.id,
                cause = error,
            )
        }
    }

    private suspend fun requestJson(
        context: ProviderContext,
        path: String,
        includeAuth: Boolean,
    ): JsonElement {
        val request = buildJsonRequest(context, path, includeAuth)
        val call = client.newCall(request)

        val response = try {
            execute(call)
        } catch (error: IOException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = preset.messages.network,
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        }

        return withContext(Dispatchers.IO) {
            response.use { result ->
                val body = result.body.string()

                if (!result.isSuccessful) {
                    throw mapHttpError(
                        context = context,
                        preset = preset,
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
                        message = preset.messages.invalidJson,
                        providerId = context.provider.id,
                        status = result.code,
                        cause = error,
                    )
                }
            }
        }
    }

    private fun buildJsonRequest(
        context: ProviderContext,
        path: String,
        includeAuth: Boolean,
    ): Request {
        val builder = Request.Builder()
            .url(joinUrl(context.provider.baseUrl, path))
            .get()
            .header("Accept", "application/json")

        applyDefaultHeaders(builder, context, preset)

        if (includeAuth) {
            context.apiKey?.takeIf { it.isNotBlank() }?.let {
                applyAuth(builder, preset.auth, it)
            }
        }

        applyAttribution(builder, preset, attribution)

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
 * Stream an OpenAI-compatible chat completion as normalized events.
 *
 * The flow is cold: each collection starts a new request. Events are emitted
 * in the order defined by the shared expected vectors: `start`,
 * content/reasoning deltas, `finish`, then `usage`. Terminal failures are
 * emitted as a single `error` event; the flow does not throw for provider,
 * network, or timeout failures. Coroutine cancellation cancels the underlying
 * OkHttp call.
 *
 * Retries happen only before any text or reasoning delta has been emitted. A
 * provider `Retry-After` value overrides the local jittered backoff.
 */
internal class OpenAICompatibleStreamer(
    private val preset: OpenAICompatiblePreset,
    client: OkHttpClient,
    private val attribution: Attribution?,
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
            buildOpenAICompatibleChatRequestBody(request, preset, supportedParameters)
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
                emit(StreamEvent.ErrorEvent(cancelledError(context).toChatError(nowInstant())))
                return@flow
            } catch (thrown: Throwable) {
                val normalized = normalizeThrown(thrown, context, receivedOutput)

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
        val request = buildOpenAICompatibleStreamRequest(context, preset, body, attribution)
        val mapper = StreamMapper(context, preset, emit)

        try {
            collectWithWatchdog(context, transport.stream(request)) { data ->
                mapper.handleData(data)
            }
        } catch (error: SseHttpException) {
            throw mapHttpError(
                context = context,
                preset = preset,
                status = error.code,
                headers = error.headers,
                body = error.body,
                nowMillis = now(),
            )
        } catch (error: SseBufferOverflowException) {
            throw ProviderRequestException(
                code = ChatErrorCode.PROVIDER,
                message = preset.messages.bufferOverflow,
                providerId = context.provider.id,
                cause = error,
            )
        } catch (error: SseReadException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = preset.messages.interrupted,
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        } catch (error: SseTransportException) {
            throw ProviderRequestException(
                code = ChatErrorCode.NETWORK,
                message = preset.messages.interrupted,
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        }

        mapper.finish()
        return mapper.error
    }

    /**
     * Collect [flow] while resetting a watchdog on every event.
     *
     * When no event arrives for [inactivityTimeoutMillis], the producer is
     * cancelled, which cancels the OkHttp call, and a retryable timeout error
     * is thrown.
     */
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
                            message = preset.messages.timeout,
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

/** Maps raw OpenAI-compatible SSE payloads into normalized events. */
private class StreamMapper(
    private val context: ProviderContext,
    private val preset: OpenAICompatiblePreset,
    private val emit: suspend (StreamEvent) -> Unit,
) {
    var error: ProviderRequestException? = null
        private set

    private var started = false
    private var finished = false
    private var responseId: String? = null
    private val emittedReasoning = mutableSetOf<String>()

    suspend fun handleData(data: String) {
        if (error != null || data == "[DONE]") return

        val obj = try {
            Json.parseToJsonElement(data) as? JsonObject
        } catch (parseError: Exception) {
            null
        }

        if (obj == null) {
            fail(
                ProviderRequestException(
                    code = ChatErrorCode.PARSE,
                    message = preset.messages.invalidStreamJson,
                    providerId = context.provider.id,
                ),
            )
            return
        }

        (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { responseId = it }

        if (preset.startBeforeStreamError) {
            ensureStarted()
        }

        val errorEnvelope = obj["error"] as? JsonObject
        if (errorEnvelope != null) {
            fail(streamError(errorEnvelope, context, preset, responseId))
            return
        }

        if (!preset.startBeforeStreamError) {
            ensureStarted()
        }

        val choices = obj["choices"] as? JsonArray
        if (choices != null && choices.isNotEmpty()) {
            val choice = choices[0] as? JsonObject
            if (choice != null) {
                val delta = choice["delta"] as? JsonObject
                if (delta != null) {
                    val text = contentToText(delta["content"])
                    if (text.isNotEmpty()) {
                        emit(StreamEvent.TextDelta(text))
                    }

                    val reasoning = reasoningToText(delta)
                    if (reasoning.isNotEmpty()) {
                        val duplicate = preset.reasoningStyle == ReasoningStyle.OPENROUTER &&
                            emittedReasoning.contains(reasoning)
                        if (!duplicate) {
                            if (preset.reasoningStyle == ReasoningStyle.OPENROUTER) {
                                emittedReasoning.add(reasoning)
                            }
                            emit(StreamEvent.ReasoningDelta(reasoning))
                        }
                    }
                }

                val finishReason = (choice["finish_reason"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                if (finishReason != null) {
                    emit(StreamEvent.Finish(mapFinishReason(finishReason)))
                    finished = true
                }
            }
        }

        val usageEnvelope = obj["usage"] as? JsonObject
        if (usageEnvelope != null) {
            val usage = normalizeUsage(usageEnvelope)
            if (usage != null) {
                emit(StreamEvent.UsageEvent(usage))
            }
        }
    }

    suspend fun finish() {
        if (error != null || !started || finished) return
        emit(StreamEvent.Finish(FinishReason.UNKNOWN))
        finished = true
    }

    private suspend fun ensureStarted() {
        if (started) return
        started = true
        emit(StreamEvent.Start(responseId))
    }

    private fun fail(error: ProviderRequestException) {
        this.error = error
    }
}

private fun protectedAdvancedFields(extra: JsonObject?): List<String> {
    if (extra == null) return emptyList()
    return PROTECTED_ADVANCED_FIELDS.filter { it in extra }.sorted()
}

private fun messageContent(
    parts: List<ContentPart>,
    attachments: Map<String, ResolvedAttachment>,
    multimodal: Boolean,
): JsonElement {
    if (!multimodal) {
        // OpenRouter keeps its M1 text-only content shape.
        return JsonPrimitive(
            parts.joinToString("") { part ->
                if (part is ContentPart.Text) part.text else ""
            },
        )
    }

    if (parts.none { it is ContentPart.Image }) {
        return JsonPrimitive(
            parts.joinToString("") { part ->
                when (part) {
                    is ContentPart.Text -> part.text
                    is ContentPart.File -> fileText(part, attachments)
                    else -> ""
                }
            },
        )
    }

    return buildJsonArray {
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
                        ?: throw missingAttachment(part.attachmentId)
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put(
                                "image_url",
                                buildJsonObject {
                                    put(
                                        "url",
                                        "data:${attachment.mimeType.ifEmpty { part.mimeType }}" +
                                            ";base64,${attachment.dataBase64}",
                                    )
                                },
                            )
                        },
                    )
                }

                is ContentPart.File -> {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", fileText(part, attachments))
                        },
                    )
                }

                else -> Unit
            }
        }
    }
}

private fun fileText(
    part: ContentPart.File,
    attachments: Map<String, ResolvedAttachment>,
): String {
    val attachment = attachments[part.attachmentId]
        ?: throw missingAttachment(part.attachmentId)
    val name = attachment.name ?: part.name
    val body = if (attachment.mimeType.startsWith("text/", ignoreCase = true)) {
        String(Base64.getDecoder().decode(attachment.dataBase64), Charsets.UTF_8)
    } else {
        ""
    }

    return "\n\n[File: $name]\n$body"
}

private fun missingAttachment(attachmentId: String): ProviderRequestException =
    ProviderRequestException(
        code = ChatErrorCode.INVALID_REQUEST,
        message = "Missing attachment $attachmentId for the OpenAI-compatible request.",
    )

private fun NormalizedRole.wireName(): String = when (this) {
    NormalizedRole.USER -> "user"
    NormalizedRole.ASSISTANT -> "assistant"
    NormalizedRole.TOOL -> "tool"
}

private fun ReasoningEffort.wireName(): String = when (this) {
    ReasoningEffort.LOW -> "low"
    ReasoningEffort.MEDIUM -> "medium"
    ReasoningEffort.HIGH -> "high"
}

private fun contentToText(content: JsonElement?): String = when (content) {
    is JsonPrimitive -> if (content.isString) content.content else ""
    is JsonArray -> content.joinToString("") { part ->
        when (part) {
            is JsonPrimitive -> if (part.isString) part.content else ""
            is JsonObject -> (part["text"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?: ""
            else -> ""
        }
    }
    else -> ""
}

private fun reasoningToText(delta: JsonObject): String {
    val details = delta["reasoning_details"] as? JsonArray
    if (details != null) {
        val text = details.joinToString("") { part ->
            (part as? JsonObject)
                ?.let { (it["text"] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
                ?: ""
        }
        if (text.isNotEmpty()) return text
    }

    (delta["reasoning"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }

    // DeepSeek and several OpenAI-compatible providers use this field.
    return (delta["reasoning_content"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: ""
}

private fun mapFinishReason(reason: String): FinishReason = when (reason) {
    "stop" -> FinishReason.STOP
    "length" -> FinishReason.LENGTH
    "content_filter" -> FinishReason.CONTENT_FILTER
    "tool_calls" -> FinishReason.TOOL_CALLS
    else -> FinishReason.UNKNOWN
}

private fun normalizeUsage(raw: JsonObject): Usage? {
    val inputTokens = (raw["prompt_tokens"] as? JsonPrimitive)?.intOrNull
    val outputTokens = (raw["completion_tokens"] as? JsonPrimitive)?.intOrNull
    val totalTokens = (raw["total_tokens"] as? JsonPrimitive)?.intOrNull
        ?: if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null
    val costUsd = (raw["cost"] as? JsonPrimitive)?.doubleOrNull

    if (inputTokens == null && outputTokens == null && totalTokens == null && costUsd == null) {
        return null
    }

    return Usage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        costUsd = costUsd,
        estimated = false,
    )
}

/** Map an HTTP error response to a normalized provider failure. */
internal fun mapHttpError(
    context: ProviderContext,
    preset: OpenAICompatiblePreset,
    status: Int,
    headers: Headers,
    body: String,
    nowMillis: Long,
): ProviderRequestException {
    val payload = parseJsonObject(body)
    val envelope = payload?.get("error") as? JsonObject
    val rawMessage = (envelope?.get("message") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: (payload?.get("message") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        ?: defaultMessage(status, preset)
    val message = redactSecret(rawMessage, context.apiKey)
    val metadata = envelope?.get("metadata") as? JsonObject
    val retryAfterSeconds = parseRetryAfterSeconds(
        headers["Retry-After"],
        Instant.ofEpochMilli(nowMillis),
    ) ?: (metadata?.get("retry_after_seconds") as? JsonPrimitive)?.doubleOrNull
    val requestId = headers["x-request-id"]
        ?: (envelope?.get("request_id") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    return ProviderRequestException(
        code = statusToCode(status, message, preset.contentFilterOn400),
        message = message,
        providerId = context.provider.id,
        status = status,
        retryable = isRetryableStatus(status),
        retryAfterSeconds = retryAfterSeconds,
        requestId = requestId,
        details = metadataDetails(metadata, retryAfterSeconds),
    )
}

/** Backwards-compatible OpenRouter-only overload. */
internal fun mapHttpError(
    context: ProviderContext,
    status: Int,
    headers: Headers,
    body: String,
    nowMillis: Long,
): ProviderRequestException = mapHttpError(
    context = context,
    preset = openRouterTransportPreset(),
    status = status,
    headers = headers,
    body = body,
    nowMillis = nowMillis,
)

private fun streamStatus(envelope: JsonObject): Int? {
    (envelope["code"] as? JsonPrimitive)?.intOrNull?.let { return it }
    (envelope["status"] as? JsonPrimitive)?.intOrNull?.let { return it }

    val type = (envelope["type"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

    return when (type) {
        "server_error" -> 500
        "rate_limit_error" -> 429
        "invalid_request_error" -> 400
        "authentication_error" -> 401
        "permission_error" -> 403
        "not_found_error" -> 404
        else -> null
    }
}

private fun streamError(
    envelope: JsonObject,
    context: ProviderContext,
    preset: OpenAICompatiblePreset,
    responseId: String?,
): ProviderRequestException {
    val status = streamStatus(envelope)
    val rawMessage = (envelope["message"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: preset.messages.streamError
    val message = redactSecret(rawMessage, context.apiKey)
    val metadata = envelope["metadata"] as? JsonObject

    return ProviderRequestException(
        code = if (status != null) {
            statusToCode(status, message, preset.contentFilterOn400)
        } else {
            ChatErrorCode.PROVIDER
        },
        message = message,
        providerId = context.provider.id,
        status = status,
        retryable = false,
        requestId = responseId,
        details = metadataDetails(metadata, null),
    )
}

private fun metadataDetails(
    metadata: JsonObject?,
    retryAfterSeconds: Double?,
): JsonObject? {
    val details = buildJsonObject {
        (metadata?.get("provider_name") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.let { put("providerName", it) }

        (metadata?.get("raw") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.let { put("raw", it) }

        if (retryAfterSeconds != null) {
            // Encode whole seconds as integers so the details object matches the
            // shared expected vectors, which use `2` rather than `2.0`.
            put(
                "retryAfterSeconds",
                if (retryAfterSeconds % 1.0 == 0.0) {
                    retryAfterSeconds.toLong()
                } else {
                    retryAfterSeconds
                },
            )
        }
    }

    return details.takeIf { it.isNotEmpty() }
}

private fun parseJsonObject(body: String): JsonObject? {
    if (body.isBlank()) return null
    return try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }
}

internal fun statusToCode(
    status: Int,
    message: String,
    detectContentFilterOn400: Boolean = false,
): ChatErrorCode = when {
    status == 401 -> ChatErrorCode.AUTH
    status == 402 -> ChatErrorCode.QUOTA
    status == 403 -> if (MODERATION_PATTERN.containsMatchIn(message)) {
        ChatErrorCode.CONTENT_FILTER
    } else {
        ChatErrorCode.PERMISSION
    }
    status == 404 -> ChatErrorCode.MODEL_NOT_FOUND
    status == 429 -> ChatErrorCode.RATE_LIMIT
    status == 413 -> ChatErrorCode.CONTEXT_LENGTH
    status == 400 -> when {
        detectContentFilterOn400 && CONTENT_FILTER_PATTERN.containsMatchIn(message) ->
            ChatErrorCode.CONTENT_FILTER
        CONTEXT_PATTERN.containsMatchIn(message) -> ChatErrorCode.CONTEXT_LENGTH
        else -> ChatErrorCode.INVALID_REQUEST
    }
    else -> ChatErrorCode.PROVIDER
}

internal fun isRetryableStatus(status: Int): Boolean = status == 429 || status >= 500

internal fun parseRetryAfterSeconds(value: String?, now: Instant): Double? {
    if (value.isNullOrBlank()) return null
    val trimmed = value.trim()

    trimmed.toDoubleOrNull()?.let { seconds ->
        return seconds.takeIf { it >= 0.0 }
    }

    return try {
        val target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        Duration.between(now, target).seconds.toDouble().coerceAtLeast(0.0)
    } catch (_: Exception) {
        null
    }
}

private fun defaultMessage(status: Int, preset: OpenAICompatiblePreset): String =
    preset.messages.status[status] ?: preset.messages.defaultHttp(status)

internal fun redactSecret(message: String, secret: String?): String {
    if (secret.isNullOrEmpty()) return message
    return message.replace(secret, "[redacted]")
}

internal fun joinUrl(baseUrl: String, path: String): String {
    if (path.startsWith("http://", ignoreCase = true) ||
        path.startsWith("https://", ignoreCase = true)
    ) {
        return path
    }
    return "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
}

internal fun ProviderRequestException.toChatError(at: Instant): ChatError = ChatError(
    code = code,
    message = message,
    providerId = providerId,
    status = status,
    retryable = retryable,
    retryAfterSeconds = retryAfterSeconds,
    requestId = requestId,
    details = details,
    at = at,
)

private fun cancelledError(
    context: ProviderContext,
    cause: Throwable? = null,
): ProviderRequestException = ProviderRequestException(
    code = ChatErrorCode.CANCELLED,
    message = "The provider request was cancelled.",
    providerId = context.provider.id,
    cause = cause,
)

private fun normalizeThrown(
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
        message = "The provider request failed.",
        providerId = context.provider.id,
        cause = error,
    )
}

private fun requiresKey(auth: ProviderAuth): Boolean = when (auth.type) {
    ProviderAuthType.BEARER, ProviderAuthType.X_API_KEY, ProviderAuthType.QUERY_KEY -> true
    ProviderAuthType.NONE -> false
}

private fun applyAuth(builder: Request.Builder, auth: ProviderAuth, apiKey: String) {
    when (auth.type) {
        ProviderAuthType.BEARER -> builder.header(
            auth.headerName ?: "Authorization",
            "${auth.scheme ?: "Bearer"} $apiKey",
        )
        ProviderAuthType.X_API_KEY -> builder.header(auth.headerName ?: "x-api-key", apiKey)
        ProviderAuthType.NONE -> Unit
        ProviderAuthType.QUERY_KEY -> throw ProviderRequestException(
            code = ChatErrorCode.INVALID_REQUEST,
            message = "API keys are never placed in URLs. Use a header-based auth style.",
        )
    }
}

private fun applyDefaultHeaders(
    builder: Request.Builder,
    context: ProviderContext,
    preset: OpenAICompatiblePreset,
) {
    preset.defaultHeaders.forEach { (name, value) -> builder.header(name, value) }
    context.provider.headers.forEach { (name, value) -> builder.header(name, value) }
}

private fun applyAttribution(
    builder: Request.Builder,
    preset: OpenAICompatiblePreset,
    attribution: Attribution?,
) {
    preset.attributionHeaders.forEach { (name, value) ->
        val resolved = when (value) {
            "app.origin" -> attribution?.origin
            "app.name" -> attribution?.title
            else -> value
        }
        if (resolved != null) {
            builder.header(name, resolved)
        }
    }
}

private fun emptyKeyInfo(): ProviderKeyInfo = ProviderKeyInfo()

private fun mapModels(
    payload: JsonElement?,
    preset: OpenAICompatiblePreset,
): List<ModelInfo> = when (preset.modelDiscovery.format) {
    ModelDiscoveryFormat.OPENROUTER -> mapOpenRouterModels(payload)
    ModelDiscoveryFormat.OPENAI -> mapOpenAIModels(payload, catalogDefaultsForPreset(preset.id))
    else -> throw OpenRouterCatalogException(
        "Unsupported model discovery format: ${preset.modelDiscovery.format}",
    )
}

// ---------------------------------------------------------------------------
// OpenAI catalog mapping and capability inference
// ---------------------------------------------------------------------------

/** Defaults used when an OpenAI-compatible `/models` response omits metadata. */
data class OpenAICatalogDefaults(
    val inputModalities: List<InputModality>,
    val outputModalities: List<OutputModality>,
    val capabilities: List<ModelCapability>,
    val supportedParameters: List<String>,
)

val OPENAI_CATALOG_DEFAULTS: OpenAICatalogDefaults = OpenAICatalogDefaults(
    inputModalities = listOf(InputModality.TEXT),
    outputModalities = listOf(OutputModality.TEXT),
    capabilities = listOf(ModelCapability.TEXT, ModelCapability.STREAMING),
    supportedParameters = listOf("temperature", "top_p", "max_tokens", "stop", "stream"),
)

private val PRESET_CATALOG_DEFAULTS: Map<String, OpenAICatalogDefaults> = mapOf(
    "anthropic" to OpenAICatalogDefaults(
        inputModalities = listOf(InputModality.TEXT, InputModality.IMAGE),
        outputModalities = listOf(OutputModality.TEXT),
        capabilities = listOf(
            ModelCapability.TEXT,
            ModelCapability.VISION,
            ModelCapability.STREAMING,
        ),
        supportedParameters = listOf("temperature", "top_p", "max_tokens", "stop", "stream"),
    ),
    "google" to OpenAICatalogDefaults(
        inputModalities = listOf(InputModality.TEXT, InputModality.IMAGE, InputModality.FILE),
        outputModalities = listOf(OutputModality.TEXT),
        capabilities = listOf(
            ModelCapability.TEXT,
            ModelCapability.VISION,
            ModelCapability.FILES,
            ModelCapability.STREAMING,
        ),
        supportedParameters = listOf("temperature", "top_p", "max_tokens", "stop", "stream"),
    ),
    "ollama" to OpenAICatalogDefaults(
        inputModalities = listOf(InputModality.TEXT),
        outputModalities = listOf(OutputModality.TEXT),
        capabilities = listOf(ModelCapability.TEXT, ModelCapability.STREAMING),
        supportedParameters = listOf("temperature", "top_p", "max_tokens", "stop", "stream"),
    ),
)

/** Return the capability-inference defaults for one preset id. */
fun catalogDefaultsForPreset(presetId: String): OpenAICatalogDefaults =
    PRESET_CATALOG_DEFAULTS[presetId] ?: OPENAI_CATALOG_DEFAULTS

private val INPUT_MODALITIES: List<InputModality> = listOf(
    InputModality.TEXT,
    InputModality.IMAGE,
    InputModality.FILE,
    InputModality.AUDIO,
    InputModality.VIDEO,
)

private val OUTPUT_MODALITIES: List<OutputModality> = listOf(
    OutputModality.TEXT,
    OutputModality.IMAGE,
    OutputModality.AUDIO,
)

private val CAPABILITY_ORDER: List<ModelCapability> = listOf(
    ModelCapability.TEXT,
    ModelCapability.VISION,
    ModelCapability.FILES,
    ModelCapability.TOOLS,
    ModelCapability.REASONING,
    ModelCapability.STREAMING,
)

private fun inputModality(value: String): InputModality? = when (value) {
    "text" -> InputModality.TEXT
    "image" -> InputModality.IMAGE
    "file" -> InputModality.FILE
    "audio" -> InputModality.AUDIO
    "video" -> InputModality.VIDEO
    else -> null
}

private fun outputModality(value: String): OutputModality? = when (value) {
    "text" -> OutputModality.TEXT
    "image" -> OutputModality.IMAGE
    "audio" -> OutputModality.AUDIO
    else -> null
}

private fun uniqueInputModalities(element: JsonElement?): List<InputModality> {
    val array = element as? JsonArray ?: return emptyList()
    return array
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        .mapNotNull(::inputModality)
        .distinct()
}

private fun uniqueOutputModalities(element: JsonElement?): List<OutputModality> {
    val array = element as? JsonArray ?: return emptyList()
    return array
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        .mapNotNull(::outputModality)
        .distinct()
}

private fun uniqueStrings(element: JsonElement?): List<String> {
    val array = element as? JsonArray ?: return emptyList()
    return array
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        .distinct()
}

private fun orderedCapabilities(element: JsonElement?): List<ModelCapability> {
    val array = element as? JsonArray ?: return emptyList()
    val declared = array
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        .mapNotNull { value -> CAPABILITY_ORDER.firstOrNull { it.wireName() == value } }
        .toSet()
    return CAPABILITY_ORDER.filter { it in declared }
}

private fun inferCapabilities(
    inputModalities: List<InputModality>,
    outputModalities: List<OutputModality>,
    supportedParameters: List<String>,
): List<ModelCapability> {
    val capabilities = mutableSetOf<ModelCapability>()

    if (InputModality.TEXT in inputModalities || OutputModality.TEXT in outputModalities) {
        capabilities += ModelCapability.TEXT
    }

    if (InputModality.IMAGE in inputModalities) {
        capabilities += ModelCapability.VISION
    }

    if (InputModality.FILE in inputModalities) {
        capabilities += ModelCapability.FILES
    }

    if ("tools" in supportedParameters || "tool_choice" in supportedParameters) {
        capabilities += ModelCapability.TOOLS
    }

    if ("reasoning" in supportedParameters || "include_reasoning" in supportedParameters) {
        capabilities += ModelCapability.REASONING
    }

    if ("stream" in supportedParameters) {
        capabilities += ModelCapability.STREAMING
    }

    return CAPABILITY_ORDER.filter { it in capabilities }
}

private fun ModelCapability.wireName(): String = when (this) {
    ModelCapability.TEXT -> "text"
    ModelCapability.VISION -> "vision"
    ModelCapability.FILES -> "files"
    ModelCapability.TOOLS -> "tools"
    ModelCapability.REASONING -> "reasoning"
    ModelCapability.STREAMING -> "streaming"
}

private fun JsonObject.firstPresent(vararg keys: String): JsonElement? {
    keys.forEach { key -> this[key]?.let { return it } }
    return null
}

private fun JsonElement?.positiveIntOrNull(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val value = primitive.doubleOrNull ?: return null
    if (!value.isFinite()) return null
    val integer = value.toLong()
    return if (integer > 0 && integer <= Int.MAX_VALUE) integer.toInt() else null
}

/** Metadata inferred from raw catalog fields and preset defaults. */
data class InferredOpenAIModelMetadata(
    val inputModalities: List<InputModality>,
    val outputModalities: List<OutputModality>,
    val capabilities: List<ModelCapability>,
    val supportedParameters: List<String>,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
)

/**
 * Infer missing model metadata from the shared rule.
 *
 * Raw provider values win. Missing modalities and supported parameters fall
 * back to the preset defaults, missing limits are `null`, and missing
 * capabilities are derived in canonical order from the effective modalities
 * and supported parameters.
 */
fun inferOpenAIModelMetadata(
    raw: JsonObject,
    defaults: OpenAICatalogDefaults = OPENAI_CATALOG_DEFAULTS,
): InferredOpenAIModelMetadata {
    val declaredInput = uniqueInputModalities(
        raw.firstPresent("inputModalities", "input_modalities"),
    )
    val declaredOutput = uniqueOutputModalities(
        raw.firstPresent("outputModalities", "output_modalities"),
    )
    val declaredParameters = uniqueStrings(
        raw.firstPresent("supportedParameters", "supported_parameters"),
    )
    val declaredCapabilities = orderedCapabilities(raw["capabilities"])

    val inputModalities = declaredInput.ifEmpty { defaults.inputModalities }
    val outputModalities = declaredOutput.ifEmpty { defaults.outputModalities }
    val supportedParameters = declaredParameters.ifEmpty { defaults.supportedParameters }
    val capabilities = declaredCapabilities.ifEmpty {
        inferCapabilities(inputModalities, outputModalities, supportedParameters)
    }

    return InferredOpenAIModelMetadata(
        inputModalities = inputModalities,
        outputModalities = outputModalities,
        capabilities = capabilities,
        supportedParameters = supportedParameters,
        contextWindow = raw.firstPresent("contextWindow", "context_window", "context_length")
            .positiveIntOrNull(),
        maxOutputTokens = raw.firstPresent(
            "maxOutputTokens",
            "max_output_tokens",
            "max_completion_tokens",
        ).positiveIntOrNull(),
    )
}

/**
 * Map one OpenAI-compatible model entry.
 *
 * OpenAI's `/models` response only guarantees `id`. Capability metadata is
 * therefore inferred from any raw fields the provider does expose, falling
 * back to the preset defaults. Embedding models are marked hidden so they never
 * become the default chat model.
 */
fun mapOpenAIModel(
    raw: JsonElement?,
    defaults: OpenAICatalogDefaults = OPENAI_CATALOG_DEFAULTS,
): ModelInfo? {
    val obj = raw as? JsonObject ?: return null
    val remoteId = (obj["id"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        .orEmpty()
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
        hidden = remoteId.contains("embedding", ignoreCase = true),
    )
}

/**
 * Map an OpenAI-compatible `/models` envelope to normalized entries.
 *
 * The envelope may be `{ data: [...] }` or a bare array. Malformed entries are
 * skipped; a malformed envelope throws.
 */
fun mapOpenAIModels(
    payload: JsonElement?,
    defaults: OpenAICatalogDefaults = OPENAI_CATALOG_DEFAULTS,
): List<ModelInfo> {
    val entries = when {
        payload is JsonArray -> payload
        payload is JsonObject && payload["data"] is JsonArray ->
            payload.getValue("data") as JsonArray
        else -> throw OpenRouterCatalogException(
            "OpenAI /models response must contain a data array",
        )
    }

    return entries.mapNotNull { mapOpenAIModel(it, defaults) }
}
