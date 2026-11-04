/**
 * Copy text to the clipboard.
 *
 * The async Clipboard API is preferred. A hidden textarea fallback keeps copy
 * working in older browsers and in test environments that do not expose
 * `navigator.clipboard`.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // Fall through to the legacy path.
  }

  if (typeof document === 'undefined') {
    return false
  }

  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()

    const copied =
      typeof document.execCommand === 'function' ? document.execCommand('copy') : false

    document.body.removeChild(textarea)
    return copied
  } catch {
    return false
  }
}
