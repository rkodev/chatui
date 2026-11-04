import type { Provider } from '../../domain'
import type { ProviderContext } from '../../providers/openrouter'
import type { ProviderRuntime } from './types'

/**
 * Build the adapter context for one provider.
 *
 * The API key is passed only to the adapter. It is never written to the
 * provider record, settings, or logs.
 */
export function buildProviderContext(
  provider: Provider,
  runtime: ProviderRuntime,
  apiKey: string | null,
): ProviderContext {
  const context: ProviderContext = {
    provider: { id: provider.id, baseUrl: provider.baseUrl },
    apiKey,
    fetch: runtime.fetch ?? globalThis.fetch,
  }

  if (runtime.attribution) {
    context.attribution = runtime.attribution
  }

  return context
}
