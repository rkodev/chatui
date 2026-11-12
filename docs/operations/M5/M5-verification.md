# M5 - Backup and sync verification

- Milestone: M5
- Date: 2026-11-12
- Outcome: Pass
- Evidence: [docs/operations/M5/evidence/](evidence/README.md)
- Parity: [contracts/parity.md](../../../contracts/parity.md)
- Decisions: [M5-decisions.md](M5-decisions.md)
- Plan: [M5 implementation plan](../M5-backup-and-sync-implementation-plan.md)

This record closes the M5 exit gate. All required web, Android, and Rust tasks
are complete. Data in every test is synthetic; no real passphrase, provider key,
prompt, or conversation content is recorded.

## Exit-gate checklist

| Gate | Result | Evidence |
| --- | --- | --- |
| Cross-device restore works with encrypted data on web and Android | Pass | [cross-client-restore.log](evidence/cross-client-restore.log) |
| Provider cannot read content, attachments, or meaningful metadata | Pass | [storage-inspection.txt](evidence/storage-inspection.txt) |
| Conflict tests pass: concurrent edits, additions, deletions | Pass | [conflict-safe-failure-tests.txt](evidence/conflict-safe-failure-tests.txt) |
| Failed or interrupted sync never destroys local data | Pass | [conflict-safe-failure-tests.txt](evidence/conflict-safe-failure-tests.txt) |
| Wrong passphrase and tampered ciphertext fail safely | Pass | [conflict-safe-failure-tests.txt](evidence/conflict-safe-failure-tests.txt) |
| No provider API keys in backup metadata | Pass | [manifest-head-object.json](evidence/manifest-head-object.json) |
| Android scheduling respects WorkManager constraints; web and Rust documented | Pass | [scheduling.txt](evidence/scheduling.txt) |
| Backup rows in parity updated and reviewed | Pass | [contracts/parity.md](../../../contracts/parity.md) |
| Backup format is versioned and round-trip tested in every shipping client | Pass | shared vectors in `contracts/fixtures/backup/`; [gate-results.md](evidence/gate-results.md) |
| `make contracts-check` passes | Pass | [gate-results.md](evidence/gate-results.md) |
| Evidence contains only synthetic data and no secrets | Pass | [evidence/README.md](evidence/README.md) |

## Commands and results

Run on 2026-11-12.

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
cd web && pnpm exec playwright test
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:connectedDebugAndroidTest
make rust-check && make rust-lint && make rust-test && make rust-build
```

- `make contracts-check` - pass (`Contract validation passed.`).
- Web lint/typecheck/build - pass; `make web-test` - 115 files, 1056 tests,
  0 failures; Playwright - 30 passed.
- Android lint/test/assemble - pass; connected instrumentation tests - 10
  tests, 0 failures, 2 skipped, on `m3_api34_phone` (API 34).
- Rust check/lint/test/build - pass; `chatui_backup` - 98 tests, 0 failures.

Failures found and resolved while closing the gate:

1. Pre-existing Rust conformance build break:
   `rust/tests/conformance/src/bin/process_death_child.rs` did not initialize
   the `storage_key` and `revision` fields added by `M5-02.3`. Fixed by adding
   `storage_key: String::new()` and `revision: 0`; the repository fills both on
   create. All Rust gates now pass, including the process-death conformance
   test.
2. Web test flake under full-suite load: the shared tampered-envelope loop ran
   many PBKDF2 derivations and exceeded Vitest's default 5-second timeout while
   passing in isolation. A 30-second per-test timeout was added. No assertion
   or product behavior changed.

Details: [gate-results.md](evidence/gate-results.md).

## Cross-client restore

Procedure:

1. Start local MinIO and create `chatui-m5-evidence`.
2. Web: seed one synthetic conversation (two messages, one image attachment)
   and run `SyncEngine.sync()`.
3. Android: seed a second synthetic conversation and attachment and run
   `SyncEngine.sync()` into the same bucket.
4. Android: run `SyncEngine.restore()` into an empty store and verify the
   web-written conversation, message order/text, and attachment object.
5. Web: run `SyncEngine.restore()` into an empty profile and verify the
   Android-written conversation, message order/text, and attachment object.

Result: pass in both directions. Conversations restored with ordered messages
and identical text; each client decrypted the other client's attachment object
to the original 8-byte synthetic payload; the manifest stayed readable. The
shipped envelope, manifest, conversation-payload codecs, sync engines, and S3
adapters were used, with no format translation and no plaintext staging area.

Log: [cross-client-restore.log](evidence/cross-client-restore.log).

## Conflict results

- Concurrent additions on both clients are both kept and sequences renumbered
  deterministically.
- Concurrent metadata edits resolve by last-write-wins on `updatedAt`, with
  `deviceId` as the tiebreak.
- Deletion tombstones win over an older update; a newer update survives an
  older message tombstone.
- Local messages without a tombstone are never dropped.
- Manifest write conflicts retry, re-read, and converge.

Pass on web, Android, and Rust. Evidence:
[conflict-safe-failure-tests.txt](evidence/conflict-safe-failure-tests.txt).

## Safe-failure results

- Wrong passphrase fails before any local mutation.
- Tampered ciphertext, IV, tag, header, and AAD are rejected with the documented
  failure codes.
- An interrupted upload leaves local data unchanged; the next sync resumes.
- Restore merges by default; replace requires explicit opt-in.

Pass on web, Android, and Rust. Evidence:
[conflict-safe-failure-tests.txt](evidence/conflict-safe-failure-tests.txt).

## Storage inspection

Bucket contents after both clients wrote:

```text
attachments/<sha256>.enc        288 bytes each
conversations/<storageKey>.enc  731 and 720 bytes
manifest.enc                    1341 bytes
```

Every object starts with the `CHATUI1\n` envelope magic followed by an
authenticated header and ciphertext. A recursive scan for synthetic titles,
message text, the test passphrase, provider IDs, and MinIO credentials found no
plaintext. The manifest HEAD response reports `ContentType:
application/octet-stream` and empty `Metadata`, so no provider keys or
meaningful metadata are stored as object metadata. Object names reveal only
opaque random storage keys or attachment SHA-256 values.

Evidence: [bucket-listing.txt](evidence/bucket-listing.txt),
[storage-inspection.txt](evidence/storage-inspection.txt), and
[manifest-head-object.json](evidence/manifest-head-object.json).

## Scheduling

- Android: `BackupScheduler` enqueues unique periodic WorkManager work with
  `NetworkType.CONNECTED` and `setRequiresBatteryNotLow(true)`, exponential
  backoff, and a 6-hour best-effort cadence. Unit tests assert the constraints,
  uniqueness, cancel behavior, and worker result mapping; connected tests pass
  on API 34.
- Web: foreground interval plus `visibilitychange` due check and optional
  Periodic Background Sync, with feature and permission detection. Documented
  in [M5-06.2-web-scheduling.md](M5-06.2-web-scheduling.md) and
  [web-scheduling.md](web-scheduling.md).
- Rust desktop: `tokio` interval-based scheduler while the app runs, with
  explicit enable/disable. Documented in
  [M5-06.3-rust-scheduling.md](M5-06.3-rust-scheduling.md) and
  [rust-scheduling.md](rust-scheduling.md).

Evidence: [scheduling.txt](evidence/scheduling.txt).

## Malicious-provider risk statement

Encryption prevents a storage provider from reading conversation content,
attachments, or metadata beyond opaque object names and sizes. A malicious
provider can still delete objects, withhold updates, or serve an older
consistent manifest (rollback). The clients validate revisions, tombstones,
envelope authentication, and conditional-write preconditions, but no client can
detect a provider that consistently presents an older state. **No Byzantine
fault tolerance is claimed.** Losing the passphrase is unrecoverable by design.

## Acceptance criteria

- [x] Cross-device restore works with encrypted data on web and Android.
- [x] The provider cannot read content, attachments, or meaningful metadata.
- [x] Conflict tests pass for concurrent edits, additions, and deletions.
- [x] A failed or interrupted sync never destroys local data.
- [x] Wrong passphrase and tampered ciphertext fail safely.
- [x] No provider API keys appear in backup metadata.
- [x] Android scheduling respects WorkManager constraints; web and Rust are documented.
- [x] Backup rows in parity are updated and reviewed.
- [x] The backup format is versioned and round-trip tested in every shipping client.
- [x] `make contracts-check` passes.
- [x] Evidence contains only synthetic data and no secrets.
