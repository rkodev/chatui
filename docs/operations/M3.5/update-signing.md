# Desktop update manifests, signing, and verification

- Task: M3.5-11.3
- Milestone: M3.5
- Spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 10.10, 12.6, and 14.5
- Plan: [M3.5 plan](../M3.5-rust-desktop-implementation-plan.md) sections 3.7 and 4.13
- Last updated: 2026-11-08

This page documents the signed update-manifest format, the pinned-key model, the
stable/beta channel behavior, the signing and rotation process, and the
verification tests. The implementation is
`rust/apps/desktop/src/updater.rs`; the schema and examples are in
`rust/dist/manifests/`.

## Security model

- Manifests are fetched over HTTPS only. A non-HTTPS manifest or artifact URL is
  rejected before any network request or artifact handling.
- The updater parses only the envelope, verifies an Ed25519 signature over the
  exact payload bytes with a pinned public key, and only then parses the
  payload. Unsigned, tampered, unknown-key, and cross-channel manifests fail
  closed.
- The artifact SHA-256 checksum is verified before the bytes are handed to the
  installer.
- Downgrades and manifests below the declared minimum supported version are
  rejected.
- A stream in progress turns an available update into a confirmation request.
  The updater never restarts silently.
- Private signing keys live only in CI secrets. They are never committed, never
  embedded in the app, and never written to logs or test fixtures.

## Manifest format

The manifest is a versioned envelope. The payload is base64-encoded UTF-8 JSON
and the signature is a base64 Ed25519 signature over the decoded payload bytes.

```json
{
  "schemaVersion": 1,
  "payload": "<base64 of the UTF-8 payload JSON>",
  "signature": "<base64 Ed25519 signature over the payload bytes>"
}
```

The payload schema is versioned independently of the release artifact list:

```json
{
  "schemaVersion": 1,
  "appVersion": "0.2.0",
  "channel": "stable",
  "target": { "os": "linux", "arch": "x86_64" },
  "artifact": {
    "url": "https://github.com/rkodev/chatui/releases/download/v0.2.0/ChatUI-0.2.0-linux-x86_64.tar.gz",
    "sha256": "<64 hex characters>",
    "size": 9003658
  },
  "minimumSupportedVersion": "0.1.0",
  "publishedAt": "2026-11-08T00:00:00Z"
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `schemaVersion` | yes | Envelope and payload schema version. Currently `1`. |
| `appVersion` | yes | Semantic version offered by the manifest. |
| `channel` | yes | `stable` or `beta`. Must match the requested channel and the signing key. |
| `target.os` | yes | `linux`, `macos`, or `windows`. |
| `target.arch` | yes | `x86_64` or `aarch64`. |
| `artifact.url` | yes | HTTPS download URL. |
| `artifact.sha256` | yes | SHA-256 hex digest of the artifact. |
| `artifact.size` | yes | Artifact size in bytes; at most 1 GiB. |
| `minimumSupportedVersion` | no | Oldest installed version that may apply the update. |
| `publishedAt` | no | Informational publication timestamp. |

The JSON Schema is `rust/dist/manifests/schema.json`. The envelope and payload
schemas are intentionally independent of the release artifact list so a new
installer format does not require a manifest schema change.

## Channels and URLs

The channel preference is persisted as JSON in the per-user config directory:

```text
<config>/chatui/update-channel.json
```

The default is `stable`. The updater reads and writes it through
`FileChannelStore`; tests use `MemoryChannelStore`. The settings UI can call
`Updater::set_channel` when the channel control is wired.

Default manifest URLs:

| Channel | URL |
| --- | --- |
| `stable` | `https://github.com/rkodev/chatui/releases/latest/download/update-stable.json` |
| `beta` | `https://github.com/rkodev/chatui/releases/download/beta/update-beta.json` |

The release owner may host the manifests on a dedicated HTTPS endpoint instead.
Change `ManifestUrls` in `updater.rs` or pass explicit URLs through
`Updater::with_parts`.

## Pinned public keys

Public keys are pinned in `rust/apps/desktop/src/updater.rs`:

| Channel | Constant |
| --- | --- |
| `stable` | `PINNED_STABLE_PUBLIC_KEY` |
| `beta` | `PINNED_BETA_PUBLIC_KEY` |

The values currently in the repository are development keys generated for this
task. The corresponding private keys were discarded and are not in the
repository. The release owner must replace both constants with the production
public keys before shipping. Update-signing key ownership is still `unknown`
(B-05); see [Blockers](#blockers).

A manifest signed by the stable key but claiming `beta`, or signed by the beta
key but claiming `stable`, is rejected as a cross-channel manifest even if the
signature itself is valid.

## Signing process

Run this outside the repository. Never commit the private key.

### 1. Generate a key pair

```bash
openssl genpkey -algorithm ED25519 -out update-signing-private.pem
openssl pkey -in update-signing-private.pem -pubout -out update-signing-public.pem
```

Extract the raw 32-byte public key and base64-encode it for the pinned constant:

```bash
openssl pkey -in update-signing-private.pem -pubout -outform DER \
  | tail -c 32 | base64
```

Store `update-signing-private.pem` in the CI secret store (for example,
`UPDATE_SIGNING_PRIVATE_KEY`) and delete the local copy after provisioning.
Generate separate keys for `stable` and `beta` so a beta-key compromise cannot
sign stable manifests.

### 2. Build the payload

Write the payload JSON exactly as it will be published. The signature covers
these bytes, so do not reformat the payload after signing.

```bash
cat > payload.json <<'JSON'
{
  "schemaVersion": 1,
  "appVersion": "0.2.0",
  "channel": "stable",
  "target": { "os": "linux", "arch": "x86_64" },
  "artifact": {
    "url": "https://github.com/rkodev/chatui/releases/download/v0.2.0/ChatUI-0.2.0-linux-x86_64.tar.gz",
    "sha256": "<sha256 of the artifact>",
    "size": 9003658
  },
  "minimumSupportedVersion": "0.1.0",
  "publishedAt": "2026-11-08T00:00:00Z"
}
JSON
```

### 3. Sign the payload bytes

Using OpenSSL:

```bash
openssl pkeyutl -sign -rawin \
  -inkey update-signing-private.pem \
  -in payload.json \
  -out payload.sig
```

Using Python (`cryptography`):

```python
import base64, json, pathlib
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

private_key = serialization.load_pem_private_key(
    pathlib.Path("update-signing-private.pem").read_bytes(), password=None
)
payload = pathlib.Path("payload.json").read_bytes()
signature = private_key.sign(payload)
envelope = {
    "schemaVersion": 1,
    "payload": base64.b64encode(payload).decode(),
    "signature": base64.b64encode(signature).decode(),
}
pathlib.Path("update-stable.json").write_text(json.dumps(envelope, indent=2) + "\n")
```

### 4. Publish

Upload the envelope as `update-stable.json` or `update-beta.json` to the channel
URL. Upload the artifact to the HTTPS URL named in the payload. Publish the
artifact checksum in the release notes or `SHA256SUMS` as well.

The release job must fail when signing material is absent. Do not publish an
unsigned manifest or an artifact whose checksum does not match the signed
payload.

## Verification flow

`Updater::check(streaming)` performs these steps in order:

1. Load the persisted channel, defaulting to `stable`.
2. Fetch the channel manifest over HTTPS with a 30-second timeout and a 64 KiB
   response limit.
3. Parse the envelope and reject an unsupported schema version.
4. Decode the payload and signature, then verify the signature with the pinned
   key for the channel.
5. Parse the payload only after the signature verifies.
6. Reject a payload channel that differs from the requested channel or from the
   key that verified it.
7. Reject a target that differs from the running OS/architecture.
8. Reject a downgrade, or a version below `minimumSupportedVersion`.
9. Reject a non-HTTPS artifact URL, an oversized artifact, or a malformed
   checksum.
10. Return `UpToDate`, `Available`, or `ConfirmationRequired` when a stream is
    active.

`Updater::apply(plan, artifact, confirmed)` verifies the artifact size and
SHA-256 and returns `VerifiedArtifact`. A plan created during an active stream
requires `confirmed == true`; otherwise it returns `ConfirmationRequired`. The
actual installer handoff is release automation (M3.5-11.4), not this task.

## Key rotation

1. Generate the replacement key pair outside the repository.
2. Sign the next manifest with the replacement key.
3. Ship an app release that pins the replacement public key. Keep the old key
   pinned in that release only if it must verify manifests published before the
   rotation.
4. Publish the new manifest after the release that pins the new key is
   available.
5. Revoke the old private key in the CI secret store and record the rotation
   date.

Because the pinned keys are compiled into the app, a key rotation requires an
app release. Do not rotate a key and publish a manifest for an app version that
cannot verify it.

## Tests

The updater tests use generated test keys and temporary manifests only. They
never use production keys or the network.

| Test | Coverage |
| --- | --- |
| `pinned_keys_parse` | Both pinned constants are valid Ed25519 keys. |
| `valid_manifest_verifies_and_parses` | Valid signature and payload. |
| `beta_channel_uses_beta_url` | Beta channel selects the beta manifest. |
| `tampered_payload_fails_signature` | Payload changed after signing. |
| `unsigned_manifest_fails_closed` | Empty signature. |
| `wrong_channel_signature_is_rejected` | Payload channel differs from the request. |
| `cross_channel_key_is_rejected` | Payload signed by the other channel's key. |
| `tampered_artifact_fails_checksum` | Artifact bytes differ from the signed size or checksum. |
| `downgrade_is_rejected` | Offered version is older than installed. |
| `up_to_date_is_reported` | Offered version equals installed. |
| `minimum_supported_version_is_enforced` | Installed version is below the minimum. |
| `wrong_target_is_rejected` | Manifest target differs from the build. |
| `insecure_artifact_url_is_rejected` | Artifact URL is HTTP. |
| `invalid_checksum_is_rejected` | Checksum is not a SHA-256 hex digest. |
| `unsupported_schema_is_rejected` | Envelope schema version is not supported. |
| `oversized_manifest_is_rejected` | Manifest exceeds the size limit. |
| `insecure_manifest_url_is_rejected_before_network` | Manifest URL is HTTP. |
| `stream_guard_requires_confirmation` | Active stream requires confirmation. |
| `channel_setting_persists_in_memory` | Channel preference round-trips. |
| `file_channel_store_round_trips` | Channel preference persists to disk. |
| `example_manifests_verify_with_pinned_keys` | Committed examples verify with the pinned keys. |

Run them with:

```bash
cd rust && cargo nextest run -p chatui-desktop
```

## Blockers

- **B-05: update-signing key ownership is unknown.** The mechanism, schema,
  pinned-key location, and tests are complete, but the Release/security owner
  must name the production key owner, generate the production key pairs, store
  the private keys in CI secrets, and replace the development public keys in
  `updater.rs` before a production release. This is an external credential and
  ownership decision, not an implementation gap.
- **B-01/B-02/B-03/B-04** (macOS/Windows runners and code-signing credentials)
  remain external release blockers recorded in
  [packaging.md](packaging.md). They do not block manifest verification.

## Related documents

- [Packaging and checksums](packaging.md)
- [M3.5 decisions](M3.5-decisions.md)
- [M3.5 verification](M3.5-verification.md)
