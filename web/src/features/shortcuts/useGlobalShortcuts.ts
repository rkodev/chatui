import { useEffect } from 'react'
import { isEditableTarget } from './shortcuts'

export interface GlobalShortcutHandlers {
  onSearch: () => void
  onNewChat: () => void
  onToggleHelp: () => void
  onCopyLastResponse: () => void
  onNavigateConversation: (direction: 'previous' | 'next') => void
  onStopStreaming: () => void
}

/**
 * Install the section 11.10 keyboard shortcuts on the window.
 *
 * Shortcuts are ignored while the user is typing in an input, textarea,
 * select, or contenteditable element. Escape is the exception: it must still
 * stop streaming and close dialogs from the composer.
 */
export function useGlobalShortcuts(handlers: GlobalShortcutHandlers): void {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      // Dialogs and sheets own the keyboard while they are open. Radix handles
      // Escape and focus restoration, so global shortcuts must not compete.
      if (document.querySelector('[role="dialog"]') !== null) {
        return
      }

      const modifier = event.ctrlKey || event.metaKey
      const key = event.key.toLowerCase()
      const typing = isEditableTarget(event.target)

      if (event.key === 'Escape') {
        handlers.onStopStreaming()
        return
      }

      // Plain character keys must always reach the focused field. Modifier
      // chords are safe to handle while typing because they do not insert text.
      if (typing && !modifier && !event.altKey) {
        return
      }

      if (modifier && !event.shiftKey && !event.altKey && key === 'k') {
        event.preventDefault()
        handlers.onSearch()
        return
      }

      if (modifier && event.shiftKey && !event.altKey && key === 'o') {
        event.preventDefault()
        handlers.onNewChat()
        return
      }

      if (modifier && !event.shiftKey && !event.altKey && event.key === '/') {
        event.preventDefault()
        handlers.onToggleHelp()
        return
      }

      if (modifier && event.shiftKey && !event.altKey && key === 'c') {
        event.preventDefault()
        handlers.onCopyLastResponse()
        return
      }

      if (event.altKey && !modifier && !event.shiftKey) {
        if (event.key === 'ArrowUp') {
          event.preventDefault()
          handlers.onNavigateConversation('previous')
        } else if (event.key === 'ArrowDown') {
          event.preventDefault()
          handlers.onNavigateConversation('next')
        }
      }
    }

    window.addEventListener('keydown', handleKeyDown)

    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [handlers])
}
