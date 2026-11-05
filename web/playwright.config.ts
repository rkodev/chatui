import { defineConfig, devices } from '@playwright/test'
import process from 'node:process'

const PORT = 4173
const baseURL = `http://127.0.0.1:${PORT}`

/**
 * Playwright configuration for the M2 web end-to-end suite.
 *
 * The suite runs against the production build served by `vite preview`, with
 * deterministic provider mocks installed at the browser boundary. Failure
 * artifacts are limited to a screenshot and a trace; tests use fake keys only.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: process.env.CI === 'true',
  retries: process.env.CI === 'true' ? 1 : 0,
  workers: process.env.CI === 'true' ? 2 : undefined,
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: `pnpm build && pnpm preview --host 127.0.0.1 --port ${PORT} --strictPort`,
    url: baseURL,
    reuseExistingServer: process.env.CI !== 'true',
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
})
