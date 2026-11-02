import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { OpenRouterRequestError, type ModelInfo } from '../../providers/openrouter'
import { OnboardingScreen } from './OnboardingScreen'
import type { ConnectResult, OnboardingServiceLike } from './types'

const models: ModelInfo[] = [
  {
    remoteId: 'openai/gpt-4o-mini',
    label: 'OpenAI: GPT-4o mini',
    contextWindow: 128000,
    maxOutputTokens: 16384,
    capabilities: ['text'],
    pricing: null,
    canonicalSlug: null,
    inputModalities: ['text'],
    outputModalities: ['text'],
    supportedParameters: [],
    metadata: null,
    isFree: false,
  },
]

function createService(overrides: Partial<OnboardingServiceLike> = {}): OnboardingServiceLike {
  return {
    loadCatalog: vi.fn(async () => models),
    connect: vi.fn(async () => ({ conversationId: 'conversation-1', notice: null })),
    isOnboardingComplete: vi.fn(async () => false),
    ...overrides,
  }
}

describe('OnboardingScreen', () => {
  it('shows the first-run welcome, preselects OpenRouter, and keeps chat disabled', async () => {
    const service = createService()

    render(<OnboardingScreen service={service} onConnected={vi.fn()} />)

    expect(screen.getByRole('heading', { name: 'Welcome to ChatUI' })).toBeInTheDocument()
    expect(screen.getByText(/bring-your-own-key/i)).toBeInTheDocument()
    expect(screen.getByText(/stay on this device/i)).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /OpenRouter/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: /^OpenAI/ })).toBeDisabled()
    expect(screen.getByText('Recommended')).toBeInTheDocument()
    expect(await screen.findByText('OpenAI: GPT-4o mini')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start chatting' })).toBeDisabled()
    expect(
      screen.getByText('Chat stays disabled until your key is validated.'),
    ).toBeInTheDocument()
  })

  it('shows an actionable auth error and preserves the form', async () => {
    const user = userEvent.setup()
    const service = createService({
      connect: vi.fn(async () => {
        throw new OpenRouterRequestError('auth', 'Invalid API key sk-test-key', {
          status: 401,
        })
      }),
    })

    render(<OnboardingScreen service={service} onConnected={vi.fn()} />)

    await user.type(screen.getByLabelText('OpenRouter API key'), 'sk-test-key')
    await user.type(screen.getByLabelText('Label (optional)'), 'Work')
    await user.click(screen.getByRole('button', { name: 'Connect' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'Your API key was rejected. Check the key and try again.',
    )
    expect(alert).not.toHaveTextContent('sk-test-key')
    expect(screen.getByLabelText('OpenRouter API key')).toHaveValue('sk-test-key')
    expect(screen.getByLabelText('Label (optional)')).toHaveValue('Work')
    expect(screen.getByRole('button', { name: 'Connect' })).toBeEnabled()
  })

  it('connects with a valid key and opens a new chat', async () => {
    const user = userEvent.setup()
    const onConnected = vi.fn()
    const connect = vi.fn(async () => ({
      conversationId: 'conversation-1',
      notice: null,
    }))
    const service = createService({ connect })

    render(<OnboardingScreen service={service} onConnected={onConnected} />)

    await user.type(screen.getByLabelText('OpenRouter API key'), '  sk-test-key  ')
    await user.type(screen.getByLabelText('Label (optional)'), 'Work')
    await user.click(screen.getByRole('button', { name: 'Connect' }))

    await waitFor(() => expect(onConnected).toHaveBeenCalledWith('conversation-1'))
    expect(connect).toHaveBeenCalledWith({
      apiKey: 'sk-test-key',
      label: 'Work',
      rememberKeys: true,
    })
  })

  it('disables Connect and shows inline testing while validation runs', async () => {
    const user = userEvent.setup()
    let resolveConnect: (result: ConnectResult) => void = () => undefined
    const connect = vi.fn(
      () =>
        new Promise<ConnectResult>((resolve) => {
          resolveConnect = resolve
        }),
    )
    const service = createService({ connect })

    render(<OnboardingScreen service={service} onConnected={vi.fn()} />)

    await user.type(screen.getByLabelText('OpenRouter API key'), 'sk-test-key')
    await user.click(screen.getByRole('button', { name: 'Connect' }))

    expect(screen.getByRole('button', { name: 'Testing…' })).toBeDisabled()
    expect(screen.getByText('Testing key…')).toBeInTheDocument()

    resolveConnect({ conversationId: 'conversation-1', notice: null })

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Connected' })).toBeDisabled(),
    )
  })

  it('shows a non-blocking catalog error and still allows connecting', async () => {
    const user = userEvent.setup()
    const service = createService({
      loadCatalog: vi.fn(async () => {
        throw new Error('Catalog unavailable')
      }),
    })

    render(<OnboardingScreen service={service} onConnected={vi.fn()} />)

    expect(await screen.findByText(/could not load the model catalog/i)).toBeInTheDocument()

    await user.type(screen.getByLabelText('OpenRouter API key'), 'sk-test-key')

    expect(screen.getByRole('button', { name: 'Connect' })).toBeEnabled()
  })

  it('requires a key before connecting', async () => {
    const user = userEvent.setup()
    const service = createService()

    render(<OnboardingScreen service={service} onConnected={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Connect' })).toBeDisabled()

    await user.type(screen.getByLabelText('OpenRouter API key'), '   ')

    expect(screen.getByRole('button', { name: 'Connect' })).toBeDisabled()
  })
})
