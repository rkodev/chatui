package dev.chatui.core.network

import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.StreamEvent
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Attribution headers supplied by runtime config. */
data class OpenRouterAttribution(
    val origin: String,
    val title: String,
)

/**
 * Normalized OpenRouter request failure.
 *
 * [message] is safe to show to a user: the API key is redacted before the
 * exception is created. The key is never placed in a URL or a log line.
 */
class OpenRouterRequestException(
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
    fun withRetryable(retryable: Boolean): OpenRouterRequestException =
        OpenRouterRequestException(
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
    fun withProviderId(providerId: String): OpenRouterRequestException =
        OpenRouterRequestException(
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

/**
 * OpenRouter key validation, public model catalog, and chat streaming.
 *
 * The key and catalog methods use the same mapping and cache semantics as the
 * web client. [streamChat] emits normalized events and never throws for
 * provider, network, or timeout failures; coroutine cancellation cancels the
 * underlying OkHttp call.
 */
class OpenRouterAdapter(
    private val client: OkHttpClient,
    private val attribution: OpenRouterAttribution? = null,
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
     * off by the JSON request timeouts. The 120 s inactivity watchdog in
     * [OpenRouterStreamer] bounds a stalled stream instead.
     */
    private val streamClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val streamer = OpenRouterStreamer(
        client = streamClient,
        attribution = attribution,
        now = now,
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = random,
        sleep = sleep,
    )

    /**
     * Validate an OpenRouter key with `GET /key`.
     *
     * The key is sent only in the `Authorization` header. It is never placed in
     * the URL, and provider error messages are redacted before they reach the
     * caller.
     */
    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo {
        if (context.apiKey.isNullOrBlank()) {
            throw OpenRouterRequestException(
                code = ChatErrorCode.AUTH,
                message = "An OpenRouter API key is required.",
                providerId = context.provider.id,
            )
        }

        val payload = requestJson(context, "/key", includeAuth = true)

        return try {
            mapOpenRouterKey(payload)
        } catch (error: OpenRouterCatalogException) {
            throw OpenRouterRequestException(
                code = ChatErrorCode.PARSE,
                message = "OpenRouter /key response was not valid.",
                providerId = context.provider.id,
                cause = error,
            )
        }
    }

    /**
     * Load the OpenRouter catalog with a 24-hour cache and last-known-good
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
        val cacheKey = openRouterCatalogCacheKey(context.provider.id)

        val cached = try {
            cache.read(cacheKey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

        if (cached != null && !forceRefresh && isCatalogFresh(cached, now())) {
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
        val payload = requestJson(context, "/models", includeAuth = false)

        return try {
            mapOpenRouterModels(payload)
        } catch (error: OpenRouterCatalogException) {
            throw OpenRouterRequestException(
                code = ChatErrorCode.PARSE,
                message = "OpenRouter /models response was not valid.",
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
        val request = buildRequest(context, joinUrl(context.provider.baseUrl, path), includeAuth)
        val call = client.newCall(request)

        val response = try {
            execute(call)
        } catch (error: IOException) {
            throw OpenRouterRequestException(
                code = ChatErrorCode.NETWORK,
                message = "Could not reach OpenRouter.",
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
                        status = result.code,
                        headers = result.headers,
                        body = body,
                        nowMillis = now(),
                    )
                }

                try {
                    Json.parseToJsonElement(body)
                } catch (error: Exception) {
                    throw OpenRouterRequestException(
                        code = ChatErrorCode.PARSE,
                        message = "OpenRouter returned invalid JSON.",
                        providerId = context.provider.id,
                        status = result.code,
                        cause = error,
                    )
                }
            }
        }
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

    private fun buildRequest(
        context: ProviderContext,
        url: String,
        includeAuth: Boolean,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")

        if (includeAuth) {
            context.apiKey?.takeIf { it.isNotBlank() }?.let {
                builder.header("Authorization", "Bearer $it")
            }
        }

        attribution?.let {
            builder.header("HTTP-Referer", it.origin)
            builder.header("X-Title", it.title)
        }

        return builder.build()
    }
}
