# T000 - Repository foundations and developer commands

- Milestone: M0
- Depends on: none
- Suggested subagent: general
- Parallel-safe: no, this blocks everything
- Read: `docs/IMPLEMENTATION.md` sections 4, 14, 16, 17 (M0)

## Goal

Create the root repository structure, developer entry points, and ignore rules for a greenfield monorepo containing `web/`, `android/`, and optional `rust/`. Do not scaffold any application code here; that belongs to T010, T020, and T030.

## Owns

- `README.md`
- `Makefile`
- `.gitignore`
- `.editorconfig`
- `.github/` directory placeholder (workflows are T040)
- Empty top-level directories: `contracts/`, `scripts/`, `web/`, `android/`, `rust/`

Do not create files under `contracts/` or `scripts/` that belong to T001. Do not create application manifests or source files.

## Deliverables

1. Root `README.md` with:
   - Project summary, BYOK/privacy statement, and a link to `docs/IMPLEMENTATION.md`.
   - Client overview: web (React/Vite PWA), native Android (Kotlin/Compose), optional multiplatform Rust.
   - Prerequisites: Node LTS + pnpm, JDK + Android SDK, Rust stable.
   - Quick start placeholders for each client and a link to `docs/tasks/README.md`.
   - Security note: never commit keys or signing material.
2. `Makefile` with discoverable targets. Keep recipes thin; they should call the platform tooling rather than embedding logic.
   - Contracts: `contracts-check`, `contracts-install`.
   - Web: `web-install`, `web-dev`, `web-lint`, `web-typecheck`, `web-test`, `web-build`, `web-e2e`.
   - Android: `android-test`, `android-lint`, `android-assemble`.
   - Rust: `rust-check`, `rust-lint`, `rust-test`, `rust-build`, `rust-run`.
   - Meta: `help`, `format`, `clean` (only remove build outputs, never source or user data).
   - Use `.PHONY` and clear failure messages when a prerequisite directory is missing.
3. `.gitignore` covering Node, pnpm, Vite, Playwright, Gradle, Android Studio, Rust `target/`, IDE files, OS files, `.env`, local `config.local.json`, keystores, APK/AAB, and generated coverage.
4. `.editorconfig` for UTF-8, LF, final newline, and consistent indentation.

## Steps

1. Create the directory skeleton exactly as in section 4 of the spec, except for files owned by other tasks.
2. Write the root README and Makefile.
3. Add ignore/editor rules.
4. Run `make help` and check that every documented target appears.
5. Note in the task report that `make contracts-check` is expected to fail until T001 lands.

## Acceptance criteria

- `make help` works without any language toolchain installed.
- The directory skeleton exists and matches section 4 in shape.
- No root Node workspace or lockfile couples `web/`, `android/`, and `rust/`.
- No application code, provider metadata, or secrets are added.
- Root README points to the spec and task index.

## Verification

```bash
make help
test -d docs && test -d contracts && test -d scripts
test -d web && test -d android && test -d rust
git status --short
```

## Out of scope

- CI workflow implementation (T040).
- Contracts and fixture content (T001).
- Any client scaffold or dependency install (T010, T020, T030).
- Choosing a license or product branding; those remain open questions in spec section 20.
