import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from '../../ui/primitives'
import { SHORTCUTS } from './shortcuts'
import './shortcuts.css'

export interface ShortcutsHelpDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Keyboard shortcut reference opened with Ctrl/Cmd + /. */
export function ShortcutsHelpDialog({ open, onOpenChange }: ShortcutsHelpDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="shortcuts-help">
        <DialogTitle>Keyboard shortcuts</DialogTitle>
        <DialogDescription>
          Shortcuts are ignored while you are typing in a text field, except Escape.
        </DialogDescription>
        <dl className="shortcuts-help__list">
          {SHORTCUTS.map((shortcut) => (
            <div key={shortcut.keys} className="shortcuts-help__row">
              <dt>
                <kbd>{shortcut.keys}</kbd>
              </dt>
              <dd>{shortcut.action}</dd>
            </div>
          ))}
        </dl>
      </DialogContent>
    </Dialog>
  )
}
