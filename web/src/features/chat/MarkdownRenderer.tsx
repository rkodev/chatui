import {
  Children,
  isValidElement,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from 'react'
import Markdown, { type Components, type ExtraProps } from 'react-markdown'
import rehypeSanitize from 'rehype-sanitize'
import remarkGfm from 'remark-gfm'
import { CodeBlock } from './CodeBlock'
import {
  MARKDOWN_SANITIZE_SCHEMA,
  closeIncompleteFence,
  remarkHtmlAsText,
  sanitizeMarkdownUrl,
} from './markdownSanitize'
import './chat.css'

const SAFE_PROTOCOL = /^(https?:|mailto:)/i

function MarkdownLink({
  href,
  children,
  node,
  ...props
}: ComponentPropsWithoutRef<'a'> & ExtraProps) {
  void node

  const safe = sanitizeMarkdownUrl(href)

  if (safe === null) {
    return <span className="markdown__link-blocked">{children}</span>
  }

  const external = SAFE_PROTOCOL.test(safe)

  return (
    <a
      href={safe}
      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
      {...props}
    >
      {children}
    </a>
  )
}

function MarkdownImage({
  src,
  alt,
  node,
  ...props
}: ComponentPropsWithoutRef<'img'> & ExtraProps) {
  void node
  void props

  const safe = sanitizeMarkdownUrl(src)
  const label = alt?.trim() || 'Remote image'

  return (
    <span
      className="markdown__image-blocked"
      role="note"
      aria-label={`${label}. Remote images are blocked.`}
    >
      <span className="markdown__image-blocked-label">{label}</span>
      <span className="markdown__image-blocked-note">Remote images are blocked.</span>
      {safe !== null && (
        <a href={safe} target="_blank" rel="noopener noreferrer">
          Open image
        </a>
      )}
    </span>
  )
}

const components: Components = {
  a: MarkdownLink,
  img: MarkdownImage,
  pre({ children, node, ...props }) {
    void node

    const child = Children.toArray(children)[0]

    if (isValidElement<{ className?: string; children?: ReactNode }>(child)) {
      const className = child.props.className ?? ''
      const language = /language-([\w-]+)/.exec(className)?.[1] ?? null
      const code = String(child.props.children ?? '').replace(/\n$/, '')

      return <CodeBlock code={code} language={language} />
    }

    return <pre {...props}>{children}</pre>
  },
  code({ children, node, ...props }) {
    void node
    void props

    return <CodeBlock code={String(children ?? '')} inline />
  },
}

export interface MarkdownRendererProps {
  markdown: string
  className?: string
}

/**
 * Safe Markdown renderer for message content.
 *
 * GFM is enabled for tables, task lists, strikethrough, and autolinks. Raw
 * HTML is rendered as text, links are restricted to the M1 allowlist, remote
 * images are replaced with a blocked placeholder, and an unterminated fence is
 * closed for rendering so streaming code never flashes raw backticks.
 */
export function MarkdownRenderer({ markdown, className }: MarkdownRendererProps) {
  return (
    <div className={['markdown', className].filter(Boolean).join(' ')}>
      <Markdown
        remarkPlugins={[remarkGfm, remarkHtmlAsText]}
        rehypePlugins={[[rehypeSanitize, MARKDOWN_SANITIZE_SCHEMA]]}
        urlTransform={(url) => sanitizeMarkdownUrl(url) ?? ''}
        components={components}
      >
        {closeIncompleteFence(markdown)}
      </Markdown>
    </div>
  )
}
