# M4 release decisions

- Milestone: M4
- Recorded by: `M4-00.1` preflight
- Date: 2026-11-10
- Baseline commit: `495e2c0397975edf920da9b06cd840fab2fde017`
- Owner: Product/release owner
- Status: Confirmed for the phase 1 preview

This file freezes the M4 release scope and the decisions later M4 tasks depend
on. Change a decision only with a new dated entry and a matching update to
`contracts/parity.md` and the release notes.

## DEC-M4-01 - Rust desktop release status

**Decision: `Rust desktop: not shipping`.**

- The Rust desktop client is a post-preview / parallel deliverable for this
  release.
- Basis: [M3.5-decisions.md](../M3.5/M3.5-decisions.md) DEC-01 (`defer`) and
  DEC-15 (M4 does not depend on Rust while the decision is `defer`).
- Consequence: every M4 Rust item marked "only if shipping" is skipped. Every
  surface that mentions Rust must say `post-preview / parallel`, including
  `contracts/parity.md` and the release notes.
- Reversal: a later `proceed` decision requires the full M3.5 exit gate and spec
  section 10.12 release gate before Rust is treated as shipping.

## DEC-M4-02 - Enabled clients

| Client | Status for this release |
| --- | --- |
| Web | Required |
| Android | Required |
| Rust desktop | Not enabled (post-preview / parallel) |

Web and Android are the only release-gated clients. A feature complete on one
required client and absent on the other is a release blocker unless an approved
exception is recorded in `contracts/parity.md`.

## DEC-M4-03 - Release version and tag format

- App semver for this release: **`0.1.0`**.
- Git tag: annotated tag **`v0.1.0`** (the `v` prefix is optional for tooling;
  the semantic part is `0.1.0`).
- Android `versionName`: `0.1.0`; `versionCode`: CI-provided positive integer,
  otherwise the git commit count (monotonic).
- Web container tags: `0.1.0` and `latest`.
- Rationale: `0.1.0` matches the existing Android default `0.1.0-dev` and marks
  the first phase 1 preview. The web `package.json` version is not the release
  source of truth; the tag and CI inputs are.

## DEC-M4-04 - Version integers (spec 16.5)

| Version | Value for this release |
| --- | --- |
| App semver | `0.1.0` |
| Contract version (all `contracts/schema/*.json`) | `1` |
| Backup/export format version | `1` |
| Web IndexedDB schema version (`DB_VERSION`) | `1` |
| Android Room schema version (`CHATUI_DATABASE_VERSION`) | `1` |
| Android export format version (`CHATUI_EXPORT_FORMAT_VERSION`) | `1` |
| Rust SQLite migration head | `0001_initial.sql` (not shipping) |

These integers are independent. A change to one does not imply a change to
another. Release notes must state upgrade and migration notes for any change.

## DEC-M4-05 - Target hardware and measurement methodology

| Measurement | Target / method |
| --- | --- |
| Web Lighthouse mobile | Chrome mobile emulation (Moto G Power class, simulated slow 4G) against the production build; record performance and accessibility scores. Baseline host: Ubuntu 26.04, Chrome 157.0.8103.38. |
| Web bundle | Production `vite build`; initial JS = entry chunk gzip size; total JS = all `dist/assets/*.js`. |
| Web 10,000-message scroll | Mid-tier device/browser; record frame drops and method in `M4-performance.md`. |
| Android cold start | `adb shell am start -W` on a mid-tier physical device where available; the preflight baseline used the `m3_api34_phone` AVD (API 34, x86_64) and is labelled emulator. |
| Android artifact size | Debug APK, release APK, and release AAB from `android/app/build/outputs/`. |
| Rust | Not measured; not shipping. |

Lighthouse and cold-start numbers vary by hardware. Every later measurement
must record the device, OS, browser/tool versions, and exact command.

## DEC-M4-06 - Capacitor

- Capacitor is **not included** in this release (default: no).
- If it is ever added as an internal shell, it must not persist keys unless the
  WebView storage is equivalently protected. It must never become the primary
  Android product.

## DEC-M4-07 - Approved release exceptions

**None.** No feature, accessibility, performance, security, or parity exception
is approved for this release. Any exception added later must record an owner,
a date, and the affected parity row.

## Parity rows without evidence today

The commitment table in [`contracts/parity.md`](../../../contracts/parity.md)
records requirements, not evidence. The following rows lack release evidence
today. `M4-06.1` must verify them and attach evidence; it must not rediscover
this list from scratch.

| Parity row | Client | Gap | Owner |
| --- | --- | --- | --- |
| PWA install | Web | Not implemented; no service worker or manifest evidence yet. | `M4-02.1` |
| Per-row evidence links | Web + Android | The parity file has no owner/evidence column; critical cases 1-28 are not individually linked to artifacts. | `M4-06.1` |
| Desktop packaging (installer/AppImage) | Rust | Not shipping; mark `post-preview / parallel`. | `M4-06.1` |
| Auto-update | Rust | Not shipping; mark `post-preview / parallel`. | `M4-06.1` |
| Backup/sync | All | `Future`; not a phase 1 row. | `M4-06.1` (confirm `Future`) |

Rows with existing M1/M2/M3 evidence (onboarding, `/key`, catalog, streaming,
routing, OpenAI-compatible, Anthropic, Gemini, Ollama, model switching,
persistence, search, Markdown/code/math, attachments, export/import, native
share target) still need their evidence linked per row by `M4-06.1`. Android
math typesetting remains the documented raw-source fallback, not a new gap.

## Entry-criteria status

See `M4-baseline.md` for the checklist and evidence. The M3 exit gate was met
on 2026-11-10 (`M3-07.3`, `M3-07.4`, and `M3-07.5` are `Complete`; M3 is
`Complete`), so M4 implementation tasks may start. The per-row parity evidence
remains with `M4-06.1`.
