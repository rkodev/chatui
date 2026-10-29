# T041 - Parity audit and phase 1 release gate

- Milestone: M4
- Depends on: T015, T025, T040
- Suggested subagent: general
- Parallel-safe: no, run after platform work is complete
- Read: `docs/IMPLEMENTATION.md` sections 5.4, 14, 15.2, 17 (M4), 18, 19, 21.7

## Goal

Perform the final parity audit and phase 1 release gate. This task is mostly verification and documentation, not feature development. It must surface gaps rather than silently mark incomplete work as done.

## Owns

- `contracts/parity.md` final audit
- `docs/RELEASE.md` or equivalent release checklist
- Release notes draft
- A verification report under `docs/release/` if useful

Do not change client behavior to make a checklist pass unless the fix is a small, clearly scoped defect with tests. Otherwise create a follow-up task.

## Deliverables

1. Parity audit:
   - Walk every row in `contracts/parity.md`.
   - Verify with a test, manual check, or linked artifact.
   - Mark Web/Android complete; mark Rust optional/complete/blocked honestly.
   - Record approved exceptions with an owner and date.
2. Release gate verification:
   - Contracts validate.
   - Web lint/typecheck/unit/E2E/build pass.
   - Android lint/unit/instrumented/assemble pass.
   - Rust fmt/clippy/tests/build pass if Rust is shipping.
   - OpenRouter conformance across `/key`, `/models`, streaming, reasoning, usage/cost, routing, errors, and CORS behavior on web.
   - Critical test cases 1-28 from section 15.2 have evidence.
3. Security and privacy verification:
   - No keys in logs, URLs, Cache API, exports, or backup metadata.
   - CSP/security headers present on web.
   - Android/Rust secret storage and backup exclusion verified.
   - No analytics or hidden network destinations.
4. Accessibility and performance verification:
   - axe/Lighthouse web results.
   - Android TalkBack and 48 dp targets.
   - Rust desktop screen-reader and keyboard results.
   - Bundle, APK, installer, startup, and scroll budgets.
5. Deployment verification:
   - Static build and container run with SPA fallback and correct headers.
   - `config.json` is public-only.
   - Android artifacts and signing policy documented.
   - Rust artifacts and update manifest signing documented if shipping.
6. Release notes with upgrade/migration notes and known issues.
7. Definition-of-done review against section 21.7.

## Steps

1. Re-run all global verification commands and capture results.
2. Audit parity row by row.
3. Audit security, accessibility, performance, and deployment.
4. Write release notes and blockers.
5. Escalate any unresolved blocker instead of declaring success.

## Acceptance criteria

- `contracts/parity.md` is complete and reviewed.
- Every section 15.2 critical case has evidence.
- OpenRouter works end-to-end on web and Android, and on Rust if shipping.
- The definition of done in section 21.7 is met or explicitly exempted.
- Release notes and migration notes exist.
- Any remaining blocker is listed with an owner and is not hidden.

## Verification

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
cd web && pnpm exec playwright test
cd android && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
cd rust && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo nextest run --workspace
```

## Out of scope

- Large feature fixes; create follow-up tasks.
- Backup/sync (T042) and managed hosting (T043).
