import { completeOnboarding, expect, FAKE_KEY, gotoOnboarding, test } from './fixtures/app'

test.describe('first-run onboarding', () => {
  test('shows onboarding, preselects OpenRouter, and blocks sending', async ({ page }) => {
    await gotoOnboarding(page)

    await expect(page.getByRole('radio', { name: /OpenRouter/ })).toBeChecked()
    await expect(page.getByRole('button', { name: 'Start chatting' })).toBeDisabled()
    await expect(
      page.getByText('Chat stays disabled until your key is validated.', { exact: true }),
    ).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Available models' })).toBeVisible()
    await expect(page.getByText('Auto Router', { exact: true })).toBeVisible()
  })

  test('rejects an invalid key, preserves the form, and does not start a chat', async ({
    page,
    openRouter,
  }) => {
    openRouter.setKeyResponse({
      status: 401,
      body: { error: { message: 'Invalid API key' } },
    })

    await gotoOnboarding(page)
    await page.getByLabel('OpenRouter API key').fill(FAKE_KEY)
    await page.getByRole('button', { name: 'Connect' }).click()

    await expect(page.getByRole('alert')).toContainText('API key rejected')
    await expect(page.getByLabel('OpenRouter API key')).toHaveValue(FAKE_KEY)
    await expect(page).toHaveURL(/\/$/u)
    await expect(page.getByRole('button', { name: 'Start chatting' })).toBeDisabled()
  })

  test('connects with a valid key, loads models, and opens a chat', async ({
    page,
    openRouter,
  }) => {
    await completeOnboarding(page)

    await expect(page.getByRole('heading', { name: 'New chat' })).toBeVisible()
    await expect(page.getByText('Auto Router', { exact: true }).first()).toBeVisible()
    await expect(page.locator('#composer-input')).toBeEnabled()
    expect(openRouter.requests).toHaveLength(0)
  })
})
