package dev.chatui.core.providers

import dev.chatui.core.network.ModelInfo
import dev.chatui.core.network.OpenRouterAdapter
import dev.chatui.core.network.ProviderAdapterRegistry
import dev.chatui.core.network.ProviderContext
import dev.chatui.core.network.ProviderKeyInfo

/**
 * Provider operations used by the provider and model services.
 *
 * The interface keeps the services independent of the concrete OpenRouter
 * adapter so tests can inject fixture data without a network request.
 */
interface ProviderGateway {
    /** Validate the supplied key and return normalized key metadata. */
    suspend fun validateKey(context: ProviderContext): ProviderKeyInfo

    /**
     * List the provider catalog.
     *
     * When [forceRefresh] is true the implementation bypasses the catalog TTL
     * and performs a discovery request.
     */
    suspend fun listModels(
        context: ProviderContext,
        forceRefresh: Boolean = false,
    ): List<ModelInfo>
}

/**
 * [ProviderGateway] backed by the OpenRouter adapter.
 *
 * A forced refresh uses [OpenRouterAdapter.refreshCatalog] so the adapter's
 * last-known-good cache is updated. A stale result with a refresh error is
 * surfaced to the caller rather than silently treated as a successful refresh.
 */
class OpenRouterGateway(
    private val adapter: OpenRouterAdapter,
) : ProviderGateway {
    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo =
        adapter.validateKey(context)

    override suspend fun listModels(
        context: ProviderContext,
        forceRefresh: Boolean,
    ): List<ModelInfo> {
        if (!forceRefresh) {
            return adapter.listModels(context)
        }

        val result = adapter.refreshCatalog(context)
        result.refreshError?.let { error ->
            if (result.stale) {
                throw error
            }
        }
        return result.models
    }
}

/**
 * [ProviderGateway] that dispatches to the native adapter for the provider's
 * kind.
 *
 * OpenAI-compatible providers keep the M1 OpenRouter transport. Anthropic
 * providers use the native Anthropic adapter for key validation and catalog
 * discovery.
 */
class RoutingProviderGateway(
    private val adapters: ProviderAdapterRegistry,
) : ProviderGateway {
    override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo =
        adapters.requireAdapter(context.provider.kind).validateKey(context)

    override suspend fun listModels(
        context: ProviderContext,
        forceRefresh: Boolean,
    ): List<ModelInfo> {
        val adapter = adapters.requireCatalogAdapter(context.provider.kind)

        if (!forceRefresh) {
            return adapter.listModels(context)
        }

        val result = adapter.refreshCatalog(context)
        result.refreshError?.let { error ->
            if (result.stale) {
                throw error
            }
        }
        return result.models
    }
}
