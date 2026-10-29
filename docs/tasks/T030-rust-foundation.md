# T030 - Rust workspace, domain, contracts, storage, secrets

- Milestone: M3.5
- Depends on: T001
- Suggested subagent: general
- Parallel-safe: yes, with T010 and T020
- Read: `docs/IMPLEMENTATION.md` sections 4, 5, 6, 10.1-10.6, 12.6, 15.1, 21.4-21.5

## Goal

Create the Rust multiplatform workspace and headless core: domain types, contract loading, SQLite storage, attachments, export/import, secure secret storage, and a minimal desktop shell. Do not implement provider adapters or chat features.

## Owns

- `rust/Cargo.toml`, `rust/Cargo.lock`, `rust/rust-toolchain.toml`
- `rust/crates/chatui-domain/**`
- `rust/crates/chatui-contracts/**`
- `rust/crates/chatui-storage/**`
- `rust/crates/chatui-secrets/**`
- `rust/crates/chatui-ui/**` skeleton only (module roots, tokens, app bootstrap)
- `rust/apps/desktop/**`, `rust/apps/android/**` placeholder, `rust/apps/ios/**` placeholder, `rust/apps/wasm/**` placeholder
- `rust/tests/conformance/**` skeleton

## Deliverables

1. Cargo workspace with edition 2024, resolver 3, shared dependency versions, and a pinned toolchain via `rust-toolchain.toml`.
2. `chatui-domain`: serde structs/enums mirroring section 6 with matching JSON names, UUIDv7 IDs, RFC 3339 timestamps, `StreamEvent`, `FinishReason`, `ChatError`, `Settings`. No I/O dependencies.
3. `chatui-contracts`: load `contracts/providers.json` and schemas at build time or runtime; strict `serde` types plus `jsonschema` validation for imports. Add tests that deserialize every fixture.
4. `chatui-storage`: SQLite (prefer `sqlx` with runtime-tokio; `rusqlite` acceptable) with WAL, foreign keys, busy timeout, versioned migrations, repositories, FTS5 message search, content-addressed attachments under the app-data directory, and export/import using `chatui.export`.
5. `chatui-secrets`: `keyring` integration for Linux Secret Service, macOS Keychain, and Windows Credential Manager; Android Keystore bridge abstraction; explicit encrypted-file fallback using AES-256-GCM or XChaCha20-Poly1305 with Argon2id; session-only mode; redaction and zeroization.
6. Minimal `chatui-ui` and `apps/desktop` shell: window opens, design tokens from section 11.12, theme switching, no feature screens.
7. Tests: domain JSON round trips, contracts fixture loading, migrations from an empty database, repository CRUD, FTS search, attachment storage/orphan cleanup, export/import round trip, secret encryption/decryption, and redaction.

## Steps

1. Create the workspace and pinned toolchain.
2. Implement domain and contracts.
3. Implement storage and secrets.
4. Add the desktop shell skeleton.
5. Add tests and run the Rust gate.

## Acceptance criteria

- `cargo build --workspace` and `cargo nextest run --workspace` pass on a clean checkout without Android/iOS SDKs.
- Domain types serialize to the same JSON shapes as the shared contracts.
- SQLite persists conversations, messages, providers, models, attachments, and settings with FTS search.
- Attachments are content-addressed and orphan cleanup works.
- Secrets use an OS credential store or an explicit encrypted fallback; no plaintext keys.
- The desktop shell launches with themes/tokens but no chat features.
- No provider adapters or onboarding/chat UI are implemented yet.

## Verification

```bash
cd rust && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo nextest run --workspace
cd rust && cargo build --workspace
```

## Out of scope

- Provider adapters, onboarding, model catalog UI (T031).
- Chat, conversations, settings UI (T032).
- Packaging, updater, final security/accessibility/perf (T033).
- Additional providers and parity (T034).
