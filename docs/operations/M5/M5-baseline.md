# M5 baseline preflight

- Milestone: M5
- Recorded by: `M5-00.1`
- Date: 2026-11-11
- Baseline commit: `135dc19` (M5 plan and task breakdown)
- Purpose: record the pre-implementation contract, web, Android, and Rust
  baseline so later M5 tasks verify against known results instead of guesses.

This task changes no product behavior. It adds the frozen decisions, the
cross-client known-answer vectors, and this baseline record.

## Environment

| Item | Value |
| --- | --- |
| Host OS | Ubuntu 26.04.1 LTS, Linux 7.0.0-42-generic, x86_64 |
| Node | v24.16.0 |
| pnpm | 11.7.0 |
| JDK | OpenJDK 21.0.2 LTS |
| Rust | cargo 1.98.1 |
| Android SDK | Present; Gradle wrapper used by `make android-*` |
| Network | Not required for the recorded gates |

## Gate results

| Command | Result |
| --- | --- |
| `make contracts-check` | Pass: `Contract validation passed.` |
| `make web-lint` | Pass: `eslint .` with no findings. |
| `make web-typecheck` | Pass: `tsc -b` with no errors. |
| `make web-test` | Pass: 101 files, 882 tests, 0 failures. |
| `make android-lint` | Pass: `BUILD SUCCESSFUL` in 1m 8s. |
| `make android-test` | Pass: `BUILD SUCCESSFUL` in 1m 7s; 1,110 tests, 0 failures, 0 errors, 0 skipped. |
| `make rust-check` | Pass: `cargo check --workspace --all-targets` finished in 23.16s. |
| `make rust-test` | Pass: 1,081 tests, 0 failures, 0 ignored. |

### Android test count detail

`make android-test` runs `./gradlew testDebugUnitTest`. The result tree contains
117 `testDebugUnitTest` result files and 22 JVM `test` result files for the
`core/common`, `core/model`, and `core/network` modules. The combined count is
1,110 tests. 46 of those tests were up-to-date from 2026-11-08; the remaining
1,064 ran on 2026-11-11. All results report 0 failures, 0 errors, and 0 skipped.

### Rust test count detail

The 1,081 tests are the sum of the workspace unit, integration, and doc tests:
`chatui_conformance` 15+14+5+12+16+15+18+1, `chatui_contracts` 28+1,
`chatui_desktop` 113, `chatui_domain` 36+1, `chatui_providers` 124+22+13+44+19+15+31,
`chatui_secrets` 66+1, `chatui_storage` 71, and `chatui_ui` 399. All report
0 failures and 0 ignored.

## Contracts baseline

`make contracts-check` passes with the new
`contracts/fixtures/backup/vectors.json` in the tree. The validator currently
checks that every fixture is valid UTF-8 JSON and scans for secret patterns; it
does not yet validate the backup vector structure against a schema.

- Owner for schema and validator coverage: `M5-01.1` (contracts envelope and
  manifest).
- The vectors are frozen by `M5-decisions.md` `DEC-M5-16`. Do not regenerate
  them casually.

### Vector verification

The preflight independently verified the known-answer vector before freezing it:

- Parsed the envelope, checked the `CHATUI1\n` magic, the big-endian header
  length, the canonical header bytes, the 12-byte IV, the ciphertext, and the
  16-byte tag.
- Rebuilt the length-prefixed AAD and matched `aadBase64`.
- Derived the key with PBKDF2-HMAC-SHA256 (600,000 iterations), decrypted with
  AES-256-GCM, gunzipped the plaintext, and matched `uncompressedBase64`.
- Matched `ciphertextBase64`, `tagBase64`, `envelopeBase64`, and
  `envelopeSha256` (`7e80bf27515f0f5aeda52115641603e1f3e0ae498dd2dbb3faff2e574eabc02a`).
- Ran the failure vectors: wrong passphrase, tampered IV, tampered ciphertext,
  tampered tag, tampered AAD object key, tampered header iterations, and
  tampered header object key all fail authentication; the tampered header
  version is rejected by version validation.

The verification script was an ad hoc preflight check and is not committed.
`M5-01.1` and the per-client codec tasks add permanent tests.

## Web baseline

| Gate | Result |
| --- | --- |
| Lint | Pass, no findings. |
| Typecheck | Pass, no errors. |
| Unit/component tests | Pass, 101 files, 882 tests, 0 failures. |

No web build, Playwright, or Lighthouse run is part of the `M5-00.1`
verification. Those remain M4 release-gate evidence and are not re-measured
here.

## Android baseline

| Gate | Result |
| --- | --- |
| Lint | Pass, `BUILD SUCCESSFUL`. |
| Unit tests | Pass, 1,110 tests, 0 failures, 0 errors, 0 skipped. |

Connected-device tests, screenshot goldens, and release assembly are not part
of the `M5-00.1` verification. They remain M4 release-gate evidence.

## Rust baseline

| Gate | Result |
| --- | --- |
| `cargo check --workspace --all-targets` | Pass. |
| `cargo test --workspace` | Pass, 1,081 tests, 0 failures, 0 ignored. |

Rust is now required for M5 (`DEC-M5-03`). This baseline is the first M5 Rust
gate; later Rust tasks must keep it green and add the backup vector tests.

## Entry-gate checklist

| Criterion | Status | Evidence / owner |
| --- | --- | --- |
| M4 exit gate met and phase 1 released | Pass | `docs/operations/M4/M4-verification.md` (2026-11-10). |
| Phase 2 scope explicitly confirmed | Pass | `M5-decisions.md` `DEC-M5-01` (2026-11-11). |
| Backup format version and manifest contract frozen | Pass | `M5-decisions.md` `DEC-M5-02`, `DEC-M5-05`, `DEC-M5-06`, `DEC-M5-07`; `contracts/fixtures/backup/vectors.json`. |
| Each shipping client can enumerate conversations, messages, and attachments without UI involvement | Pass | Web `web/src/storage/ExportReader.ts`; Android `ConversationRepository`, `MessageRepository`, `AttachmentRepository`; Rust `chatui-storage` repositories. |
| Rust release status re-confirmed | Pass | `M5-decisions.md` `DEC-M5-03`: shipping and required. |
| `make contracts-check` baseline | Pass | This file, "Gate results". |
| Web baseline | Pass | This file, "Web baseline". |
| Android baseline | Pass | This file, "Android baseline". |
| Rust baseline | Pass | This file, "Rust baseline". |
| No secrets, prompts, or conversation content in vectors | Pass | Synthetic values only; reviewed in `M5-00.1`. |

No entry-gate blocker remains. `M5-01.1` is the next task in dependency order.

## Handoff notes

- `M5-01.1` owns the backup envelope and manifest schemas plus validator
  coverage. It must validate `contracts/fixtures/backup/vectors.json` and add
  broken-vector self-tests.
- The vector passphrase is a public test value. Never use it for a real backup.
- The gzip reference bytes are not a cross-client requirement; the round-trip
  result is.
- Rust is required for M5. Do not mark Rust tasks `Cancelled` or optional
  without a new dated decision that supersedes `DEC-M5-03`.

## Redaction

No API keys, real passphrases, prompts, conversation content, personal data, or
signing material are recorded here. Test counts and command results only.
