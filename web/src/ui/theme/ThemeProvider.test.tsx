import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { ThemeProvider } from './ThemeProvider'
import { useTheme } from './useTheme'

function ThemeProbe() {
  const { theme, density, chatFontSize, setTheme, setDensity, setChatFontSize } = useTheme()

  return (
    <div>
      <span data-testid="theme">{theme}</span>
      <span data-testid="density">{density}</span>
      <span data-testid="chat-font-size">{chatFontSize}</span>
      <button type="button" onClick={() => setTheme('dark')}>
        Use dark theme
      </button>
      <button type="button" onClick={() => setDensity('compact')}>
        Use compact density
      </button>
      <button type="button" onClick={() => setChatFontSize('lg')}>
        Use large chat font
      </button>
    </div>
  )
}

beforeEach(() => {
  document.documentElement.removeAttribute('data-theme')
  document.documentElement.removeAttribute('data-density')
  document.documentElement.removeAttribute('data-chat-font-size')
})

describe('ThemeProvider', () => {
  it('applies the default appearance attributes to the document element', () => {
    render(
      <ThemeProvider>
        <ThemeProbe />
      </ThemeProvider>,
    )

    expect(document.documentElement.dataset.theme).toBe('system')
    expect(document.documentElement.dataset.density).toBe('comfortable')
    expect(document.documentElement.dataset.chatFontSize).toBe('md')
  })

  it('accepts initial theme, density, and chat font size values', () => {
    render(
      <ThemeProvider initialTheme="dark" initialDensity="compact" initialChatFontSize="sm">
        <ThemeProbe />
      </ThemeProvider>,
    )

    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.dataset.density).toBe('compact')
    expect(document.documentElement.dataset.chatFontSize).toBe('sm')
  })

  it('updates the document attributes when the modes change', async () => {
    const user = userEvent.setup()

    render(
      <ThemeProvider>
        <ThemeProbe />
      </ThemeProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'Use dark theme' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(screen.getByTestId('theme')).toHaveTextContent('dark')

    await user.click(screen.getByRole('button', { name: 'Use compact density' }))
    expect(document.documentElement.dataset.density).toBe('compact')
    expect(screen.getByTestId('density')).toHaveTextContent('compact')

    await user.click(screen.getByRole('button', { name: 'Use large chat font' }))
    expect(document.documentElement.dataset.chatFontSize).toBe('lg')
    expect(screen.getByTestId('chat-font-size')).toHaveTextContent('lg')
  })
})
