import {
  Children,
  isValidElement,
  useMemo,
  useState,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from 'react'
import Markdown, { type Components, type ExtraProps } from 'react-markdown'
import rehypeSanitize from 'rehype-sanitize'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import { CodeBlock } from './CodeBlock'
import { MathRenderer } from './MathRenderer'
import {
  MARKDOWN_SANITIZE_SCHEMA,
  closeIncompleteFence,
  remarkHtmlAsText,
  sanitizeImageUrl,
  sanitizeMarkdownUrl,
} from './markdownSanitize'
import './chat.css'

const SAFE_PROTOCOL = /^(https?:|mailto:)/i
const MATH_LANGUAGE = /\blanguage-math\b/

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

interface MarkdownImageProps extends ComponentPropsWithoutRef<'img'>, ExtraProps {
  loadRemoteImages: boolean
}

function MarkdownImage({ src, alt, loadRemoteImages, node, ...props }: MarkdownImageProps) {
  void node
  void props

  const [loadedHere, setLoadedHere] = useState(false)
  const safe = sanitizeImageUrl(src)
  const label = alt?.trim() || 'Remote image'

  if (safe === null) {
    return (
      <span className="markdown__image-blocked" role="note">
        <span className="markdown__image-blocked-label">{label}</span>
        <span className="markdown__image-blocked-note">Remote image source blocked.</span>
      </span>
    )
  }

  if (loadRemoteImages || loadedHere) {
    return (
      <span className="markdown__image">
        <img
          src={safe}
          alt={alt ?? ''}
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
        />
      </span>
    )
  }

  return (
    <span
      className="markdown__image-blocked"
      role="note"
      aria-label={`${label}. Remote images are blocked.`}
    >
      <span className="markdown__image-blocked-label">{label}</span>
      <span className="markdown__image-blocked-note">Remote images are blocked.</span>
      <button type="button" className="markdown__image-load" onClick={() => setLoadedHere(true)}>
        Load image
      </button>
      <a href={safe} target="_blank" rel="noopener noreferrer">
        Open image
      </a>
    </span>
  )
}

function markdownComponents(loadRemoteImages: boolean): Components {
  return {
    a: MarkdownLink,
    img: (props) => <MarkdownImage {...props} loadRemoteImages={loadRemoteImages} />,
    pre({ children, node, ...props }) {
      void node

      const child = Children.toArray(children)[0]

      if (isValidElement<{ className?: string; children?: ReactNode }>(child)) {
        const className = child.props.className ?? ''
        const code = String(child.props.children ?? '').replace(/\n$/, '')

        if (MATH_LANGUAGE.test(className)) {
          return <MathRenderer source={code} display />
        }

        const language = /language-([\w-]+)/.exec(className)?.[1] ?? null

        return <CodeBlock code={code} language={language} />
      }

      return <pre {...props}>{children}</pre>
    },
    code({ children, className, node, ...props }) {
      void node
      void props

      const code = String(children ?? '').replace(/\n$/, '')

      if (MATH_LANGUAGE.test(className ?? '')) {
        return <MathRenderer source={code} display={false} />
      }

      return <CodeBlock code={code} inline />
    },
    table({ children, node, ...props }) {
      void node

      return (
        <div className="markdown__table-scroll">
          <table {...props}>{children}</table>
        </div>
      )
    },
  }
}

export interface MarkdownRendererProps {
  markdown: string
  className?: string
  /** Global remote-image policy. Defaults to blocked (spec 11.6). */
  loadRemoteImages?: boolean
}

/**
 * Safe Markdown renderer for message content.
 *
 * GFM is enabled for tables, task lists, strikethrough, and autolinks, and math
 * is parsed with `remark-math`. Raw HTML is rendered as text, links are
 * restricted to the M1 allowlist, remote images are replaced with a blocked
 * placeholder unless the setting or a per-message load action allows them, and
 * an unterminated fence is closed for rendering so streaming code never flashes
 * raw backticks.
 */
export function MarkdownRenderer({
  markdown,
  className,
  loadRemoteImages = false,
}: MarkdownRendererProps) {
  const components = useMemo(() => markdownComponents(loadRemoteImages), [loadRemoteImages])

  return (
    <div className={['markdown', className].filter(Boolean).join(' ')}>
      <Markdown
        remarkPlugins={[remarkGfm, remarkMath, remarkHtmlAsText]}
        rehypePlugins={[[rehypeSanitize, MARKDOWN_SANITIZE_SCHEMA]]}
        urlTransform={(url) => sanitizeMarkdownUrl(url) ?? ''}
        components={components}
      >
        {closeIncompleteFence(markdown)}
      </Markdown>
    </div>
  )
}
