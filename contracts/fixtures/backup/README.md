# Backup known-answer vectors

- Contract: [M5-00.1](../../../docs/operations/M5/M5-00.1-preflight-decisions-vectors.md)
- Decisions: [M5-decisions.md](../../../docs/operations/M5/M5-decisions.md)
- Format: `chatui.backup.vectors` version `1`
- Status: Frozen for backup format `v: 1` / `formatVersion: 1`

`vectors.json` is a **known-answer contract**, not sample data. Every shipping
client must reproduce the same bytes for the same inputs. A mismatch is a
format bug, not a test-fixture bug.

## Rules

- Do not regenerate this file casually. A change to any expected byte is a
  backup format change and requires a dated decision in `M5-decisions.md`, a
  format version bump, and updates to every client.
- All values are synthetic. The passphrase is a public test value and must
  never be used for a real backup.
- Never add real passphrases, API keys, prompts, conversation content, or
  personal data.
- The vectors are deterministic: fixed passphrase, salt, IV, header, and
  plaintext. Do not replace them with random values.
- The gzip bytes are a reference produced with zlib level 9 and mtime 0. Exact
  gzip bytes are not a cross-client requirement; clients must decompress the
  reference stream and produce a gzip stream that decompresses to the same
  canonical JSON.

## What is covered

- Envelope magic, header length, canonical header JSON, IV, ciphertext, and tag.
- AAD construction for `manifest`, `conversation`, and `attachment`.
- IV, salt, key, and tag lengths.
- Gzip round trip for a conversation payload.
- Wrong passphrase, tampered IV, tampered ciphertext, tampered tag, tampered
  AAD, tampered header iterations, tampered header object key, and tampered
  header version.

## How to use

1. Parse the envelope from `envelopeVectors[0].envelopeBase64`.
2. Derive the key with PBKDF2-HMAC-SHA256 using the vector passphrase, salt, and
   iterations.
3. Build the AAD with the length-prefixed encoding in `envelope.aadEncoding`.
4. Decrypt with AES-256-GCM and verify the 16-byte tag.
5. Gunzip the plaintext and compare it to `uncompressedBase64`.
6. Run every `failureVectors` case and assert the documented `expected` error.

The canonical header key order and AAD encoding are frozen in
`M5-decisions.md`. Do not infer them from a JSON serializer's default order.

## Schema fixtures

These files are synthetic examples validated by `scripts/validate-contracts.mjs`
against the backup schemas. They are not known-answer vectors and contain no
real user data, prompts, API keys, or passphrases.

| File | Schema | Purpose |
| --- | --- | --- |
| `manifest.json` | `backup-manifest-body.schema.json` | Valid manifest body with a conversation index, attachment index, and tombstone. |
| `conversation.json` | `backup-conversation.schema.json` | Valid self-contained conversation with metadata, messages, and attachment references. |
| `envelope-header.json` | `backup-envelope.schema.json` | Valid canonical envelope header. |
| `invalid/envelope-missing-field.json` | `backup-envelope.schema.json` | Fails because `salt` is missing. |
| `invalid/envelope-wrong-alg.json` | `backup-envelope.schema.json` | Fails because `alg` is not `AES-256-GCM`. |
| `invalid/envelope-wrong-kdf.json` | `backup-envelope.schema.json` | Fails because `kdf` is not `pbkdf2-sha256`. |
| `invalid/envelope-bad-object-type.json` | `backup-envelope.schema.json` | Fails because `objectType` is not a v1 backup object type. |
| `invalid/envelope-bad-format-version.json` | `backup-envelope.schema.json` | Fails because `formatVersion` is not `1`. |

Each invalid fixture must fail for exactly the documented reason. The validator
also mutates valid fixtures in memory to prove the new schemas reject broken
vectors.
