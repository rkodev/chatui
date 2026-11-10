# Accessibility and UX evidence (M4-01.1)

Date: 2026-11-10
Task: [M4-01.1](../M4-01.1-accessibility-ux-polish.md)
Clients checked: web (required), Android (required), Rust desktop (not shipping).

## axe results

The Playwright suite runs `@axe-core/playwright` with WCAG 2.0/2.1/2.2 A and AA
tags on every main route. The test fails on any `serious` or `critical`
violation. Full JSON and HTML reports are in [`axe/`](axe/).

| Route | URL | serious | critical | moderate | minor |
| --- | --- | --- | --- | --- | --- |
| onboarding | `/` (first run) | 0 | 0 | 0 | 0 |
| chat | `/chat/:id` | 0 | 0 | 0 | 0 |
| conversations | `/` (restored chat with sidebar) | 0 | 0 | 0 | 0 |
| providers | `/settings/providers` | 0 | 0 | 0 | 0 |
| settings | `/settings/appearance` | 0 | 0 | 0 | 0 |
| data | `/settings/data` | 0 | 0 | 0 | 0 |

Command:

```bash
cd web && pnpm exec playwright test e2e/accessibility.spec.ts
```

## Keyboard and focus

See [keyboard-checklist.md](keyboard-checklist.md).

## Reduced motion and scaling

See [reduced-motion.md](reduced-motion.md).

## Android

See [android-talkback.md](android-talkback.md).

## Rust desktop

Rust desktop is `not shipping` per `M4-decisions.md` DEC-M4-01. The Rust
accessibility items are skipped as `post-preview / parallel`; no Rust UI
changes were made.

## Fixes made in this task

- Added the section 11.10 global shortcut handler and shortcut help dialog.
- Added explicit focus return for the conversation model picker.
- Added the 48 rem message/composer column and aligned the sidebar drawer
  breakpoint with section 11.9 (drawer below 840 px, persistent at 840 px and
  above).
- Added `data-message-text` to message rows so copy-last-response uses the
  message text rather than rendered metadata.
- Extended the Playwright accessibility suite to cover axe, keyboard, focus
  return, live regions, responsive breakpoints, states, reduced motion,
  scalable text, overflow, and shortcuts.
- Preserved newlines in the mock provider so Markdown tables and fenced code
  blocks can be exercised end to end.

## Accepted exceptions

None. No serious or critical axe findings, no keyboard blockers, and no
unresolved web accessibility defects were found.
