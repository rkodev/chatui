# M5 backup and sync decisions

- Milestone: M5
- Recorded by: `M5-00.1` preflight
- Date: 2026-11-11
- Baseline commit: `135dc19` (M5 plan and task breakdown)
- Owner: Product/release owner
- Status: Confirmed for phase 2
- Supersedes: `DEC-M4-01` for M5 onward (Rust desktop is now required)

This file freezes the backup format, product decisions, and cross-client test
vectors before any client code is written. Change a decision only with a new
dated entry and a matching update to `contracts/fixtures/backup/vectors.json`,
`contracts/parity.md`, and every shipping client.

## DEC-M5-01 - Phase 2 scope authorization

**Decision: phase 2 is authorized. M5 starts 2026-11-11.**

- The user explicitly authorized M5 preflight on 2026-11-11 by requesting
  implementation of `M5-00.1`.
- Scope: encrypted, user-owned backup and sync as defined by the
  [M5 milestone](../../milestones/M5-backup-and-sync.md) and the
  [M5 plan](../M5-backup-and-sync-implementation-plan.md).
- Required clients: web, Android, and Rust desktop (see `DEC-M5-03`).
- Out of scope: M6 managed hosting, a server-side backup proxy, server-side key
  custody, plaintext metadata services, and passphrase recovery.
- Rationale: the M4 exit gate passed on 2026-11-10 and the user confirmed the
  phase 2 scope.
- Consequence: `M5-00.1` is `Complete`; later M5 tasks may start in dependency
  order.

## DEC-M5-02 - Backup format version

**Decision: `formatVersion: 1` and envelope `v: 1`.**

- `v` is the envelope format version. `formatVersion` is the backup payload
  format version. Both are `1` for this milestone.
- A v1 client must reject `v != 1` or `formatVersion != 1` with
  `unsupported-version` before decrypting.
- A v1 client must reject an unknown `alg` or `kdf` with `unsupported-alg` or
  `unsupported-kdf`.
- A future format change requires a new version, a dated decision, and new
  vectors. Do not silently extend v1.
- Rationale: matches spec 21.5 and `DEC-M4-04` (backup/export format `1`).

## DEC-M5-03 - Rust desktop scope

**Decision: Rust desktop is shipping and required for M5.**

- This supersedes `DEC-M4-01` for M5 onward. The M4 release remains as shipped
  without Rust; M5 is the first milestone that gates on Rust.
- Rust tasks `M5-01.4`, `M5-02.3`, `M5-03.3`, `M5-04.3`, `M5-05.3`, `M5-06.3`,
  and `M5-07.3` are required, not optional.
- Rust must pass the same envelope, AAD, gzip, failure, manifest, and sync
  vectors as web and Android.
- The M5 exit gate includes Rust. Rust must also meet the M3.5 exit gate and the
  spec section 10.12 release gate before M5 can close.
- Rationale: user decision on 2026-11-11.
- Reversal: a later `not shipping` decision requires a new dated entry and a
  matching update to `contracts/parity.md` and the M5 task index.

## DEC-M5-04 - KDF v1 and v2

**Decision: v1 uses PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt, and a
32-byte key. Argon2id is documented only and is not implemented in v1.**

- Salt scope: one random 16-byte salt per backup set, generated on first setup,
  stored locally, and repeated in every envelope header. Clients must reject an
  object whose header salt differs from the manifest salt.
- The salt is standard base64 with padding in the header.
- v2 will use Argon2id and is selected by `v: 2` / `formatVersion: 2`. Do not
  emit Argon2id under v1.
- Rationale: spec 13.4; vetted libraries; a versioned upgrade path without
  breaking v1 clients.

## DEC-M5-05 - Envelope byte layout

**Decision: the envelope is exactly the following byte sequence.**

| Offset | Length | Content |
| --- | --- | --- |
| 0 | 8 | ASCII `CHATUI1\n` (`43 48 41 54 55 49 31 0a`) |
| 8 | 4 | Big-endian `uint32` header length |
| 12 | `headerLength` | UTF-8 canonical JSON header |
| `12 + headerLength` | 12 | Random IV |
| `24 + headerLength` | `n` | AES-256-GCM ciphertext |
| `24 + headerLength + n` | 16 | AES-256-GCM tag |

- The envelope remainder after the IV is `ciphertext || tag`. The tag is always
  the last 16 bytes.
- `headerLength` must be greater than 0 and at most 65,536 bytes.
- Reject an envelope shorter than `12 + headerLength + 12 + 16` bytes.
- Reject a magic mismatch with `malformed-envelope`.
- Rationale: spec 21.5; unambiguous offsets; the tag placement matches
  WebCrypto and Tink conventions and is easy to verify in Rust.

## DEC-M5-06 - Header JSON fields and canonicalization

**Decision: the header has exactly these fields, in this order.**

```json
{
  "v": 1,
  "formatVersion": 1,
  "alg": "AES-256-GCM",
  "kdf": "pbkdf2-sha256",
  "iterations": 600000,
  "salt": "<standard base64 of 16 bytes>",
  "objectType": "conversation",
  "objectKey": "conversations/<32 lowercase hex>.enc"
}
```

- Canonical serialization: UTF-8 JSON, no insignificant whitespace, keys in the
  order above, standard base64 with padding for `salt`.
- `objectType` is one of `manifest`, `conversation`, or `attachment` for backup
  objects. Other schema values are reserved for future use and are not valid in
  a v1 backup envelope.
- `objectKey` is ASCII and must match the object's actual storage path.
- The wire value of `kdf` is lowercase `pbkdf2-sha256` to match
  `contracts/schema/backup-manifest.schema.json`. Spec 13.4 displays the
  human-readable name `PBKDF2-SHA256`; the wire value is authoritative.
- Rationale: byte-for-byte cross-client vectors; no serializer-dependent key
  order.

## DEC-M5-07 - AAD encoding

**Decision: the AAD is the following byte string, with no separators.**

```text
uint32be(byteLength(objectType)) || UTF-8(objectType)
|| uint32be(byteLength(objectKey)) || UTF-8(objectKey)
```

- Lengths are unsigned 32-bit big-endian byte counts of the UTF-8 encodings.
- Example for `manifest` / `manifest.enc`:
  `00000008 6d616e6966657374 0000000c 6d616e69666573742e656e63`.
- The header is not directly included in the AAD. Every header field is still
  protected: `v`, `formatVersion`, `alg`, and `kdf` are validated before use;
  `iterations` and `salt` change the derived key; `objectType` and `objectKey`
  are bound by the AAD. The failure vectors cover tampered iterations, tampered
  object key, and tampered version.
- Rationale: length-prefixed encoding is unambiguous even if an object type or
  key contains a separator; it binds ciphertext to its storage location.

## DEC-M5-08 - Compression

**Decision: gzip conversation payloads before encryption; never compress
manifests or attachments.**

- Conversation plaintext inside the envelope is a gzip stream of the canonical
  conversation JSON.
- Manifest plaintext is canonical JSON, not gzipped.
- Attachment plaintext is the raw attachment bytes, not gzipped.
- The reference gzip vector uses zlib level 9 and mtime 0. Exact gzip bytes are
  not a cross-client requirement; clients must decompress any valid gzip stream
  and produce a gzip stream that decompresses to the same canonical JSON.
- Rationale: spec 13.4; attachments are already compressed; the manifest is
  small and benefits from direct JSON validation.

## DEC-M5-09 - Object layout and keys

**Decision: use the following object layout under the user's prefix.**

```text
<prefix>/
|-- manifest.enc
|-- conversations/
|   `-- <storageKey>.enc
`-- attachments/
    `-- <sha256>.enc
```

- `storageKey` is 32 lowercase hexadecimal characters (128 random bits),
  generated when the conversation is created. Do not use the conversation UUID
  as an object name.
- `sha256` is 64 lowercase hexadecimal characters of the attachment bytes.
- Attachments are content-addressed and immutable.
- The manifest is encrypted with the same envelope as conversations.
- Object names are opaque; do not include titles, provider names, or other
  metadata in paths.
- Rationale: spec 13.3; opaque names; content-addressed attachments deduplicate
  safely.

## DEC-M5-10 - Revision model and tombstones

**Decision: each conversation has a monotonic integer `revision` starting at 1
and incremented on every local change that affects the conversation or its
messages.**

- The manifest stores the current revision per conversation.
- Merge by revision; a higher revision wins for the conversation index.
- Deletions use a tombstone with `deletedAt` (ISO 8601 UTC) and `deviceId`.
- A deletion wins over an older update. Keep the tombstone until the remote
  manifest confirms it; local hard-delete after 30 days.
- Messages are immutable and identified by UUID. Merge as a set, ordered by
  `sequence`, then `createdAt`, then `id`.
- Concurrent additions are both kept and sequences are renumbered
  deterministically.
- Never silently drop local messages.
- Rationale: spec 13.5; simple merge; no silent data loss.

## DEC-M5-11 - Restore and delete rules

**Decision: restore is non-destructive by default.**

- Default restore merges remote data into local data. It never wipes local data.
- Replace requires an explicit user confirmation that names the data to be
  replaced.
- Delete remote backup requires a typed confirmation.
- A wrong passphrase or tampered object fails before any local mutation.
- Rationale: spec 13.7; restore surprises are worse than a failed restore.

## DEC-M5-12 - Storage backends

**Decision: S3-compatible is the first backend. Azure Blob, Google Cloud
Storage, and Google Drive are also required.**

| Order | Backend | Auth | Notes |
| --- | --- | --- | --- |
| 1 | S3-compatible | Access key + secret, SigV4 | AWS, Contabo, Cloudflare R2, MinIO, Backblaze B2; conditional writes with `If-Match`/`If-None-Match` where supported; document bucket CORS for web. |
| 2 | Azure Blob | Account name + key or SAS | Block blobs; use ETag preconditions where supported. |
| 3 | Google Cloud Storage | Service account JSON initially; user OAuth later | Use generation preconditions where supported. |
| 4 | Google Drive | OAuth; app data folder or user-selected folder | Not object storage: no ETag/`If-Match`; use conservative read-after-write and conflict retry; store only opaque encrypted objects. |

- All backends must store only encrypted objects. There is no plaintext upload
  path.
- Test connectivity before saving configuration.
- Handle eventual consistency conservatively; never assume read-after-write.
- Rationale: user decision on 2026-11-11; spec 13.6. Google Drive is included
  because the user asked for it, with explicit caveats about weaker
  conditional-write semantics.

## DEC-M5-13 - Scheduling constraints

**Decision: scheduling is best-effort and platform-constrained.**

- Web: sync on app open, manual "Back up now", and Periodic Background Sync
  where the browser supports it. Do not promise background execution. Document
  the constraints in the backup settings UI.
- Android: WorkManager with `NetworkType.CONNECTED` and battery-not-low
  constraints. Periodic work has a 15-minute minimum; use unique work and
  exponential backoff. Do not promise exact timing.
- Rust: sync on launch and an optional timer while the app is running. Do not
  install an OS background service.
- Rationale: platform constraints; spec 13.6 and 13.7.

## DEC-M5-14 - Passphrase recovery

**Decision: there is no passphrase recovery by design.**

- The passphrase is never stored. Only the salt and a verification token are
  stored.
- Warn before enabling backup that losing the passphrase means losing access.
- Rationale: spec 13.2 and 13.7; a recovery path would create a server-side
  key-custody risk.

## DEC-M5-15 - Failure codes for vectors

**Decision: use these stable failure codes in cross-client tests.**

| Code | Meaning |
| --- | --- |
| `authentication-failed` | Wrong passphrase, tampered IV, ciphertext, tag, AAD, or key-derivation field. |
| `unsupported-version` | `v` or `formatVersion` is not supported. |
| `unsupported-alg` | `alg` is not supported. |
| `unsupported-kdf` | `kdf` is not supported. |
| `malformed-envelope` | Magic, length, or minimum size is invalid. |
| `invalid-header` | Header JSON is invalid or a field is missing or out of range. |
| `decompression-failed` | The decrypted conversation payload is not valid gzip. |

- Rationale: clients must fail the same way for the same vector.

## DEC-M5-16 - Test vector policy

**Decision: `contracts/fixtures/backup/vectors.json` is a frozen known-answer
contract.** See
[contracts/fixtures/backup/README.md](../../../contracts/fixtures/backup/README.md).

- Do not regenerate it casually. Any expected-byte change is a format change
  and requires a dated decision, a version bump, and updates to every client.
- Use synthetic data only. Never add real passphrases, API keys, prompts,
  conversation content, or personal data.
- The gzip reference bytes are not a cross-client requirement; the round-trip
  result is.
- Rationale: prevent silent cross-client format drift.

## Entry-gate checklist

| Criterion | Status | Evidence / owner |
| --- | --- | --- |
| M4 exit gate met and phase 1 released | Pass | `docs/operations/M4/M4-verification.md` (2026-11-10). |
| Phase 2 scope explicitly confirmed | Pass | `DEC-M5-01`, 2026-11-11. |
| Backup format version and manifest contract frozen | Pass | `DEC-M5-02`, `DEC-M5-05`, `DEC-M5-06`, `DEC-M5-07`; `contracts/fixtures/backup/vectors.json`. |
| Each shipping client can enumerate conversations, messages, and attachments without UI involvement | Pass | Web `web/src/storage/ExportReader.ts`; Android `ConversationRepository`, `MessageRepository`, `AttachmentRepository`; Rust `chatui-storage` repositories. |
| Rust release status re-confirmed | Pass | `DEC-M5-03`: shipping and required. |
| `make contracts-check` baseline | Pass | [M5-baseline.md](M5-baseline.md), "Gate results". |
| Web baseline | Pass | [M5-baseline.md](M5-baseline.md), "Web baseline". |
| Android baseline | Pass | [M5-baseline.md](M5-baseline.md), "Android baseline". |
| Rust baseline | Pass | [M5-baseline.md](M5-baseline.md), "Rust baseline". |
| No secrets, prompts, or conversation content in vectors | Pass | Synthetic values only; reviewed in `M5-00.1`. |

## Redaction

No API keys, real passphrases, prompts, conversation content, personal data, or
signing material are recorded here. The vector passphrase is a public test
value and must never be used for a real backup.
