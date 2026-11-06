import {
  assistantMessages,
  completeOnboarding,
  expect,
  newChat,
  openConversationSettings,
  selectConversation,
  sendMessage,
  switchModel,
  test,
  waitForAssistantText,
} from './fixtures/app'
import { switchConversationProvider } from './fixtures/indexedDb'
import { DEFAULT_CATALOG } from './fixtures/mockProvider'

test.describe('chat streaming and lifecycle', () => {
  test('sends a message and renders the mocked stream with usage', async ({
    page,
    openRouter,
  }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'Hello mock')
    await waitForAssistantText(page, 'Hello from the mock provider.')

    await expect(page.locator('article[data-role="user"]').last()).toContainText('Hello mock')
    await expect(assistantMessages(page).last()).toHaveAttribute('data-status', 'complete')
    await expect(assistantMessages(page).last()).toContainText('12 in')
    await expect(assistantMessages(page).last()).toContainText('6 out')

    const request = openRouter.lastRequest()
    expect(request?.model).toBe('openrouter/auto')
    expect(request?.messages.at(-1)).toEqual({ role: 'user', content: 'Hello mock' })
  })

  test('switches model mid-conversation and uses it on the next send', async ({
    page,
    openRouter,
  }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'First model')
    await waitForAssistantText(page, 'Hello from the mock provider.')

    await openConversationSettings(page)
    await switchModel(page, 'Auto Router', 'OpenAI: GPT-4o mini')
    await expect(page.getByRole('button', { name: /OpenAI: GPT-4o mini/ })).toBeVisible()

    await page.getByRole('link', { name: 'Back to chat' }).click()
    openRouter.setChat({ type: 'success', text: 'Second model response.' })
    await sendMessage(page, 'Second model')
    await waitForAssistantText(page, 'Second model response.')

    expect(openRouter.lastRequest()?.model).toBe('openai/gpt-4o-mini')
    await expect(assistantMessages(page).first()).toContainText('Auto Router')
    await expect(assistantMessages(page).last()).toContainText('OpenAI: GPT-4o mini')
  })

  test('persists a provider switch and uses the new provider on the next send', async ({
    page,
    openRouter,
  }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'Provider switch')
    await waitForAssistantText(page, 'Hello from the mock provider.')

    openRouter.setCatalog([
      ...DEFAULT_CATALOG,
      {
        id: 'second/only',
        name: 'Second Provider Only',
        context_length: 32000,
        architecture: { modality: 'text->text' },
        pricing: { prompt: '0.000001', completion: '0.000002' },
        supported_parameters: ['temperature', 'top_p', 'max_tokens'],
      },
    ])

    await page.goto('/settings/providers/new')
    await expect(page.getByRole('heading', { name: 'Welcome to ChatUI' })).toBeVisible()
    await page.getByLabel('OpenRouter API key').fill('second-test-key-not-a-secret')
    await page.getByLabel('Label (optional)').fill('Second OpenRouter')
    await page.getByRole('button', { name: 'Connect' }).click()
    await expect(page).toHaveURL(/\/chat\/[^/]+$/u)

    await page.goto('/')
    await selectConversation(page, 'Provider switch')

    const switched = await switchConversationProvider(page, {
      conversationTitle: 'Provider switch',
      providerLabel: 'Second OpenRouter',
      preferredRemoteId: 'second/only',
    })
    expect(switched.remoteId).toBe('second/only')

    await page.reload()
    await selectConversation(page, 'Provider switch')
    await expect(page.getByText('Second Provider Only', { exact: true }).first()).toBeVisible()

    openRouter.setChat({ type: 'success', text: 'Provider switch response.' })
    await sendMessage(page, 'After provider switch')
    await waitForAssistantText(page, 'Provider switch response.')
    expect(openRouter.lastRequest()?.model).toBe('second/only')
  })

  test('reloads and restores the conversation and messages', async ({ page }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'Reload persistence')
    await waitForAssistantText(page, 'Hello from the mock provider.')

    await page.reload()

    await expect(page).toHaveURL(/\/chat\/[^/]+$/u)
    await expect(page.getByRole('heading', { name: 'Reload persistence' })).toBeVisible()
    await expect(page.locator('article[data-role="user"]').last()).toContainText(
      'Reload persistence',
    )
    await expect(assistantMessages(page).last()).toContainText('Hello from the mock provider.')
  })

  test('shows a provider error and retries the response', async ({ page, openRouter }) => {
    await completeOnboarding(page)
    openRouter.setChat({ type: 'error', message: 'Mock provider failure', code: 500 })

    await sendMessage(page, 'Trigger error')
    const failed = assistantMessages(page).last()
    await expect(failed).toHaveAttribute('data-status', 'error')
    await expect(failed.getByRole('alert')).toContainText('Could not complete the request')
    await expect(failed.getByRole('button', { name: 'Retry response' })).toBeVisible()

    openRouter.setChat({ type: 'success', text: 'Recovered after retry.' })
    await failed.getByRole('button', { name: 'Retry response' }).click()

    await expect(assistantMessages(page).last()).toContainText('Recovered after retry.')
    await expect(assistantMessages(page).last()).toHaveAttribute('data-status', 'complete')
    expect(openRouter.lastRequest()?.model).toBe('openrouter/auto')
  })

  test('cancels an in-flight response and marks it cancelled', async ({ page, openRouter }) => {
    await completeOnboarding(page)
    openRouter.setChat({ type: 'delay', delayMs: 3000, text: 'Too late' })

    await sendMessage(page, 'Cancel me')
    await expect(page.getByRole('button', { name: 'Stop response' })).toBeVisible()
    await page.getByRole('button', { name: 'Stop response' }).click()

    const cancelled = assistantMessages(page).last()
    await expect(cancelled).toHaveAttribute('data-status', 'cancelled')
    await expect(cancelled).toContainText('Response cancelled. Partial content is preserved.')
    await expect(cancelled).not.toContainText('Too late')
  })

  test('reads history offline and disables sending', async ({ page, context, openRouter }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'First conversation')
    await waitForAssistantText(page, 'Hello from the mock provider.')
    await page.reload()

    await newChat(page)
    openRouter.setChat({ type: 'success', text: 'Second conversation response.' })
    await sendMessage(page, 'Second conversation')
    await waitForAssistantText(page, 'Second conversation response.')
    await page.reload()

    await context.setOffline(true)
    await selectConversation(page, 'First conversation')

    await expect(page.locator('article[data-role="user"]').last()).toContainText(
      'First conversation',
    )
    await expect(
      page.getByText('You are offline. History is available; sending is disabled.'),
    ).toBeVisible()
    await expect(page.getByText('You are offline. Sending is disabled.')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Send message' })).toBeDisabled()

    await context.setOffline(false)
  })
})
