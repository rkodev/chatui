# M3 - Android depth

- Status: Planned
- Target: 2 weeks
- Depends on: M2
- Blocks: M4
- Platforms: android
- Spec: sections 3.8, 9.3-9.11, 11.11, 12.3, 14.4, 15.1, 15.2 (cases 26-27 plus regressions), 17 (M3)

## Objective

Make the native Android app platform-complete and release-configurable. M1 and M2 deliver the shared product behavior; M3 adds Android-specific integration, lifecycle recovery, secure-storage hardening, UI test coverage, screenshots, release configuration, and device evidence. No new cross-platform product features should originate here.

## Entry criteria

- M2 exit gate is met on Android: OpenRouter and launch providers work, attachments and parameters work, search/export/import exist, and the Android foundation is stable.
- Contract fixtures and the parity checklist are current.

## Scope by workstream

### 1. Secret storage and privacy hardening

Deliver:

- Verify the Tink `AndroidKeysetManager` implementation uses an Android Keystore master key, AEAD AES-256-GCM, and ciphertext-only persistence. The keyset is never exported and no plaintext key is stored.
- Key rotation, "Forget key", and "Forget all keys" paths are covered by tests.
- Backup exclusion is real, not assumed: `android:allowBackup="false"` or explicit data-extraction rules exclude secret stores and databases. Verify keys are not restored on another device.
- Optional `FLAG_SECURE` on secret-entry and key-detail screens.
- No keys in logs, crash reports, debug overlays, screenshots, or analytics. Confirm redacted logging is the only debug path.
- Certificate validation stays enabled. Cleartext is limited to `localhost` and private-range hosts via network security config and requires an explicit user warning.
- Optional biometric or device-credential gate before revealing/copying a key is an enhancement, not a release blocker.

### 2. Platform integration

Deliver:

- Share target for `ACTION_SEND` with `text/plain` that prefills the composer without losing current drafts.
- Photo picker for images and Storage Access Framework document picker for files.
- Clipboard image paste and drag-and-drop on ChromeOS/tablets.
- Copy message and code-block text to the clipboard.
- Open provider documentation in a Custom Tab.
- Predictive back ordering: close drawer, sheet, dialog, keyboard, then navigate.
- Hardware keyboard shortcuts for common actions.
- Deep link `chatui://chat/<id>` that opens the matching conversation or a safe not-found state.
- Tablet/foldable layout with a permanent sidebar at width >= 840 dp, correct window insets, and edge-to-edge behavior.
- Android 13+ per-app language support only if localization exists; do not add translations in M3.

### 3. Lifecycle and recovery

Deliver:

- Configuration changes do not lose an active stream or its partial content.
- Process death during streaming preserves partial content and marks the message interrupted; the user sees an explanation and can regenerate.
- Drafts, navigation location, and conversation selection survive process death where practical.
- On startup, mark leftover `streaming` messages as cancelled/interrupted, preserve partial text, and clean orphan attachments.
- Background behavior is documented: streaming continues while foregrounded; a foreground service or WorkManager-based survival is an enhancement and must not be presented as guaranteed.
- Do not schedule background sync before backup exists.

### 4. UI tests and screenshots

Deliver:

- Compose UI tests for onboarding, chat/send/stream, model picker, provider management, settings, and error states.
- Roborazzi or equivalent screenshot tests for key screens in light/dark and phone/tablet configurations.
- Tests for date grouping, search, delete/undo, export/import round trip, invalid imports, clear data, share intent parsing, deep links, and process-death recovery.
- Large-conversation test that verifies virtualization or at least bounded composition.

### 5. Performance and release configuration

Deliver:

- Release and debug build types with minification, resource shrinking, and a mapping artifact for crash deobfuscation. Destructive Room migration is forbidden in release builds.
- Signing policy using CI secrets, monotonically increasing `versionCode`, semantic `versionName`, and no committed keystores or passwords.
- Baseline profile generation and startup verification. Target cold start under 1.5 s on a mid-tier device.
- Performance checks: `LazyColumn` stable keys/content type, no history recomposition during streaming, image decode sizing, Coil thumbnail caching. Debug APK under 20 MB and release APK under 15 MB if feasible; record actuals.
- Release artifacts: signed APK/AAB for internal testing and GitHub Releases. Play Store listing and privacy-policy content are out of scope.
- Accessibility baseline: Material 3 semantics, content descriptions, minimum 48 dp targets, TalkBack pass, reduced motion, optional dynamic color. The full cross-platform accessibility audit is M4.
- Manual device matrix: Android 8, 12, 14, current, one low-RAM device, and one tablet. Record results and release blockers.

### 6. Parity

- Update `contracts/parity.md` for Android share target, desktop/mobile-specific rows, and any behavior changed by platform integration.
- Critical Android cases covered in M3: 26 (process death preserves partial content) and 27 (backup exclusion prevents key restore), plus regressions from M1 and M2.

## Out of scope

- New product features or provider protocols.
- Web PWA, deployment, and web accessibility/performance audit (M4).
- Rust packaging and release (M3.5).
- Backup/sync and managed hosting (M5, M6).
- Play Store publishing, store listing copy, data-safety declarations, and privacy policy beyond app behavior.
- Localization and translations.
- Full cross-platform release audit (M4).

## Exit gate

- Release build is minified, signed through CI secrets, and produces a mapping artifact; no keystore is committed.
- Keys are encrypted with Tink/Android Keystore, excluded from backup, and verified not to restore on another device.
- Cleartext is limited to localhost/private ranges; certificate validation is intact.
- Share target, photo/document pickers, drag-and-drop, clipboard paste, deep links, and predictive back work.
- Configuration changes and process death preserve or clearly interrupt streams with partial content.
- Compose UI and Roborazzi suites pass for onboarding, chat, model picker, provider management, settings, and error states.
- Baseline profile is generated and the cold-start budget is met or documented. APK size budgets are met or documented.
- TalkBack and 48 dp targets pass; reduced motion is respected.
- Manual device matrix is recorded with no release-blocking issues.
- Critical cases 26-27 and M1/M2 regressions have evidence.
- `contracts/parity.md` reflects the delivered Android behavior.

## Verification

```bash
make contracts-check
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
cd android && ./gradlew :app:connectedDebugAndroidTest :app:verifyRoborazziDebug
```

Plus the manual device matrix and baseline-profile/startup measurements recorded in the milestone report.

## Task-generation guidance

Expect 5-7 tasks. Suggested slices:

1. Secret storage hardening, backup exclusion, FLAG_SECURE, and network security config.
2. Share target, photo/document pickers, drag-and-drop, clipboard, Custom Tabs, and deep links.
3. Predictive back, keyboard shortcuts, tablet/foldable layout, and window insets.
4. Lifecycle/process-death recovery and startup cleanup.
5. Compose UI tests, Roborazzi screenshots, and large-conversation coverage.
6. Release build configuration, signing policy, baseline profiles, and artifact generation.
7. Performance budgets, accessibility baseline, and device matrix evidence.

Keep Android-only work in this milestone. Do not reopen M1/M2 feature scope except for small, test-covered defects.

## Risks and decisions to confirm

- Process death during streaming is easy to claim and hard to verify. Require a real test/device check and preserve partial content.
- Backup exclusion must be validated on-device; manifest flags alone are not proof.
- Baseline profiles and startup budgets can be noisy across devices. Record hardware and methodology.
- Share targets and deep links can drop drafts or reopen stale screens. Test intent edge cases.
- Release signing must never depend on committed material.
- Android background restrictions can interrupt streams. Document the limitation rather than overpromising.
