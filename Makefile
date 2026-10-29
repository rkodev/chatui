SHELL := /bin/bash

WEB_DIR := web
ANDROID_DIR := android
RUST_DIR := rust

.PHONY: help contracts-install contracts-check \
	web-install web-dev web-lint web-typecheck web-test web-build web-e2e \
	android-test android-lint android-assemble \
	rust-check rust-lint rust-test rust-build rust-run \
	format clean

# Fail early with an actionable message instead of a confusing tool error.
define require_file
@test -e $(1) || { echo "error: $(1) is missing. $(2)"; exit 1; }
endef

help:
	@printf '%s\n' \
		'ChatUI developer commands' \
		'' \
		'Contracts:' \
		'  make contracts-install   Install contract validator dependencies' \
		'  make contracts-check     Validate providers, schemas, fixtures, and parity' \
		'' \
		'Web:' \
		'  make web-install         Install web dependencies with pnpm' \
		'  make web-dev             Start the Vite development server' \
		'  make web-lint            Run ESLint' \
		'  make web-typecheck       Run TypeScript checks' \
		'  make web-test            Run unit and component tests' \
		'  make web-build           Build the production web bundle' \
		'  make web-e2e             Run Playwright end-to-end tests' \
		'' \
		'Android:' \
		'  make android-test        Run Android unit tests' \
		'  make android-lint        Run Android lint' \
		'  make android-assemble    Assemble the debug APK' \
		'' \
		'Rust (optional):' \
		'  make rust-check          Type-check the Rust workspace' \
		'  make rust-lint           Run Clippy with warnings denied' \
		'  make rust-test           Run Rust tests' \
		'  make rust-build          Build Rust release artifacts' \
		'  make rust-run            Run the default Rust application' \
		'' \
		'Meta:' \
		'  make format              Format web and Rust sources' \
		'  make clean               Remove build outputs only' \
		'' \
		'See README.md and docs/milestones/README.md for prerequisites and milestone ownership.'

contracts-install:
	$(call require_file,scripts/package.json,Contract tooling is not present yet (see docs/milestones/M0-foundations.md).)
	npm --prefix scripts install

contracts-check:
	$(call require_file,scripts/validate-contracts.mjs,Contract validator is not present yet (see docs/milestones/M0-foundations.md).)
	node scripts/validate-contracts.mjs

web-install:
	$(call require_file,$(WEB_DIR)/package.json,Web scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	pnpm --dir $(WEB_DIR) install

web-dev:
	$(call require_file,$(WEB_DIR)/package.json,Web scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	pnpm --dir $(WEB_DIR) dev

web-lint:
	$(call require_file,$(WEB_DIR)/package.json,Web scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	pnpm --dir $(WEB_DIR) lint

web-typecheck:
	$(call require_file,$(WEB_DIR)/package.json,Web scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	pnpm --dir $(WEB_DIR) typecheck

web-test:
	$(call require_file,$(WEB_DIR)/package.json,Web scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	pnpm --dir $(WEB_DIR) test

web-build:
	$(call require_file,$(WEB_DIR)/package.json,Web scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	pnpm --dir $(WEB_DIR) build

web-e2e:
	$(call require_file,$(WEB_DIR)/package.json,Web end-to-end setup is not present yet (see docs/milestones/M2-providers-and-content.md).)
	pnpm --dir $(WEB_DIR) exec playwright test

android-test:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew testDebugUnitTest

android-lint:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew lintDebug

android-assemble:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew assembleDebug

rust-check:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-multiplatform.md).)
	cargo check --manifest-path $(RUST_DIR)/Cargo.toml --workspace --all-targets

rust-lint:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-multiplatform.md).)
	cargo clippy --manifest-path $(RUST_DIR)/Cargo.toml --workspace --all-targets -- -D warnings

rust-test:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-multiplatform.md).)
	cargo test --manifest-path $(RUST_DIR)/Cargo.toml --workspace

rust-build:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-multiplatform.md).)
	cargo build --manifest-path $(RUST_DIR)/Cargo.toml --workspace --release

rust-run:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-multiplatform.md).)
	cargo run --manifest-path $(RUST_DIR)/Cargo.toml

format:
	@if [ -f $(WEB_DIR)/package.json ]; then pnpm --dir $(WEB_DIR) exec prettier --write .; else echo "skip: web scaffold is not present"; fi
	@if [ -f $(RUST_DIR)/Cargo.toml ]; then cargo fmt --manifest-path $(RUST_DIR)/Cargo.toml --all; else echo "skip: rust workspace is not present"; fi

clean:
	@rm -rf $(WEB_DIR)/dist $(WEB_DIR)/coverage $(WEB_DIR)/test-results $(WEB_DIR)/playwright-report $(WEB_DIR)/.vite $(WEB_DIR)/node_modules/.vite
	@rm -rf $(RUST_DIR)/target
	@rm -rf $(ANDROID_DIR)/build $(ANDROID_DIR)/*/build $(ANDROID_DIR)/.gradle $(ANDROID_DIR)/.cxx
	@echo "Removed build outputs only; source, dependencies, and user data were left untouched."
