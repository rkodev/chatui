# Reduced motion, scaling, and overflow (M4-01.1)

Date: 2026-11-10
Browser: Chromium (Playwright 1.64.1).

## Reduced motion

`web/src/styles/layout.css` disables animation and transition durations and
forces `scroll-behavior: auto` under `prefers-reduced-motion: reduce`.

Automated check: `web/e2e/accessibility.spec.ts` emulates
`prefers-reduced-motion: reduce` and asserts that a shared button's computed
`transition-duration` is effectively zero (`1e-05s` in Chromium) and that the
chat screen's computed `scroll-behavior` is `auto`.

Result: pass.

## Scalable text

The layout uses `rem` for the message column and composer maximum width. The
automated check sets the root font size to 32 px and asserts that the computed
maximum width becomes 1536 px (48 rem × 32 px), proving the column scales with
user text size rather than a fixed pixel value.

Result: pass.

## Table and code overflow

Markdown tables render inside `.markdown__table-scroll` and the table itself
uses `overflow-x: auto`. Fenced code blocks use `.code-block__pre` with
`overflow-x: auto`. The automated check sends a wide table and a long code line,
then asserts both computed `overflow-x` values are `auto` and the document has
no horizontal page overflow.

Result: pass.

## Message column

At 1280 px the message body and composer are capped at 48 rem (768 px) and
centered. The responsive test asserts the computed `max-width` is `768px` and
the rendered width never exceeds it.

Result: pass.
