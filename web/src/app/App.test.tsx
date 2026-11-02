import { createMemoryHistory } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'
import { createAppRouter } from './router'

describe('App', () => {
  it('renders the routed shell', async () => {
    const router = createAppRouter(createMemoryHistory({ initialEntries: ['/'] }))

    render(<App router={router} />)

    expect(await screen.findByRole('heading', { name: 'ChatUI' })).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { name: 'No conversation selected' }),
    ).toBeInTheDocument()
  })
})
