import { newId, nowIso, type Provider } from '../../domain'
import { getPreset } from '../../providers/registry'
import type { SecretMode } from '../../storage/SecretStore'
import { buildProviderContext } from './runtime'
import {
  ProviderServiceError,
  type CreateOpenRouterProviderInput,
  type ProviderKeyMetadata,
  type ProviderServiceOptions,
} from './types'

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

  constructor(options: ProviderServiceOptions) {
    this.providers = options.providers
    this.secrets = options.secrets
    this.adapter = options.adapter
    this.runtime = options
    this.timestamp = options.nowIso ?? nowIso
    this.generateId = options.newId ?? newId
  }

  /**
   * Create an OpenRouter provider and store its key.
   *
   * The provider record is written with `metadata: null` and a `secretId`; the
   * plaintext key is written only to the secret store. If the provider write
   * fails, the newly written secret is removed.
   */
  async createOpenRouterProvider(input: CreateOpenRouterProviderInput): Promise<Provider> {
    const apiKey = requireNonEmpty(input.apiKey, 'apiKey')
    const preset = getPreset('openrouter')

    if (!preset) {
      throw new ProviderServiceError('invalid_input', 'OpenRouter preset is missing')
    }

    const id = this.generateId()
    const secretId = this.generateId()
    const now = this.timestamp()
    const rememberKeys = input.rememberKeys ?? true
    const mode: SecretMode = rememberKeys ? 'remember' : 'session'
    const label = input.label?.trim() || preset.label

    const provider: Provider = {
      id,
      presetId: preset.id,
      label,
      kind: preset.kind,
      baseUrl: preset.baseUrl,
      authType: preset.auth.type,
      secretId,
      headers: {},
      enabled: true,
      browserCors: preset.browserCors,
      metadata: null,
      createdAt: now,
      updatedAt: now,
    }

    await this.secrets.setSecret(secretId, apiKey, mode)

    try {
      return await this.providers.create(provider)
    } catch (error) {
      await this.secrets.deleteSecret(secretId).catch(() => undefined)
      throw error
    }
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
