import { z } from 'zod'
import providersJson from '../../../contracts/providers.json'

/**
 * Provider presets are shared contracts. The values live in
 * `contracts/providers.json`; this module only describes their shape and
 * validates the imported document at runtime.
 */
export const providerKindSchema = z.enum(['openai-compatible', 'anthropic', 'google-gemini'])

export const providerAuthTypeSchema = z.enum(['bearer', 'x-api-key', 'query-key', 'none'])

export const browserCorsSchema = z.enum(['supported', 'unsupported', 'unknown'])

export const modelDiscoveryFormatSchema = z.enum([
  'openrouter',
  'openai',
  'anthropic',
  'gemini',
  'ollama-tags',
])

const httpUrlSchema = z
  .url()
  .refine((value) => /^https?:\/\//.test(value), 'Must be an http(s) URL')

export const providerAuthSchema = z
  .object({
    type: providerAuthTypeSchema,
    headerName: z.string().min(1).optional(),
    scheme: z.string().min(1).optional(),
    queryParam: z.string().min(1).optional(),
  })
  .strict()

export const keyValidationSchema = z
  .object({
    method: z.literal('GET'),
    path: z.string().min(1),
  })
  .strict()
  .nullable()

export const modelDiscoverySchema = z
  .object({
    path: z.string().min(1),
    format: modelDiscoveryFormatSchema,
    public: z.boolean(),
    cacheTtlSeconds: z.number().int().min(0),
  })
  .strict()

export const providerPresetSchema = z
  .object({
    id: z.string().regex(/^[a-z][a-z0-9-]*$/),
    label: z.string().min(1),
    kind: providerKindSchema,
    baseUrl: httpUrlSchema,
    auth: providerAuthSchema,
    keyValidation: keyValidationSchema,
    modelDiscovery: modelDiscoverySchema,
    defaultHeaders: z.record(z.string(), z.string()),
    attributionHeaders: z.record(z.string(), z.string()),
    browserCors: browserCorsSchema,
    docsUrl: httpUrlSchema.nullable(),
    defaultModelId: z.string().min(1),
    default: z.boolean(),
    reference: z.boolean(),
    required: z.boolean(),
  })
  .strict()

export type ProviderPreset = z.infer<typeof providerPresetSchema>

export class ProviderRegistryError extends Error {
  readonly issues: string[]

  constructor(message: string, issues: string[] = []) {
    super(message)
    this.name = 'ProviderRegistryError'
    this.issues = issues
  }
}

function formatIssues(error: z.ZodError): string[] {
  return error.issues.map((issue) => {
    const path = issue.path.length > 0 ? issue.path.join('.') : '(root)'
    return `${path}: ${issue.message}`
  })
}

/**
 * Validate and return provider presets.
 *
 * The default input is the checked-in contracts document. Tests pass malformed
 * documents directly so validation failures are visible without touching the
 * contracts tree.
 */
export function loadProviderPresets(input: unknown = providersJson): ProviderPreset[] {
  const result = z.array(providerPresetSchema).safeParse(input)

  if (!result.success) {
    const issues = formatIssues(result.error)
    throw new ProviderRegistryError(
      `Provider presets are invalid:\n${issues.join('\n')}`,
      issues,
    )
  }

  const seen = new Set<string>()
  const duplicates = new Set<string>()

  for (const preset of result.data) {
    if (seen.has(preset.id)) {
      duplicates.add(preset.id)
    }
    seen.add(preset.id)
  }

  if (duplicates.size > 0) {
    const ids = [...duplicates].sort()
    throw new ProviderRegistryError(
      `Duplicate provider preset id(s): ${ids.join(', ')}`,
      ids.map((id) => `id: duplicate "${id}"`),
    )
  }

  return result.data
}

/**
 * The validated contracts document. Importing this module fails loudly when
 * the checked-in presets are malformed.
 */
export const providerPresets: readonly ProviderPreset[] = loadProviderPresets()

export function getPreset(
  id: string,
  presets: readonly ProviderPreset[] = providerPresets,
): ProviderPreset | undefined {
  return presets.find((preset) => preset.id === id)
}

/**
 * Return the single preset marked `default: true` in contracts.
 *
 * Missing or ambiguous defaults are configuration errors, not a reason to
 * silently pick the first preset.
 */
export function getDefaultPreset(
  presets: readonly ProviderPreset[] = providerPresets,
): ProviderPreset {
  const defaults = presets.filter((preset) => preset.default)

  if (defaults.length === 0) {
    throw new ProviderRegistryError('No provider preset is marked as default')
  }

  if (defaults.length > 1) {
    throw new ProviderRegistryError(
      `Multiple provider presets are marked as default: ${defaults
        .map((preset) => preset.id)
        .join(', ')}`,
    )
  }

  return defaults[0]
}

/**
 * M1 exposes OpenRouter only. Other presets remain in contracts so later
 * milestones can enable them without duplicating provider metadata.
 */
export const M1_ENABLED_PRESET_IDS = ['openrouter'] as const

export type M1EnabledPresetId = (typeof M1_ENABLED_PRESET_IDS)[number]

export function isPresetEnabledInM1(id: string): boolean {
  return (M1_ENABLED_PRESET_IDS as readonly string[]).includes(id)
}

export type PresetAvailabilityStatus = 'available' | 'coming-later'

export interface PresetAvailability {
  preset: ProviderPreset
  enabled: boolean
  selectable: boolean
  status: PresetAvailabilityStatus
}

/**
 * Describe every contract preset for UI code. Only OpenRouter is selectable in
 * M1; the rest are labelled as coming later rather than hidden from contracts.
 */
export function getPresetAvailability(
  presets: readonly ProviderPreset[] = providerPresets,
): PresetAvailability[] {
  return presets.map((preset) => {
    const enabled = isPresetEnabledInM1(preset.id)

    return {
      preset,
      enabled,
      selectable: enabled,
      status: enabled ? 'available' : 'coming-later',
    }
  })
}

export function getEnabledPresets(
  presets: readonly ProviderPreset[] = providerPresets,
): ProviderPreset[] {
  return presets.filter((preset) => isPresetEnabledInM1(preset.id))
}
