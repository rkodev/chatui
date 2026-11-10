# Web keyboard checklist (M4-01.1)

Date: 2026-11-10
Browser: Chromium (Playwright 1.64.1, production build served by `vite preview`).

## Flows exercised

| Flow | Keyboard path | Result |
| --- | --- | --- |
| Onboarding | Tab to the API key field, type a key, Tab to Connect | Pass; focus ring visible on the field and button |
| Chat send | Focus composer, type, press Enter | Pass; message sends |
| Chat stop | Press Escape while streaming | Pass; response is cancelled and partial content is preserved |
| Model picker | Focus the model chip, press Enter, Tab inside the dialog, press Escape | Pass; focus stays trapped, Escape closes, focus returns to the chip |
| Conversation actions | Focus the actions button, press Enter, press Escape | Pass; menu closes and focus returns to the trigger |
| Settings navigation | Focus Settings, press Enter, focus Data, press Enter | Pass; route changes without a pointer |
| Data controls | Tab to Export all data | Pass; focus ring visible |
| Shortcuts help | Press Ctrl/Cmd + / | Pass; dialog opens and Escape closes it |

## Focus visibility

The shared `:focus-visible` rule in `web/src/styles/layout.css` draws a 2 px
outline with a 2 px offset using the accent token. The Playwright checks assert
that the focused onboarding field, Connect button, and data export button have a
non-`none` outline with a non-zero width.

## Dialog and sheet focus management

- Radix Dialog owns focus trapping, Escape handling, and focus restoration.
- The conversation model picker now explicitly restores focus to its trigger
  when it closes, because it is opened from a controlled chip rather than a
  `DialogTrigger`.
- The conversation actions menu uses Radix Menu and restores focus to its
  trigger on Escape.
- Global shortcuts are ignored while a dialog is open so the dialog keeps
  keyboard priority.

## Shortcuts

Implemented in `web/src/features/shortcuts` and mounted from the conversation
sidebar container:

| Shortcut | Action |
| --- | --- |
| `Ctrl/Cmd + K` | Focus conversation search |
| `Ctrl/Cmd + Shift + O` | New chat |
| `Ctrl/Cmd + /` | Toggle shortcuts help |
| `Esc` | Stop streaming or close a dialog/sheet |
| `Enter` | Send (mode-dependent) |
| `Shift + Enter` | Newline |
| `Ctrl/Cmd + Shift + C` | Copy last response |
| `Alt + Up/Down` | Previous/next conversation |

Plain character keys always reach the focused field. Modifier chords are
handled while typing because they do not insert text; this matches the Android
M3-03.4 decision. Escape is ignored while a dialog is open so Radix can close
the dialog and restore focus.

## Automated coverage

`web/e2e/accessibility.spec.ts` covers the flows above, including focus return
and the typing guard. `web/src/features/shortcuts/shortcuts.test.tsx` covers the
shortcut map, editable-target detection, and the help dialog.
