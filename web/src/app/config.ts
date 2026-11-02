import { createContext, useContext } from 'react'
import { z } from 'zod'

/**
 * Public runtime configuration served from `/config.json`.
 *
 * This file must never contain secrets. Validation is intentionally shape-only
 * in M0: `enabledPresets` is not cross-checked against `contracts/providers.json`
 * until M1, when provider presets are loaded at runtime.
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
 * Fetch and validate the public runtime configuration.
 *
 * The request uses a no-cache policy so a redeployed `config.json` is picked up
 * without relying on the browser cache. Any failure throws `ConfigError`.
 */
export async function loadConfig(fetchImpl: FetchLike = fetch): Promise<AppConfig> {
  let response: Response

  try {
    response = await fetchImpl('/config.json', { cache: 'no-cache' })
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
