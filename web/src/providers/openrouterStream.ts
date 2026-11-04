import {
  DEFAULT_SUPPORTED_PARAMETERS,
  ProviderRequestError,
  buildChatRequestBody as buildGenericChatRequestBody,
  buildStreamHeaders as buildGenericStreamHeaders,
  estimateUsage,
  streamChat as streamGenericChat,
  toChatError,
  type NormalizedChatMessage,
  type NormalizedChatRequest,
  type ProviderContext,
  type StreamChatOptions,
  type StreamEvent,
} from './openaiCompatible'
import { streamAnthropicChat } from './anthropic'
import { streamGeminiChat } from './gemini'
import { OPENROUTER_PRESET } from './openrouterPreset'

export {
  DEFAULT_SUPPORTED_PARAMETERS,
  ProviderRequestError as OpenRouterRequestError,
  estimateUsage,
  toChatError,
}
export type {
  NormalizedChatMessage,
  NormalizedChatRequest,
  StreamChatOptions,
  StreamEvent,
}

/**
 * Build the OpenRouter `POST /chat/completions` body.
 *
 * This wrapper keeps the M1 entry point stable while the implementation lives
 * in the preset-driven OpenAI-compatible transport.
 */
export function buildChatRequestBody(
  request: NormalizedChatRequest,
  supportedParameters: readonly string[] = DEFAULT_SUPPORTED_PARAMETERS,
): Record<string, unknown> {
  return buildGenericChatRequestBody(request, OPENROUTER_PRESET, supportedParameters)
}

/** Build OpenRouter streaming headers, including attribution. */
export function buildStreamHeaders(context: ProviderContext): Headers {
  return buildGenericStreamHeaders(context, context.preset ?? OPENROUTER_PRESET)
}

/**
 * Stream an OpenRouter chat completion.
 *
 * The preset defaults to OpenRouter when the context does not carry one, so
 * existing M1 callers keep their behavior. A context preset wins, which lets
 * the same transport serve other OpenAI-compatible providers.
 */
export async function streamChat(
  context: ProviderContext,
  request: NormalizedChatRequest,
  signal: AbortSignal,
  onEvent: (event: StreamEvent) => void,
  options: StreamChatOptions = {},
): Promise<void> {
  const preset = options.preset ?? context.preset ?? OPENROUTER_PRESET

  if (preset.kind === 'anthropic') {
    return streamAnthropicChat(context, request, signal, onEvent, { ...options, preset })
  }

  if (preset.kind === 'google-gemini') {
    return streamGeminiChat(context, request, signal, onEvent, { ...options, preset })
  }

  return streamGenericChat(context, request, signal, onEvent, { ...options, preset })
}

export { ProviderRequestError }
