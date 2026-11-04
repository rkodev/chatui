import { describe, expect, it } from 'vitest'
import { OpenRouterRequestError } from '../../providers/openrouter'
import { ProviderServiceError } from '../providers'
import { describeCatalogError, describeOnboardingError } from './errorCopy'
import { OnboardingError } from './types'

describe('describeOnboardingError', () => {
  it('maps auth failures to actionable key copy', () => {
    const copy = describeOnboardingError(
      new OpenRouterRequestError('auth', 'Invalid API key sk-test-key', { status: 401 }),
      'sk-test-key',
    )

    expect(copy.title).toBe('API key rejected')
    expect(copy.message).toBe('Your API key was rejected. Check the key and try again.')
    expect(copy.details).not.toContain('sk-test-key')
  })

  it('points CORS and network failures at the Android app or a proxy', () => {
    for (const code of ['cors', 'network'] as const) {
      const copy = describeOnboardingError(
        new OpenRouterRequestError(code, 'Failed to fetch', { status: null }),
      )

      expect(copy.message).toContain('browser CORS restriction')
      expect(copy.message).toContain('Android app')
      expect(copy.message).toContain('self-hosted proxy')
    }
  })

  it('maps quota and rate-limit failures without retrying', () => {
    const quota = describeOnboardingError(
      new OpenRouterRequestError('quota', 'Insufficient credits', { status: 402 }),
    )
    const rateLimit = describeOnboardingError(
      new OpenRouterRequestError('rate_limit', 'Too many requests', { status: 429 }),
    )

    expect(quota.message).toContain('insufficient credits')
    expect(rateLimit.message).toContain('rate limiting')
  })

  it('maps provider-service failures', () => {
    const copy = describeOnboardingError(
      new ProviderServiceError('missing_key', 'The stored key is unavailable.'),
    )

    expect(copy.title).toBe('Key unavailable')
    expect(copy.message).toContain('Re-enter the key')
  })

  it('maps onboarding service failures', () => {
    const copy = describeOnboardingError(
      new OnboardingError('no_model', 'No text model is available for OpenRouter.'),
    )

    expect(copy.title).toBe('No model available')
    expect(copy.message).toBe('No text model is available for OpenRouter.')
  })

  it('redacts the typed key from unknown errors', () => {
    const copy = describeOnboardingError(new Error('boom sk-test-key'), 'sk-test-key')

    expect(copy.details).toBe('boom [redacted]')
  })
})

describe('describeCatalogError', () => {
  it('keeps catalog failures non-blocking', () => {
    const message = describeCatalogError(new Error('Catalog unavailable'))

    expect(message).toContain('You can still connect')
  })
})
