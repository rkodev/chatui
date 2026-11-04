import { useEffect, useState } from 'react'
import 'katex/dist/katex.min.css'
import './chat.css'

export interface MathRendererProps {
  /** TeX source without the surrounding dollar delimiters. */
  source: string
  display: boolean
}

type MathState = {
  key: string
  status: 'ready' | 'error'
  html?: string
}

function rawSource(source: string, display: boolean): string {
  return display ? `$$\n${source}\n$$` : `$${source}$`
}

/**
 * Lazy KaTeX renderer with a raw-source fallback.
 *
 * KaTeX is loaded only when a message actually contains math. Rendering uses
 * `trust: false`, so commands such as `\href` and `\htmlClass` cannot inject
 * URLs or HTML, and expansion limits bound hostile input. Any parse failure
 * keeps the original TeX visible instead of breaking the message.
 */
export function MathRenderer({ source, display }: MathRendererProps) {
  const key = `${display ? 'block' : 'inline'}:${source}`
  const [state, setState] = useState<MathState | null>(null)
  const current = state?.key === key ? state : null

  useEffect(() => {
    let cancelled = false

    import('katex')
      .then((module) => {
        const katex = module.default

        try {
          const html = katex.renderToString(source, {
            displayMode: display,
            throwOnError: true,
            trust: false,
            strict: 'ignore',
            maxExpand: 1000,
            maxSize: 100,
          })

          if (!cancelled) {
            setState({ key, status: 'ready', html })
          }
        } catch {
          if (!cancelled) {
            setState({ key, status: 'error' })
          }
        }
      })
      .catch(() => {
        if (!cancelled) {
          setState({ key, status: 'error' })
        }
      })

    return () => {
      cancelled = true
    }
  }, [display, key, source])

  if (current?.status === 'ready' && current.html !== undefined) {
    return (
      <span
        className={`math${display ? ' math--display' : ' math--inline'}`}
        data-math-source={source}
        dangerouslySetInnerHTML={{ __html: current.html }}
      />
    )
  }

  const raw = rawSource(source, display)

  if (display) {
    return (
      <pre className="math__fallback math__fallback--display">
        <code>{raw}</code>
      </pre>
    )
  }

  return (
    <code className="math__fallback math__fallback--inline" data-math-source={source}>
      {raw}
    </code>
  )
}
