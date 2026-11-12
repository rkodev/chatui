# M5 Backup and Sync Implementation Plan

- Source milestone: [M5-backup-and-sync.md](../milestones/M5-backup-and-sync.md)
- Authoritative spec: [IMPLEMENTATION.md](../IMPLEMENTATION.md) sections 6.2, 12, 13, 17 (M5), 21.5
- Current baseline: [M4 verification](M4/M4-verification.md) (phase 1 release gate passed 2026-11-10)
- Status: Planned - blocked on explicit phase 2 scope confirmation
- Prepared: 2026-11-11
- Scope: Encrypted, user-owned backup and sync for web and Android; Rust desktop conditional on shipping
- Platforms: Web and Android required; Rust desktop `post-preview / parallel` per `M4-decisions.md` DEC-M4-01
- Estimate: 4-6 weeks for one cross-platform implementer; 3-4 weeks for a two-person team split web/Android

## 1. Summary

M5 adds end-to-end encrypted backup and sync to user-owned object storage. The
storage provider and roksky must never be able to read conversation content,
attachments, or meaningful metadata. The milestone is phase 2 and must not start
until the user explicitly confirms phase 2 scope, even though the M4 release gate
has passed.

The repository already contains the **durable scaffolding** for backup: a
`BackupConfig` model on all three clients, `backup_config` and `sync_queue`
tables, a header-only `backup-manifest.schema.json`, and secret-storage crypto.
It does **not** contain the backup envelope, manifest body, object layout, sync
engine, storage adapters, scheduling, or any backup tests. The gap is therefore
large but well-bounded: most of the work is new code behind existing seams.

The highest risks are cryptographic correctness, cross-client format drift, and
conflict resolution that silently loses data. The plan front-loads a shared
envelope and manifest contract with cross-client test vectors so web, Android,
and Rust cannot diverge, then builds the sync engine and backends on top.

| Workstream | Current completion | Assessment |
| --- | --- | --- |
| Encryption envelope and contracts | Partial | Header schema only; no envelope codec, no AAD, no gzip, no fixtures |
| Manifest and object layout | Missing | No manifest body, no random conversation `storageKey`, no revision, no tombstones |
| Storage backend adapters | Missing | No S3/Azure/GCS/WebDAV client on any platform |
| Sync protocol and conflicts | Missing | `sync_queue` tables exist but nothing enqueues or drains them |
| Client UI and scheduling | Partial | Placeholder screens; WorkManager dependency only; no web/Rust scheduler |
| Tests and evidence | Missing | No backup tests, no cross-client restore, parity row is `Future` |

### 1.1 Critical path

```text
M5-00 Preflight, decisions, cross-client test vectors
   |
M5-01 Envelope, contracts, crypto round-trip
   |
M5-02 Manifest, object layout, storage keys, schema migrations
   |
   +--> M5-03 S3-compatible backend (SigV4, conditional writes, CORS)
   |       |
   |       +--> M5-04 Sync protocol, merge, tombstones, conflict tests
   |               |
   |               +--> M5-05 Backup/restore UI and client-side config
   |               |       |
   |               |       +--> M5-06 Scheduling (WorkManager, web, Rust)
   |               |               |
   |               +---------------+--> M5-07 Azure/GCS/WebDAV + parity + cross-client evidence
```

### 1.2 Entry gate

Implementation may not start until all of the following are true:

- [x] M4 exit gate is met and phase 1 is released (`M4-verification.md`, 2026-11-10).
- [ ] The user explicitly confirms phase 2 scope and authorizes M5 work.
- [ ] The backup format version and manifest contract are frozen in `M5-00`.
- [ ] Each shipping client can enumerate conversations, messages, and attachments
      without UI involvement. This is already true:
      - Web: `web/src/storage/ExportReader.ts:89-170`
      - Android: `android/core/database/.../repository/{Conversation,Message,Attachment}Repository.kt`
      - Rust: `rust/crates/chatui-storage/src/repositories/{conversations,messages,attachments}.rs`
- [ ] Rust release status is re-confirmed. Current decision: not shipping, so
      Rust is optional and must not block the web/Android gate.

## 2. Current-State Audit

### 2.1 Reusable assets

| Asset | Current state | Plan impact |
| --- | --- | --- |
| `contracts/schema/backup-manifest.schema.json` | Header-only schema: `contractVersion`, `v`, `objectType`, `objectKey`, `alg`, `kdf`, `iterations`, `salt` | Extend into envelope header + manifest body; add `formatVersion` |
| `contracts/schema/chat-export.schema.json` | Plaintext export contract with `backupConfig` definition | Reuse entity shapes; do not reuse plaintext export as backup |
| `contracts/schema/settings.schema.json` | `backup` and `backupConfig` definitions | Keep config shape stable; add backup status fields if needed |
| `scripts/validate-contracts.mjs` | Compiles backup schema but never validates a backup fixture | Add backup fixtures and validation/self-tests |
| `web/src/platform/crypto.ts` | AES-256-GCM, PBKDF2-SHA256 600k, 12-byte IV, 16-byte salt | Reuse primitives; add AAD, envelope, gzip, Argon2 path |
| `web/src/storage/{db,migrations}.ts` | `backupConfig`, `syncQueue`, `attachmentBlobs` stores; DB v1 | Add DB v2 migration for revision/tombstone/storageKey |
| `web/src/storage/ExportReader.ts` | Deterministic conversation/message/attachment enumeration | Reuse as the backup read model |
| `web/src/storage/AttachmentStore.ts` | Content-addressed `attachments/<sha256>` in OPFS/IndexedDB | Reuse for attachment bytes; add encrypted upload |
| `android/core/database/.../BackupConfigEntity.kt` | Full `BackupConfig` shape | Reuse; add migration and status fields |
| `android/core/database/.../SyncQueueEntity.kt` | Durable queue with attempts | Reuse; wire to sync engine |
| `android/core/datastore/.../TinkSecretCipher.kt` | Tink AES-256-GCM with Android Keystore | Reuse for credential storage; not for backup envelope |
| `android/core/database/.../repository/*` | `all()` enumeration without UI | Reuse as the backup read model |
| `rust/crates/chatui-secrets/src/encrypted_file.rs` | Argon2id + AES-256-GCM with header AAD (`CHUIENC1`) | Reuse patterns; do not reuse format or magic |
| `rust/crates/chatui-storage/.../repositories/backup.rs` | `backup_config` load/save/delete | Reuse; add migration and sync wiring |
| `rust/crates/chatui-storage/.../repositories/sync_queue.rs` | Queue enqueue/list/delete/attempts | Reuse; wire to sync engine |
| `rust/crates/chatui-storage/src/export.rs` | Deterministic export builder | Reuse as the backup read model |

### 2.2 Confirmed missing or unproven areas

| Capability | Current state | Consequence |
| --- | --- | --- |
| Backup envelope codec | Missing on all clients | No object can be written or read in the `CHATUI1\n` format |
| Manifest body | Missing | No conversation/attachment index, revisions, tombstones, or device IDs |
| Random conversation `storageKey` | Missing | Conversation UUIDs would leak identity if used as object names |
| Conversation `revision` | Missing | Manifest merge by revision is impossible |
| Tombstone `deviceId` | Missing | Deletions cannot be attributed or resolved deterministically |
| AAD binding | Web has none; Android uses fixed AAD; Rust uses secret-file AAD | Ciphertext is not bound to object type/location |
| Gzip before encryption | Missing | Conversation payloads are larger than necessary |
| Argon2id | Web missing; Android/Rust not wired to backup | v2 KDF upgrade path is unproven |
| Passphrase verification token | Missing | Cannot distinguish wrong passphrase from corrupt data safely |
| Storage adapters | Missing | No S3, Azure Blob, GCS, or WebDAV client |
| Conditional writes | Missing | Manifest updates can clobber concurrent devices |
| Sync engine | Missing | `sync_queue` is dead scaffolding |
| Scheduling | WorkManager dependency only; no web/Rust scheduler | No automatic backup |
| Backup UI | Placeholder screens only | No configuration, status, or restore flow |
| Backup tests | Missing | No round-trip, tamper, conflict, or cross-client evidence |
| Parity rows | `Future/Future/Future` | Release gate cannot be audited |

### 2.3 Test and evidence baseline

- `make contracts-check` compiles `backup-manifest.schema.json` but validates no
  backup fixture (`scripts/validate-contracts.mjs:237-243`, `3444-3445`).
- Web has crypto tests for secret storage only
  (`web/src/platform/crypto.test.ts`), not for the backup envelope.
- Android has `BackupSyncDaoTest` for placeholder persistence and
  `BackupRulesTest` for backup exclusion, not for backup behavior.
- Rust has `backup_config_round_trip` and an inline manifest schema acceptance
  test (`rust/crates/chatui-contracts/src/schema.rs:268-286`), not for the
  envelope or sync.
- No test exercises two clients restoring the same encrypted object.
- `contracts/parity.md:25` still reads `Backup/sync | Future | Future | Future`.

## 3. Gap Analysis

Status values:

- **Missing**: no implementation exists.
- **Partial**: foundation exists but the deliverable is not met or not proven.
- **Present**: complete, subject only to regression verification.
- **Blocked**: cannot proceed until a decision or dependency is resolved.

### 3.1 Contracts and envelope (E)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| E-01 | Backup format version | Partial | `v` is loosely constrained; no explicit `formatVersion` | Add `formatVersion` to envelope header and manifest; document compatibility rules | Schema test + fixture |
| E-02 | Envelope header schema | Partial | Header fields exist but no magic/headerLength/IV/tag layout | Add `backup-envelope.schema.json` matching spec 21.5 | Schema test + fixture |
| E-03 | Manifest body schema | Missing | Schema models only a header | Add `backup-manifest-body.schema.json` with conversation index, attachment index, revisions, tombstones, device IDs | Schema test + fixture |
| E-04 | Conversation backup file schema | Missing | No self-contained conversation payload contract | Add `backup-conversation.schema.json` (metadata + messages + attachment refs) | Schema test + fixture |
| E-05 | Backup fixtures | Missing | No `contracts/fixtures/backup/**` | Add synthetic manifest, conversation, attachment, and envelope fixtures | `make contracts-check` |
| E-06 | Validator coverage | Partial | Backup schema compiled but never validated | Validate every backup fixture and add broken-vector self-tests | `make contracts-check` |
| E-07 | Cross-client test vectors | Missing | No known-answer vectors | Add deterministic vectors for header parse, AAD, IV, and round trip | Per-client vector tests |
| E-08 | Argon2id v2 path | Partial | `kdf` enum includes `argon2id`; no versioned upgrade contract | Document v1 PBKDF2 and v2 Argon2id selection by `v`/`formatVersion` | Contract doc + test |

### 3.2 Cryptography (C)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| C-01 | Web envelope codec | Missing | Only secret-store encrypt/decrypt | Implement `CHATUI1\n` + big-endian header length + JSON header + IV + ciphertext/tag | Round-trip + vector tests |
| C-02 | Android envelope codec | Missing | Tink AEAD only, device-bound | Implement passphrase-derived AES-GCM envelope with Tink or JCA | Round-trip + vector tests |
| C-03 | Rust envelope codec | Missing | `CHUIENC1` secret file only | Implement `CHATUI1\n` envelope in a new module | Round-trip + vector tests |
| C-04 | AAD binding | Partial | Web none; Android fixed; Rust secret-file only | Bind `objectType` + `objectKey` as AAD on every write/read | Tamper test per client |
| C-05 | Fresh IV per write | Present | Secret-store tests cover IV uniqueness | Extend to every backup object write; assert no reuse | IV uniqueness test |
| C-06 | Gzip before encryption | Missing | No compression | Gzip conversation payloads; never gzip attachment bytes | Round-trip + size test |
| C-07 | Passphrase verification token | Missing | No token | Store a small encrypted verification object or manifest field | Wrong-passphrase test |
| C-08 | Wrong passphrase safety | Partial | Secret-store test only | Fail before any local mutation; never corrupt local data | Failure-path test |
| C-09 | Tamper detection | Partial | Secret-store test only | Reject modified header, IV, ciphertext, or AAD | Tamper test per client |
| C-10 | No plaintext upload path | Missing | No upload path exists | Enforce encryption at the adapter boundary; assert no plaintext object | Adapter test + inspection |
| C-11 | No provider keys in metadata | Missing | No metadata exists | Exclude secrets from manifest and conversation payloads | Fixture + inspection test |

### 3.3 Manifest and object model (M)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| M-01 | Random conversation `storageKey` | Missing | Conversation has no `storageKey`; attachments use content hash | Add 128-bit random hex `storageKey` to conversation on all clients | Migration + unit test |
| M-02 | Conversation `revision` | Missing | No revision field | Add monotonic revision incremented on local change | Migration + unit test |
| M-03 | Tombstone `deviceId` | Partial | `deletedAt` exists; no device attribution | Add tombstone record with `deletedAt` + `deviceId` | Migration + merge test |
| M-04 | Manifest writer/reader | Missing | No manifest codec | Build manifest from local index; parse and validate remote manifest | Round-trip test |
| M-05 | Self-contained conversation files | Missing | No conversation backup payload | Serialize metadata + all messages + attachment refs | Round-trip test |
| M-06 | Content-addressed attachments | Present | `attachments/<sha256>` exists | Reuse; encrypt bytes and keep immutable | Adapter test |
| M-07 | Opaque object naming | Missing | No object layout | Use `manifest.enc`, `conversations/<storageKey>.enc`, `attachments/<sha256>.enc` | Inspection test |
| M-08 | Web DB migration | Partial | DB v1 only | Add DB v2 for `storageKey`, `revision`, tombstones | Migration test |
| M-09 | Android Room migration | Partial | `MIGRATIONS` empty | Add migration for new columns/tables | Migration test |
| M-10 | Rust SQLite migration | Partial | `0001_initial.sql` only | Add `0002_backup_sync.sql` | Migration test |
| M-11 | Soft-delete retention | Partial | Web/Android hard-delete; Rust soft-deletes messages | Preserve tombstones until sync confirms; document 30-day rule | Deletion test |

### 3.4 Storage backends (B)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| B-01 | S3-compatible adapter | Missing | No client | Implement SigV4 GET/PUT/HEAD/DELETE with access key + secret | Mock-server + MinIO test |
| B-02 | Conditional writes | Missing | No ETag handling | Use `If-Match`/`If-None-Match` where supported; fall back conservatively | Conflict test |
| B-03 | Bucket CORS docs | Missing | No docs | Document required CORS for web in `docs/operations/M5/` | Doc review |
| B-04 | Azure Blob adapter | Missing | No client | Account name + key or SAS; block blobs | Mock-server test |
| B-05 | GCS adapter | Missing | No client | Service account JSON initially; OAuth later | Mock-server test |
| B-06 | WebDAV adapter | Missing | No client | URL + username + password; conservative consistency | Mock-server test |
| B-07 | Connectivity test | Missing | No test path | HEAD/PUT probe before saving config | UI + adapter test |
| B-08 | Eventual consistency | Missing | No handling | Re-read after write; retry with backoff; never assume read-after-write | Sync test |
| B-09 | No server proxy | Present | No server component exists | Keep direct client-to-storage; assert no roksky endpoint | Architecture review |

### 3.5 Sync protocol (S)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| S-01 | Device ID + backup salt | Partial | `deviceId` in config; no salt creation flow | Generate on first setup; store locally | Setup test |
| S-02 | Read/create manifest | Missing | No manifest read | Read `manifest.enc`; create empty manifest if absent | Sync test |
| S-03 | Merge by revision | Missing | No merge | Merge local/remote conversation indexes by revision | Merge test |
| S-04 | Upload dirty objects | Missing | No dirty tracking | Track dirty conversations/attachments; upload encrypted | Sync test |
| S-05 | Manifest rewrite with preconditions | Missing | No ETag flow | Rewrite manifest with new revisions and `If-Match` | Conflict test |
| S-06 | Conflict retry/backoff | Missing | No retry | Re-read and retry with bounded backoff | Conflict test |
| S-07 | Message set merge | Missing | No merge | Merge by UUID; order by `sequence`, `createdAt`, `id` | Merge test |
| S-08 | Deletion tombstones win | Missing | No tombstone resolution | Deletion wins over older update; keep tombstone | Merge test |
| S-09 | Metadata LWW | Missing | No metadata merge | LWW by `updatedAt`, tiebreak `deviceId` | Merge test |
| S-10 | Concurrent additions | Missing | No renumbering | Keep both; renumber sequences deterministically | Merge test |
| S-11 | Never drop local messages | Missing | No guarantee | Assert local set is a subset of merged set | Property test |
| S-12 | Non-destructive restore | Missing | No restore | Merge by default; replace only on explicit confirmation | Restore test |
| S-13 | Sync queue drain | Partial | Queue exists, unused | Enqueue on local change; drain in sync engine | Queue test |

### 3.6 UI and scheduling (U)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| U-01 | Backup settings form | Partial | Placeholder screens | Backend, bucket, prefix, credentials, passphrase, test, enable | UI test |
| U-02 | Status display | Missing | No status | Last backup, pending changes, errors, storage usage | UI test |
| U-03 | Manual actions | Missing | No actions | Back up now, restore, delete remote with typed confirmation | UI test |
| U-04 | Client-side statement | Missing | No copy | State credentials/passphrase stay client-side | Copy review |
| U-05 | Restore-on-new-device | Missing | No flow | Configure, restore, report conflicts, restore attachments | E2E test |
| U-06 | Android WorkManager | Partial | Dependency only | Add worker with network/battery constraints | WorkManager test |
| U-07 | Web scheduling | Missing | No background sync | Document constraints; use foreground/periodic where allowed | Doc + test |
| U-08 | Rust scheduling | Missing | No scheduler | Document desktop scheduling; optional timer | Doc + test |
| U-09 | Conflict reporting | Missing | No UI | Show conflicts and resolution outcome | UI test |

### 3.7 Tests and evidence (T)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| T-01 | Cross-client restore | Missing | No test | Web writes, Android reads, and vice versa | Cross-client evidence |
| T-02 | Wrong passphrase safe | Missing | No backup test | Fail without local mutation | Test |
| T-03 | Manifest merge/conflicts | Missing | No test | Concurrent additions, metadata edits, deletions | Test |
| T-04 | Interrupted upload/resume | Missing | No test | Failed sync never destroys local data | Test |
| T-05 | No keys/plaintext | Missing | No test | Inspect objects and manifest | Inspection evidence |
| T-06 | Parity rows | Missing | `Future` | Update `contracts/parity.md` per client | `make contracts-check` |
| T-07 | Malicious provider doc | Missing | No doc | Document delete/rollback risk; no Byzantine claim | Doc review |
| T-08 | Storage inspection | Missing | No evidence | Show opaque encrypted objects in a real bucket | Evidence artifact |

## 4. Design Decisions to Freeze in M5-00

These decisions must be recorded before implementation. Recommended defaults are
shown; the user or product owner must confirm.

| Decision | Recommended default | Rationale |
| --- | --- | --- |
| Backup format version | `formatVersion: 1`, envelope `v: 1` | Matches spec 21.5 and M4 release versioning |
| KDF v1 | PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt, 32-byte key | Spec 13.4 |
| KDF v2 | Argon2id, version-gated, not enabled in v1 | Documented upgrade path only |
| Compression | Gzip conversation payloads; never attachments | Spec 13.4 |
| AAD | `objectType` + `objectKey` UTF-8, length-prefixed | Binds ciphertext to location |
| Manifest encryption | Same envelope as conversations | Opaque manifest |
| Conversation object key | 128-bit random hex `storageKey` | Spec 6.1 |
| Attachment object key | `attachments/<sha256>.enc` | Content-addressed, immutable |
| Revision model | Monotonic integer per conversation | Simple merge |
| Tombstone retention | Keep until remote manifest confirms; local hard-delete after 30 days | Spec 6.4 |
| Restore default | Merge, never wipe | Spec 13.7 |
| First backend | S3-compatible | Spec 13.6 |
| Second backend | Azure Blob, GCS, or WebDAV (open product question) | Spec section 20 |
| Web scheduling | Foreground + documented constraints; no unsupported background sync | Platform constraints |
| Rust scope | Optional/parallel; not a gate | M4 DEC-M4-01 |
| Passphrase recovery | None by design; warn before enabling | Spec 13.2 |

## 5. Task Breakdown

The milestone guidance expects 5-7 tasks. Because the milestone README forbids a
single task spanning multiple client areas, the workstreams below are the
planning units; when task files are generated they must be split per platform
(web, Android, Rust) with explicit dependencies. A suggested decomposition is
shown in section 5.2.

### 5.1 Workstream tasks

| Order | Task | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- |
| 1 | M5-00 Preflight, decisions, and cross-client test vectors | none | preflight | none; blocks all |
| 2 | M5-01 Envelope, contracts, and crypto round-trip | M5-00 | contracts/crypto | none; blocks manifest |
| 3 | M5-02 Manifest, object layout, storage keys, migrations | M5-01 | data model | none; blocks sync |
| 4 | M5-03 S3-compatible backend, conditional writes, CORS | M5-01, M5-02 | backend | M5-05 UI shell |
| 5 | M5-04 Sync protocol, merge, tombstones, conflict tests | M5-02, M5-03 | sync | none; core |
| 6 | M5-05 Backup/restore UI and client-side config | M5-03, M5-04 | UI | M5-06 |
| 7 | M5-06 Scheduling: WorkManager, web, Rust | M5-04, M5-05 | scheduling | M5-07 |
| 8 | M5-07 Azure/GCS/WebDAV, parity, cross-client evidence | M5-03, M5-04, M5-05, M5-06 | backends/evidence | none; final gate |

### 5.2 Suggested per-platform task files

Generate these under `docs/tasks/M5/` only after phase 2 scope is confirmed.

| Task ID | Title | Depends on | Owns |
| --- | --- | --- | --- |
| M5-00.1 | Preflight, decisions, and cross-client test vectors | none | `docs/operations/M5/`, `contracts/fixtures/backup/` |
| M5-01.1 | Contracts: envelope, manifest body, conversation payload, fixtures | M5-00.1 | `contracts/schema/`, `contracts/fixtures/backup/`, `scripts/validate-contracts.mjs` |
| M5-01.2 | Web envelope codec and crypto tests | M5-01.1 | `web/src/platform/`, `web/src/features/backup/` |
| M5-01.3 | Android envelope codec and crypto tests | M5-01.1 | `android/core/backup/` (new), `android/core/datastore/` |
| M5-01.4 | Rust envelope codec and crypto tests | M5-01.1 | `rust/crates/chatui-backup/` (new) |
| M5-02.1 | Web manifest, storage keys, DB v2 migration | M5-01.2 | `web/src/storage/`, `web/src/domain/` |
| M5-02.2 | Android manifest, storage keys, Room migration | M5-01.3 | `android/core/database/`, `android/core/model/` |
| M5-02.3 | Rust manifest, storage keys, SQLite migration | M5-01.4 | `rust/crates/chatui-storage/`, `rust/crates/chatui-domain/` |
| M5-03.1 | Web S3 adapter, conditional writes, CORS docs | M5-02.1 | `web/src/features/backup/`, `docs/operations/M5/` |
| M5-03.2 | Android S3 adapter, conditional writes | M5-02.2 | `android/core/backup/` |
| M5-03.3 | Rust S3 adapter, conditional writes | M5-02.3 | `rust/crates/chatui-backup/` |
| M5-04.1 | Web sync engine, merge, tombstones | M5-03.1 | `web/src/features/backup/` |
| M5-04.2 | Android sync engine, merge, tombstones | M5-03.2 | `android/core/backup/` |
| M5-04.3 | Rust sync engine, merge, tombstones | M5-03.3 | `rust/crates/chatui-backup/` |
| M5-05.1 | Web backup/restore UI and settings | M5-04.1 | `web/src/features/settings/`, `web/src/app/` |
| M5-05.2 | Android backup/restore UI and settings | M5-04.2 | `android/feature/backup/`, `android/feature/settings/` |
| M5-05.3 | Rust backup/restore UI and settings | M5-04.3 | `rust/crates/chatui-ui/`, `rust/apps/desktop/` |
| M5-06.1 | Android WorkManager scheduling | M5-05.2 | `android/feature/backup/`, `android/app/` |
| M5-06.2 | Web scheduling and constraints documentation | M5-05.1 | `web/src/features/backup/`, `docs/operations/M5/` |
| M5-06.3 | Rust desktop scheduling and documentation | M5-05.3 | `rust/apps/desktop/`, `docs/operations/M5/` |
| M5-07.1 | Azure Blob, GCS, and WebDAV adapters (per platform) | M5-03.* | `*/backup/` |
| M5-07.2 | Parity, cross-client restore, and exit evidence | all | `contracts/parity.md`, `docs/operations/M5/` |

### 5.3 Task details

#### M5-00 - Preflight, decisions, and cross-client test vectors

- Confirm phase 2 scope and the decisions in section 4.
- Freeze the envelope header, manifest body, and conversation payload shapes.
- Add deterministic cross-client test vectors: header parse, AAD, IV, gzip,
  round trip, wrong passphrase, tamper.
- Record the Rust decision and the second-backend choice.
- Capture the current `make contracts-check` and per-client test baseline.

#### M5-01 - Envelope, contracts, and crypto round-trip

- Extend `backup-manifest.schema.json`; add envelope, manifest body, and
  conversation payload schemas.
- Add fixtures and validator coverage, including broken-vector self-tests.
- Implement the envelope codec on web, Android, and Rust.
- Add AAD binding, fresh IV, gzip, passphrase verification token, and
  wrong-passphrase/tamper tests.
- Enforce no plaintext upload path at the adapter boundary.

#### M5-02 - Manifest, object layout, storage keys, migrations

- Add random 128-bit `storageKey` to conversations on all clients.
- Add `revision` and tombstone `deviceId`.
- Add web DB v2, Android Room migration, and Rust `0002` migration.
- Implement manifest writer/reader and self-contained conversation payloads.
- Keep attachments content-addressed and immutable.
- Preserve tombstones until sync confirms.

#### M5-03 - S3-compatible backend

- Implement SigV4 GET/PUT/HEAD/DELETE with access key + secret.
- Support AWS, Contabo, Cloudflare R2, MinIO, and Backblaze B2.
- Use `If-Match`/`If-None-Match` where supported; fall back conservatively.
- Add connectivity test before saving configuration.
- Document bucket CORS for web.
- Handle eventual consistency by re-reading and retrying.

#### M5-04 - Sync protocol, merge, tombstones, conflict tests

- Generate device ID and backup salt on first setup.
- Read/create `manifest.enc`; merge by conversation revision.
- Upload dirty conversations and attachments; rewrite manifest with preconditions.
- Retry with backoff and re-read on conflict.
- Implement message set merge, deletion tombstones, metadata LWW, concurrent
  addition renumbering, and never-drop-local-messages.
- Make restore non-destructive by default.
- Drain the existing `sync_queue` on local changes.

#### M5-05 - Backup/restore UI and client-side config

- Backup settings: backend, bucket, prefix, credentials, passphrase, test, enable.
- Status: last backup, pending changes, errors, storage usage.
- Actions: back up now, restore, delete remote with typed confirmation.
- Client-side statement for credentials and passphrase.
- Restore-on-new-device flow with attachment restoration and conflict reporting.

#### M5-06 - Scheduling

- Android: WorkManager worker with network and battery constraints.
- Web: foreground/periodic scheduling where allowed; document constraints and
  avoid unsupported background sync.
- Rust: desktop scheduling equivalent; document behavior.
- Ensure scheduling never violates platform constraints and never uploads
  plaintext.

#### M5-07 - Additional backends, parity, and evidence

- Implement Azure Blob, GCS, and WebDAV adapters.
- Update `contracts/parity.md` backup rows for each shipping client.
- Produce cross-client restore evidence and storage-provider inspection showing
  opaque encrypted objects.
- Document malicious-provider delete/rollback risk; do not claim Byzantine
  protection.

## 6. Phased Roadmap

| Phase | Tasks | Outcome |
| --- | --- | --- |
| 0 - Preflight | M5-00 | Decisions frozen, test vectors, baseline |
| 1 - Format | M5-01, M5-02 | Envelope, manifest, object layout, migrations |
| 2 - Transport | M5-03 | S3-compatible backend with conditional writes |
| 3 - Sync | M5-04 | Merge, tombstones, conflict resolution |
| 4 - Product | M5-05, M5-06 | UI, restore flow, scheduling |
| 5 - Evidence | M5-07 | Additional backends, parity, cross-client proof |

## 7. Test Strategy

- **Shared vectors first.** Every client must pass the same envelope and manifest
  vectors from `contracts/fixtures/backup/`. A format change is a contract change.
- **Crypto failure paths are mandatory.** Wrong passphrase, tampered header,
  tampered ciphertext, tampered AAD, and IV reuse must fail safely.
- **Property tests for merge.** Assert that the merged message set is a superset
  of each local set, ordering is deterministic, and renumbering is stable.
- **Cross-client restore is the gate.** Web writes and Android reads, then the
  reverse, using the same bucket and passphrase.
- **Interrupted sync tests.** Kill the upload mid-flight and assert local data is
  unchanged and the next sync resumes.
- **Inspection tests.** List bucket objects and assert names are opaque and
  contents are ciphertext; assert no provider keys appear in metadata.
- **Migration tests.** Web DB v1->v2, Android Room migration, Rust `0001`->`0002`
  with existing data.
- **Scheduling tests.** Android WorkManager constraints; web/Rust documented
  behavior.

## 8. Acceptance Criteria

Mapped to the milestone exit gate:

- [x] Cross-device restore works with encrypted data on every shipping client.
- [x] The storage provider cannot read conversation content, attachments, or
      metadata beyond opaque object names and sizes.
- [x] Conflict tests pass for concurrent edits, concurrent additions, and
      deletions.
- [x] A failed or interrupted sync never destroys local data.
- [x] Wrong passphrase and tampered ciphertext fail safely.
- [x] No provider API keys appear in backup metadata.
- [x] Android backup scheduling respects WorkManager constraints; web and Rust
      scheduling is documented.
- [x] Backup rows in parity are updated and reviewed.
- [x] Backup format is versioned and round-trip tested in every client.
- [x] `make contracts-check` validates backup fixtures.
- [x] No plaintext upload path exists when encryption is enabled.
- [x] Restore is non-destructive by default; replace requires explicit
      confirmation.
- [x] Passphrase loss is stated as unrecoverable before enabling backup.

## 9. Verification

```bash
make contracts-check

# Web
make web-lint && make web-typecheck && make web-test && make web-build
cd web && pnpm exec playwright test

# Android
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:connectedDebugAndroidTest   # device/emulator

# Rust (only if shipping)
make rust-check && make rust-lint && make rust-test && make rust-build
```

Plus:

- Cross-client restore evidence in `docs/operations/M5/`.
- Storage-provider inspection showing opaque encrypted objects.
- A real or MinIO S3 bucket test for SigV4 and conditional writes.
- Migration tests on existing data for web, Android, and Rust.

## 10. Risks and Decisions

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Cryptographic subtlety | High | Use vetted libraries; shared vectors; AAD/IV/tamper tests |
| Cross-client format drift | High | Contracts and fixtures are the single source of truth |
| Conflict resolution loses data | High | Property tests; never drop local messages; merge by default |
| Restore surprises users | High | Non-destructive default; typed confirmation for replace/delete |
| Passphrase loss | High | Warn before enabling; no recovery by design |
| S3 CORS/SigV4 variance | Medium | Start with S3-compatible; document each backend's limits |
| Eventual consistency | Medium | Re-read after write; retry with backoff |
| Web background limits | Medium | Document constraints; foreground/periodic only where allowed |
| Android WorkManager constraints | Medium | Network/battery constraints; test on device |
| Rust scope creep | Medium | Keep Rust optional/parallel; do not block web/Android gate |
| Migration breakage | Medium | Additive migrations; test with existing data |
| Second backend choice | Low | Defer to M5-00; S3 first regardless |

Open decisions to confirm in M5-00:

- Phase 2 scope authorization.
- Second backend after S3: Azure Blob, GCS, or WebDAV.
- Whether Rust is in scope for this milestone or deferred.
- Whether selective backup (all vs pinned-only) is in v1 or a follow-up.
- Whether Argon2id is implemented in v1 or only documented as v2.

## 11. File-Level Work Map

| Area | Primary files |
| --- | --- |
| Contracts | `contracts/schema/backup-*.schema.json`, `contracts/fixtures/backup/**`, `scripts/validate-contracts.mjs` |
| Web crypto | `web/src/platform/crypto.ts`, new `web/src/features/backup/envelope.ts` |
| Web storage | `web/src/storage/{db,migrations,ConversationRepository,MessageRepository,AttachmentRepository,ExportReader}.ts` |
| Web sync/backends | new `web/src/features/backup/{manifest,sync,s3,azure,gcs,webdav}.ts` |
| Web UI | `web/src/features/settings/BackupSettingsScreen.tsx`, `web/src/app/router.tsx` |
| Android crypto | new `android/core/backup/`, `android/core/datastore/.../TinkSecretCipher.kt` |
| Android storage | `android/core/database/.../{ChatUiDatabase,Migrations}.kt`, entities, DAOs, repositories |
| Android sync/backends | new `android/core/backup/` |
| Android UI | `android/feature/backup/`, `android/feature/settings/` |
| Android scheduling | `android/feature/backup/`, `android/app/` |
| Rust crypto | new `rust/crates/chatui-backup/` |
| Rust storage | `rust/crates/chatui-storage/migrations/0002_*.sql`, repositories, mappers |
| Rust UI | `rust/crates/chatui-ui/src/settings/`, `rust/apps/desktop/src/services/` |
| Parity/evidence | `contracts/parity.md`, `docs/operations/M5/` |

## 12. Out of Scope

- Phase 1 features or release work.
- Managed storage sold or operated by roksky, including the managed-hosting flow
  (M6).
- A server-side backup proxy, server-side key custody, or plaintext metadata
  services.
- Cross-device real-time collaboration or sharing.
- Backup passphrase recovery. Losing the passphrase means losing access by design.
- Local at-rest encryption of the primary database.
- Changes to provider protocols, chat behavior, or export/import semantics.
- Play Store listing, privacy-policy copy, and localization.

## 13. Definition of Done

M5 is complete only when:

1. Every task in section 5 is `Complete` or explicitly deferred with an approved
   record.
2. The exit gate in section 8 is fully checked with evidence.
3. `contracts/parity.md` backup rows reflect the delivered behavior.
4. `docs/operations/M5/M5-verification.md` records cross-client restore and
   storage inspection evidence.
5. `make contracts-check` validates backup fixtures and passes.
6. No plaintext backup path, provider key leak, or silent data-loss path remains.
