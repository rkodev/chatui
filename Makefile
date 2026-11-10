SHELL := /bin/bash

WEB_DIR := web
ANDROID_DIR := android
RUST_DIR := rust

.PHONY: help contracts-install contracts-check \
	web-install web-dev web-lint web-typecheck web-test web-test-coverage web-build web-e2e \
	web-container release-check \
	android-test android-lint android-assemble \
	android-test-ui android-screenshot android-release android-baseline-profile \
	rust-check rust-lint rust-test rust-build rust-run \
	rust-fmt rust-nextest rust-deny rust-audit rust-conformance rust-ui-test rust-package \
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
		'  make web-test-coverage   Run unit tests with V8 coverage (writes web/coverage)' \
		'  make web-build           Build the production web bundle' \
		'  make web-e2e             Run Playwright end-to-end tests' \
		'  make web-container       Build the static web container image locally' \
		'' \
		'Android:' \
		'  make android-test        Run Android unit tests' \
		'  make android-lint        Run Android lint' \
		'  make android-assemble    Assemble the debug APK' \
		'  make android-test-ui     Run connected instrumentation tests on a device/emulator' \
		'  make android-screenshot  Verify Roborazzi screenshot goldens' \
		'  make android-release     Build the release APK and AAB (signing from env)' \
		'  make android-baseline-profile  Generate the baseline profile on a device/emulator' \
		'' \
		'Rust (optional):' \
		'  make rust-check          Type-check the Rust workspace' \
		'  make rust-lint           Run Clippy with warnings denied' \
		'  make rust-test           Run Rust tests' \
		'  make rust-build          Build Rust release artifacts' \
		'  make rust-run            Run the default Rust application' \
		'  make rust-fmt            Check Rust formatting' \
		'  make rust-nextest        Run the Rust workspace tests with cargo-nextest' \
		'  make rust-deny           Check advisories, licenses, bans, and sources with cargo-deny' \
		'  make rust-audit          Check dependencies for known vulnerabilities with cargo-audit' \
		'  make rust-conformance    Run the chatui-conformance test package' \
		'  make rust-ui-test        Run the chatui-ui test package' \
		'  make rust-package        Run the desktop packaging command (configured in M3.5-11.2)' \
		'' \
		'Release:' \
		'  make release-check       Run the required web and Android release gates locally' \
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

web-test-coverage:
	$(call require_file,$(WEB_DIR)/package.json,Web scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	pnpm --dir $(WEB_DIR) exec vitest run --coverage

web-build:
	$(call require_file,$(WEB_DIR)/package.json,Web scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	pnpm --dir $(WEB_DIR) build

web-e2e:
	$(call require_file,$(WEB_DIR)/package.json,Web end-to-end setup is not present yet (see docs/milestones/M2-providers-and-content.md).)
	pnpm --dir $(WEB_DIR) exec playwright test

web-container:
	$(call require_file,deploy/Dockerfile,Web deployment container is not present yet (see docs/operations/M4/deployment.md).)
	docker build -f deploy/Dockerfile -t chatui-web:local .

release-check: contracts-check web-lint web-typecheck web-test-coverage web-build web-e2e android-lint android-test android-assemble
	@echo "Required web and Android release gates passed. Rust desktop is post-preview / parallel and is not part of this release."

android-test:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew testDebugUnitTest

android-lint:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew lintDebug

android-assemble:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew assembleDebug

android-test-ui:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew connectedDebugAndroidTest

android-screenshot:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew :app:verifyRoborazziDebug

android-release:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew :app:assembleRelease :app:bundleRelease

android-baseline-profile:
	$(call require_file,$(ANDROID_DIR)/gradlew,Android scaffold is not present yet (see docs/milestones/M0-foundations.md).)
	cd $(ANDROID_DIR) && ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest

rust-check:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cargo check --manifest-path $(RUST_DIR)/Cargo.toml --workspace --all-targets

rust-lint:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cargo clippy --manifest-path $(RUST_DIR)/Cargo.toml --workspace --all-targets -- -D warnings

rust-test:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cargo test --manifest-path $(RUST_DIR)/Cargo.toml --workspace

rust-build:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cargo build --manifest-path $(RUST_DIR)/Cargo.toml --workspace --release

rust-run:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cargo run --manifest-path $(RUST_DIR)/Cargo.toml -p chatui-desktop --bin chatui-desktop

rust-fmt:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cd $(RUST_DIR) && cargo fmt --all --check

rust-nextest:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cd $(RUST_DIR) && cargo nextest run --workspace

rust-deny:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cd $(RUST_DIR) && cargo deny check

rust-audit:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cd $(RUST_DIR) && cargo audit

rust-conformance:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cd $(RUST_DIR) && cargo nextest run -p chatui-conformance

rust-ui-test:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	cd $(RUST_DIR) && cargo nextest run -p chatui-ui

rust-package:
	$(call require_file,$(RUST_DIR)/Cargo.toml,Rust workspace is not present yet (see docs/milestones/M3.5-rust-desktop.md).)
	$(call require_file,$(RUST_DIR)/packaging/package.py,Desktop packaging pipeline is not present yet (see docs/operations/M3.5/packaging.md).)
	python3 $(RUST_DIR)/packaging/package.py --clean

format:
	@if [ -f $(WEB_DIR)/package.json ]; then pnpm --dir $(WEB_DIR) exec prettier --write .; else echo "skip: web scaffold is not present"; fi
	@if [ -f $(RUST_DIR)/Cargo.toml ]; then cargo fmt --manifest-path $(RUST_DIR)/Cargo.toml --all; else echo "skip: rust workspace is not present"; fi

clean:
	@rm -rf $(WEB_DIR)/dist $(WEB_DIR)/coverage $(WEB_DIR)/test-results $(WEB_DIR)/playwright-report $(WEB_DIR)/.vite $(WEB_DIR)/node_modules/.vite
	@rm -rf $(RUST_DIR)/target
	@rm -rf $(ANDROID_DIR)/build $(ANDROID_DIR)/*/build $(ANDROID_DIR)/.gradle $(ANDROID_DIR)/.cxx
	@echo "Removed build outputs only; source, dependencies, and user data were left untouched."
