import { createContext, useContext } from 'react'
import { z } from 'zod'
import {
  isPresetEnabledInM2,
  providerPresets,
  type ProviderPreset,
} from '../providers/registry'

/**
 * Public runtime configuration served from `/config.json`.
 *
 * This file must never contain secrets. The schema validates the config shape;
 * `loadConfig` also cross-checks preset IDs against `contracts/providers.json`
 * and enforces the M2 enablement contract.
 */
export const configSchema = z
  .object({
    appName: z.string().trim().min(1),
    defaultTheme: z.enum(['system', 'light', 'dark']),
    defaultProviderPreset: z.string().trim().min(1),
    enabledPresets: z.array(z.string().trim().min(1)).min(1),
    allowCustomProviders: z.boolean(),
    openRouter: z
      .object({
        attributionOrigin: z.url(),
        appTitle: z.string().trim().min(1),
      })
      .strict(),
    docsUrl: z.url(),
    telemetry: z.boolean(),
  })
  .strict()

export type AppConfig = z.infer<typeof configSchema>

export type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

interface ConfigErrorOptions {
  cause?: unknown
  issues?: string[]
}

/**
 * Raised when `/config.json` cannot be fetched, parsed, or validated.
 *
 * The bootstrap path renders this error instead of falling back to defaults so
 * a misconfigured deployment fails visibly.
 */
export class ConfigError extends Error {
  readonly issues: string[]

  constructor(message: string, options: ConfigErrorOptions = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'ConfigError'
    this.issues = options.issues ?? []
  }
}

function formatIssues(error: z.ZodError): string[] {
  return error.issues.map((issue) => {
    const path = issue.path.length > 0 ? issue.path.join('.') : '(root)'
    return `${path}: ${issue.message}`
  })
}

/**
 * Cross-check runtime config against the provider contracts.
 *
 * M2 enables the launch OpenAI-compatible presets. OpenRouter remains the
 * contract default and must stay enabled. Custom providers are allowed only
 * when `allowCustomProviders` is true and `custom` is listed in
 * `enabledPresets`. Native-adapter presets stay unavailable until their
 * adapters land.
 */
export function validateConfigPresets(
  config: AppConfig,
  presets: readonly ProviderPreset[] = providerPresets,
): string[] {
  const issues: string[] = []
  const known = new Map(presets.map((preset) => [preset.id, preset]))
  const defaultPreset = presets.find((preset) => preset.default)

  if (!defaultPreset) {
    issues.push('contracts: no provider preset is marked as default')
  } else if (config.defaultProviderPreset !== defaultPreset.id) {
    issues.push(`defaultProviderPreset: must be "${defaultPreset.id}"`)
  }

  if (!known.has(config.defaultProviderPreset)) {
    issues.push(
      `defaultProviderPreset: unknown provider preset "${config.defaultProviderPreset}"`,
    )
  }

  if (!config.enabledPresets.includes(config.defaultProviderPreset)) {
    issues.push(
      `defaultProviderPreset: "${config.defaultProviderPreset}" is not in enabledPresets`,
    )
  }

  const seen = new Set<string>()

  for (const id of config.enabledPresets) {
    if (seen.has(id)) {
      issues.push(`enabledPresets: duplicate provider preset "${id}"`)
      continue
    }

    seen.add(id)

    if (!known.has(id)) {
      issues.push(`enabledPresets: unknown provider preset "${id}"`)
      continue
    }

    if (!isPresetEnabledInM2(id)) {
      issues.push(`enabledPresets: "${id}" is not available in M2`)
      continue
    }

    if (id === 'custom' && !config.allowCustomProviders) {
      issues.push('enabledPresets: "custom" requires allowCustomProviders to be true')
    }
  }

  if (config.allowCustomProviders && !config.enabledPresets.includes('custom')) {
    issues.push('allowCustomProviders: "custom" must be listed in enabledPresets')
  }

  if (defaultPreset && !defaultPreset.required) {
    issues.push(`contracts: default preset "${defaultPreset.id}" must be marked required`)
  }

  return issues
}

/**
 * Fetch and validate the public runtime configuration.
 *
 * The request uses a `no-store` policy so a redeployed `config.json` is picked
 * up without relying on the browser or service-worker cache. Any failure throws
 * `ConfigError`.
 */
export async function loadConfig(
  fetchImpl: FetchLike = fetch,
  presets: readonly ProviderPreset[] = providerPresets,
): Promise<AppConfig> {
  let response: Response

  try {
    response = await fetchImpl('/config.json', { cache: 'no-store' })
  } catch (error) {
    throw new ConfigError(
      'Could not load /config.json. Check that the file is deployed and reachable.',
      { cause: error },
    )
  }

  if (!response.ok) {
    throw new ConfigError(
      `Could not load /config.json: HTTP ${response.status} ${response.statusText}`.trim(),
    )
  }

  let json: unknown

  try {
    json = await response.json()
  } catch (error) {
    throw new ConfigError('/config.json is not valid JSON.', { cause: error })
  }

  const result = configSchema.safeParse(json)

  if (!result.success) {
    const issues = formatIssues(result.error)
    throw new ConfigError(`/config.json is invalid:\n${issues.join('\n')}`, { issues })
  }

  const presetIssues = validateConfigPresets(result.data, presets)

  if (presetIssues.length > 0) {
    throw new ConfigError(`/config.json is invalid:\n${presetIssues.join('\n')}`, {
      issues: presetIssues,
    })
  }

  return result.data
}

export const ConfigContext = createContext<AppConfig | null>(null)

/**
 * Read the validated runtime configuration from the nearest `ConfigContext`.
 *
 * Throws when used outside the bootstrap provider so missing configuration is
 * never silently replaced with defaults.
 */
export function useConfig(): AppConfig {
  const config = useContext(ConfigContext)

  if (config === null) {
    throw new Error('useConfig must be used inside the ConfigContext provider')
  }

  return config
}
