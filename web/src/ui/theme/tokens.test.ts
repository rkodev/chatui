import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function readStyle(name: string): string {
  return readFileSync(resolve(process.cwd(), 'src/styles', name), 'utf8')
}

const tokensCss = readStyle('tokens.css')
const themesCss = readStyle('themes.css')
const layoutCss = readStyle('layout.css')
const typographyCss = readStyle('typography.css')

const requiredTokens = [
  '--color-bg-canvas',
  '--color-bg-surface',
  '--color-bg-surface-raised',
  '--color-bg-user-bubble',
  '--color-bg-code',
  '--color-fg-primary',
  '--color-fg-secondary',
  '--color-fg-muted',
  '--color-fg-on-accent',
  '--color-accent',
  '--color-accent-hover',
  '--color-border-subtle',
  '--color-border-strong',
  '--color-danger',
  '--color-warning',
  '--color-success',
  '--space-1',
  '--space-2',
  '--space-3',
  '--space-4',
  '--space-5',
  '--space-6',
  '--space-7',
  '--radius-sm',
  '--radius-md',
  '--radius-lg',
  '--radius-full',
  '--font-body-family',
  '--font-body-size',
  '--font-body-line-height',
  '--font-code-family',
  '--font-code-size',
  '--font-heading-family',
  '--font-heading-size',
  '--font-heading-size-1',
  '--font-heading-size-2',
  '--font-heading-size-3',
  '--font-heading-line-height',
  '--font-heading-weight',
]

function blockFor(css: string, selector: string): string {
  const selectorIndex = css.indexOf(selector)

  if (selectorIndex === -1) {
    throw new Error(`Missing selector ${selector}`)
  }

  const open = css.indexOf('{', selectorIndex)
  const close = css.indexOf('}', open)

  if (open === -1 || close === -1) {
    throw new Error(`Malformed block for ${selector}`)
  }

  return css.slice(open + 1, close)
}

function colorToken(block: string, name: string): string {
  const match = block.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`))

  if (!match) {
    throw new Error(`Missing color token ${name}`)
  }

  return match[1]
}

function relativeLuminance(hex: string): number {
  const value = hex.replace('#', '')
  const channels = [0, 2, 4].map((offset) => {
    const channel = Number.parseInt(value.slice(offset, offset + 2), 16) / 255
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  })

  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
}

function contrastRatio(foreground: string, background: string): number {
  const lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background))
  const darker = Math.min(relativeLuminance(foreground), relativeLuminance(background))
  return (lighter + 0.05) / (darker + 0.05)
}

describe('design tokens', () => {
  it('defines every semantic token from spec 11.12', () => {
    for (const token of requiredTokens) {
      expect(tokensCss).toContain(`${token}:`)
    }
  })

  it('defines light, dark, and system theme overrides', () => {
    expect(themesCss).toContain(":root[data-theme='light']")
    expect(themesCss).toContain(":root[data-theme='dark']")
    expect(themesCss).toContain(":root[data-theme='system']")
    expect(themesCss).toContain('@media (prefers-color-scheme: dark)')
  })

  it('defines comfortable and compact density selectors', () => {
    expect(layoutCss).toContain(":root[data-density='comfortable']")
    expect(layoutCss).toContain(":root[data-density='compact']")
  })

  it('defines sm, md, and lg chat font size selectors', () => {
    expect(typographyCss).toContain(":root[data-chat-font-size='sm']")
    expect(typographyCss).toContain(":root[data-chat-font-size='md']")
    expect(typographyCss).toContain(":root[data-chat-font-size='lg']")
  })

  it('defines visible focus and reduced-motion rules', () => {
    expect(layoutCss).toContain(':focus-visible')
    expect(layoutCss).toContain('@media (prefers-reduced-motion: reduce)')
  })

  it.each([
    ['light', ":root[data-theme='light']"],
    ['dark', ":root[data-theme='dark']"],
  ])('keeps normal text at 4.5:1 or better in the %s theme', (_name, selector) => {
    const block = blockFor(themesCss, selector)
    const backgrounds = [
      '--color-bg-canvas',
      '--color-bg-surface',
      '--color-bg-surface-raised',
      '--color-bg-user-bubble',
      '--color-bg-code',
    ]
    const foregrounds = ['--color-fg-primary', '--color-fg-secondary', '--color-fg-muted']

    for (const background of backgrounds) {
      for (const foreground of foregrounds) {
        const ratio = contrastRatio(colorToken(block, foreground), colorToken(block, background))
        expect(ratio, `${foreground} on ${background}`).toBeGreaterThanOrEqual(4.5)
      }
    }

    for (const accent of ['--color-accent', '--color-accent-hover']) {
      const ratio = contrastRatio(
        colorToken(block, '--color-fg-on-accent'),
        colorToken(block, accent),
      )
      expect(ratio, `--color-fg-on-accent on ${accent}`).toBeGreaterThanOrEqual(4.5)
    }

    for (const status of ['--color-danger', '--color-warning', '--color-success']) {
      const ratio = contrastRatio(colorToken(block, status), colorToken(block, '--color-bg-canvas'))
      expect(ratio, `${status} on --color-bg-canvas`).toBeGreaterThanOrEqual(4.5)
    }
  })
})
