# Signed update manifests

This directory holds the versioned update-manifest schema and illustrative
signed manifests. The updater implementation is
`rust/apps/desktop/src/updater.rs`; the signing process is documented in
`docs/operations/M3.5/update-signing.md`.

## Files

| File | Purpose |
| --- | --- |
| `schema.json` | JSON Schema for the envelope and the signed payload. |
| `example-stable.json` | Example stable-channel envelope signed by the pinned development key. |
| `example-beta.json` | Example beta-channel envelope signed by the pinned development key. |

The examples are illustrative. They are signed by development keys generated
for M3.5-11.3, not by production keys. The private keys were discarded and are
not in the repository. Replace the pinned public keys in `updater.rs` with the
production keys before shipping.

## Envelope

```json
{
  "schemaVersion": 1,
  "payload": "<base64 of the UTF-8 payload JSON>",
  "signature": "<base64 Ed25519 signature over the payload bytes>"
}
```

The updater parses only the envelope, verifies the signature over the decoded
payload bytes, and only then parses the payload. A manifest that is unsigned,
tampered with, signed by an unknown key, or signed for a different channel is
rejected before any artifact is considered.

## Payload

```json
{
  "schemaVersion": 1,
  "appVersion": "0.2.0",
  "channel": "stable",
  "target": { "os": "linux", "arch": "x86_64" },
  "artifact": {
    "url": "https://example.test/ChatUI-0.2.0-linux-x86_64.tar.gz",
    "sha256": "<64 hex characters>",
    "size": 9003658
  },
  "minimumSupportedVersion": "0.1.0",
  "publishedAt": "2026-11-08T00:00:00Z"
}
```

`minimumSupportedVersion` and `publishedAt` are optional. The artifact URL must
be HTTPS, the checksum must be a SHA-256 hex digest, and the declared size must
not exceed 1 GiB.

## Regenerating examples

Do not commit private keys. To regenerate examples, generate a throwaway key
pair outside the repository, sign the payload bytes, and replace the examples
and the pinned public keys together. See
`docs/operations/M3.5/update-signing.md` for the full process.
