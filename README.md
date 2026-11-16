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
- **Rust (optional)**: Native desktop workspace for Windows, macOS, and Linux.
  Optional for the phase 1 web and Android release gate.

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
M0; `make contracts-check` validates providers, schemas, fixtures, and parity.

## Task automation with Copilot

With Python 3.10+ and an authenticated GitHub Copilot CLI on `PATH`, use the
Copilot counterpart to `scripts\run-tasks` from PowerShell:

```powershell
# Preview the next eligible task without starting Copilot or changing files.
python .\scripts\run-copilot-tasks.py .\docs\operations\android-minimalist-design-plan --dry-run

# Execute one task, explicitly approving tool use.
python .\scripts\run-copilot-tasks.py .\docs\operations\android-minimalist-design-plan --max-tasks 1 --allow-all-tools
```

The default model is **`gpt-6.1-sol`** (GPT-6.1). Override it with `--model`
or `COPILOT_MODEL`; the command-line option takes precedence. The default task
folder is `docs\operations\M0`. `--max-tasks` / `MAX_TASKS` limits attempts
(`0` means unlimited); `--include-in-progress` / `INCLUDE_IN_PROGRESS=1`
allows resuming eligible work.

Each task gets a fresh noninteractive session, using the existing
`next-task` dependency selector and the Markdown workflow in
`.opencode\agent\task-runner.md`, including scoped per-task commits. No
OpenCode agent installation is required. Tasks must finish as `Complete` or
`Blocked`; both states are mirrored into the task index. Blocked dependencies
are not bypassed, and failed or unfinished sessions stop the batch.

No extra permissions or folder trust are granted by default. Review existing
Copilot permissions before running; use repeatable `--allow-tool`, `--deny-tool`,
and `--allow-url` rules, or explicitly opt into `--allow-all-tools` as above.
Tool approval does not grant all paths or URLs. Add `--trust-folder` only after
reviewing the checkout configuration if it is not already trusted. OpenCode
YAML permission settings are not imported as Copilot permissions. An optional
`--agent` selects an installed Copilot custom agent; it is not assumed to exist.
Use `--help` for all options.

## Security

Never commit API keys, provider credentials, signing material, keystores, APKs
built with release signing keys, `.env` files, or local configuration overrides
such as `config.local.json`. Keep secrets in local environment files or CI
secret stores only. See section 12 of the specification for the full security
and privacy model.
