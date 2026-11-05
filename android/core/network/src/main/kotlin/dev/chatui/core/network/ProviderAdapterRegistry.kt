package dev.chatui.core.network

import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.StreamEvent
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * Maps a provider kind to its native adapter.
 *
 * The registry is the Android equivalent of the web provider dispatch layer:
 * feature code keeps a single [ProviderAdapter] and the registry selects the
 * native implementation from `context.provider.kind`. OpenAI-compatible
 * providers keep the M1 OpenRouter transport until a later task generalizes
 * per-preset dispatch.
 */
class ProviderAdapterRegistry(
    private val adapters: Map<ProviderKind, ProviderAdapter>,
    private val fallbackKind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
) {
    /** Return the adapter registered for [kind], or null. */
    fun adapterFor(kind: ProviderKind): ProviderAdapter? = adapters[kind]

    /** Return the adapter for [kind], falling back to the default transport. */
    fun requireAdapter(kind: ProviderKind): ProviderAdapter =
        adapters[kind]
            ?: adapters[fallbackKind]
            ?: throw ProviderRequestException(
                code = ChatErrorCode.INVALID_REQUEST,
                message = "No provider adapter is registered for $kind.",
            )

    /** Return the catalog-capable adapter for [kind], or null. */
    fun catalogAdapterFor(kind: ProviderKind): CatalogAdapter? =
        requireAdapter(kind) as? CatalogAdapter

    /** Return the catalog-capable adapter for [kind] or fail clearly. */
    fun requireCatalogAdapter(kind: ProviderKind): CatalogAdapter =
        catalogAdapterFor(kind)
            ?: throw ProviderRequestException(
                code = ChatErrorCode.INVALID_REQUEST,
                message = "The provider adapter for $kind does not support catalog operations.",
            )

    companion object {
        /** Build the production registry with every M2 native adapter. */
        fun create(
            client: OkHttpClient,
            cache: CatalogCache = getDefaultCatalogCache(),
            now: () -> Long = System::currentTimeMillis,
            retryPolicy: OpenRouterRetryPolicy = OpenRouterRetryPolicy(),
            inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
            random: Random = Random.Default,
            sleep: suspend (Long) -> Unit = { delay(it) },
        ): ProviderAdapterRegistry = ProviderAdapterRegistry(
            mapOf(
                ProviderKind.OPENAI_COMPATIBLE to OpenRouterAdapter(
                    client = client,
                    cache = cache,
                    now = now,
                    retryPolicy = retryPolicy,
                    inactivityTimeoutMillis = inactivityTimeoutMillis,
                    random = random,
                    sleep = sleep,
                ),
                ProviderKind.ANTHROPIC to AnthropicAdapter(
                    client = client,
                    cache = cache,
                    now = now,
                    retryPolicy = retryPolicy,
                    inactivityTimeoutMillis = inactivityTimeoutMillis,
                    random = random,
                    sleep = sleep,
                ),
                ProviderKind.GOOGLE_GEMINI to GeminiAdapter(
                    client = client,
                    cache = cache,
                    now = now,
                    retryPolicy = retryPolicy,
                    inactivityTimeoutMillis = inactivityTimeoutMillis,
                    random = random,
                    sleep = sleep,
                ),
            ),
        )
    }
}

/**
 * A [ProviderAdapter] that dispatches each call through a
 * [ProviderAdapterRegistry] using `context.provider.kind`.
 *
 * [kind] reports the fallback transport kind because the interface requires a
 * single value; dispatch never uses it.
 */
class RoutingProviderAdapter(
    private val registry: ProviderAdapterRegistry,
) : ProviderAdapter {
    override val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE

    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo =
        registry.requireAdapter(context.provider.kind).validateKey(context)

    override suspend fun listModels(context: ProviderContext): List<ModelInfo> =
        registry.requireAdapter(context.provider.kind).listModels(context)

    override fun streamChat(
        context: ProviderContext,
        request: NormalizedChatRequest,
    ): Flow<StreamEvent> =
        registry.requireAdapter(context.provider.kind).streamChat(context, request)
}
