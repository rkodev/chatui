import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfigContext } from '../../app/config'
import { ConversationRoutingControls } from './ConversationRoutingControls'
import { ProviderManagementProvider } from './ProviderManagementProvider'
import {
  DEFAULT_ROUTING_PREFERENCES,
  withRoutingPreferences,
  type RoutingPreferences,
} from './routing'
import {
  createFakeProviderManagementService,
  createTestConversation,
  testConfig,
} from './testSupport'

function renderControls(service = createFakeProviderManagementService()) {
  render(
    <ConfigContext.Provider value={testConfig}>
      <ProviderManagementProvider service={service}>
        <ConversationRoutingControls conversationId="conversation-1" />
      </ProviderManagementProvider>
    </ConfigContext.Provider>,
  )
  return service
}

describe('ConversationRoutingControls', () => {
  it('persists curated routing preferences and shows a summary only when active', async () => {
    const user = userEvent.setup()
    const conversation = createTestConversation()
    let preferences: RoutingPreferences = DEFAULT_ROUTING_PREFERENCES
    const setConversationRouting = vi.fn(
      async (_id: string, next: RoutingPreferences) => {
        preferences = next
        return {
          ...conversation,
          params: withRoutingPreferences(conversation.params, next),
        }
      },
    )
    renderControls(
      createFakeProviderManagementService({
        getConversation: async () => conversation,
        getConversationRouting: async () => preferences,
        setConversationRouting,
      }),
    )

    expect(await screen.findByText('OpenRouter routing')).toBeInTheDocument()
    expect(screen.queryByText(/Fallbacks on/)).not.toBeInTheDocument()
    expect(
      screen.getByText(/Denying data collection can make some models unavailable/),
    ).toBeInTheDocument()

    await user.click(
      screen.getByRole('checkbox', { name: 'Use custom routing preferences' }),
    )
    await waitFor(() =>
      expect(setConversationRouting).toHaveBeenCalledWith('conversation-1', {
        enabled: true,
        allowFallbacks: true,
        sort: null,
        denyDataCollection: false,
      }),
    )
    expect(await screen.findByText('Fallbacks on')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Sort providers by'), 'price')
    await waitFor(() =>
      expect(setConversationRouting).toHaveBeenLastCalledWith('conversation-1', {
        enabled: true,
        allowFallbacks: true,
        sort: 'price',
        denyDataCollection: false,
      }),
    )
    expect(await screen.findByText('Fallbacks on, cheapest provider')).toBeInTheDocument()

    await user.click(
      screen.getByRole('checkbox', {
        name: 'Do not use providers that may train on prompts',
      }),
    )
    await waitFor(() =>
      expect(setConversationRouting).toHaveBeenLastCalledWith('conversation-1', {
        enabled: true,
        allowFallbacks: true,
        sort: 'price',
        denyDataCollection: true,
      }),
    )
    expect(
      await screen.findByText('Fallbacks on, cheapest provider, data collection denied'),
    ).toBeInTheDocument()
  })
})
