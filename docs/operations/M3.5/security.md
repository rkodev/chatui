# M3.5 Secrets Security Policy and Evidence

- Status: Implemented by M3.5-04.3; OS-specific backup exclusion remains a documented procedure.
- Owner: Secrets/security owner.
- Scope: `chatui-secrets` and the desktop app-data directory policy.
- Source plan: [M3.5 Rust Desktop Implementation Plan](../M3.5-rust-desktop-implementation-plan.md) sections 3.4 and 4.6.
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 10.6, 12.1, and 12.6.

This document records the redaction API, the release logging policy, the panic
hook, the zeroization audit, the backup-exclusion procedure for each supported
OS, and the source audit for keys in URLs, filenames, environment dumps, and
error messages. It contains no real credentials. Test values are sentinels such
as `sk-test-not-a-real-key`.

## 1. Redaction API

`chatui_secrets::redaction` provides:

| Item | Purpose |
| --- | --- |
| `Redactor` | Reusable policy that scrubs headers, query parameters, bearer tokens, API-key-shaped strings, and registered exact values. |
| `Redactor::with_secret_header` | Registers an application-specific header name whose value must be redacted. |
| `Redactor::with_secret_value` | Registers an exact secret value that must be redacted wherever it appears. The value is held in a zeroizing buffer and is never printed by `Debug`. |
| `RedactingFormat` | A `tracing_subscriber::fmt::FormatEvent` wrapper that formats the event first and redacts the complete output second. |
| `init_logging` | Installs the redacting global subscriber with the build-appropriate level. |
| `filter_for` / `logging_filter` | Return the maximum level for a build. |

The default policy redacts:

- `Authorization`
- `x-api-key`
- `x-goog-api-key`
- `anthropic-dangerous-direct-browser-access`
- custom header names registered with `with_secret_header`
- exact values registered with `with_secret_value`
- bearer tokens
- API-key-shaped strings with prefixes such as `sk-`, `sk_`, `AIza`, `gsk_`,
  `xai-`, `hf_`, `github_pat_`, `glpat-`, `AKIA`, `ASIA`, `ya29.`, and `eyJ`
- sensitive query parameters such as `api_key`, `access_token`, `token`,
  `password`, `passphrase`, and `secret`

Redaction is applied to the fully formatted event, not only to the message
field. A secret cannot escape through a structured field, a span attribute, or a
message that a log call did not anticipate.

## 2. Release logging policy

| Build | Maximum level | Request/response bodies | Telemetry |
| --- | --- | --- | --- |
| Debug | `DEBUG` | Never logged by `chatui-secrets` | None |
| Release | `WARN` | Never logged by `chatui-secrets` | None |

`init_logging` installs a redacting subscriber. Release builds do not configure
verbose request or response logging. The crate does not add analytics,
telemetry, or remote logging. Provider adapters must log metadata only and must
not log request or response bodies in release builds.

## 3. Panic hook

`chatui_secrets::panic::install_panic_hook` installs a hook that:

- extracts the panic payload and source location;
- redacts the payload with the same `Redactor` policy as the logging layer;
- truncates the payload to `MAX_PANIC_PAYLOAD_CHARS` (512 characters);
- prints `chatui panic at <file>:<line>:<column>: <redacted payload>`;
- never prints request or response bodies on its own.

`install_panic_hook_with` accepts a custom `Redactor`.
`install_panic_hook_with_reporter` accepts a custom reporter for applications
that route panic reports through their own diagnostics channel.

Limitation: the panic payload is owned by the Rust panic runtime. The hook
prevents it from being printed, but it cannot zeroize the runtime-owned payload
buffer. Callers should avoid formatting secrets into panic messages in the first
place.

## 4. Zeroization audit

| Buffer or type | Mechanism | Notes |
| --- | --- | --- |
| `SecretValue` | `Zeroizing<String>` plus an explicit `Zeroize` implementation | Zeroized on drop and on explicit `zeroize()`. `Debug` prints `SecretValue([REDACTED])`. |
| `EncryptedFile::derive_key` | `Zeroizing<[u8; 32]>` | Argon2id output is zeroized when the function returns. |
| `EncryptedFile::decrypt` | `Zeroizing<Vec<u8>>` | Plaintext is zeroized when the caller drops it. |
| `EncryptedFileStore::save` | `Zeroizing<Vec<u8>>` | JSON plaintext is zeroized after encryption. |
| `EncryptedFileStore::load` | Each value is wrapped in `Zeroizing<String>` before ID validation | An early return on a corrupt ID still zeroizes every remaining value on drop. |
| `SessionStore` | `SecretValue` values | Cleared by `lock()`, `forget_all_keys()`, and `Drop`. |
| `KeyringStore` | Values are moved into `SecretValue` | The OS backend may copy the value internally; that copy is outside this crate's control. |
| `FallbackChoice` / `EncryptedFileStore` passphrase | `SecretValue` | `Debug` output is redacted. |
| `Redactor` registered values | `Zeroizing<String>` | `Debug` prints only the count. |
| `SecretError` / `EncryptedFileError` | No secret values | Variants carry static categories or non-secret reasons. |

## 5. Backup exclusion

The encrypted fallback file is `secrets.enc` under the platform app-data
directory returned by `directories::ProjectDirs::from("", "", "chatui")`:

| OS | App-data path | Backup behavior | Procedure / limitation |
| --- | --- | --- | --- |
| Linux | `$XDG_DATA_HOME/chatui` or `~/.local/share/chatui` | No standard OS-level per-file exclusion. Backup tools vary. | Exclude the directory or `secrets.enc` in the backup tool. Examples: `rsync --exclude 'chatui/secrets.enc'` and `tar --exclude='*/chatui/secrets.enc'`. The file is encrypted, but exclusion is still recommended. Explicit limitation: the user or distribution backup tool must apply the exclusion. |
| macOS | `~/Library/Application Support/chatui` | Time Machine and iCloud Drive may include Application Support. | Mark the directory and `secrets.enc` with `NSURLIsExcludedFromBackupKey` from the desktop shell, and use `tmutil addexclusion <path>` for Time Machine. Explicit limitation: the Rust crate does not call Foundation yet; the desktop shell or M5 backup work must apply the resource value. |
| Windows | `%APPDATA%\chatui\data` (Roaming AppData) | OneDrive Known Folder Move does not sync AppData by default, but third-party backup tools may include it. | Exclude `%APPDATA%\chatui\data\secrets.enc` in the backup tool. Windows has no standard per-file cloud-backup exclusion for OneDrive. Explicit limitation: the current `directories` data directory is Roaming AppData; M5 may switch to Local AppData if the product decides that roaming is undesirable. |

The SQLite conversation database is not encrypted at rest in phase 1. The same
directory-level exclusion should cover both `secrets.enc` and the database.
Backup/sync implementation is out of scope for M3.5 and belongs to M5.

## 6. Source audit

Audit commands ran from the repository root on 2026-11-07 against
`rust/crates/chatui-secrets/src`:

```bash
grep -rn "std::env\|env::var\|env!\|option_env!" rust/crates/chatui-secrets/src
grep -rn "Url\|url::\|query_pairs\|query_pairs_mut\|format!.*expose\|expose()" rust/crates/chatui-secrets/src
grep -rn "println!\|eprintln!\|dbg!\|print!\|panic!\|unwrap()\|expect(" rust/crates/chatui-secrets/src
grep -rn "derive(.*Debug\|impl fmt::Debug\|impl std::fmt::Debug" rust/crates/chatui-secrets/src
```

Results:

| Check | Result |
| --- | --- |
| Keys in URLs | No URL construction or query-parameter auth in `chatui-secrets`. Provider adapters must use headers. |
| Keys in filenames | The fallback file name is the fixed `secrets.enc`. Secret IDs are validated to a small ASCII alphabet and are non-secret by contract. No secret value is used in a path. |
| Environment dumps | No `std::env`, `env::var`, `env!`, or `option_env!` usage in `chatui-secrets`. |
| Error messages | `SecretError` and `EncryptedFileError` carry static categories or non-secret reasons. `InvalidId` carries a reason such as `must not be empty`, never the rejected value. |
| Logging macros | No `println!`, `eprintln!`, `dbg!`, or `print!` in non-test code. The panic hook is the only intentional stderr writer. |
| `expose()` call sites | Keyring `set_password`, Argon2id input, JSON serialization, and tests. No logging or error formatting. |
| `Debug` implementations | `SecretValue`, `SessionStore`, `KeyringStore`, `KeyLifecycle`, `EncryptedFileStore`, and `Redactor` are custom and redacted. `FallbackChoice` derives `Debug` but its passphrase field is a `SecretValue`, so it prints `SecretValue([REDACTED])`. `KdfParams`, `EncryptedFile`, `SecretId`, and the error enums contain no secret values. |

## 7. Tests

Redaction tests in `redaction.rs`:

- `redacts_authorization_header`
- `redacts_required_api_key_headers`
- `redacts_custom_secret_header`
- `redacts_json_style_header`
- `redacts_bearer_tokens_anywhere`
- `redacts_api_key_shaped_strings`
- `redacts_sensitive_query_parameters`
- `redacts_registered_secret_values`
- `preserves_ordinary_text`
- `preserves_line_endings`
- `redactor_debug_does_not_print_registered_values`
- `release_filter_is_not_verbose`
- `redacting_format_redacts_event_fields`

Panic tests in `panic.rs`:

- `panic_message_redacts_headers_and_tokens`
- `panic_message_redacts_registered_values`
- `panic_message_truncates_long_payloads`
- `panic_report_writes_redacted_output`
- `panic_message_keeps_location_context`

Zeroization and `Debug` tests:

- `store::tests::secret_value_can_be_zeroized_explicitly`
- `store::tests::secret_value_debug_is_redacted`
- `encrypted_file::tests::fallback_choice_debug_redacts_the_passphrase`
- `encrypted_file::tests::encrypted_file_store_debug_omits_the_passphrase`
- `encrypted_file::tests::no_plaintext_appears_on_disk`
- `keyring_store::tests::errors_do_not_contain_the_secret`

All tests use sentinels only. No real key is present in the repository, tests,
or this document.

## 8. Limitations and follow-ups

- macOS and Windows backup exclusion is documented, not implemented in Rust.
  The desktop shell or M5 backup work must apply the platform-specific
  exclusion.
- The panic payload is redacted before printing but is not zeroized because the
  panic runtime owns it.
- The OS credential-store backend may copy a secret internally. The crate
  zeroizes its own buffers and never logs the value.
- Release logging is limited to `WARN`; request and response bodies are never
  logged by this crate.
- No telemetry, analytics, or remote logging is added.
