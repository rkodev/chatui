package dev.chatui.core.network

import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.ReasoningEffort
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Usage
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Default inactivity watchdog for an OpenRouter stream. */
internal const val DEFAULT_INACTIVITY_TIMEOUT_MS: Long = 120_000L

/** Parameters that are safe to serialize when the catalog does not say otherwise. */
internal val DEFAULT_SUPPORTED_PARAMETERS: List<String> = listOf(
    "temperature",
    "top_p",
    "max_tokens",
    "stop",
    "stream",
    "tools",
    "tool_choice",
)

private val PROTECTED_ADVANCED_FIELDS: List<String> = listOf(
    "model",
    "messages",
    "stream",
    "stream_options",
)

private val ADVANCED_PASSTHROUGH_FIELDS: List<String> = listOf(
    "provider",
    "models",
    "route",
    "reasoning",
)

private val MODERATION_PATTERN = Regex("moderation|policy|content", RegexOption.IGNORE_CASE)

private val CONTEXT_PATTERN = Regex(
    "context length|context_length|maximum context|too many tokens",
    RegexOption.IGNORE_CASE,
)

/**
 * Retry timing for OpenRouter requests from spec section 7.9.
 *
 * At most [maxAttempts] total attempts, exponential backoff with symmetric
 * jitter, base [baseDelayMillis], capped at [maxDelayMillis]. A provider
 * `Retry-After` value overrides the local backoff, matching the web client.
 */
data class OpenRouterRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(baseDelayMillis > 0) { "baseDelayMillis must be positive" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be >= baseDelayMillis" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be in 0.0..1.0" }
    }

    /** True when another attempt is allowed after [attemptsMade] attempts. */
    fun canRetry(attemptsMade: Int): Boolean = attemptsMade in 1 until maxAttempts

    /** Capped exponential delay for [retryIndex] without jitter. */
    fun backoffMillis(retryIndex: Int): Long {
        require(retryIndex >= 0) { "retryIndex must be non-negative" }
        val exponential = baseDelayMillis.toDouble() * 2.0.pow(retryIndex)
        return minOf(exponential, maxDelayMillis.toDouble()).toLong()
    }

    /** Exponential delay for [retryIndex] with symmetric jitter. */
    fun jitteredBackoffMillis(retryIndex: Int, random: Random): Long {
        val base = backoffMillis(retryIndex)
        if (jitterFraction == 0.0) return base
        val jitter = base * jitterFraction
        val offset = (random.nextDouble() * 2.0 - 1.0) * jitter
        return (base + offset).toLong().coerceIn(0L, maxDelayMillis)
    }

    /**
     * Delay before the next attempt, or `null` when no retry is allowed.
     *
     * A provider `Retry-After` value wins over the local backoff policy.
     */
    fun nextDelayMillis(
        attemptsMade: Int,
        retryAfterSeconds: Long?,
        random: Random,
    ): Long? {
        if (!canRetry(attemptsMade)) return null
        if (retryAfterSeconds != null) {
            return retryAfterSeconds.coerceAtLeast(0) * 1_000L
        }
        return jitteredBackoffMillis(attemptsMade - 1, random)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_BASE_DELAY_MILLIS: Long = 800L
        const val DEFAULT_MAX_DELAY_MILLIS: Long = 8_000L
        const val DEFAULT_JITTER_FRACTION: Double = 0.2
    }
}

/**
 * Build the exact OpenRouter `POST /chat/completions` body.
 *
 * The function is pure so tests can assert the serialized JSON against the
 * shared request vectors. It throws an `invalid_request` error before any
 * network call when advanced JSON tries to override a protected field.
 */
internal fun buildChatRequestBody(
    request: NormalizedChatRequest,
    supportedParameters: List<String> = DEFAULT_SUPPORTED_PARAMETERS,
): JsonObject {
    val supported = supportedParameters.toSet()
    val extra = request.params.extra["openrouter"] as? JsonObject
    val protectedFields = protectedAdvancedFields(extra)

    if (protectedFields.isNotEmpty()) {
        throw OpenRouterRequestException(
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
                    put("content", messageContent(message.parts))
                },
            )
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

        val reasoning = mutableMapOf<String, JsonElement>()
        request.params.reasoningEffort
            ?.takeIf { "reasoning" in supported }
            ?.let { effort -> reasoning["effort"] = JsonPrimitive(effort.wireName()) }

        if (extra != null) {
            val advancedReasoning = extra["reasoning"]
            if ("reasoning" in supported && advancedReasoning is JsonObject) {
                reasoning.putAll(advancedReasoning)
            }

            ADVANCED_PASSTHROUGH_FIELDS.forEach { field ->
                if (field == "reasoning") return@forEach
                extra[field]?.let { put(field, it) }
            }
        }

        if (reasoning.isNotEmpty()) {
            put("reasoning", JsonObject(reasoning))
        }
    }
}

/** Build the streaming request headers, including attribution. */
internal fun buildStreamRequest(
    context: ProviderContext,
    body: JsonObject,
    attribution: OpenRouterAttribution?,
): Request {
    val builder = Request.Builder()
        .url(joinUrl(context.provider.baseUrl, "/chat/completions"))
        .post(body.toString().toRequestBody(null))
        .header("Accept", "text/event-stream")
        .header("Content-Type", "application/json")

    context.apiKey?.takeIf { it.isNotBlank() }?.let {
        builder.header("Authorization", "Bearer $it")
    }

    attribution?.let {
        builder.header("HTTP-Referer", it.origin)
        builder.header("X-Title", it.title)
    }

    return builder.build()
}

/**
 * Stream an OpenRouter chat completion as normalized events.
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
internal class OpenRouterStreamer(
    client: OkHttpClient,
    private val attribution: OpenRouterAttribution?,
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
            buildChatRequestBody(request, supportedParameters)
        } catch (error: OpenRouterRequestException) {
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
    ): OpenRouterRequestException? {
        val request = buildStreamRequest(context, body, attribution)
        val mapper = StreamMapper(context, emit)

        try {
            collectWithWatchdog(context, transport.stream(request)) { data ->
                mapper.handleData(data)
            }
        } catch (error: SseHttpException) {
            throw mapHttpError(context, error.code, error.headers, error.body, now())
        } catch (error: SseBufferOverflowException) {
            throw OpenRouterRequestException(
                code = ChatErrorCode.PROVIDER,
                message = "The OpenRouter stream exceeded the buffer limit.",
                providerId = context.provider.id,
                cause = error,
            )
        } catch (error: SseReadException) {
            throw OpenRouterRequestException(
                code = ChatErrorCode.NETWORK,
                message = "The OpenRouter stream was interrupted.",
                providerId = context.provider.id,
                retryable = true,
                cause = error,
            )
        } catch (error: SseTransportException) {
            throw OpenRouterRequestException(
                code = ChatErrorCode.NETWORK,
                message = "The OpenRouter stream was interrupted.",
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
                        throw OpenRouterRequestException(
                            code = ChatErrorCode.TIMEOUT,
                            message = "OpenRouter stopped responding.",
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

/** Maps raw OpenRouter SSE payloads into normalized events. */
private class StreamMapper(
    private val context: ProviderContext,
    private val emit: suspend (StreamEvent) -> Unit,
) {
    var error: OpenRouterRequestException? = null
        private set

    private var started = false
    private var finished = false
    private var responseId: String? = null
    private val emittedReasoning = mutableSetOf<String>()

    suspend fun handleData(data: String) {
        if (error != null || data == "[DONE]") return

        val obj = try {
            Json.decodeFromString(JsonObject.serializer(), data)
        } catch (parseError: Exception) {
            fail(
                OpenRouterRequestException(
                    code = ChatErrorCode.PARSE,
                    message = "OpenRouter stream contained invalid JSON.",
                    providerId = context.provider.id,
                    cause = parseError,
                ),
            )
            return
        }

        (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { responseId = it }

        ensureStarted()

        val errorEnvelope = obj["error"] as? JsonObject
        if (errorEnvelope != null) {
            fail(streamError(errorEnvelope, context, responseId))
            return
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
                    if (reasoning.isNotEmpty() && emittedReasoning.add(reasoning)) {
                        emit(StreamEvent.ReasoningDelta(reasoning))
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

    private fun fail(error: OpenRouterRequestException) {
        this.error = error
    }
}

private fun protectedAdvancedFields(extra: JsonObject?): List<String> {
    if (extra == null) return emptyList()
    return PROTECTED_ADVANCED_FIELDS.filter { it in extra }.sorted()
}

private fun messageContent(parts: List<ContentPart>): String =
    parts.joinToString("") { part ->
        when (part) {
            is ContentPart.Text -> part.text
            else -> ""
        }
    }

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

    return (delta["reasoning"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
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

/** Map an HTTP error response to a normalized OpenRouter failure. */
internal fun mapHttpError(
    context: ProviderContext,
    status: Int,
    headers: Headers,
    body: String,
    nowMillis: Long,
): OpenRouterRequestException {
    val payload = parseJsonObject(body)
    val envelope = payload?.get("error") as? JsonObject
    val rawMessage = (envelope?.get("message") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: (payload?.get("message") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        ?: defaultMessage(status)
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

    return OpenRouterRequestException(
        code = statusToCode(status, message),
        message = message,
        providerId = context.provider.id,
        status = status,
        retryable = isRetryableStatus(status),
        retryAfterSeconds = retryAfterSeconds,
        requestId = requestId,
        details = metadataDetails(metadata, retryAfterSeconds),
    )
}

private fun streamError(
    envelope: JsonObject,
    context: ProviderContext,
    responseId: String?,
): OpenRouterRequestException {
    val status = (envelope["code"] as? JsonPrimitive)?.intOrNull
    val rawMessage = (envelope["message"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: "OpenRouter stream returned an error."
    val message = redactSecret(rawMessage, context.apiKey)
    val metadata = envelope["metadata"] as? JsonObject

    return OpenRouterRequestException(
        code = if (status != null) statusToCode(status, message) else ChatErrorCode.PROVIDER,
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

internal fun statusToCode(status: Int, message: String): ChatErrorCode = when {
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
    status == 400 -> if (CONTEXT_PATTERN.containsMatchIn(message)) {
        ChatErrorCode.CONTEXT_LENGTH
    } else {
        ChatErrorCode.INVALID_REQUEST
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

private fun defaultMessage(status: Int): String = when (status) {
    401 -> "Authentication failed. Check your OpenRouter API key."
    402 -> "Insufficient credits. Add more credits to continue."
    403 -> "OpenRouter blocked the request."
    404 -> "The requested model was not found."
    429 -> "Rate limit exceeded. Please retry later."
    503 -> "No available upstream provider could serve the request."
    else -> "OpenRouter request failed with HTTP $status"
}

internal fun redactSecret(message: String, secret: String?): String {
    if (secret.isNullOrEmpty()) return message
    return message.replace(secret, "[redacted]")
}

internal fun joinUrl(baseUrl: String, path: String): String =
    "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

internal fun OpenRouterRequestException.toChatError(at: Instant): ChatError = ChatError(
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
): OpenRouterRequestException = OpenRouterRequestException(
    code = ChatErrorCode.CANCELLED,
    message = "OpenRouter request was cancelled.",
    providerId = context.provider.id,
    cause = cause,
)

private fun normalizeThrown(
    error: Throwable,
    context: ProviderContext,
    receivedOutput: Boolean,
): OpenRouterRequestException {
    if (error is OpenRouterRequestException) {
        if (receivedOutput &&
            (error.code == ChatErrorCode.NETWORK || error.code == ChatErrorCode.TIMEOUT)
        ) {
            return error.withRetryable(false)
        }
        return error
    }

    return OpenRouterRequestException(
        code = ChatErrorCode.UNKNOWN,
        message = "OpenRouter request failed.",
        providerId = context.provider.id,
        cause = error,
    )
}
