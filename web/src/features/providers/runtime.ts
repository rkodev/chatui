import type { Provider } from '../../domain'
import type { ProviderAuthConfig, ProviderContext } from '../../providers/openaiCompatible'
import { getOpenAICompatiblePreset } from '../../providers/presets'
import type { ProviderRuntime } from './types'

function authFor(
  provider: Provider,
  preset: ReturnType<typeof getOpenAICompatiblePreset>,
): ProviderAuthConfig {
  if (preset && preset.auth.type === provider.authType) {
    return { ...preset.auth, type: provider.authType }
  }

  switch (provider.authType) {
    case 'bearer':
      return { type: 'bearer', headerName: 'Authorization', scheme: 'Bearer' }
    case 'x-api-key':
      return { type: 'x-api-key', headerName: 'x-api-key' }
    case 'query-key':
      return { type: 'query-key', queryParam: 'key' }
    case 'none':
      return { type: 'none' }
  }
}

/**
 * Build the adapter context for one provider.
 *
 * The context carries only the provider id, base URL, auth style, non-secret
 * headers, and the API key. The key is passed only to the adapter. It is never
 * written to the provider record, settings, or logs.
 */
export function buildProviderContext(
  provider: Provider,
  runtime: ProviderRuntime,
  apiKey: string | null,
): ProviderContext {
  const preset = getOpenAICompatiblePreset(provider.presetId)
  const context: ProviderContext = {
    provider: { id: provider.id, baseUrl: provider.baseUrl },
    apiKey,
    fetch: runtime.fetch ?? globalThis.fetch,
    auth: authFor(provider, preset),
    headers: {
      ...(preset?.defaultHeaders ?? {}),
      ...provider.headers,
    },
  }

  if (preset) {
    context.preset = preset
  }

  if (runtime.attribution) {
    context.attribution = runtime.attribution
  }

  return context
}
