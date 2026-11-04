import type { ChatErrorCode } from '../../domain'
import { OpenRouterRequestError } from '../../providers/openrouter'
import { ProviderServiceError } from '../providers'
import { OnboardingError } from './types'

export interface OnboardingErrorCopy {
  title: string
  message: string
  details: string | null
}

const AUTH_MESSAGE = 'Your API key was rejected. Check the key and try again.'
const CORS_MESSAGE =
  'The request could not reach OpenRouter. This may be a browser CORS restriction. Try the Android app or a self-hosted proxy.'

function redact(message: string, secret: string | undefined): string {
  if (!secret) {
    return message
  }

  return message.split(secret).join('[redacted]')
}

function copyForCode(
  code: ChatErrorCode,
  rawMessage: string,
  secret: string | undefined,
): OnboardingErrorCopy {
  const details = redact(rawMessage, secret)

  switch (code) {
    case 'auth':
      return { title: 'API key rejected', message: AUTH_MESSAGE, details }
    case 'permission':
      return {
        title: 'Permission denied',
        message:
          'This key does not have permission to use OpenRouter. Check the key permissions and try again.',
        details,
      }
    case 'quota':
      return {
        title: 'Insufficient credits',
        message: 'OpenRouter reports insufficient credits. Add credits or choose a free model.',
        details,
      }
    case 'rate_limit':
      return {
        title: 'Rate limited',
        message: 'OpenRouter is rate limiting requests. Wait a moment and try again.',
        details,
      }
    case 'model_not_found':
      return {
        title: 'Model unavailable',
        message: 'The selected model is unavailable. Choose another model.',
        details,
      }
    case 'context_length':
      return {
        title: 'Conversation too long',
        message:
          'The conversation is too long for this model. Start a new chat or reduce the context.',
        details,
      }
    case 'content_filter':
      return {
        title: 'Request blocked',
        message: 'OpenRouter blocked the request. Check the key and try again.',
        details,
      }
    case 'invalid_request':
      return {
        title: 'Request rejected',
        message: 'OpenRouter rejected the request. Check the key and try again.',
        details,
      }
    case 'network':
    case 'cors':
      return { title: 'Could not reach OpenRouter', message: CORS_MESSAGE, details }
    case 'timeout':
      return {
        title: 'Request timed out',
        message: 'The request timed out. Check your connection and try again.',
        details,
      }
    case 'cancelled':
      return {
        title: 'Connection cancelled',
        message: 'The connection was cancelled. Try again.',
        details,
      }
    case 'parse':
      return {
        title: 'Unexpected response',
        message: 'OpenRouter returned an unexpected response. Try again.',
        details,
      }
    case 'storage':
      return {
        title: 'Could not save the key',
        message:
          'ChatUI could not save the key on this device. Check browser storage permissions and try again.',
        details,
      }
    case 'provider':
    case 'unknown':
      return {
        title: 'Could not connect',
        message: 'OpenRouter could not complete the request. Try again.',
        details,
      }
  }
}

function copyForProviderService(
  error: ProviderServiceError,
  secret: string | undefined,
): OnboardingErrorCopy {
  const details = redact(error.message, secret)

  switch (error.code) {
    case 'missing_key':
      return {
        title: 'Key unavailable',
        message: 'The key could not be read from this device. Re-enter the key and try again.',
        details,
      }
    case 'invalid_input':
      return {
        title: 'Check the key',
        message: 'Enter a valid OpenRouter API key and try again.',
        details,
      }
    case 'not_found':
      return {
        title: 'Provider not found',
        message: 'The provider record was not found. Try connecting again.',
        details,
      }
    case 'storage':
      return {
        title: 'Could not save the key',
        message:
          'ChatUI could not save the key on this device. Check browser storage permissions and try again.',
        details,
      }
  }
}

function copyForOnboardingError(
  error: OnboardingError,
  secret: string | undefined,
): OnboardingErrorCopy {
  const details = redact(error.message, secret)

  switch (error.code) {
    case 'invalid_input':
      return { title: 'Enter an API key', message: error.message, details: null }
    case 'no_model':
      return { title: 'No model available', message: error.message, details: null }
    case 'not_configured':
      return { title: 'Could not start a chat', message: error.message, details: null }
    case 'storage':
      return {
        title: 'Could not save setup',
        message: error.message,
        details,
      }
  }
}

/**
 * Map any onboarding failure to plain-language, actionable copy.
 *
 * The typed key is redacted from every message and detail string so it can
 * never be echoed back into the DOM or a diagnostics panel.
 */
export function describeOnboardingError(
  error: unknown,
  secret?: string,
): OnboardingErrorCopy {
  if (error instanceof OpenRouterRequestError) {
    return copyForCode(error.code, error.message, secret)
  }

  if (error instanceof ProviderServiceError) {
    return copyForProviderService(error, secret)
  }

  if (error instanceof OnboardingError) {
    return copyForOnboardingError(error, secret)
  }

  const message = error instanceof Error ? error.message : String(error)

  return {
    title: 'Could not connect',
    message: 'Something went wrong while connecting. Try again.',
    details: redact(message, secret),
  }
}

/** Non-blocking catalog failure copy. Connecting remains available. */
export function describeCatalogError(error: unknown): string {
  if (
    error instanceof OpenRouterRequestError &&
    (error.code === 'cors' || error.code === 'network')
  ) {
    return `Could not load the model catalog. ${CORS_MESSAGE} You can still connect; models will load after validation.`
  }

  return 'Could not load the model catalog. You can still connect; models will load after validation.'
}
