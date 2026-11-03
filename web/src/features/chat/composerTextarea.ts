/** Composer grows from one line up to this many lines, then scrolls. */
export const MAX_COMPOSER_LINES = 8

const FALLBACK_LINE_HEIGHT_PX = 20

/**
 * Resize a composer textarea to its content, capped at eight lines.
 *
 * The height is measured from `scrollHeight` so wrapped lines count. The
 * computed line height and vertical padding keep the cap aligned with the
 * rendered font and density tokens. When the content exceeds the cap the
 * textarea scrolls internally instead of growing further.
 */
export function resizeComposerTextarea(element: HTMLTextAreaElement): void {
  element.style.height = 'auto'

  const style = window.getComputedStyle(element)
  const lineHeight = parseFloat(style.lineHeight) || FALLBACK_LINE_HEIGHT_PX
  const padding = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.paddingBottom) || 0)
  const border =
    (parseFloat(style.borderTopWidth) || 0) + (parseFloat(style.borderBottomWidth) || 0)
  const minHeight = lineHeight + padding + border
  const maxHeight = lineHeight * MAX_COMPOSER_LINES + padding + border
  const nextHeight = Math.min(Math.max(element.scrollHeight, minHeight), maxHeight)

  element.style.height = `${nextHeight}px`
  element.style.overflowY = element.scrollHeight > maxHeight ? 'auto' : 'hidden'
}
