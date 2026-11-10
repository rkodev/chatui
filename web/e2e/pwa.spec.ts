import { completeOnboarding, expect, sendMessage, test, waitForAssistantText } from './fixtures/app'

test.describe('PWA shell, cache policy, and offline navigation', () => {
  test('ships an installable manifest with maskable icons', async ({ page }) => {
    const response = await page.request.get('/manifest.webmanifest')
    expect(response.ok()).toBe(true)

    const manifest = (await response.json()) as {
      name: string
      short_name: string
      start_url: string
      display: string
      theme_color: string
      background_color: string
      icons: Array<{ src: string; sizes: string; purpose: string }>
    }

    expect(manifest.name).toBe('ChatUI')
    expect(manifest.short_name).toBe('ChatUI')
    expect(manifest.start_url).toBe('/')
    expect(manifest.display).toBe('standalone')
    expect(manifest.theme_color).toBe('#1f2937')
    expect(manifest.background_color).toBe('#ffffff')
    expect(manifest.icons).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ src: '/icons/icon-192.png', sizes: '192x192' }),
        expect.objectContaining({ src: '/icons/icon-512.png', sizes: '512x512' }),
        expect.objectContaining({
          src: '/icons/maskable-512.png',
          sizes: '512x512',
          purpose: 'maskable',
        }),
      ]),
    )
  })

  test('registers at the root scope and caches only the shell and hashed assets', async ({
    page,
  }) => {
    await completeOnboarding(page)

    const registration = await page.evaluate(async () => {
      const ready = await navigator.serviceWorker.ready
      return { scope: ready.scope, active: ready.active?.state ?? null }
    })

    expect(registration.scope).toBe(`${new URL(page.url()).origin}/`)
    expect(registration.active).toBe('activated')

    await sendMessage(page, 'PWA cache probe message')
    await waitForAssistantText(page, 'Hello from the mock provider.')

    const report = await page.evaluate(async () => {
      const keys = await caches.keys()
      const urls: string[] = []
      const bodies: string[] = []

      for (const key of keys) {
        const cache = await caches.open(key)

        for (const request of await cache.keys()) {
          const url = new URL(request.url)
          urls.push(url.pathname)

          const response = await cache.match(request)

          if (response) {
            bodies.push(await response.clone().text())
          }
        }
      }

      return {
        keys,
        urls,
        bodies,
        controlled: navigator.serviceWorker.controller !== null,
        configCached: urls.includes('/config.json'),
        apiCached: urls.some((path) => path.startsWith('/api/')),
        providerCached: urls.some((path) => path.includes('openrouter')),
      }
    })

    expect(report.controlled).toBe(true)
    expect(report.keys.length).toBeGreaterThan(0)
    expect(report.keys.every((key) => key.startsWith('chatui-shell-'))).toBe(true)
    expect(report.urls).toContain('/index.html')
    expect(report.urls).toContain('/manifest.webmanifest')
    expect(report.urls.some((path) => path.startsWith('/assets/'))).toBe(true)
    expect(report.urls.some((path) => path.startsWith('/icons/'))).toBe(true)
    expect(report.configCached).toBe(false)
    expect(report.apiCached).toBe(false)
    expect(report.providerCached).toBe(false)
    expect(report.bodies.join('\n')).not.toContain('PWA cache probe message')
  })

  test('serves the cached shell for navigation while offline', async ({ page }) => {
    await completeOnboarding(page)

    await page.evaluate(async () => {
      await navigator.serviceWorker.ready
    })

    await page.context().setOffline(true)

    try {
      const response = await page.goto('/')
      expect(response).not.toBeNull()
      // The worker answers the navigation from the precached shell. Runtime
      // config is intentionally never cached, so a cold offline start stops at
      // the configuration error while an already-running page keeps history.
      expect(response?.fromServiceWorker()).toBe(true)
      expect(await response?.text()).toContain('<div id="root"></div>')
    } finally {
      await page.context().setOffline(false)
    }
  })
})
