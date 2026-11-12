# M5-07.4 gate results

Date: 2026-11-12
Host: Linux; Android AVD `m3_api34_phone` (API 34, emulator-5554); local MinIO.

## Full gate

| Command | Result |
| --- | --- |
| `make contracts-check` | Pass - `Contract validation passed.` |
| `make web-lint` | Pass |
| `make web-typecheck` | Pass |
| `make web-test` | Pass - 115 files, 1056 tests |
| `make web-build` | Pass |
| `cd web && pnpm exec playwright test` | Pass - 30 tests |
| `make android-lint` | Pass - `BUILD SUCCESSFUL` |
| `make android-test` | Pass - `BUILD SUCCESSFUL` |
| `make android-assemble` | Pass - `BUILD SUCCESSFUL` |
| `cd android && ./gradlew :app:connectedDebugAndroidTest` | Pass - 10 tests, 0 failures, 2 skipped |
| `make rust-check` | Pass |
| `make rust-lint` | Pass - Clippy with warnings denied |
| `make rust-test` | Pass - `chatui_backup`: 98 tests, 0 failures |
| `make rust-build` | Pass - release profile |

## Notes

- The pre-existing Rust conformance build break from `M5-07.4` was fixed by
  adding the `storage_key` and `revision` fields to the `Conversation`
  initializer in
  `rust/tests/conformance/src/bin/process_death_child.rs`. `make rust-check`,
  `make rust-lint`, and `make rust-test` now pass, including
  `process_death_preserves_partial_content_and_marks_cancelled`.
- `web/src/features/backup/envelope.test.ts` received a 30-second per-test
  timeout on the shared tampered-vector loop. The loop runs many PBKDF2
  derivations; under the full parallel suite it exceeded the default 5-second
  timeout on this host while passing in isolation. No assertion or product
  behavior changed.
- The Android connected run used a fresh emulator boot. An earlier attempt
  produced emulator QEMU hangs; the passing run is recorded above.

## Backup-specific verification

| Check | Result |
| --- | --- |
| Cross-client restore web -> Android | Pass - `evidence/cross-client-restore.log` |
| Cross-client restore Android -> web | Pass - `evidence/cross-client-restore.log` |
| Conflict and safe-failure suites | Pass - `evidence/conflict-safe-failure-tests.txt` |
| Bucket object names and sizes | Pass - `evidence/bucket-listing.txt` |
| Ciphertext only, no plaintext/secrets | Pass - `evidence/storage-inspection.txt` |
| Manifest HEAD has no metadata/provider keys | Pass - `evidence/manifest-head-object.json` |
| Android WorkManager constraints | Pass - `evidence/scheduling.txt` |
