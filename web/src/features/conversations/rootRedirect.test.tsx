import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConfigContext, type AppConfig } from '../../app/config'
import { createAppRouter } from '../../app/router'
import { ChatServiceProvider } from '../chat'
import { createFakeChatService } from '../chat/testSupport'
import { OnboardingServiceProvider, type OnboardingServiceLike } from '../onboarding'
import { createTestConversation } from '../providers/testSupport'
import { ConversationHistoryProvider } from './ConversationHistoryProvider'
import type { ConversationHistoryServiceLike } from './historyService'
import type { RecoveryServiceLike } from './recoveryService'
import { createFakeConversationHistoryService, createFakeRecoveryService } from './testSupport'

const testConfig: AppConfig = {
  appName: 'ChatUI',
  defaultTheme: 'system',
  defaultProviderPreset: 'openrouter',
  enabledPresets: ['openrouter'],
  allowCustomProviders: false,
  openRouter: {
    attributionOrigin: 'https://chat.example.com',
    appTitle: 'ChatUI',
  },
  docsUrl: 'https://github.com/rkodev/chatui',
  telemetry: false,
}

interface RenderRootOptions {
  onboardingComplete: boolean
  historyService: ConversationHistoryServiceLike
  recoveryService?: RecoveryServiceLike
  path?: string
}

function renderRoot({
  onboardingComplete,
  historyService,
  recoveryService = createFakeRecoveryService(),
  path = '/',
}: RenderRootOptions) {
  const router = createAppRouter(createMemoryHistory({ initialEntries: [path] }))
  const onboardingService: OnboardingServiceLike = {
    loadCatalog: async () => [],
    connect: async () => ({ conversationId: 'conversation-1', notice: null }),
    isOnboardingComplete: async () => onboardingComplete,
  }

  render(
    <ConfigContext.Provider value={testConfig}>
      <ConversationHistoryProvider service={historyService} recoveryService={recoveryService}>
        <ChatServiceProvider service={createFakeChatService()}>
          <OnboardingServiceProvider service={onboardingService}>
            <RouterProvider router={router} />
          </OnboardingServiceProvider>
        </ChatServiceProvider>
      </ConversationHistoryProvider>
    </ConfigContext.Provider>,
  )

  return router
}

describe('root redirect', () => {
  it('shows onboarding when onboarding is incomplete', async () => {
    const router = renderRoot({
      onboardingComplete: false,
      historyService: createFakeConversationHistoryService(),
    })

    expect(await screen.findByRole('heading', { name: 'Welcome to ChatUI' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
  })

  it('restores the last active conversation', async () => {
    const lastActive = createTestConversation({ id: 'last-active', title: 'Last active' })
    const router = renderRoot({
      onboardingComplete: true,
      historyService: createFakeConversationHistoryService({
        getLastActiveConversation: async () => lastActive,
      }),
    })

    await waitFor(() => expect(router.state.location.pathname).toBe('/chat/last-active'))
  })

  it('creates a safe new chat when there is no last active conversation', async () => {
    const created = createTestConversation({ id: 'created', title: 'New chat' })
    const createConversation = vi.fn(async () => ({
      ok: true as const,
      conversation: created,
    }))
    const setLastActiveConversation = vi.fn(async () => undefined)
    const router = renderRoot({
      onboardingComplete: true,
      historyService: createFakeConversationHistoryService({
        getLastActiveConversation: async () => null,
        createConversation,
        setLastActiveConversation,
      }),
    })

    await waitFor(() => expect(router.state.location.pathname).toBe('/chat/created'))
    expect(createConversation).toHaveBeenCalledTimes(1)
    expect(setLastActiveConversation).toHaveBeenCalledWith('created')
  })

  it('refreshes the sidebar after the root redirect creates a new chat', async () => {
    const created = createTestConversation({ id: 'created', title: 'Created chat' })
    let listCalls = 0
    const router = renderRoot({
      onboardingComplete: true,
      historyService: createFakeConversationHistoryService({
        getLastActiveConversation: async () => null,
        createConversation: async () => ({ ok: true, conversation: created }),
        listConversations: async () => {
          listCalls += 1
          return { items: listCalls === 1 ? [] : [created], nextCursor: null }
        },
      }),
    })

    await waitFor(() => expect(router.state.location.pathname).toBe('/chat/created'))

    const sidebar = screen.getByRole('complementary', { name: 'Conversation history' })
    expect(await within(sidebar).findByText('Created chat')).toBeInTheDocument()
  })

  it('shows the no-conversation fallback when a new chat cannot be created', async () => {
    renderRoot({
      onboardingComplete: true,
      historyService: createFakeConversationHistoryService({
        getLastActiveConversation: async () => null,
        createConversation: async () => ({
          ok: false,
          reason: 'not_configured',
          message: 'No provider is configured.',
        }),
      }),
    })

    expect(
      await screen.findByRole('heading', { name: 'No conversation selected' }),
    ).toBeInTheDocument()
    expect(screen.getByText('No provider is configured.')).toBeInTheDocument()
  })

  it('runs startup recovery before rendering the routed content', async () => {
    let resolveRecovery: () => void = () => {}
    const recoveryService = createFakeRecoveryService({
      recoverInterruptedMessages: () =>
        new Promise((resolve) => {
          resolveRecovery = () => resolve({ recovered: [], failed: [] })
        }),
    })
    const lastActive = createTestConversation({ id: 'last-active', title: 'Last active' })
    const router = renderRoot({
      onboardingComplete: true,
      historyService: createFakeConversationHistoryService({
        getLastActiveConversation: async () => lastActive,
      }),
      recoveryService,
    })

    expect(screen.getByRole('status')).toHaveTextContent('Preparing conversation history…')
    expect(router.state.location.pathname).toBe('/')

    await act(async () => {
      resolveRecovery()
    })

    await waitFor(() => expect(router.state.location.pathname).toBe('/chat/last-active'))
  })
})
