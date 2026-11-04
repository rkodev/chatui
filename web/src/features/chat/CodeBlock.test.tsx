import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CodeBlock } from './CodeBlock'

vi.mock('./highlighter', () => ({
  normalizeLanguage: (language: string | null | undefined) => language ?? null,
  highlightCode: () => new Promise<null>(() => undefined),
}))

const writeText = vi.fn()

describe('CodeBlock', () => {
  it('shows the language label and code text', () => {
    render(<CodeBlock code="const answer = 42" language="ts" />)

    expect(screen.getByText('ts')).toBeInTheDocument()
    expect(screen.getByText('const answer = 42')).toBeInTheDocument()
  })

  it('copies the exact source text', async () => {
    const user = userEvent.setup()
    writeText.mockReset()
    writeText.mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    const code = 'const answer = 42\nconsole.log(answer)'

    render(<CodeBlock code={code} language="ts" />)

    await user.click(screen.getByRole('button', { name: 'Copy code' }))

    expect(writeText).toHaveBeenCalledWith(code)
    expect(screen.getByRole('button', { name: 'Copy code' })).toHaveTextContent('Copied')
  })

  it('toggles line wrapping explicitly', async () => {
    const user = userEvent.setup()

    render(<CodeBlock code="a very long line" language="text" />)

    const wrapButton = screen.getByRole('button', { name: 'Wrap long lines' })
    expect(wrapButton).toHaveAttribute('aria-pressed', 'false')

    await user.click(wrapButton)

    expect(screen.getByRole('button', { name: 'Disable line wrapping' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('renders inline code without block controls', () => {
    render(<CodeBlock code="inline" inline />)

    expect(screen.getByText('inline').tagName).toBe('CODE')
    expect(screen.queryByRole('button', { name: 'Copy code' })).not.toBeInTheDocument()
  })
})
