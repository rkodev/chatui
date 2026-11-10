import { test as base, expect, type Page } from '@playwright/test'
import {
  FAKE_API_KEY,
  OPENROUTER_BASE_URL,
  installMockProvider,
  type MockProviderController,
} from './mockProvider'

export { expect }

export interface AppFixtures {
  openRouter: MockProviderController
}

/**
 * Base test with a deterministic OpenRouter mock installed before navigation.
 *
 * Each Playwright test gets a fresh browser context, so IndexedDB, localStorage,
 * and the catalog cache start empty. Tests never share provider state.
 */
export const test = base.extend<AppFixtures>({
  openRouter: [
    async ({ page }, provide) => {
      const controller = await installMockProvider(page, { baseUrl: OPENROUTER_BASE_URL })
      await provide(controller)
    },
    { auto: true },
  ],
})

export const FAKE_KEY = FAKE_API_KEY

export async function gotoOnboarding(page: Page): Promise<void> {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Welcome to ChatUI' })).toBeVisible()
}

export async function completeOnboarding(
  page: Page,
  options: { key?: string; label?: string } = {},
): Promise<void> {
  await gotoOnboarding(page)
  await expect(page.getByRole('radio', { name: /OpenRouter/ })).toBeChecked()
  await expect(page.getByRole('button', { name: 'Start chatting' })).toBeDisabled()

  await page.getByLabel('OpenRouter API key').fill(options.key ?? FAKE_KEY)

  if (options.label !== undefined) {
    await page.getByLabel('Label (optional)').fill(options.label)
  }

  await page.getByRole('button', { name: 'Connect' }).click()
  await expect(page).toHaveURL(/\/chat\/[^/]+$/u)
  await expect(page.locator('#composer-input')).toBeEnabled()
}

export async function sendMessage(page: Page, text: string): Promise<void> {
  const input = page.locator('#composer-input')
  await input.fill(text)
  await page.getByRole('button', { name: 'Send message' }).click()
}

export function assistantMessages(page: Page) {
  return page.locator('article[data-role="assistant"]')
}

export function userMessages(page: Page) {
  return page.locator('article[data-role="user"]')
}

export async function waitForAssistantText(page: Page, text: string): Promise<void> {
  await expect(assistantMessages(page).last()).toContainText(text)
}

export async function openConversationSettings(page: Page): Promise<void> {
  await page.getByRole('link', { name: 'Conversation settings' }).click()
  await expect(page.getByRole('heading', { name: 'Conversation settings' })).toBeVisible()
}

export async function switchModel(
  page: Page,
  currentLabel: string,
  targetLabel: string,
): Promise<void> {
  await page.getByRole('button', { name: new RegExp(currentLabel, 'u') }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: new RegExp(targetLabel, 'u') }).first().click()
  await expect(dialog).toBeHidden()
}

export async function openDataSettings(page: Page): Promise<void> {
  await page.getByRole('link', { name: 'Settings', exact: true }).click()
  await page.getByRole('link', { name: 'Data' }).click()
  await expect(page.getByRole('heading', { name: 'Data', exact: true })).toBeVisible()
}

export async function clearAllData(page: Page): Promise<void> {
  await page.getByLabel(/Type DELETE ALL DATA to confirm/u).fill('DELETE ALL DATA')
  await page.getByRole('button', { name: 'Clear all data' }).click()
  await expect(page.getByRole('status').filter({ hasText: /Removed/u })).toBeVisible()
}

export async function deleteConversation(page: Page, title: string): Promise<void> {
  await page.getByRole('button', { name: `Actions for ${title}` }).click()
  await page.getByRole('menuitem', { name: 'Delete' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Delete' }).click()
  await expect(dialog).toBeHidden()
}

export async function newChat(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'New chat', exact: true }).click()
  await expect(page).toHaveURL(/\/chat\/[^/]+$/u)
}

export async function selectConversation(page: Page, title: string): Promise<void> {
  await page.getByRole('button', { name: new RegExp(`^${title}`, 'u') }).first().click()
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
}
