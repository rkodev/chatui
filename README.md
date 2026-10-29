# ChatUI

ChatUI is a local-first, bring-your-own-key (BYOK) chat client for multiple
model providers. Conversations, settings, attachments, and provider API keys
stay on the user's device. Provider requests go directly from the client to the
provider the user configured; ChatUI operates no proxy that can read prompts,
responses, or keys.

The authoritative product and engineering specification lives in
[`docs/IMPLEMENTATION.md`](docs/IMPLEMENTATION.md). The milestone index is
[`docs/milestones/README.md`](docs/milestones/README.md); concrete implementation
tasks are generated per milestone from there.

## Clients

- **Web**: React 19 + TypeScript + Vite installable PWA. Runs entirely in the
  browser and supports BYOK provider access subject to provider CORS policy.
- **Android**: Native Kotlin + Jetpack Compose application with Room, DataStore,
  and platform-backed secret storage.
- **Rust (optional)**: Multiplatform workspace for desktop, Android, iOS, and
  WASM targets. Optional for the phase 1 web and Android release gate.

Each client is an independent build. There is no root Node workspace or lockfile
coupling `web/`, `android/`, and `rust/`, and the Rust workspace is not a
build-time dependency of the web or Android clients.

## Prerequisites

- **Web**: Node.js LTS and pnpm.
- **Android**: JDK and the Android SDK.
- **Rust (optional)**: Rust stable. The workspace pins its toolchain once the
  Rust client is scaffolded.

## Quick start

Web:

```bash
make web-install
make web-dev
```

Android:

```bash
make android-assemble
# or, once a device or emulator is available:
# cd android && ./gradlew installDebug
```

Rust (optional):

```bash
make rust-run
```

Shared contracts:

```bash
make contracts-install
make contracts-check
```

Run `make help` for the full target list. Shared contracts are established in
M0, so `make contracts-check` fails with a clear message until that milestone's
contract work lands.

## Security

Never commit API keys, provider credentials, signing material, keystores, APKs
built with release signing keys, `.env` files, or local configuration overrides
such as `config.local.json`. Keep secrets in local environment files or CI
secret stores only. See section 12 of the specification for the full security
and privacy model.
