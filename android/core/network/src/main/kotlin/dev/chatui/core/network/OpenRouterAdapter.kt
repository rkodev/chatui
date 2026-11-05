package dev.chatui.core.network

import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.StreamEvent
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * OpenRouter key validation, public model catalog, and chat streaming.
 *
 * This adapter is a thin OpenRouter preset over the shared preset-driven
 * OpenAI-compatible transport. Its public surface and M1 behavior are
 * unchanged: the same messages, curated extras, attribution headers, retry
 * policy, and catalog cache semantics apply.
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
) : CatalogAdapter {
    override val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE

    private val delegate = OpenAICompatibleAdapter(
        preset = openRouterTransportPreset(),
        client = client,
        attribution = attribution,
        cache = cache,
        now = now,
        retryPolicy = retryPolicy,
        inactivityTimeoutMillis = inactivityTimeoutMillis,
        random = random,
        sleep = sleep,
        supportedParameters = supportedParameters,
    )

    /**
     * Validate an OpenRouter key with `GET /key`.
     *
     * The key is sent only in the `Authorization` header. It is never placed in
     * the URL, and provider error messages are redacted before they reach the
     * caller.
     */
    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo =
        delegate.validateKey(context)

    /**
     * Load the OpenRouter catalog with a 24-hour cache and last-known-good
     * fallback.
     */
    override suspend fun loadCatalog(
        context: ProviderContext,
        forceRefresh: Boolean,
    ): CatalogResult = delegate.loadCatalog(context, forceRefresh)

    /**
     * Adapter-compatible catalog method.
     *
     * Returns the normalized models. Use [loadCatalog] when the caller needs
     * the cache, staleness, and refresh-error flags.
     */
    override suspend fun listModels(context: ProviderContext): List<ModelInfo> =
        delegate.listModels(context)

    /** Force a catalog refresh while preserving last-known-good behavior. */
    override suspend fun refreshCatalog(context: ProviderContext): CatalogResult =
        delegate.refreshCatalog(context)

    /**
     * Stream a normalized chat response.
     *
     * The returned flow is cold: the request starts when collection starts and
     * is cancelled when collection is cancelled.
     */
    override fun streamChat(
        context: ProviderContext,
        request: NormalizedChatRequest,
    ): Flow<StreamEvent> = delegate.streamChat(context, request)

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
    ): Flow<StreamEvent> = delegate.streamChat(context, request, supportedParameters)
}
