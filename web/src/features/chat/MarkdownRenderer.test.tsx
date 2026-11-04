import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MarkdownRenderer } from './MarkdownRenderer'
import { closeIncompleteFence, sanitizeMarkdownUrl } from './markdownSanitize'

vi.mock('./highlighter', () => ({
  normalizeLanguage: (language: string | null | undefined) => language ?? null,
  highlightCode: () => new Promise<null>(() => undefined),
}))

describe('MarkdownRenderer', () => {
  it('renders GFM headings, lists, tables, blockquotes, task lists, and code', () => {
    render(
      <MarkdownRenderer
        markdown={[
          '# Heading',
          '',
          '- one',
          '- two',
          '',
          '> quoted',
          '',
          '| A | B |',
          '| - | - |',
          '| 1 | 2 |',
          '',
          '- [x] done',
          '- [ ] todo',
          '',
          'Inline `code` and [link](https://example.com).',
          '',
          '```js',
          'const answer = 42',
          '```',
        ].join('\n')}
      />,
    )

    expect(screen.getByRole('heading', { name: 'Heading' })).toBeInTheDocument()
    expect(screen.getByText('one')).toBeInTheDocument()
    expect(screen.getByText('quoted')).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes).toHaveLength(2)
    expect(checkboxes[0]).toBeChecked()
    expect(checkboxes[1]).not.toBeChecked()
    expect(screen.getByText('code')).toBeInTheDocument()
    expect(screen.getByText('const answer = 42')).toBeInTheDocument()
  })

  it('renders raw HTML as inert text', () => {
    const { container } = render(
      <MarkdownRenderer
        markdown={'<script>alert("xss")</script>\n\n<img src="x" onerror="alert(1)">'}
      />,
    )

    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container).toHaveTextContent('<script>alert("xss")</script>')
    expect(container).toHaveTextContent('<img src="x" onerror="alert(1)">')
  })

  it('allows only http, https, mailto, and in-app anchors', () => {
    render(
      <MarkdownRenderer
        markdown={[
          '[External](https://example.com/path)',
          '[Mail](mailto:hello@example.com)',
          '[Anchor](#section)',
          '[Bad](javascript:alert(1))',
          '[File](file:///etc/passwd)',
          '[Relative](/settings)',
        ].join('\n\n')}
      />,
    )

    const external = screen.getByRole('link', { name: 'External' })
    expect(external).toHaveAttribute('href', 'https://example.com/path')
    expect(external).toHaveAttribute('target', '_blank')
    expect(external).toHaveAttribute('rel', 'noopener noreferrer')

    const mail = screen.getByRole('link', { name: 'Mail' })
    expect(mail).toHaveAttribute('href', 'mailto:hello@example.com')
    expect(mail).toHaveAttribute('target', '_blank')

    const anchor = screen.getByRole('link', { name: 'Anchor' })
    expect(anchor).toHaveAttribute('href', '#section')
    expect(anchor).not.toHaveAttribute('target')

    expect(screen.queryByRole('link', { name: 'Bad' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'File' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Relative' })).not.toBeInTheDocument()
    expect(screen.getByText('Bad')).toBeInTheDocument()
  })

  it('blocks remote images and offers a link instead', () => {
    const { container } = render(
      <MarkdownRenderer markdown={'![A diagram](https://example.com/diagram.png)'} />,
    )

    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('A diagram')).toBeInTheDocument()
    expect(screen.getByText('Remote images are blocked.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open image' })).toHaveAttribute(
      'href',
      'https://example.com/diagram.png',
    )
  })

  it('does not link unsafe image sources', () => {
    render(<MarkdownRenderer markdown={'![Unsafe](javascript:alert(1))'} />)

    expect(screen.getByText('Unsafe')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Open image' })).not.toBeInTheDocument()
  })

  it('renders an incomplete streaming fence as a code block', () => {
    const { container } = render(
      <MarkdownRenderer markdown={'```ts\nconst streaming = true'} />,
    )

    expect(screen.getByText('const streaming = true')).toBeInTheDocument()
    expect(screen.getByText('ts')).toBeInTheDocument()
    expect(container).not.toHaveTextContent('```')
  })
})

describe('closeIncompleteFence', () => {
  it('closes an unterminated backtick fence', () => {
    expect(closeIncompleteFence('```js\nconst x = 1')).toBe('```js\nconst x = 1\n```')
  })

  it('closes an unterminated tilde fence with the same length', () => {
    expect(closeIncompleteFence('~~~~\ncode')).toBe('~~~~\ncode\n~~~~')
  })

  it('leaves a closed fence unchanged', () => {
    const markdown = '```js\nconst x = 1\n```'
    expect(closeIncompleteFence(markdown)).toBe(markdown)
  })
})

describe('sanitizeMarkdownUrl', () => {
  it('accepts the allowlist and rejects everything else', () => {
    expect(sanitizeMarkdownUrl('https://example.com')).toBe('https://example.com')
    expect(sanitizeMarkdownUrl('http://example.com')).toBe('http://example.com')
    expect(sanitizeMarkdownUrl('mailto:a@example.com')).toBe('mailto:a@example.com')
    expect(sanitizeMarkdownUrl('#anchor')).toBe('#anchor')
    expect(sanitizeMarkdownUrl('javascript:alert(1)')).toBeNull()
    expect(sanitizeMarkdownUrl('data:text/html,hi')).toBeNull()
    expect(sanitizeMarkdownUrl('/relative')).toBeNull()
    expect(sanitizeMarkdownUrl('//example.com')).toBeNull()
    expect(sanitizeMarkdownUrl('')).toBeNull()
  })
})
