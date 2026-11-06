import { newId, nowIso, type Provider, type ProviderAuthType } from '../../domain'
import {
  getPreset,
  isPresetEnabledInM2,
  M2_ENABLED_PRESET_IDS,
} from '../../providers/registry'
import type { SecretMode } from '../../storage/SecretStore'
import { buildProviderContext } from './runtime'
import {
  ProviderServiceError,
  type CreateCustomProviderInput,
  type CreateOpenRouterProviderInput,
  type CreatePresetProviderInput,
  type ProviderKeyMetadata,
  type ProviderServiceOptions,
} from './types'

const FORBIDDEN_HEADER_NAMES = new Set([
  'authorization',
  'proxy-authorization',
  'x-api-key',
  'x-goog-api-key',
  'api-key',
  'cookie',
  'set-cookie',
  'x-auth-token',
  'x-access-token',
])

const AUTH_HEADER_BY_TYPE: Record<ProviderAuthType, string | null> = {
  bearer: 'authorization',
  'x-api-key': 'x-api-key',
  'query-key': null,
  none: null,
}

const HEADER_NAME_PATTERN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/

function requireNonEmpty(value: string, field: string): string {
  const trimmed = value.trim()

  if (trimmed === '') {
    throw new ProviderServiceError('invalid_input', `${field} must not be empty`)
  }

  return trimmed
}

function withoutKeyMetadata(
  metadata: Record<string, unknown> | null,
): Record<string, unknown> | null {
  if (metadata === null) {
    return null
  }

  const next = { ...metadata }
  delete next.key
  return Object.keys(next).length > 0 ? next : null
}

function validateBaseUrl(value: string): string {
  const trimmed = value.trim()

  if (trimmed === '') {
    throw new ProviderServiceError(
      'invalid_input',
      'Enter a base URL, for example https://api.example.com/v1.',
    )
  }

  let url: URL

  try {
    url = new URL(trimmed)
  } catch {
    throw new ProviderServiceError(
      'invalid_input',
      'Enter a valid http(s) base URL, for example https://api.example.com/v1.',
    )
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ProviderServiceError(
      'invalid_input',
      'Base URL must start with http:// or https://.',
    )
  }

  if (url.username !== '' || url.password !== '') {
    throw new ProviderServiceError(
      'invalid_input',
      'Base URL must not include credentials. Store the key with the auth style instead.',
    )
  }

  if (url.search !== '' || url.hash !== '') {
    throw new ProviderServiceError(
      'invalid_input',
      'Base URL must not include a query string or fragment.',
    )
  }

  return trimmed
}

function validateCustomAuthType(value: ProviderAuthType | undefined): ProviderAuthType {
  if (value === 'bearer' || value === 'x-api-key' || value === 'none') {
    return value
  }

  if (value === 'query-key') {
    throw new ProviderServiceError(
      'invalid_input',
      'Query-string API keys are not supported. Choose bearer or x-api-key.',
    )
  }

  throw new ProviderServiceError(
    'invalid_input',
    'Choose an authentication style: bearer, x-api-key, or none.',
  )
}

function validateApiKey(
  authType: ProviderAuthType,
  apiKey: string | undefined,
): string | null {
  if (authType === 'none') {
    if (apiKey !== undefined && apiKey.trim() !== '') {
      throw new ProviderServiceError(
        'invalid_input',
        'This authentication style does not use an API key. Remove the key or choose bearer or x-api-key.',
      )
    }

    return null
  }

  if (authType === 'query-key') {
    throw new ProviderServiceError(
      'invalid_input',
      'Query-string API keys are not supported. Choose bearer or x-api-key.',
    )
  }

  const trimmed = apiKey?.trim() ?? ''

  if (trimmed === '') {
    throw new ProviderServiceError(
      'invalid_input',
      'Enter an API key for the selected authentication style.',
    )
  }

  return trimmed
}

function validateHeaders(
  headers: Record<string, string> | undefined,
  authType: ProviderAuthType,
): Record<string, string> {
  if (headers === undefined) {
    return {}
  }

  if (typeof headers !== 'object' || headers === null || Array.isArray(headers)) {
    throw new ProviderServiceError('invalid_input', 'Headers must be a name/value object.')
  }

  const result: Record<string, string> = {}
  const authHeader = AUTH_HEADER_BY_TYPE[authType]

  for (const [rawName, rawValue] of Object.entries(headers)) {
    const name = rawName.trim()

    if (name === '') {
      throw new ProviderServiceError('invalid_input', 'Header names must not be empty.')
    }

    if (!HEADER_NAME_PATTERN.test(name)) {
      throw new ProviderServiceError(
        'invalid_input',
        `Header "${name}" is not a valid HTTP header name.`,
      )
    }

    const lower = name.toLowerCase()

    if (FORBIDDEN_HEADER_NAMES.has(lower) || lower === authHeader) {
      throw new ProviderServiceError(
        'invalid_input',
        `Header "${name}" is reserved for authentication. Remove it and use the auth style instead.`,
      )
    }

    if (typeof rawValue !== 'string') {
      throw new ProviderServiceError(
        'invalid_input',
        `Header "${name}" must have a string value.`,
      )
    }

    const value = rawValue.trim()

    if (value === '') {
      throw new ProviderServiceError(
        'invalid_input',
        `Header "${name}" must have a value.`,
      )
    }

    if (/[\r\n]/.test(value)) {
      throw new ProviderServiceError(
        'invalid_input',
        `Header "${name}" must not contain line breaks.`,
      )
    }

    result[name] = value
  }

  return result
}

/**
 * Read the cached `/key` metadata from a provider record.
 *
 * Returns null when the provider has never been validated. The returned object
 * never contains the API key.
 */
export function getProviderKeyMetadata(provider: Provider): ProviderKeyMetadata | null {
  const value = provider.metadata?.key

  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }

  return value as ProviderKeyMetadata
}

/**
 * Provider lifecycle service.
 *
 * The service owns secret writes and provider-record updates so UI code never
 * handles crypto or plaintext persistence directly. Provider records contain
 * only `secretId`; the key itself lives in the secret store.
 */
export class ProviderService {
  private readonly providers: ProviderServiceOptions['providers']
  private readonly secrets: ProviderServiceOptions['secrets']
  private readonly adapter: ProviderServiceOptions['adapter']
  private readonly runtime: ProviderServiceOptions
  private readonly timestamp: () => string
  private readonly generateId: () => string
  private readonly enabledPresetIds: readonly string[]
  private readonly allowCustomProviders: boolean

  constructor(options: ProviderServiceOptions) {
    this.providers = options.providers
    this.secrets = options.secrets
    this.adapter = options.adapter
    this.runtime = options
    this.timestamp = options.nowIso ?? nowIso
    this.generateId = options.newId ?? newId
    this.enabledPresetIds = options.enabledPresetIds ?? M2_ENABLED_PRESET_IDS
    this.allowCustomProviders = options.allowCustomProviders ?? false
  }

  /**
   * Create an OpenRouter provider and store its key.
   *
   * Kept as a named entry point for onboarding. It delegates to the generic
   * preset path so OpenRouter and the other OpenAI-compatible presets share
   * validation and rollback behavior.
   */
  async createOpenRouterProvider(input: CreateOpenRouterProviderInput): Promise<Provider> {
    return this.createPresetProvider({
      presetId: 'openrouter',
      apiKey: input.apiKey,
      label: input.label,
      rememberKeys: input.rememberKeys,
    })
  }

  /**
   * Create a provider from an enabled OpenAI-compatible contract preset.
   *
   * The provider record is written with a `secretId`; the plaintext key is
   * written only to the secret store. If the provider write fails, the newly
   * written secret is removed. Presets that do not use a key get no secret.
   */
  async createPresetProvider(input: CreatePresetProviderInput): Promise<Provider> {
    const preset = getPreset(input.presetId)

    if (!preset) {
      throw new ProviderServiceError(
        'invalid_input',
        `Unknown provider preset "${input.presetId}".`,
      )
    }

    if (preset.id === 'custom') {
      throw new ProviderServiceError(
        'invalid_input',
        'Use createCustomProvider to add a Custom provider.',
      )
    }

    if (!isPresetEnabledInM2(preset.id)) {
      throw new ProviderServiceError(
        'invalid_input',
        `${preset.label} is not available in M2.`,
      )
    }

    if (!this.enabledPresetIds.includes(preset.id)) {
      throw new ProviderServiceError(
        'invalid_input',
        `${preset.label} is not enabled in this deployment.`,
      )
    }

    const apiKey = validateApiKey(preset.auth.type, input.apiKey)
    const label = await this.resolveLabel(input.label, preset.label)
    const headers = validateHeaders(input.headers, preset.auth.type)
    const id = this.generateId()
    const secretId = apiKey === null ? null : this.generateId()
    const now = this.timestamp()

    const provider: Provider = {
      id,
      presetId: preset.id,
      label,
      kind: preset.kind,
      baseUrl: preset.baseUrl,
      authType: preset.auth.type,
      secretId,
      headers,
      enabled: true,
      browserCors: preset.browserCors,
      metadata: null,
      createdAt: now,
      updatedAt: now,
    }

    return this.persistProvider(
      provider,
      apiKey === null || secretId === null
        ? null
        : {
            id: secretId,
            value: apiKey,
            mode: input.rememberKeys === false ? 'session' : 'remember',
          },
    )
  }

  /**
   * Create a Custom OpenAI-compatible provider.
   *
   * The base URL and auth style are required. Non-secret headers are stored on
   * the provider record; the key is stored only in the secret store. Invalid
   * input is rejected before any write, and a failed provider write removes the
   * newly written secret.
   */
  async createCustomProvider(input: CreateCustomProviderInput): Promise<Provider> {
    if (!this.allowCustomProviders || !this.enabledPresetIds.includes('custom')) {
      throw new ProviderServiceError(
        'invalid_input',
        'Custom providers are not enabled in this deployment.',
      )
    }

    const preset = getPreset('custom')

    if (!preset) {
      throw new ProviderServiceError('invalid_input', 'Custom provider preset is missing')
    }

    const baseUrl = validateBaseUrl(input.baseUrl)
    const authType = validateCustomAuthType(input.authType)
    const apiKey = validateApiKey(authType, input.apiKey)
    const label = await this.resolveLabel(input.label, preset.label)
    const headers = validateHeaders(input.headers, authType)
    const id = this.generateId()
    const secretId = apiKey === null ? null : this.generateId()
    const now = this.timestamp()

    const provider: Provider = {
      id,
      presetId: preset.id,
      label,
      kind: preset.kind,
      baseUrl,
      authType,
      secretId,
      headers,
      enabled: true,
      browserCors: preset.browserCors,
      metadata: null,
      createdAt: now,
      updatedAt: now,
    }

    return this.persistProvider(
      provider,
      apiKey === null || secretId === null
        ? null
        : {
            id: secretId,
            value: apiKey,
            mode: input.rememberKeys === false ? 'session' : 'remember',
          },
    )
  }

  /**
   * Validate the stored key with `GET /key` and cache the normalized metadata.
   *
   * Adapter errors propagate unchanged so the UI can map `ChatErrorCode` to
   * actionable copy. The key is never included in the provider record.
   */
  async validateProviderKey(
    providerId: string,
    options: { signal?: AbortSignal } = {},
  ): Promise<Provider> {
    const provider = await this.requireProvider(providerId)

    if (!provider.secretId) {
      throw new ProviderServiceError(
        'missing_key',
        'This provider has no stored key. Add a key before validating.',
        providerId,
      )
    }

    const apiKey = await this.secrets.getSecret(provider.secretId)

    if (!apiKey) {
      throw new ProviderServiceError(
        'missing_key',
        'The stored key is unavailable. Re-enter the key to continue.',
        providerId,
      )
    }

    const signal = options.signal ?? new AbortController().signal
    const keyInfo = await this.adapter.validateKey(
      buildProviderContext(provider, this.runtime, apiKey),
      signal,
    )

    const metadata: Record<string, unknown> = {
      ...(provider.metadata ?? {}),
      key: {
        ...keyInfo,
        validatedAt: this.timestamp(),
      } satisfies ProviderKeyMetadata,
    }

    return this.providers.update(provider.id, { metadata })
  }

  /**
   * Replace the stored key and clear stale validation metadata.
   *
   * The new secret is written before the provider points at it. The old secret
   * is removed only after the provider update succeeds.
   */
  async rotateKey(providerId: string, apiKey: string, rememberKeys = true): Promise<Provider> {
    const provider = await this.requireProvider(providerId)
    const value = requireNonEmpty(apiKey, 'apiKey')
    const secretId = this.generateId()
    const mode: SecretMode = rememberKeys ? 'remember' : 'session'

    await this.secrets.setSecret(secretId, value, mode)

    try {
      const updated = await this.providers.update(provider.id, {
        secretId,
        metadata: withoutKeyMetadata(provider.metadata),
      })

      if (provider.secretId) {
        await this.secrets.deleteSecret(provider.secretId).catch(() => undefined)
      }

      return updated
    } catch (error) {
      await this.secrets.deleteSecret(secretId).catch(() => undefined)
      throw error
    }
  }

  /** Remove the stored key and clear cached key metadata. */
  async removeKey(providerId: string): Promise<Provider> {
    const provider = await this.requireProvider(providerId)

    if (provider.secretId) {
      await this.secrets.deleteSecret(provider.secretId)
    }

    return this.providers.update(provider.id, {
      secretId: null,
      metadata: withoutKeyMetadata(provider.metadata),
    })
  }

  async setProviderEnabled(providerId: string, enabled: boolean): Promise<Provider> {
    const provider = await this.requireProvider(providerId)
    return this.providers.update(provider.id, { enabled })
  }

  /**
   * Delete a provider and its dependent records.
   *
   * `ProviderRepository.delete` cascades models and the referenced secret. The
   * explicit secret delete keeps fake stores and future implementations
   * consistent.
   */
  async deleteProvider(providerId: string): Promise<void> {
    const provider = await this.providers.get(providerId)

    if (!provider) {
      return
    }

    await this.providers.delete(providerId)

    if (provider.secretId) {
      await this.secrets.deleteSecret(provider.secretId).catch(() => undefined)
    }
  }

  async getProvider(providerId: string): Promise<Provider | null> {
    return this.providers.get(providerId)
  }

  async listProviders(options: { enabled?: boolean } = {}): Promise<Provider[]> {
    return this.providers.list(options)
  }

  private async resolveLabel(label: string | undefined, fallback: string): Promise<string> {
    const trimmed = label?.trim() || fallback
    const existing = await this.providers.list()

    if (
      existing.some(
        (provider) => provider.label.trim().toLowerCase() === trimmed.toLowerCase(),
      )
    ) {
      throw new ProviderServiceError(
        'invalid_input',
        `A provider named "${trimmed}" already exists. Choose a different label.`,
      )
    }

    return trimmed
  }

  private async persistProvider(
    provider: Provider,
    secret: { id: string; value: string; mode: SecretMode } | null,
  ): Promise<Provider> {
    if (secret !== null) {
      try {
        await this.secrets.setSecret(secret.id, secret.value, secret.mode)
      } catch (error) {
        throw new ProviderServiceError(
          'storage',
          'Could not save the API key on this device. Check browser storage permissions and try again.',
          null,
          { cause: error },
        )
      }
    }

    try {
      return await this.providers.create(provider)
    } catch (error) {
      if (secret !== null) {
        await this.secrets.deleteSecret(secret.id).catch(() => undefined)
      }

      throw new ProviderServiceError(
        'storage',
        'Could not save the provider. Try again.',
        null,
        { cause: error },
      )
    }
  }

  private async requireProvider(providerId: string): Promise<Provider> {
    const provider = await this.providers.get(providerId)

    if (!provider) {
      throw new ProviderServiceError(
        'not_found',
        `Provider ${providerId} was not found.`,
        providerId,
      )
    }

    return provider
  }
}
