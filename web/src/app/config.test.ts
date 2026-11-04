import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { ConfigError, configSchema, loadConfig, type FetchLike } from './config'

const validConfig = {
  appName: 'ChatUI',
  defaultTheme: 'system',
  defaultProviderPreset: 'openrouter',
  enabledPresets: ['openrouter', 'openai', 'custom'],
  allowCustomProviders: true,
  openRouter: {
    attributionOrigin: 'https://chat.example.com',
    appTitle: 'ChatUI',
  },
  docsUrl: 'https://github.com/rkodev/chatui',
  telemetry: false,
}

interface FakeResponseInit {
  ok?: boolean
  status?: number
  statusText?: string
  json?: () => Promise<unknown>
}

function fakeResponse(body: unknown, init: FakeResponseInit = {}): Response {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    statusText: init.statusText ?? 'OK',
    json: init.json ?? (async () => body),
  } as Response
}

describe('configSchema', () => {
  it('accepts the checked-in public config', () => {
    const raw = readFileSync(resolve(process.cwd(), 'public/config.json'), 'utf8')
    const parsed = configSchema.parse(JSON.parse(raw))

    expect(parsed.appName).toBe('ChatUI')
    expect(parsed.enabledPresets).toEqual(
      expect.arrayContaining(['openrouter', 'openai', 'custom']),
    )
    expect(parsed.allowCustomProviders).toBe(true)
  })

  it('accepts a valid config object', () => {
    expect(configSchema.parse(validConfig)).toEqual(validConfig)
  })

  it('rejects missing required fields', () => {
    const withoutAppName: Record<string, unknown> = { ...validConfig }
    delete withoutAppName.appName

    const result = configSchema.safeParse(withoutAppName)

    expect(result.success).toBe(false)
  })

  it('rejects invalid field shapes', () => {
    const result = configSchema.safeParse({ ...validConfig, defaultTheme: 'sepia' })

    expect(result.success).toBe(false)
  })

  it('rejects unknown fields', () => {
    const result = configSchema.safeParse({ ...validConfig, unexpected: true })

    expect(result.success).toBe(false)
  })
})

describe('loadConfig', () => {
  it('loads and validates config with a no-cache request', async () => {
    const fetchMock = vi.fn<FetchLike>(async () => fakeResponse(validConfig))

    await expect(loadConfig(fetchMock)).resolves.toEqual(validConfig)
    expect(fetchMock).toHaveBeenCalledWith('/config.json', { cache: 'no-cache' })
  })

  it('throws ConfigError when the request fails', async () => {
    const fetchMock = vi.fn<FetchLike>(async () => {
      throw new Error('offline')
    })

    await expect(loadConfig(fetchMock)).rejects.toThrow(ConfigError)
    await expect(loadConfig(fetchMock)).rejects.toThrow('Could not load /config.json')
  })

  it('throws ConfigError for a non-OK response', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse('missing', { ok: false, status: 404, statusText: 'Not Found' }),
    )

    await expect(loadConfig(fetchMock)).rejects.toThrow('HTTP 404 Not Found')
  })

  it('throws ConfigError for invalid JSON', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse(undefined, {
        json: async () => {
          throw new SyntaxError('bad json')
        },
      }),
    )

    await expect(loadConfig(fetchMock)).rejects.toThrow('/config.json is not valid JSON.')
  })

  it('throws ConfigError with issues for an invalid shape', async () => {
    const fetchMock = vi.fn<FetchLike>(async () => fakeResponse({ ...validConfig, appName: '' }))

    try {
      await loadConfig(fetchMock)
      throw new Error('Expected loadConfig to reject')
    } catch (error) {
      expect(error).toBeInstanceOf(ConfigError)
      expect((error as ConfigError).issues.length).toBeGreaterThan(0)
      expect((error as ConfigError).message).toContain('/config.json is invalid')
    }
  })

  it('accepts the M2 launch presets', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse({
        ...validConfig,
        enabledPresets: ['openrouter', 'openai', 'groq', 'custom'],
      }),
    )

    await expect(loadConfig(fetchMock)).resolves.toMatchObject({
      enabledPresets: ['openrouter', 'openai', 'groq', 'custom'],
    })
  })

  it('rejects presets that are not available in M2', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse({ ...validConfig, enabledPresets: ['openrouter', 'google'] }),
    )

    await expect(loadConfig(fetchMock)).rejects.toThrow(
      '"google" is not available in M2',
    )
  })

  it('rejects unknown presets', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse({ ...validConfig, enabledPresets: ['openrouter', 'unknown'] }),
    )

    await expect(loadConfig(fetchMock)).rejects.toThrow(
      'unknown provider preset "unknown"',
    )
  })

  it('rejects duplicate enabled presets', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse({ ...validConfig, enabledPresets: ['openrouter', 'openrouter'] }),
    )

    await expect(loadConfig(fetchMock)).rejects.toThrow(
      'duplicate provider preset "openrouter"',
    )
  })

  it('rejects custom in enabledPresets when custom creation is disabled', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse({ ...validConfig, allowCustomProviders: false }),
    )

    await expect(loadConfig(fetchMock)).rejects.toThrow(
      '"custom" requires allowCustomProviders to be true',
    )
  })

  it('rejects custom creation without custom in enabledPresets', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse({
        ...validConfig,
        enabledPresets: ['openrouter', 'openai'],
        allowCustomProviders: true,
      }),
    )

    await expect(loadConfig(fetchMock)).rejects.toThrow(
      '"custom" must be listed in enabledPresets',
    )
  })

  it('rejects a default preset that is not OpenRouter', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse({ ...validConfig, defaultProviderPreset: 'openai' }),
    )

    await expect(loadConfig(fetchMock)).rejects.toThrow(
      'defaultProviderPreset: must be "openrouter"',
    )
  })

  it('rejects a default preset that is not enabled', async () => {
    const fetchMock = vi.fn<FetchLike>(async () =>
      fakeResponse({
        ...validConfig,
        enabledPresets: ['openai', 'custom'],
      }),
    )

    await expect(loadConfig(fetchMock)).rejects.toThrow(
      '"openrouter" is not in enabledPresets',
    )
  })
})
