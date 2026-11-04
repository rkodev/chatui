import { defaultSchema, type Options as SanitizeSchema } from 'rehype-sanitize'

/**
 * Sanitize schema for rendered Markdown.
 *
 * Raw HTML is never enabled (`rehype-raw` is not installed), so HTML in the
 * source stays text. The schema is still applied as defence in depth and
 * narrows link protocols to the M1 allowlist.
 */
export const MARKDOWN_SANITIZE_SCHEMA: SanitizeSchema = {
  ...defaultSchema,
  protocols: {
    ...defaultSchema.protocols,
    href: ['http', 'https', 'mailto'],
  },
}

const HAS_PROTOCOL = /^[a-z][a-z0-9+.-]*:/i

/**
 * Return a safe URL, or null when the URL is not allowed.
 *
 * Allowed: absolute `http`, `https`, and `mailto` URLs plus in-app `#anchor`
 * links. Relative paths, protocol-relative URLs, and every other scheme are
 * rejected.
 */
export function sanitizeMarkdownUrl(url: string | null | undefined): string | null {
  if (!url) {
    return null
  }

  const trimmed = url.trim()

  if (trimmed === '') {
    return null
  }

  if (trimmed.startsWith('#')) {
    return trimmed
  }

  if (!HAS_PROTOCOL.test(trimmed)) {
    return null
  }

  try {
    const parsed = new URL(trimmed)

    if (
      parsed.protocol === 'http:' ||
      parsed.protocol === 'https:' ||
      parsed.protocol === 'mailto:'
    ) {
      return trimmed
    }
  } catch {
    return null
  }

  return null
}

/**
 * Return a safe remote-image URL, or null when the URL is not allowed.
 *
 * Images may only point at absolute `http` or `https` URLs. `mailto`, anchors,
 * relative paths, data URLs, and every other scheme are rejected.
 */
export function sanitizeImageUrl(url: string | null | undefined): string | null {
  const safe = sanitizeMarkdownUrl(url)

  if (safe === null) {
    return null
  }

  try {
    const parsed = new URL(safe)

    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? safe : null
  } catch {
    return null
  }
}

/**
 * Close an unterminated fenced code block for rendering only.
 *
 * Streaming text can end in the middle of a fence. Appending the matching
 * closing fence lets the Markdown parser render a code block instead of
 * flashing raw backticks. The original text is never mutated.
 */
export function closeIncompleteFence(markdown: string): string {
  const lines = markdown.split('\n')
  let open: { character: string; length: number } | null = null

  for (const line of lines) {
    const match = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line)

    if (!match) {
      continue
    }

    const fence = match[1]
    const character = fence[0]
    const length = fence.length

    if (open === null) {
      open = { character, length }
      continue
    }

    if (character === open.character && length >= open.length && match[2].trim() === '') {
      open = null
    }
  }

  if (open === null) {
    return markdown
  }

  const closing = open.character.repeat(open.length)
  return markdown.endsWith('\n') ? `${markdown}${closing}` : `${markdown}\n${closing}`
}

interface MarkdownNode {
  type: string
  value?: unknown
  children?: MarkdownNode[]
}

/**
 * Remark plugin that turns raw HTML nodes into plain text.
 *
 * `react-markdown` normally promotes raw HTML to `raw` nodes, which
 * `rehype-sanitize` then removes. Rewriting the nodes to text before the
 * Markdown-to-HTML step keeps the source visible while React still escapes it,
 * so raw HTML is inert and never parsed.
 */
export function remarkHtmlAsText() {
  return (tree: MarkdownNode): void => {
    const walk = (node: MarkdownNode): void => {
      if (!node.children) {
        return
      }

      for (let index = 0; index < node.children.length; index += 1) {
        const child = node.children[index]

        if (child.type === 'html' && typeof child.value === 'string') {
          node.children[index] = { type: 'text', value: child.value }
        } else {
          walk(child)
        }
      }
    }

    walk(tree)
  }
}
