import {
  Fragment,
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react'
import { Button } from '../../ui/primitives'
import { copyText } from './clipboard'
import {
  highlightCode,
  normalizeLanguage,
  type HighlightedCode,
} from './highlighter'
import './chat.css'

export interface CodeBlockProps {
  code: string
  language?: string | null
  inline?: boolean
}

function tokenStyle(token: HighlightedCode['tokens'][number][number]): CSSProperties {
  const style: CSSProperties = {}

  if (token.color) {
    style.color = token.color
  }

  if (token.fontStyle) {
    if (token.fontStyle & 1) {
      style.fontStyle = 'italic'
    }
    if (token.fontStyle & 2) {
      style.fontWeight = 700
    }
    if (token.fontStyle & 4) {
      style.textDecoration = 'underline'
    }
  }

  return style
}

function renderTokens(tokens: HighlightedCode['tokens']): ReactNode {
  return tokens.map((line, lineIndex) => (
    <Fragment key={lineIndex}>
      {lineIndex > 0 ? '\n' : null}
      {line.map((token, tokenIndex) => (
        <span key={tokenIndex} style={tokenStyle(token)}>
          {token.content}
        </span>
      ))}
    </Fragment>
  ))
}

/**
 * Fenced code block with a language label, exact-text copy, and a wrap toggle.
 *
 * Highlighting is lazy: the Shiki module is imported only when a code block is
 * rendered, and the plain code is shown until the highlighter resolves. Copy
 * always uses the original source text, never the highlighted DOM.
 */
export function CodeBlock({ code, language, inline = false }: CodeBlockProps) {
  const [highlighted, setHighlighted] = useState<{
    key: string
    value: HighlightedCode
  } | null>(null)
  const [wrap, setWrap] = useState(false)
  const [copied, setCopied] = useState(false)
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const normalized = normalizeLanguage(language)
  const highlightKey = `${normalized ?? 'text'}:${code}`
  const activeHighlighted =
    highlighted !== null && highlighted.key === highlightKey ? highlighted.value : null

  useEffect(() => {
    if (normalized === null) {
      return
    }

    let cancelled = false

    highlightCode(code, normalized).then((result) => {
      if (!cancelled && result !== null) {
        setHighlighted({ key: highlightKey, value: result })
      }
    })

    return () => {
      cancelled = true
    }
  }, [code, highlightKey, normalized])

  useEffect(
    () => () => {
      if (copyTimer.current !== null) {
        clearTimeout(copyTimer.current)
      }
    },
    [],
  )

  const handleCopy = useCallback(async () => {
    const copiedOk = await copyText(code)

    if (!copiedOk) {
      return
    }

    setCopied(true)

    if (copyTimer.current !== null) {
      clearTimeout(copyTimer.current)
    }

    copyTimer.current = setTimeout(() => {
      setCopied(false)
      copyTimer.current = null
    }, 1500)
  }, [code])

  if (inline) {
    return <code className="markdown__inline-code">{code}</code>
  }

  const label = language?.trim() || 'Code'

  return (
    <div className="code-block" data-language={normalized ?? 'text'}>
      <div className="code-block__header">
        <span className="code-block__language">{label}</span>
        <div className="code-block__actions">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setWrap((current) => !current)}
            aria-pressed={wrap}
            aria-label={wrap ? 'Disable line wrapping' : 'Wrap long lines'}
          >
            {wrap ? 'No wrap' : 'Wrap lines'}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={handleCopy}
            aria-label="Copy code"
          >
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>
      </div>
      <pre
        className={`code-block__pre${wrap ? ' code-block__pre--wrap' : ''}`}
        style={
          activeHighlighted === null
            ? undefined
            : {
                backgroundColor: activeHighlighted.background,
                color: activeHighlighted.foreground,
              }
        }
      >
        <code>{activeHighlighted === null ? code : renderTokens(activeHighlighted.tokens)}</code>
      </pre>
    </div>
  )
}
