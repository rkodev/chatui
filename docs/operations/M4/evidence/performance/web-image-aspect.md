# Web image aspect-ratio verification

- Date: 2026-11-10
- Build: production `vite build` served by `vite preview`
- Browser: Playwright Chromium 1248 (headless), desktop viewport
- Method: complete onboarding against the deterministic OpenRouter mock, send a
  message whose response is `![Diagram](https://example.com/diagram.png)`, click
  "Load image", then read the computed style of `.markdown__image`.

## Result

```json
{"aspectRatio":"16 / 9","width":"640px","display":"block"}
```

The image wrapper reserves a 16:9 box before the remote image loads. Images that
declare `width` and `height` get an exact inline `aspect-ratio` instead. The
temporary Playwright spec was removed after recording.
