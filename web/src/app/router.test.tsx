import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { createAppRouter } from './router'

function renderRoute(path: string) {
  const router = createAppRouter(createMemoryHistory({ initialEntries: [path] }))
  render(<RouterProvider router={router} />)
  return router
}

describe('app router', () => {
  it.each([
    ['/', 'No conversation selected'],
    ['/chat/conversation-1', 'Chat placeholder'],
    ['/settings/appearance', 'Appearance settings placeholder'],
    ['/settings/providers', 'Providers settings placeholder'],
    ['/settings/providers/new', 'New provider placeholder'],
    ['/settings/providers/provider-1', 'Provider details placeholder'],
    ['/settings/models', 'Models settings placeholder'],
    ['/settings/data', 'Data settings placeholder'],
    ['/settings/backup', 'Backup settings placeholder'],
    ['/settings/about', 'About settings placeholder'],
  ])('resolves %s', async (path, heading) => {
    renderRoute(path)

    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
  })

  it('redirects /settings to /settings/appearance', async () => {
    const router = renderRoute('/settings')

    expect(
      await screen.findByRole('heading', { name: 'Appearance settings placeholder' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/settings/appearance')
  })

  it('renders a visible fallback for unknown routes', async () => {
    renderRoute('/does-not-exist')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
