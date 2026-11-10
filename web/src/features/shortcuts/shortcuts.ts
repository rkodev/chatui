/**
 * Keyboard shortcut definitions from spec section 11.10.
 *
 * The labels are platform-neutral: `Mod` means Ctrl on Windows/Linux and Cmd
 * on macOS. The help dialog renders these rows so the documented shortcuts and
 * the implemented handlers cannot drift apart.
 */
export interface ShortcutDefinition {
  keys: string
  action: string
}

export const SHORTCUTS: readonly ShortcutDefinition[] = [
  { keys: 'Ctrl/Cmd + K', action: 'Search conversations' },
  { keys: 'Ctrl/Cmd + Shift + O', action: 'New chat' },
  { keys: 'Ctrl/Cmd + /', action: 'Toggle shortcuts help' },
  { keys: 'Esc', action: 'Stop streaming or close a dialog' },
  { keys: 'Enter', action: 'Send (mode-dependent)' },
  { keys: 'Shift + Enter', action: 'Newline' },
  { keys: 'Ctrl/Cmd + Shift + C', action: 'Copy last response' },
  { keys: 'Alt + Up/Down', action: 'Navigate conversations' },
]

/**
 * True when the event target accepts text input.
 *
 * Global shortcuts must not fire while the user is typing, except for Escape,
 * which is handled separately so it can stop streaming or close a dialog.
 */
export function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false
  }

  if (
    target.isContentEditable ||
    target.contentEditable === 'true' ||
    target.getAttribute('contenteditable') === 'true'
  ) {
    return true
  }

  const tag = target.tagName.toLowerCase()

  return tag === 'input' || tag === 'textarea' || tag === 'select'
}
