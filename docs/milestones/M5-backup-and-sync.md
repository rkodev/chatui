# M5 - Backup and sync (future)

- Status: Future
- Target: 3-4 weeks
- Depends on: M4 and a stable phase 1 release
- Blocks: M6 BYO storage onboarding
- Platforms: all shipping clients (web, Android, and Rust if shipped)
- Spec: sections 6.2 (BackupConfig), 12, 13, 17 (M5), 21.5

## Objective

Add encrypted, user-owned backup and sync for phase 2. Users choose their own object storage, and the storage provider or roksky must never be able to read conversation content. This milestone must not start until phase 1 is stable and the M4 release gate has passed.

## Entry criteria

- M4 exit gate is met and phase 1 has been released.
- Backup format and manifest contracts from M0 are stable enough to extend with a version field.
- Each shipping client has a repository layer that can enumerate conversations, messages, and attachments without UI involvement.

## Scope by workstream

### 1. Encryption envelope and contracts

Deliver:

- Extend `contracts/schema/backup-manifest.schema.json` and related schemas with a backup format version.
- Envelope layout from section 21.5:
  - Magic `CHATUI1\n`.
  - Header JSON with `v`, `alg`, `kdf`, `iterations`, `salt`, `objectType`, and `objectKey`.
  - Fresh random 12-byte IV per write.
  - AES-256-GCM ciphertext with authentication tag.
- Key derivation: PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte random salt, 32-byte key. Include a version field and a documented Argon2id v2 upgrade path.
- Object type and storage key as associated data so ciphertext is bound to its location.
- Gzip compression before encryption for conversation payloads; never compress attachment bytes that are already compressed.
- New random IV for every file write; never reuse an IV with the same key.
- Tests for round trip, IV uniqueness, wrong passphrase, tampering, and header/version handling.

Boundaries:

- No plaintext upload path when encryption is enabled.
- The passphrase is never stored; only salt and verification material.
- Provider API keys are never included in backup metadata.

### 2. Manifest and object layout

Deliver:

- Object layout per section 13.3: `manifest.enc`, `conversations/<random-storage-key>.enc`, and `attachments/<sha256>.enc`.
- Manifest contents: conversation index, attachment index, revision numbers, tombstones, device IDs, and format/version metadata.
- Conversation files are self-contained: conversation metadata, all messages, and attachment references.
- Attachments are content-addressed and immutable.
- Random `storageKey` values from the conversation record; do not use conversation UUIDs as object names in phase 2 sync.
- Manifest encryption and opaque object naming so filenames, sizes, and timestamps do not directly reveal conversation identity.

### 3. Storage backend adapters

Deliver:

- S3-compatible backend first: access key + secret with SigV4; AWS, Contabo, Cloudflare R2, MinIO, and Backblaze B2 as targets; conditional writes with `If-Match`/ETag where supported; bucket CORS documentation for web.
- Azure Blob: account name + key or SAS; block blobs.
- Google Cloud Storage: service account JSON initially, with user OAuth as a later preference.
- WebDAV: URL + username + password; treat weak consistency conservatively.
- Connectivity test before saving configuration.
- Handle eventual consistency conservatively; never upload unencrypted content.
- No server component and no proxying through roksky.

### 4. Sync protocol and conflict resolution

Deliver:

- Device ID and random backup salt stored locally on first setup.
- Read `manifest.enc`; if absent, create an empty manifest.
- Merge local and remote manifests by conversation revision.
- Upload dirty conversations and attachments.
- Rewrite the manifest with new revisions and ETag/version preconditions.
- On conflict, retry with backoff and re-read.
- Conflict rules from section 13.5:
  - Messages are immutable, identified by UUID, merged as a set, ordered by `sequence`, then `createdAt`, then `id`.
  - Deletions use tombstones with `deletedAt` and `deviceId`; a deletion wins over an older update.
  - Conversation metadata uses last-write-wins by `updatedAt` with `deviceId` as tiebreaker.
  - Concurrent additions are both kept and sequences renumbered deterministically.
  - Never silently drop local messages.
- Restore is non-destructive by default: merge, do not wipe local data unless the user explicitly chooses replace.

### 5. Client UI and scheduling

Deliver on each shipping client:

- Backup settings: backend, bucket, prefix, credentials, passphrase, test, enable.
- Show last backup time, pending changes, errors, and storage usage.
- "Back up now", "Restore from backup", and "Delete remote backup" with typed confirmation.
- Clear statement that storage credentials and the passphrase stay client-side.
- Android WorkManager integration with network and battery constraints.
- Web and Rust scheduling equivalents where applicable; no background scheduling that violates platform constraints.
- Restore-on-new-device flow, including attachment restoration and conflict reporting.

### 6. Tests and evidence

Deliver:

- Cross-client restore using the shared envelope and manifest.
- Wrong passphrase fails safely without corrupting local data.
- Manifest merge, concurrent additions, concurrent metadata edits, deletion tombstones, and deterministic sequence renumbering.
- Interrupted upload/resume and failed sync never destroy local data.
- No provider keys in backup metadata; no plaintext objects when encryption is on.
- Backup rows in `contracts/parity.md` updated for each shipping client.
- Document that a malicious storage provider can delete or roll back data; do not claim Byzantine protection.

## Out of scope

- Phase 1 features or release work.
- Managed storage sold or operated by roksky, including the managed-hosting flow (M6).
- A server-side backup proxy, server-side key custody, or plaintext metadata services.
- Cross-device real-time collaboration or sharing.
- Backup passphrase recovery. Losing the passphrase means losing access by design.

## Exit gate

- Cross-device restore works with encrypted data on every shipping client.
- The storage provider cannot read conversation content, attachments, or metadata beyond opaque object names and sizes.
- Conflict tests pass for concurrent edits, concurrent additions, and deletions.
- A failed or interrupted sync never destroys local data.
- Wrong passphrase and tampered ciphertext fail safely.
- No provider API keys appear in backup metadata.
- Android backup scheduling respects WorkManager constraints; web and Rust scheduling is documented.
- Backup rows in parity are updated and reviewed.
- Backup format is versioned and round-trip tested in every client.

## Verification

```bash
make contracts-check
# plus platform-specific backup test suites added by this milestone
```

Plus cross-client restore evidence and storage-provider inspection showing opaque encrypted objects.

## Task-generation guidance

Expect 5-7 tasks:

1. Backup envelope, contracts, and cryptographic round-trip tests.
2. Manifest format, object layout, and random storage keys.
3. S3-compatible backend with SigV4, conditional writes, and CORS documentation.
4. Sync protocol, merge, tombstones, and conflict tests.
5. Backup/restore UI and client-side configuration.
6. Android WorkManager scheduling plus web/Rust scheduling equivalents.
7. Azure Blob, GCS, and WebDAV backends, plus parity and cross-client evidence.

Generate these only after M4 is complete and the user confirms phase 2 scope.

## Risks and decisions to confirm

- Cryptography is easy to get subtly wrong. Use vetted libraries and test IV uniqueness, AAD binding, and tamper detection.
- Object storage CORS and SigV4 vary. Start with S3-compatible and document every backend's limitations.
- Conflict resolution can lose data if rules are ambiguous. Prefer preserving both concurrent additions and never dropping local messages.
- Restore can surprise users. Merge by default and require explicit confirmation for replace or remote deletion.
- Passphrase loss is unrecoverable. State this clearly before enabling backup.
- Which backend follows S3-compatible: Azure Blob, GCS, or WebDAV. This remains an open product question in spec section 20.
