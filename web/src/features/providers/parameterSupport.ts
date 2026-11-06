import type { ChatParams } from '../../domain'

/**
 * Protected request fields from spec 6.2. The request builders reject these in
 * provider extras, and the editor rejects them before persistence so users see
 * the problem while editing rather than at send time.
 */
export const PROTECTED_EXTRA_FIELDS = ['model', 'messages', 'stream', 'stream_options'] as const

export type ProtectedExtraField = (typeof PROTECTED_EXTRA_FIELDS)[number]

export type CoreParameterKey =
  'temperature' | 'topP' | 'maxOutputTokens' | 'stop' | 'reasoningEffort'

export interface CoreParameterDefinition {
  key: CoreParameterKey
  label: string
  /** Provider-native parameter names that can carry this value. */
  supportedNames: readonly string[]
}

/**
 * Core generation controls and the provider parameter names they map to.
 *
 * A value is considered supported when the model reports at least one of these
 * names. Values are never deleted when unsupported; adapters omit them from the
 * request and the UI explains the omission.
 */
export const CORE_PARAMETER_DEFINITIONS: readonly CoreParameterDefinition[] = [
  { key: 'temperature', label: 'Temperature', supportedNames: ['temperature'] },
  { key: 'topP', label: 'Top P', supportedNames: ['top_p'] },
  { key: 'maxOutputTokens', label: 'Max output tokens', supportedNames: ['max_tokens'] },
  { key: 'stop', label: 'Stop sequences', supportedNames: ['stop'] },
  {
    key: 'reasoningEffort',
    label: 'Reasoning effort',
    supportedNames: ['reasoning', 'reasoning_effort'],
  },
]

export interface ProviderExtrasParseResult {
  ok: boolean
  value?: Record<string, unknown>
  message?: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * The shared extras namespace rule.
 *
 * Every adapter reads `params.extra[namespace]`, where the namespace is the
 * provider preset id. Gemini's preset id is `google`, matching its adapter.
 */
export function extraNamespaceForPreset(presetId: string): string {
  return presetId
}

/** Read this provider's extras object, or an empty object when absent. */
export function readProviderExtras(params: ChatParams, namespace: string): Record<string, unknown> {
  const extra = params.extra[namespace]
  return isRecord(extra) ? extra : {}
}

/** Serialize this provider's extras for the JSON editor. */
export function stringifyProviderExtras(params: ChatParams, namespace: string): string {
  const extra = readProviderExtras(params, namespace)
  return Object.keys(extra).length === 0 ? '' : JSON.stringify(extra, null, 2)
}

/**
 * Return a copy of `params` with one namespace replaced.
 *
 * An empty object removes the namespace entirely so conversations do not carry
 * meaningless `{}` entries. Other provider namespaces are preserved.
 */
export function withProviderExtras(
  params: ChatParams,
  namespace: string,
  extras: Record<string, unknown>,
): ChatParams {
  const extra = { ...params.extra }

  if (Object.keys(extras).length === 0) {
    delete extra[namespace]
  } else {
    extra[namespace] = extras
  }

  return {
    ...params,
    stop: [...params.stop],
    extra,
  }
}

/**
 * Drop extra namespaces that belong to a different provider preset.
 *
 * Called when a model switch changes provider so stale advanced options cannot
 * silently follow the conversation into another provider. Core parameters are
 * preserved regardless of the new model's support list.
 */
export function pruneExtrasForPreset(
  extra: Record<string, unknown>,
  presetId: string,
): Record<string, unknown> {
  const namespace = extraNamespaceForPreset(presetId)

  return namespace in extra ? { [namespace]: extra[namespace] } : {}
}

/**
 * Parse provider extras from the editor.
 *
 * Accepts an empty string as an empty object, rejects non-object JSON, and
 * rejects protected core fields with a message listing them.
 */
export function parseProviderExtras(text: string): ProviderExtrasParseResult {
  const trimmed = text.trim()

  if (trimmed === '') {
    return { ok: true, value: {} }
  }

  let parsed: unknown

  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return { ok: false, message: 'Provider extras must be valid JSON.' }
  }

  if (!isRecord(parsed)) {
    return { ok: false, message: 'Provider extras must be a JSON object.' }
  }

  const protectedFields = PROTECTED_EXTRA_FIELDS.filter((field) => field in parsed).sort()

  if (protectedFields.length > 0) {
    return {
      ok: false,
      message: `Provider extras cannot override protected fields: ${protectedFields.join(', ')}.`,
    }
  }

  return { ok: true, value: parsed }
}

function hasValue(params: ChatParams, key: CoreParameterKey): boolean {
  if (key === 'stop') {
    return params.stop.length > 0
  }

  return params[key] !== null
}

/** Core parameters with a value that the selected model does not support. */
export function unsupportedParameters(
  params: ChatParams,
  supportedParameters: readonly string[],
): CoreParameterDefinition[] {
  const supported = new Set(supportedParameters)

  return CORE_PARAMETER_DEFINITIONS.filter(
    (definition) =>
      hasValue(params, definition.key) &&
      !definition.supportedNames.some((name) => supported.has(name)),
  )
}

/** Human-readable explanation for ignored values, or null when all are used. */
export function unsupportedParameterNote(
  params: ChatParams,
  supportedParameters: readonly string[],
): string | null {
  const unsupported = unsupportedParameters(params, supportedParameters)

  if (unsupported.length === 0) {
    return null
  }

  const labels = unsupported.map((definition) => definition.label).join(', ')
  return `This model ignores these values, so they are not sent: ${labels}.`
}
