import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import AxeBuilder from '@axe-core/playwright'
import type { Page } from '@playwright/test'
import {
  assistantMessages,
  completeOnboarding,
  expect,
  gotoOnboarding,
  newChat,
  sendMessage,
  test,
  waitForAssistantText,
} from './fixtures/app'

const EVIDENCE_DIR = path.resolve(
  process.cwd(),
  '..',
  'docs',
  'operations',
  'M4',
  'evidence',
  'accessibility',
  'axe',
)

interface AxeSummary {
  route: string
  url: string
  serious: number
  critical: number
  moderate: number
  minor: number
  violations: Array<{ id: string; impact: string | null; help: string; nodes: number }>
}

async function runAxe(page: Page, route: string): Promise<AxeSummary> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()

  const summary: AxeSummary = {
    route,
    url: page.url(),
    serious: results.violations.filter((violation) => violation.impact === 'serious').length,
    critical: results.violations.filter((violation) => violation.impact === 'critical').length,
    moderate: results.violations.filter((violation) => violation.impact === 'moderate').length,
    minor: results.violations.filter((violation) => violation.impact === 'minor').length,
    violations: results.violations.map((violation) => ({
      id: violation.id,
      impact: violation.impact,
      help: violation.help,
      nodes: violation.nodes.length,
    })),
  }

  await mkdir(EVIDENCE_DIR, { recursive: true })
  await writeFile(
    path.join(EVIDENCE_DIR, `${route}.json`),
    `${JSON.stringify({ summary, results }, null, 2)}\n`,
    'utf8',
  )

  const rows = results.violations
    .map(
      (violation) =>
        `<tr><td>${violation.id}</td><td>${violation.impact ?? ''}</td><td>${violation.help}</td><td>${violation.nodes.length}</td></tr>`,
    )
    .join('\n')

  await writeFile(
    path.join(EVIDENCE_DIR, `${route}.html`),
    `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>axe: ${route}</title></head><body><h1>axe: ${route}</h1><p>URL: ${summary.url}</p><p>serious: ${summary.serious}, critical: ${summary.critical}, moderate: ${summary.moderate}, minor: ${summary.minor}</p><table><thead><tr><th>Rule</th><th>Impact</th><th>Help</th><th>Nodes</th></tr></thead><tbody>${rows}</tbody></table></body></html>\n`,
    'utf8',
  )

  return summary
}

async function expectNoSeriousOrCritical(page: Page, route: string): Promise<void> {
  const summary = await runAxe(page, route)
  expect(
    { serious: summary.serious, critical: summary.critical },
    `${route} axe violations: ${JSON.stringify(summary.violations)}`,
  ).toEqual({ serious: 0, critical: 0 })
}

async function activeElementInfo(page: Page): Promise<{
  tag: string
  id: string
  label: string
  text: string
  outlineStyle: string
  outlineWidth: string
}> {
  return page.evaluate(() => {
    const element = document.activeElement as HTMLElement | null
    const style = element ? getComputedStyle(element) : null

    return {
      tag: element?.tagName.toLowerCase() ?? '',
      id: element?.id ?? '',
      label: element?.getAttribute('aria-label') ?? '',
      text: (element?.textContent ?? '').trim().slice(0, 80),
      outlineStyle: style?.outlineStyle ?? '',
      outlineWidth: style?.outlineWidth ?? '',
    }
  })
}

async function tabUntil(
  page: Page,
  predicate: (info: Awaited<ReturnType<typeof activeElementInfo>>) => boolean,
  maxTabs = 40,
): Promise<Awaited<ReturnType<typeof activeElementInfo>>> {
  for (let index = 0; index < maxTabs; index += 1) {
    await page.keyboard.press('Tab')
    const info = await activeElementInfo(page)

    if (predicate(info)) {
      return info
    }
  }

  throw new Error('Tab order did not reach the expected control')
}

test.describe('accessibility: axe on main routes', () => {
  test('onboarding has no serious or critical axe issues', async ({ page }) => {
    await gotoOnboarding(page)
    await expectNoSeriousOrCritical(page, 'onboarding')
  })

  test('chat, conversations, providers, settings, and data have no serious or critical axe issues', async ({
    page,
  }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'Axe route check')
    await waitForAssistantText(page, 'Hello from the mock provider.')
    await expectNoSeriousOrCritical(page, 'chat')

    await page.goto('/')
    await expect(page).toHaveURL(/\/chat\/[^/]+$/u)
    await expectNoSeriousOrCritical(page, 'conversations')

    await page.goto('/settings/providers')
    await expect(page.getByRole('heading', { name: 'Providers', exact: true })).toBeVisible()
    await expectNoSeriousOrCritical(page, 'providers')

    await page.goto('/settings/appearance')
    await expect(page.getByRole('heading', { name: 'Appearance' })).toBeVisible()
    await expectNoSeriousOrCritical(page, 'settings')

    await page.goto('/settings/data')
    await expect(page.getByRole('heading', { name: 'Data', exact: true })).toBeVisible()
    await expectNoSeriousOrCritical(page, 'data')
  })
})

test.describe('accessibility: keyboard and focus', () => {
  test('onboarding is reachable by keyboard with a visible focus ring', async ({ page }) => {
    await gotoOnboarding(page)

    const keyInput = await tabUntil(page, (info) => info.id === 'onboarding-api-key')
    expect(keyInput.tag).toBe('input')
    expect(keyInput.outlineStyle).not.toBe('none')
    expect(keyInput.outlineWidth).not.toBe('0px')

    await page.keyboard.type('test-key-not-a-secret')
    const connect = await tabUntil(page, (info) => info.text === 'Connect')
    expect(connect.tag).toBe('button')
    expect(connect.outlineStyle).not.toBe('none')
  })

  test('chat send, stop, and Escape work from the keyboard', async ({ page, openRouter }) => {
    await completeOnboarding(page)
    openRouter.setChat({ type: 'delay', delayMs: 3000, text: 'Keyboard stop' })

    const composer = page.locator('#composer-input')
    await composer.focus()
    await page.keyboard.type('Keyboard send')
    await page.keyboard.press('Enter')

    await expect(page.getByRole('button', { name: 'Stop response' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(assistantMessages(page).last()).toHaveAttribute('data-status', 'cancelled')
  })

  test('model picker traps focus, closes on Escape, and returns focus to the trigger', async ({
    page,
  }) => {
    await completeOnboarding(page)
    await page.getByRole('link', { name: 'Conversation settings' }).click()
    await expect(page.getByRole('heading', { name: 'Conversation settings' })).toBeVisible()

    const trigger = page.getByRole('button', { name: /Auto Router/u }).first()
    await trigger.press('Enter')

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    for (let index = 0; index < 8; index += 1) {
      await page.keyboard.press('Tab')
      const inside = await page.evaluate(() => {
        const active = document.activeElement
        const dialogElement = document.querySelector('[role="dialog"]')
        return active !== null && dialogElement !== null && dialogElement.contains(active)
      })
      expect(inside).toBe(true)
    }

    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
    await expect(trigger).toBeFocused()
  })

  test('conversation actions menu closes on Escape and returns focus', async ({ page }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'Menu focus')
    await waitForAssistantText(page, 'Hello from the mock provider.')
    await page.reload()
    await expect(page.locator('#chat-title')).toHaveText('Menu focus')
    await expect(page.locator('#composer-input')).toBeEnabled()

    const trigger = page.getByRole('button', { name: 'Actions for Menu focus' })
    await trigger.press('Enter')
    await expect(page.getByRole('menuitem', { name: 'Delete' })).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(page.getByRole('menuitem', { name: 'Delete' })).toBeHidden()
    await expect(trigger).toBeFocused()
  })

  test('settings navigation is keyboard-only', async ({ page }) => {
    await completeOnboarding(page)

    const settingsLink = page.getByRole('link', { name: 'Settings', exact: true })
    await settingsLink.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: 'Appearance' })).toBeVisible()

    const dataLink = page.getByRole('link', { name: 'Data', exact: true })
    await dataLink.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: 'Data', exact: true })).toBeVisible()

    const exportButton = page.getByRole('button', { name: 'Export all data (JSON)' })
    await exportButton.focus()
    const info = await activeElementInfo(page)
    expect(info.outlineStyle).not.toBe('none')
  })
})

test.describe('accessibility: live regions', () => {
  test('announces stream start and complete without announcing tokens', async ({
    page,
    openRouter,
  }) => {
    await completeOnboarding(page)
    openRouter.setChat({ type: 'delay', delayMs: 800, text: 'Token text should not announce' })

    await sendMessage(page, 'Announce me')
    const announcement = page.getByTestId('chat-announcement')

    await expect(announcement).toHaveText('Response started')
    await expect(announcement).not.toContainText('Token text should not announce')
    await expect(announcement).toHaveText('Response complete', { timeout: 10_000 })
    await expect(announcement).not.toContainText('Token text should not announce')
  })

  test('announces errors with plain language, not raw JSON', async ({ page, openRouter }) => {
    await completeOnboarding(page)
    openRouter.setChat({ type: 'error', message: '{"error":{"message":"raw json"}}', code: 500 })

    await sendMessage(page, 'Announce error')
    const announcement = page.getByTestId('chat-announcement')

    await expect(announcement).toContainText('Response failed.')
    await expect(announcement).not.toContainText('{')
    await expect(announcement).not.toContainText('raw json')
  })
})

test.describe('accessibility: responsive layout', () => {
  test('uses the section 11.9 breakpoints and a 48 rem message column', async ({ page }) => {
    await completeOnboarding(page)

    await page.setViewportSize({ width: 599, height: 900 })
    await expect(page.getByRole('button', { name: 'Open history' })).toBeVisible()
    const mobileSidebar = await page.locator('.conversation-sidebar').boundingBox()
    expect(mobileSidebar?.x ?? 0).toBeLessThan(0)

    await page.setViewportSize({ width: 600, height: 900 })
    await expect(page.getByRole('button', { name: 'Open history' })).toBeVisible()

    await page.setViewportSize({ width: 839, height: 900 })
    await expect(page.getByRole('button', { name: 'Open history' })).toBeVisible()

    await page.setViewportSize({ width: 840, height: 900 })
    await expect(page.getByRole('button', { name: 'Open history' })).toBeHidden()
    const desktopSidebar = await page.locator('.conversation-sidebar').boundingBox()
    expect(desktopSidebar?.x ?? -1).toBeGreaterThanOrEqual(0)

    await page.setViewportSize({ width: 1280, height: 900 })
    await expect(page.locator('.conversation-sidebar')).toBeVisible()

    const widths = await page.evaluate(() => {
      const body = document.querySelector('.chat-screen__body')
      const composer = document.querySelector('.chat-screen__composer')

      return {
        bodyMax: body ? getComputedStyle(body).maxWidth : '',
        composerMax: composer ? getComputedStyle(composer).maxWidth : '',
        bodyWidth: body ? body.getBoundingClientRect().width : 0,
        composerWidth: composer ? composer.getBoundingClientRect().width : 0,
      }
    })

    expect(widths.bodyMax).toBe('768px')
    expect(widths.composerMax).toBe('768px')
    expect(widths.bodyWidth).toBeLessThanOrEqual(768)
    expect(widths.composerWidth).toBeLessThanOrEqual(768)
  })
})

test.describe('accessibility: motion, scaling, and overflow', () => {
  test('respects reduced motion', async ({ page }) => {
    await completeOnboarding(page)
    await page.emulateMedia({ reducedMotion: 'reduce' })

    const styles = await page.evaluate(() => {
      const button = document.querySelector('.ui-button')
      const screen = document.querySelector('.chat-screen')

      return {
        transition: button ? getComputedStyle(button).transitionDuration : '',
        scrollBehavior: screen ? getComputedStyle(screen).scrollBehavior : '',
      }
    })

    expect(['0s', '0.01ms', '0.00001s', '1e-05s']).toContain(styles.transition)
    expect(styles.scrollBehavior).toBe('auto')
  })

  test('scales with the root font size and keeps tables and code scrollable', async ({
    page,
    openRouter,
  }) => {
    await completeOnboarding(page)

    await page.evaluate(() => {
      document.documentElement.style.fontSize = '32px'
    })
    const maxWidth = await page.evaluate(() => {
      const body = document.querySelector('.chat-screen__body')
      return body ? getComputedStyle(body).maxWidth : ''
    })
    expect(maxWidth).toBe('1536px')

    await page.evaluate(() => {
      document.documentElement.style.fontSize = ''
    })

    openRouter.setChat({
      type: 'success',
      text: [
        '| Column one | Column two | Column three |',
        '| --- | --- | --- |',
        '| A very long cell value that should scroll | Another long value | Third long value |',
        '',
        '```text',
        'a-very-long-code-line-that-should-scroll-horizontally-without-breaking-the-layout',
        '```',
      ].join('\n'),
    })
    await sendMessage(page, 'Overflow check')
    await waitForAssistantText(page, 'Column one')
    await expect(assistantMessages(page).last()).toHaveAttribute('data-status', 'complete')

    const overflow = await page.evaluate(() => {
      const table = document.querySelector('.markdown table')
      const code = document.querySelector('.code-block__pre')

      return {
        tableOverflow: table ? getComputedStyle(table).overflowX : '',
        codeOverflow: code ? getComputedStyle(code).overflowX : '',
        pageOverflow: document.documentElement.scrollWidth > window.innerWidth,
      }
    })

    expect(overflow.tableOverflow).toBe('auto')
    expect(overflow.codeOverflow).toBe('auto')
    expect(overflow.pageOverflow).toBe(false)
  })
})

test.describe('accessibility: states', () => {
  test('empty, error, and offline states use plain-language copy', async ({
    page,
    context,
    openRouter,
  }) => {
    await completeOnboarding(page)
    await expect(page.getByRole('heading', { name: 'Start the conversation' })).toBeVisible()

    openRouter.setChat({ type: 'error', message: '{"error":{"message":"raw json"}}', code: 500 })
    await sendMessage(page, 'State error')
    const failed = assistantMessages(page).last()
    await expect(failed).toHaveAttribute('data-status', 'error')
    const alert = failed.getByRole('alert')
    await expect(alert).toContainText('Could not complete the request')
    await expect(alert).not.toContainText('{')
    await expect(alert).not.toContainText('raw json')

    await context.setOffline(true)
    await expect(
      page.getByText('You are offline. History is available; sending is disabled.'),
    ).toBeVisible()
    await expect(page.getByText('You are offline. Sending is disabled.')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Send message' })).toBeDisabled()
    await context.setOffline(false)
  })
})

test.describe('accessibility: keyboard shortcuts', () => {
  test('section 11.10 shortcuts work and do not fire while typing', async ({
    page,
    context,
    openRouter,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await completeOnboarding(page)

    await page.keyboard.press('Control+k')
    await expect(page.locator('#conversation-search')).toBeFocused()

    await page.keyboard.press('Control+/')
    const help = page.getByRole('dialog', { name: 'Keyboard shortcuts' })
    await expect(help).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(help).toBeHidden()

    const firstUrl = page.url()
    await page.locator('#composer-input').focus()
    await page.keyboard.type('ok/')
    await expect(page.locator('#composer-input')).toHaveValue('ok/')
    await expect(page).toHaveURL(firstUrl)

    await page.keyboard.press('Control+Shift+O')
    await expect(page).not.toHaveURL(firstUrl)

    openRouter.setChat({ type: 'success', text: 'Copy this response.' })
    await sendMessage(page, 'Copy shortcut')
    await waitForAssistantText(page, 'Copy this response.')
    await page.locator('#composer-input').focus()
    await page.keyboard.press('Control+Shift+C')
    await expect
      .poll(() => page.evaluate(() => navigator.clipboard.readText()))
      .toContain('Copy this response.')

    await newChat(page)
    await sendMessage(page, 'Second conversation')
    await waitForAssistantText(page, 'Copy this response.')
    const secondTitle = await page.locator('#chat-title').textContent()
    await page.keyboard.press('Alt+ArrowDown')
    await expect(page.locator('#chat-title')).not.toHaveText(secondTitle ?? '')
  })
})
