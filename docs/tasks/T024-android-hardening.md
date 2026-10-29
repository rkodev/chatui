# T024 - Android release, security, accessibility, performance

- Milestone: M3/M4
- Depends on: T023
- Suggested subagent: general
- Parallel-safe: yes, with T014 and T033
- Read: `docs/IMPLEMENTATION.md` sections 3.8, 9.6-9.11, 11.11, 12.3, 14.4, 15.1, 18

## Goal

Harden the Android client for release: signing and packaging rules, network security, backup exclusion, screenshot protection, baseline profiles, cold-start and APK budgets, and accessibility. No new product features.

## Owns

- `android/app/build.gradle.kts` release/signing configuration
- `android/app/proguard-rules.pro`
- `android/app/src/main/AndroidManifest.xml` security-related attributes
- `android/app/src/main/res/xml/network_security_config.xml`, backup/data-extraction rules
- `android/baseline-prof.txt` or generated baseline profile
- Release/packaging documentation under `android/README.md`
- Accessibility fixes inside existing features, scoped to semantics/touch targets

## Deliverables

1. Debug/release build types with minification, resource shrinking, and a crash-deobfuscation mapping artifact. Destructive Room migration forbidden in release.
2. Signing policy: CI-secret-based signing, monotonically increasing `versionCode`, semantic version name. Never commit keystores or passwords.
3. Network security config: HTTPS default; cleartext only for `localhost` and private-range hosts with an explicit user warning. Certificate validation enabled; no trust-all manager.
4. Backup exclusion: `android:allowBackup="false"` or explicit extraction rules excluding secrets and databases. Verify keys are not restored on another device.
5. Secret-entry and key-detail screens optionally use `FLAG_SECURE`; no keys in logs, crash reports, or debug overlays.
6. Baseline profile generation and startup verification. Target cold start under 1.5 s on a mid-tier device.
7. Performance checks: `LazyColumn` stable keys/contentType, no history recomposition during streaming, image decode sizing, Coil thumbnail cache. Debug APK under 20 MB and release under 15 MB if feasible; document actuals.
8. Accessibility: Material 3 semantics, content descriptions, 48 dp touch targets, predictive back ordering, TalkBack pass, reduced motion, dynamic color optional.
9. Manual device matrix: Android 8, 12, 14, current, one low-RAM device, one tablet. Record results.
10. Release artifact generation: signed APK/AAB for internal testing and GitHub Releases.

## Steps

1. Configure release build, signing, and packaging.
2. Add network/backup/screenshot protections.
3. Generate baseline profile and measure startup/size.
4. Run accessibility and device matrix checks.
5. Fix findings and produce release artifacts.

## Acceptance criteria

- Release build is minified, signed through CI secrets, and produces a mapping file.
- Cleartext is limited to localhost/private ranges; certificate validation is intact.
- Keys and databases are excluded from backup.
- Baseline profile is generated and cold-start budget is met or documented.
- TalkBack and keyboard navigation work; touch targets meet 48 dp.
- Manual matrix is recorded with no release-blocking issues.
- Parity is updated where behavior changed.

## Verification

```bash
cd android && ./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
```

## Out of scope

- New features or providers.
- Additional provider tests, Compose UI suite, Roborazzi, final parity (T025).
- Play Store listing and privacy policy content beyond the app itself.
