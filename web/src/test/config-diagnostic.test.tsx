import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('../app/config', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../app/config')>()
  return { ...actual, loadConfig: vi.fn() }
})

import { bootstrap } from '../app/bootstrap'
import { ConfigError, loadConfig } from '../app/config'

describe('shell config diagnostic', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.mocked(loadConfig).mockReset()
  })

  it('shows a visible diagnostic when config is invalid', async () => {
    document.body.innerHTML = '<div id="root"></div>'
    vi.mocked(loadConfig).mockRejectedValue(
      new ConfigError('/config.json is invalid:\nappName: Too small'),
    )

    await bootstrap()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/configuration error/i)).toBeInTheDocument()
    expect(screen.getByText(/appName: Too small/)).toBeInTheDocument()
  })
})
