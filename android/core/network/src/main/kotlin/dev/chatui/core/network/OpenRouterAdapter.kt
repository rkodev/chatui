package dev.chatui.core.network

import dev.chatui.core.model.ChatErrorCode
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * OpenRouter key validation and public model catalog.
 *
 * This class intentionally does not implement [ProviderAdapter] yet: chat
 * streaming is added by M1-06.4. The key and catalog methods already use the
 * same mapping and cache semantics as the web client.
 */
class OpenRouterAdapter(
    private val client: OkHttpClient,
    private val attribution: OpenRouterAttribution? = null,
    private val cache: CatalogCache = getDefaultCatalogCache(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Validate an OpenRouter key with `GET /key`.
     *
     * The key is sent only in the `Authorization` header. It is never placed in
     * the URL, and provider error messages are redacted before they reach the
     * caller.
     */
    suspend fun validateKey(context: ProviderContext): ProviderKeyInfo {
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
    suspend fun listModels(context: ProviderContext): List<ModelInfo> =
        loadCatalog(context).models

    /** Force a catalog refresh while preserving last-known-good behavior. */
    suspend fun refreshCatalog(context: ProviderContext): CatalogResult =
        loadCatalog(context, forceRefresh = true)

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
                    throw httpError(
                        context = context,
                        status = result.code,
                        retryAfter = result.header("Retry-After"),
                        body = body,
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

    private fun httpError(
        context: ProviderContext,
        status: Int,
        retryAfter: String?,
        body: String,
    ): OpenRouterRequestException = OpenRouterRequestException(
        code = statusToCode(status),
        message = redactSecret(readErrorMessage(status, body), context.apiKey),
        providerId = context.provider.id,
        status = status,
        retryable = isRetryableStatus(status),
        retryAfterSeconds = parseRetryAfter(retryAfter),
    )
}

private fun joinUrl(baseUrl: String, path: String): String =
    "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

private fun redactSecret(message: String, secret: String?): String {
    if (secret.isNullOrEmpty()) {
        return message
    }

    return message.replace(secret, "[redacted]")
}

private fun statusToCode(status: Int): ChatErrorCode = when (status) {
    401 -> ChatErrorCode.AUTH
    402 -> ChatErrorCode.QUOTA
    403 -> ChatErrorCode.PERMISSION
    404 -> ChatErrorCode.MODEL_NOT_FOUND
    429 -> ChatErrorCode.RATE_LIMIT
    else -> ChatErrorCode.PROVIDER
}

private fun isRetryableStatus(status: Int): Boolean = status == 429 || status >= 500

private fun parseRetryAfter(value: String?): Double? {
    if (value.isNullOrBlank()) {
        return null
    }

    val seconds = value.trim().toDoubleOrNull() ?: return null
    return seconds.takeIf { it >= 0.0 }
}

private fun readErrorMessage(status: Int, body: String): String {
    if (body.isBlank()) {
        return "OpenRouter request failed with HTTP $status"
    }

    return try {
        val parsed = Json.parseToJsonElement(body)
        val obj = parsed as? JsonObject
        val error = obj?.get("error") as? JsonObject
        val errorMessage = (error?.get("message") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        val message = (obj?.get("message") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

        errorMessage ?: message ?: body.take(500)
    } catch (_: Exception) {
        body.take(500)
    }
}
