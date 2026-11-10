import type { Page, Route } from '@playwright/test'

/**
 * Deterministic provider mock for the Playwright suite.
 *
 * The mock intercepts the provider base URL at the browser boundary, so the
 * application exercises its real transport, SSE parser, retry policy, and
 * persistence code. No live provider is contacted and no real key is used.
 */

export const OPENROUTER_BASE_URL = 'https://openrouter.ai/api/v1'
export const OPENAI_BASE_URL = 'https://api.openai.com/v1'
export const CUSTOM_BASE_URL = 'https://mock.example/v1'

export const FAKE_API_KEY = 'test-key-not-a-secret'

export interface MockModel {
  id: string
  name: string
  context_length?: number
  architecture?: {
    modality?: string
    input_modalities?: string[]
    output_modalities?: string[]
  }
  pricing?: Record<string, string>
  supported_parameters?: string[]
  top_provider?: {
    context_length?: number
    max_completion_tokens?: number
    is_moderated?: boolean
  }
}

export interface MockChatRequest {
  model: string
  messages: Array<{ role: string; content: unknown }>
  stream?: boolean
  [key: string]: unknown
}

export interface MockUsage {
  prompt_tokens: number
  completion_tokens: number
  total_tokens: number
}

export type ChatScenario =
  | {
      type: 'success'
      text?: string
      reasoning?: string
      usage?: MockUsage
    }
  | {
      type: 'error'
      message?: string
      code?: number
    }
  | {
      type: 'http-error'
      status: number
      message?: string
    }
  | {
      type: 'delay'
      delayMs: number
      text?: string
    }

export interface MockKeyResponse {
  status: number
  body: unknown
}

export interface MockProviderOptions {
  baseUrl: string
  catalog?: MockModel[]
  keyInfo?: Record<string, unknown>
  chat?: ChatScenario | ((request: MockChatRequest) => ChatScenario)
}

export interface MockProviderController {
  readonly baseUrl: string
  readonly requests: MockChatRequest[]
  setCatalog(catalog: MockModel[]): void
  setChat(scenario: ChatScenario | ((request: MockChatRequest) => ChatScenario)): void
  setKeyResponse(response: MockKeyResponse | null): void
  lastRequest(): MockChatRequest | undefined
  reset(): void
}

export const DEFAULT_CATALOG: MockModel[] = [
  {
    id: 'openrouter/auto',
    name: 'Auto Router',
    context_length: 128000,
    architecture: { modality: 'text->text' },
    pricing: { prompt: '0.000001', completion: '0.000002' },
    supported_parameters: ['temperature', 'top_p', 'max_tokens', 'stop', 'reasoning'],
    top_provider: { context_length: 128000, max_completion_tokens: 4096, is_moderated: false },
  },
  {
    id: 'openai/gpt-4o-mini',
    name: 'OpenAI: GPT-4o mini',
    context_length: 128000,
    architecture: { modality: 'text+image->text' },
    pricing: { prompt: '0.00000015', completion: '0.0000006' },
    supported_parameters: ['temperature', 'top_p', 'max_tokens', 'stop'],
    top_provider: { context_length: 128000, max_completion_tokens: 16384, is_moderated: false },
  },
  {
    id: 'anthropic/claude-sonnet',
    name: 'Anthropic: Claude Sonnet',
    context_length: 200000,
    architecture: { modality: 'text+image->text' },
    pricing: { prompt: '0.000003', completion: '0.000015' },
    supported_parameters: ['temperature', 'top_p', 'max_tokens', 'stop', 'reasoning'],
    top_provider: { context_length: 200000, max_completion_tokens: 8192, is_moderated: false },
  },
  {
    id: 'meta/llama-free:free',
    name: 'Meta: Llama Free',
    context_length: 8192,
    architecture: { modality: 'text->text' },
    pricing: { prompt: '0', completion: '0' },
    supported_parameters: ['temperature', 'top_p', 'max_tokens'],
    top_provider: { context_length: 8192, max_completion_tokens: 2048, is_moderated: false },
  },
]

const DEFAULT_KEY_INFO = {
  label: 'E2E test key',
  usage: 0,
  limit: null,
  limit_remaining: null,
  is_free_tier: true,
  rate_limit: null,
}

const CORS_HEADERS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers':
    'authorization, content-type, http-referer, x-title, x-api-key',
  'access-control-expose-headers': '*',
}

function jsonResponse(body: unknown, status = 200) {
  return {
    status,
    headers: {
      ...CORS_HEADERS,
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
  }
}

function sseResponse(body: string) {
  return {
    status: 200,
    headers: {
      ...CORS_HEADERS,
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      connection: 'keep-alive',
    },
    body,
  }
}

function sseEvent(payload: unknown): string {
  return `data: ${JSON.stringify(payload)}\n\n`
}

function successBody(scenario: Extract<ChatScenario, { type: 'success' }>): string {
  const text = scenario.text ?? 'Hello from the mock provider.'
  const chunks = text.match(/.{1,8}/gsu) ?? [text]
  const events: string[] = [': OPENROUTER PROCESSING\n\n']

  events.push(
    sseEvent({
      id: 'gen-e2e',
      choices: [{ delta: { role: 'assistant', content: '' } }],
    }),
  )

  if (scenario.reasoning) {
    events.push(
      sseEvent({
        id: 'gen-e2e',
        choices: [{ delta: { reasoning: scenario.reasoning } }],
      }),
    )
  }

  for (const chunk of chunks) {
    events.push(
      sseEvent({
        id: 'gen-e2e',
        choices: [{ delta: { content: chunk } }],
      }),
    )
  }

  events.push(
    sseEvent({
      id: 'gen-e2e',
      choices: [{ delta: {}, finish_reason: 'stop' }],
    }),
  )
  events.push(
    sseEvent({
      id: 'gen-e2e',
      choices: [],
      usage: scenario.usage ?? {
        prompt_tokens: 12,
        completion_tokens: 6,
        total_tokens: 18,
      },
    }),
  )
  events.push('data: [DONE]\n\n')

  return events.join('')
}

function errorBody(scenario: Extract<ChatScenario, { type: 'error' }>): string {
  return [
    sseEvent({
      error: {
        message: scenario.message ?? 'Mock provider failure',
        code: scenario.code ?? 500,
      },
    }),
    'data: [DONE]\n\n',
  ].join('')
}

function pathFor(baseUrl: string, requestUrl: string): string {
  const base = new URL(baseUrl)
  const url = new URL(requestUrl)
  return url.pathname.slice(base.pathname.length) || '/'
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}

/**
 * Install the mock for one provider base URL.
 *
 * The returned controller lets a test change the catalog, key response, or
 * chat scenario between requests without reinstalling routes.
 */
export async function installMockProvider(
  page: Page,
  options: MockProviderOptions,
): Promise<MockProviderController> {
  const baseUrl = options.baseUrl.replace(/\/$/u, '')
  const requests: MockChatRequest[] = []
  let catalog = options.catalog ?? DEFAULT_CATALOG
  let keyResponse: MockKeyResponse | null = null
  let chat = options.chat ?? { type: 'success' }

  const handler = async (route: Route): Promise<void> => {
    const request = route.request()
    const method = request.method()
    const path = pathFor(baseUrl, request.url())

    if (method === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: CORS_HEADERS })
      return
    }

    if (method === 'GET' && path.endsWith('/models')) {
      await route.fulfill(jsonResponse({ data: catalog }))
      return
    }

    if (method === 'GET' && path.endsWith('/key')) {
      if (keyResponse !== null) {
        await route.fulfill(jsonResponse(keyResponse.body, keyResponse.status))
        return
      }

      await route.fulfill(jsonResponse({ data: options.keyInfo ?? DEFAULT_KEY_INFO }))
      return
    }

    if (method === 'POST' && path.endsWith('/chat/completions')) {
      const body = (request.postDataJSON() ?? {}) as MockChatRequest
      requests.push(body)
      const scenario = typeof chat === 'function' ? chat(body) : chat

      if (scenario.type === 'http-error') {
        await route.fulfill(
          jsonResponse(
            { error: { message: scenario.message ?? 'Mock HTTP failure' } },
            scenario.status,
          ),
        )
        return
      }

      if (scenario.type === 'error') {
        await route.fulfill(sseResponse(errorBody(scenario)))
        return
      }

      if (scenario.type === 'delay') {
        await delay(scenario.delayMs)
        await route
          .fulfill(sseResponse(successBody({ type: 'success', text: scenario.text })))
          .catch(() => undefined)
        return
      }

      await route.fulfill(sseResponse(successBody(scenario)))
      return
    }

    await route.fulfill(jsonResponse({ error: { message: `Unhandled mock path ${path}` } }, 404))
  }

  await page.route(`${baseUrl}/**`, handler)

  return {
    baseUrl,
    requests,
    setCatalog(next) {
      catalog = next
    },
    setChat(next) {
      chat = next
    },
    setKeyResponse(next) {
      keyResponse = next
    },
    lastRequest() {
      return requests.at(-1)
    },
    reset() {
      requests.length = 0
      catalog = options.catalog ?? DEFAULT_CATALOG
      keyResponse = null
      chat = options.chat ?? { type: 'success' }
    },
  }
}
