import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { MarkdownRenderer } from './MarkdownRenderer'
import { closeIncompleteFence, sanitizeImageUrl, sanitizeMarkdownUrl } from './markdownSanitize'

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

  it('wraps tables in a horizontal scroll container', () => {
    const { container } = render(
      <MarkdownRenderer markdown={['| A | B |', '| - | - |', '| 1 | 2 |'].join('\n')} />,
    )

    expect(container.querySelector('.markdown__table-scroll table')).not.toBeNull()
  })

  it('renders inline and block math through the lazy KaTeX renderer', async () => {
    const { container } = render(
      <MarkdownRenderer
        markdown={['Euler: $e^{i\\pi}+1=0$.', '', '$$', '\\int_0^1 x^2 dx', '$$'].join('\n')}
      />,
    )

    await waitFor(
      () => {
        expect(container.querySelectorAll('.math')).toHaveLength(2)
      },
      { timeout: 5000 },
    )

    expect(container.querySelector('.math--inline .katex')).not.toBeNull()
    expect(container.querySelector('.math--display .katex')).not.toBeNull()
  })

  it('falls back to raw source when math cannot be parsed', async () => {
    const { container } = render(<MarkdownRenderer markdown={'$\\frac{1}{$'} />)

    await waitFor(
      () => {
        expect(container.querySelector('.math__fallback')).not.toBeNull()
      },
      { timeout: 5000 },
    )

    expect(container.querySelector('.katex')).toBeNull()
    expect(container).toHaveTextContent('$\\frac{1}{$')
  })

  it('keeps incomplete streaming math as raw text', () => {
    const { container } = render(<MarkdownRenderer markdown={'Here is $x^2'} />)

    expect(container.querySelector('.math')).toBeNull()
    expect(container).toHaveTextContent('Here is $x^2')
  })

  it('does not allow math commands to inject links or scripts', async () => {
    const { container } = render(<MarkdownRenderer markdown={'$\\href{javascript:alert(1)}{x}$'} />)

    await waitFor(
      () => {
        expect(container.querySelector('.math, .math__fallback')).not.toBeNull()
      },
      { timeout: 5000 },
    )

    expect(container.querySelector('a[href^="javascript"]')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
  })

  it('blocks remote images and offers a link instead', () => {
    const { container } = render(
      <MarkdownRenderer markdown={'![A diagram](https://example.com/diagram.png)'} />,
    )

    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('A diagram')).toBeInTheDocument()
    expect(screen.getByText('Remote images are blocked.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Load image' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open image' })).toHaveAttribute(
      'href',
      'https://example.com/diagram.png',
    )
  })

  it('loads a remote image only for the message that requested it', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <MarkdownRenderer markdown={'![A diagram](https://example.com/diagram.png)'} />,
    )

    expect(container.querySelector('img')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Load image' }))

    const image = container.querySelector('img')
    expect(image).not.toBeNull()
    expect(image).toHaveAttribute('src', 'https://example.com/diagram.png')
    expect(image).toHaveAttribute('referrerpolicy', 'no-referrer')
  })

  it('renders remote images when the setting explicitly allows them', () => {
    const { container } = render(
      <MarkdownRenderer
        markdown={'![A diagram](https://example.com/diagram.png)'}
        loadRemoteImages
      />,
    )

    expect(container.querySelector('img')).toHaveAttribute('src', 'https://example.com/diagram.png')
    expect(screen.queryByText('Remote images are blocked.')).not.toBeInTheDocument()
  })

  it('does not load data or script image sources', () => {
    const { container } = render(
      <MarkdownRenderer
        markdown={['![Data](data:image/png;base64,AAAA)', '![Unsafe](javascript:alert(1))'].join(
          '\n\n',
        )}
        loadRemoteImages
      />,
    )

    expect(container.querySelector('img')).toBeNull()
    expect(screen.getAllByText('Remote image source blocked.')).toHaveLength(2)
  })

  it('does not link unsafe image sources', () => {
    render(<MarkdownRenderer markdown={'![Unsafe](javascript:alert(1))'} />)

    expect(screen.getByText('Unsafe')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Open image' })).not.toBeInTheDocument()
  })

  it('renders an incomplete streaming fence as a code block', () => {
    const { container } = render(<MarkdownRenderer markdown={'```ts\nconst streaming = true'} />)

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

describe('sanitizeImageUrl', () => {
  it('allows only absolute http and https image URLs', () => {
    expect(sanitizeImageUrl('https://example.com/a.png')).toBe('https://example.com/a.png')
    expect(sanitizeImageUrl('http://example.com/a.png')).toBe('http://example.com/a.png')
    expect(sanitizeImageUrl('mailto:a@example.com')).toBeNull()
    expect(sanitizeImageUrl('#anchor')).toBeNull()
    expect(sanitizeImageUrl('data:image/png;base64,AAAA')).toBeNull()
    expect(sanitizeImageUrl('javascript:alert(1)')).toBeNull()
    expect(sanitizeImageUrl('/relative.png')).toBeNull()
    expect(sanitizeImageUrl(null)).toBeNull()
  })
})
