import type { ChatError, ChatErrorCode } from '../../domain'

export interface ChatErrorCopy {
  title: string
  message: string
  details: string | null
}

const CORS_MESSAGE =
  'The request could not reach the provider. This may be a browser CORS restriction. Try the Android app or a self-hosted proxy.'

function detailsFor(error: ChatError): string | null {
  if (error.details !== null) {
    try {
      return JSON.stringify(error.details, null, 2)
    } catch {
      return String(error.details)
    }
  }

  const parts: string[] = []

  if (error.status !== null) {
    parts.push(`HTTP ${error.status}`)
  }
  if (error.requestId !== null) {
    parts.push(`Request ${error.requestId}`)
  }
  if (error.retryAfterSeconds !== null) {
    parts.push(`Retry after ${error.retryAfterSeconds}s`)
  }

  return parts.length > 0 ? parts.join('\n') : null
}

function copyForCode(code: ChatErrorCode): { title: string; message: string } {
  switch (code) {
    case 'auth':
      return {
        title: 'API key rejected',
        message: 'Your API key was rejected. Check the key and try again.',
      }
    case 'permission':
      return {
        title: 'Permission denied',
        message: 'This key does not have permission to use the provider. Check the key permissions.',
      }
    case 'rate_limit':
      return {
        title: 'Rate limited',
        message: 'This provider is rate limiting requests. Wait a moment and try again.',
      }
    case 'quota':
      return {
        title: 'Insufficient credits',
        message: 'The provider reports insufficient credits. Add credits or choose a free model.',
      }
    case 'model_not_found':
      return {
        title: 'Model unavailable',
        message: 'The selected model is unavailable. Choose another model.',
      }
    case 'context_length':
      return {
        title: 'Conversation too long',
        message:
          'The conversation is too long for this model. Start a new chat or reduce the context.',
      }
    case 'content_filter':
      return {
        title: 'Request blocked',
        message: 'The provider blocked the request. Try a different prompt or model.',
      }
    case 'invalid_request':
      return {
        title: 'Request rejected',
        message: 'The provider rejected the request. Check the model settings and try again.',
      }
    case 'network':
    case 'cors':
      return { title: 'Could not reach the provider', message: CORS_MESSAGE }
    case 'timeout':
      return {
        title: 'Request timed out',
        message: 'The request timed out. Check your connection and try again.',
      }
    case 'cancelled':
      return {
        title: 'Response cancelled',
        message: 'The response was cancelled. Partial content is preserved.',
      }
    case 'parse':
      return {
        title: 'Unexpected response',
        message: 'The provider returned an unexpected response. Try again.',
      }
    case 'storage':
      return {
        title: 'Could not save the message',
        message:
          'ChatUI could not save the message on this device. Check browser storage permissions and try again.',
      }
    case 'provider':
    case 'unknown':
      return {
        title: 'Could not complete the request',
        message: 'The provider could not complete the request. Try again.',
      }
  }
}

/**
 * Map a normalized chat error to plain-language, actionable copy.
 *
 * Raw provider JSON is never the primary message. It is exposed only through
 * the optional details disclosure.
 */
export function describeChatError(error: ChatError): ChatErrorCopy {
  const copy = copyForCode(error.code)

  return {
    title: copy.title,
    message: copy.message,
    details: detailsFor(error),
  }
}
