import type { HighlighterCore } from 'shiki/core'
import type { LanguageRegistration } from 'shiki/types'

/**
 * Bounded language set for M1 code blocks.
 *
 * The list is intentionally small. Shiki itself is loaded lazily by
 * `CodeBlock`, and only these grammars are registered with the highlighter.
 */
export const SUPPORTED_LANGUAGES = [
  'bash',
  'c',
  'cpp',
  'csharp',
  'css',
  'diff',
  'go',
  'html',
  'java',
  'javascript',
  'json',
  'jsx',
  'kotlin',
  'markdown',
  'php',
  'python',
  'ruby',
  'rust',
  'shellscript',
  'sql',
  'swift',
  'toml',
  'tsx',
  'typescript',
  'xml',
  'yaml',
] as const

export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number]

type LanguageLoader = () => Promise<{ default: LanguageRegistration[] }>

const LANGUAGE_LOADERS: Record<SupportedLanguage, LanguageLoader> = {
  bash: () => import('shiki/langs/bash.mjs'),
  c: () => import('shiki/langs/c.mjs'),
  cpp: () => import('shiki/langs/cpp.mjs'),
  csharp: () => import('shiki/langs/csharp.mjs'),
  css: () => import('shiki/langs/css.mjs'),
  diff: () => import('shiki/langs/diff.mjs'),
  go: () => import('shiki/langs/go.mjs'),
  html: () => import('shiki/langs/html.mjs'),
  java: () => import('shiki/langs/java.mjs'),
  javascript: () => import('shiki/langs/javascript.mjs'),
  json: () => import('shiki/langs/json.mjs'),
  jsx: () => import('shiki/langs/jsx.mjs'),
  kotlin: () => import('shiki/langs/kotlin.mjs'),
  markdown: () => import('shiki/langs/markdown.mjs'),
  php: () => import('shiki/langs/php.mjs'),
  python: () => import('shiki/langs/python.mjs'),
  ruby: () => import('shiki/langs/ruby.mjs'),
  rust: () => import('shiki/langs/rust.mjs'),
  shellscript: () => import('shiki/langs/shellscript.mjs'),
  sql: () => import('shiki/langs/sql.mjs'),
  swift: () => import('shiki/langs/swift.mjs'),
  toml: () => import('shiki/langs/toml.mjs'),
  tsx: () => import('shiki/langs/tsx.mjs'),
  typescript: () => import('shiki/langs/typescript.mjs'),
  xml: () => import('shiki/langs/xml.mjs'),
  yaml: () => import('shiki/langs/yaml.mjs'),
}

const LANGUAGE_ALIASES: Record<string, SupportedLanguage> = {
  bash: 'bash',
  c: 'c',
  'c++': 'cpp',
  cc: 'cpp',
  cpp: 'cpp',
  cs: 'csharp',
  csharp: 'csharp',
  css: 'css',
  diff: 'diff',
  go: 'go',
  golang: 'go',
  html: 'html',
  java: 'java',
  js: 'javascript',
  javascript: 'javascript',
  json: 'json',
  jsx: 'jsx',
  kt: 'kotlin',
  kotlin: 'kotlin',
  md: 'markdown',
  markdown: 'markdown',
  php: 'php',
  py: 'python',
  python: 'python',
  rb: 'ruby',
  ruby: 'ruby',
  rs: 'rust',
  rust: 'rust',
  sh: 'shellscript',
  shell: 'shellscript',
  shellscript: 'shellscript',
  sql: 'sql',
  swift: 'swift',
  toml: 'toml',
  ts: 'typescript',
  tsx: 'tsx',
  typescript: 'typescript',
  xml: 'xml',
  yml: 'yaml',
  yaml: 'yaml',
  zsh: 'shellscript',
}

const SUPPORTED = new Set<string>(SUPPORTED_LANGUAGES)

/** Normalize a Markdown fence info string to a supported Shiki language. */
export function normalizeLanguage(language: string | null | undefined): SupportedLanguage | null {
  if (!language) {
    return null
  }

  const normalized = language.trim().toLowerCase().replace(/^\./, '')

  if (normalized === '') {
    return null
  }

  const alias = LANGUAGE_ALIASES[normalized]

  if (alias !== undefined) {
    return alias
  }

  return SUPPORTED.has(normalized) ? (normalized as SupportedLanguage) : null
}

export interface HighlightedCode {
  tokens: Awaited<ReturnType<HighlighterCore['codeToTokens']>>['tokens']
  foreground: string
  background: string
}

let highlighterPromise: Promise<HighlighterCore | null> | null = null

/**
 * Load Shiki on first use.
 *
 * Only the highlighter core and the theme are loaded upfront. The JavaScript
 * regex engine avoids shipping the Oniguruma WASM binary. If a grammar cannot
 * be compiled by that engine, `highlightCode` falls back to plain text instead
 * of breaking the message.
 */
export function loadHighlighter(): Promise<HighlighterCore | null> {
  if (highlighterPromise === null) {
    highlighterPromise = Promise.all([
      import('shiki/core'),
      import('shiki/engine/javascript'),
    ])
      .then(([core, engine]) =>
        core.createHighlighterCore({
          themes: [() => import('shiki/themes/github-dark.mjs')],
          langs: [],
          engine: engine.createJavaScriptRegexEngine(),
        }),
      )
      .catch(() => null)
  }

  return highlighterPromise
}

/** Highlight code with the bounded language set, or return null on failure. */
export async function highlightCode(
  code: string,
  language: SupportedLanguage | null,
): Promise<HighlightedCode | null> {
  if (language === null) {
    return null
  }

  const highlighter = await loadHighlighter()

  if (highlighter === null) {
    return null
  }

  try {
    await highlighter.loadLanguage(LANGUAGE_LOADERS[language])

    const result = highlighter.codeToTokens(code, {
      lang: language,
      theme: 'github-dark',
    })

    return {
      tokens: result.tokens,
      foreground: result.fg ?? '#e6edf3',
      background: result.bg ?? '#0d1117',
    }
  } catch {
    return null
  }
}
