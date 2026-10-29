# T042 - Encrypted backup and sync (future)

- Milestone: M5
- Depends on: T001, T010, T020, T030, and a stable phase 1
- Suggested subagent: general
- Parallel-safe: no, phase 2 only
- Read: `docs/IMPLEMENTATION.md` sections 6.2 (BackupConfig), 12, 13, 21.5

## Goal

Design and implement encrypted, user-owned backup and sync for phase 2. Do not start this task until phase 1 is stable and the release gate has passed. The storage provider and roksky must never be able to read plaintext content.

## Owns

- `contracts/schema/backup-manifest.schema.json` extensions if needed (coordinate with T001)
- Backup/sync modules under `web/src/features/backup/**`, `web/src/storage/backup/**`
- Android `feature/backup/**`, `core/backup/**`
- Rust `crates/chatui-backup/**` if Rust ships backup
- Backup tests and fixtures
- `contracts/parity.md` backup rows

## Deliverables

1. Encryption envelope:
   - Magic `CHATUI1\n`, header JSON with version, algorithm, KDF, iterations, salt, object type, and object key.
   - AES-256-GCM with a fresh 12-byte IV per write.
   - PBKDF2-HMAC-SHA256 with 600,000 iterations and a 16-byte salt; plan an Argon2id v2 upgrade with a version field.
   - Object type and storage key as associated data.
   - Gzip before encryption for conversations, not for already-compressed attachments.
2. Manifest, object layout, and random storage keys as in section 13.3.
3. S3-compatible backend first (SigV4, conditional writes/ETag, CORS documentation), then Azure Blob, GCS, and WebDAV adapters.
4. Manual backup/restore UI:
   - Backend, bucket, prefix, credentials, passphrase, test, enable.
   - Last backup time, pending changes, errors, storage usage.
   - "Back up now", "Restore from backup", "Delete remote backup" with typed confirmation.
   - Restore merges by default; never wipes local data unless explicitly chosen.
5. Sync protocol and conflict rules:
   - Device ID, random backup salt, manifest revisions.
   - Immutable messages merged by UUID and ordered by sequence/createdAt/id.
   - Tombstones and last-write-wins metadata with deviceId tiebreaker.
   - Deterministic renumbering; never drop local messages.
6. Android WorkManager integration with network/battery constraints; web and Rust equivalents where applicable.
7. Tests: envelope round trip, IV uniqueness, wrong passphrase, manifest merge, concurrent additions, deletion tombstones, interrupted upload, and cross-client restore.

## Steps

1. Implement and test the envelope and manifest.
2. Add the S3-compatible backend and connectivity test.
3. Add UI and sync state.
4. Add conflict tests and cross-client restore.
5. Add remaining backends.

## Acceptance criteria

- Cross-device restore works with encrypted data.
- The storage provider cannot read content.
- Conflict tests pass for concurrent edits and deletions.
- No provider API keys appear in backup metadata.
- Failed or interrupted sync never destroys local data.
- Backup rows in parity are updated.

## Verification

```bash
make contracts-check
# plus the platform-specific backup test suites added by this task
```

## Out of scope

- Phase 1 features or release work.
- Managed storage sold by roksky (T043).
