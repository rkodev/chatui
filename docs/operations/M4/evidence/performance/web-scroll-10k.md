# Web 10,000-message scroll measurement

- Date: 2026-11-10
- Build: production `vite build` served by `vite preview` on `127.0.0.1:4173`
- Browser: Playwright Chromium 1248 (headless), desktop viewport 1280x720
- Host: `ronald-Lenovo-Legion-S7-15IMH5`, Ubuntu 26.04.1 LTS, x86_64,
  16 logical CPUs / 30 GiB RAM
- Method: complete onboarding against the deterministic OpenRouter mock, seed
  10,000 persisted messages into IndexedDB for the created conversation, reload
  the chat route, click "Load older messages" until every page is loaded, then
  scroll the `.message-list` container from top to bottom over 3 seconds while
  recording `requestAnimationFrame` deltas.

## Results

| Run | Loaded pages | Frames | Mean frame | p95 frame | FPS | Dropped (>20 ms) |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 190 | 181 | 16.60 ms | 16.70 ms | 60.23 | 0 (0%) |
| 2 | 198 | 179 | 16.76 ms | 16.80 ms | 59.65 | 1 (0.56%) |
| 3 | 198 | 181 | 16.66 ms | 16.70 ms | 60.03 | 0 (0%) |

Median FPS: **60.03**. The list is windowed (`useWindowedList`), message rows
are memoized by id plus content revision, and the streaming row is isolated
from history. The measured scroll height was ~1,205,000 px, consistent with
10,000 loaded rows.

## Notes

- This is a headless Chromium run on the recorded host, not a physical
  mid-tier phone. It is the same browser engine and viewport used by the
  Playwright suite. The 60 fps target is met with 0-1 dropped frames.
- The temporary Playwright measurement spec was removed after recording; it is
  not part of the committed test suite because loading 10,000 messages takes
  ~19 seconds per run.
