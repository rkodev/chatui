# M3 Android Depth Implementation Plan

- Source milestone: [M3-android-depth.md](../milestones/M3-android-depth.md)
- Task breakdown: [M3 task list](M3/README.md)
- Authoritative spec: [IMPLEMENTATION.md](../IMPLEMENTATION.md)
- Foundation baseline: [M2 verification evidence](M2/M2-verification.md)
- Current parity: [contracts/parity.md](../../contracts/parity.md)
- Status: Proposed
- Prepared: 2026-11-06
- Scope: Android-only platform completion: secret-storage and transport hardening, intents and links, adaptive shell, lifecycle recovery, Compose/screenshot tests, release configuration, performance, accessibility, device evidence, and parity
- Platforms: Android
- Planning assumption: M2 is complete and is the baseline. Web behavior, provider protocols, Rust, backup/sync, and Play Store publishing are out of scope.

## 1. Summary

M1 and M2 delivered the shared product behavior on Android: OpenRouter and launch providers, streaming, reasoning, persistence, recovery, attachments, parameters, search, export/import, data controls, and settings. M2 closed with 781 Android JVM/Robolectric tests passing and a debug APK of 18,545,791 bytes. It did not use a device or emulator.

M3 is not a feature milestone. It makes the existing Android app platform-complete and release-configurable. The largest missing areas are:

- Inbound share target, `chatui://` deep links, and Custom Tabs for provider documentation.
- An adaptive shell with a permanent sidebar at width >= 840 dp, proper insets, predictive-back ordering, and hardware shortcuts.
- Draft persistence across process death and startup orphan-attachment cleanup.
- Roborazzi screenshots, an instrumentation smoke suite, and device evidence.
- Release minification, shrink resources, mapping artifacts, CI signing, monotonic versioning, baseline profiles, and APK/AAB evidence.
- Device-level proof for the two critical cases: process death preserves partial content (case 26) and backup exclusion prevents key restore (case 27).

The existing foundation is strong but several M3 claims are currently unproven rather than unimplemented. The plan separates "present but unverified" from "missing" so evidence work is not mistaken for feature work.

| Workstream | Current completion | Assessment |
| --- | --- | --- |
| Secret storage and privacy hardening | Partial | Tink/Keystore, ciphertext-only storage, backup flags, and redaction exist; no FLAG_SECURE, no network security config, no device proof for cases 26-27 |
| Platform integration | Partial | Photo picker, SAF, clipboard image paste, drag-and-drop, and copy exist; share target, deep links, Custom Tabs, predictive-back ordering, shortcuts, and tablet shell are missing |
| Lifecycle and recovery | Partial | Recovery service, interrupted notice, 1-second persistence, and regenerate exist; drafts are in-memory and startup does not clean orphan attachments; no process-death evidence |
| UI tests and screenshots | Partial | Broad Robolectric Compose coverage and a 10,000-row test exist; no Roborazzi, no `src/androidTest`, no intent/deep-link tests |
| Performance and release configuration | Missing | Release is unminified and unsigned; no R8 rules, mapping artifact, baseline profile, release CI, startup measurement, or release-size evidence |
| Parity and exit-gate evidence | Missing | No M3 implementation-status section, no M3 verification report, no device matrix, and no case 26-27 evidence |
| M2 carryovers | Partial | Local Ollama cleartext, Anthropic/Gemini discovery without a key, stale onboarding copy, and device-level input checks remain open |

### 1.1 Critical path

```text
M3-01 Secret storage, backup proof, FLAG_SECURE, network security
   |
M3-03 Adaptive shell, insets, predictive back, shortcuts
   |
   +--> M3-02 Share target, deep links, Custom Tabs
   |
   +--> M3-04 Lifecycle, draft persistence, startup cleanup
   |
   +--> M3-06 Release config, signing, baseline profile, performance
   |
M3-05 Compose/screenshot/instrumentation tests
   |
M3-07 Accessibility, device matrix, M2 closeout, parity, exit-gate evidence
```

M3-01 and M3-03 have no dependency on each other, but they both touch the manifest and shell surfaces. Keep manifest edits in M3-01 (security attributes) and M3-02 (intents) and sequence M3-02 after M3-03 to avoid shell merge conflicts. M3-04 is independent of M3-03. M3-05 must follow the UI, intent, and lifecycle work it captures. M3-06 must follow the final app shape so the baseline profile and size measurements are meaningful. M3-07 is the final gate and cannot start until all prior tasks and evidence exist.

### 1.2 Estimate

The milestone target is two weeks. That target assumes a narrow interpretation: secret hardening, intents, screenshots, and a basic adaptive shell. It does not include device-lab turnaround or the first signed/uploaded release.

| Staffing | Estimate | Notes |
| --- | --- | --- |
| One Android implementer | 18-24 working days | The adaptive shell, release pipeline, screenshot suite, and device matrix are too large for one two-week stream |
| Two Android implementers plus a verification owner | 11-14 working days | Recommended split: one on security/intents/lifecycle, one on shell/tests/release, verification owner on device and evidence |
| Two Android implementers | 14-18 working days | No dedicated device owner; release signing and device evidence become the schedule risk |

The largest schedule risks are device availability, CI signing secrets, baseline-profile noise, and the adaptive-shell regression surface.

## 2. Current-State Audit

### 2.1 Reusable M2 assets

| Asset | Current state | M3 impact |
| --- | --- | --- |
| `android/core/datastore/secret/TinkSecretCipher.kt` | Tink AEAD AES-256-GCM with `AndroidKeysetManager` and Android Keystore master key | Production implementation is present; needs device-level proof and rotation decision |
| `android/core/datastore/secret/SecretStore.kt` | Ciphertext-only Room persistence plus `forgetKey` and `forgetAll` | Provider-key rotation and forget paths already tested; no keyset versioning |
| `android/app/src/main/AndroidManifest.xml` | `allowBackup="false"`, explicit backup and data-extraction rules, predictive back enabled | Backup flags are present; network security config, share target, and deep links are absent |
| `android/app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` | Exclude database, DataStore, and secret keyset from backup and device transfer | Static implementation is good; on-device restore proof is missing |
| `android/core/common/Redaction.kt` | Header/text redaction for diagnostics | Reusable for the logging audit; no debug logging interceptor is installed |
| `android/feature/chat/ChatScreen.kt` | Photo picker, SAF document picker, clipboard image paste, drag-and-drop, copy | M2 platform input paths already exist; device evidence is still required |
| `android/feature/chat/ChatMessageActions.kt` and `ChatCodeBlock.kt` | Copy message and exact code text | Meets the clipboard deliverable; keep regression tests |
| `android/feature/chat/ChatRunner.kt` | Stream ownership, 1-second persistence, cancellation, regenerate, retry | Configuration-change survival is architecturally supported; no test proves it |
| `android/core/database/recovery/StreamingRecoveryService.kt` and `AppStartup.kt` | Startup marks `streaming` rows cancelled and preserves partial content | Reuse for case 26; startup does not yet clean orphan attachments |
| `android/feature/chat/ChatDrafts.kt` | Per-conversation in-memory drafts | Wrong lifetime for process death; replace with persistent drafts |
| `android/feature/conversations/ConversationListScreen.kt` | `ConversationDrawer` and grouped history already exist | Drawer exists but production shell still uses bottom navigation |
| `android/feature/chat/ChatMessageList.kt` | Stable keys/content types and hoisted streaming row | Performance requirement is implemented and tested |
| `android/core/database/ChatUiDatabase.kt` | No destructive migration fallback; `DestructiveMigrationGuardTest` proves it | Reuse as release evidence; ensure R8 does not change behavior |
| `android/app/build.gradle.kts` | Debug and release types exist; release is not minified | M3 must configure minify, shrink, signing, mapping, and versioning |
| `.github/workflows/android.yml` | Debug lint, unit tests, assemble, debug APK artifact | No release, screenshot, instrumentation, or device jobs |
| Existing Android tests | 781 tests, including Compose, Room, provider, data, search, export/import, and large-list tests | Reuse as the regression baseline; add M3-specific suites |

### 2.2 Confirmed missing or unproven areas

| Path or capability | Current state | Consequence |
| --- | --- | --- |
| `android/app/src/main/AndroidManifest.xml` | No `networkSecurityConfig`; no share `intent-filter`; no `chatui` deep-link filter | Local cleartext providers do not work on a device; app cannot receive shares or links |
| `android/app/src/main/java/dev/chatui/MainActivity.kt` | No `onNewIntent`, no intent parsing, no pending share/deep-link state | Warm-start intents are dropped; cold-start links cannot route |
| Provider documentation link | `LocalUriHandler` opens the default browser | M3 requires a Custom Tab with a safe fallback |
| `android/app/src/main/java/dev/chatui/shell/ShellScaffold` | Fixed bottom navigation on every width | No permanent sidebar at >= 840 dp; drawer is not used in production |
| Chat layout | `Scaffold` padding and `enableEdgeToEdge()`; no explicit IME inset | Composer may be obscured by the keyboard; safe-drawing behavior is unverified |
| Draft storage | `InMemoryChatDrafts` | Drafts are lost on process death |
| Orphan attachment cleanup | Runs only when a chat ViewModel opens | Orphans survive until a chat is opened; startup cleanup is missing |
| `android/app/build.gradle.kts` | `isMinifyEnabled = false`; no shrink, signing, or mapping | Release is not shippable and has no crash deobfuscation artifact |
| `android/gradle.properties` and `libs.versions.toml` | No baseline profile, Roborazzi, `androidx.browser`, or window-size-class entries | Required M3 dependencies are absent |
| `android/app/src/androidTest/` | Does not exist | `connectedDebugAndroidTest` has no tests and no device evidence |
| Screenshot goldens | None | No light/dark or phone/tablet visual evidence |
| `.github/workflows/android.yml` | Debug-only gates | No signed release, mapping artifact, screenshots, or release artifacts |
| `contracts/parity.md` | M1/M2 implementation status only; M2 lists M3 as missing | M3 cannot claim completion until parity is updated |
| `docs/operations/M3/` | Does not exist | No task records or M3 verification report yet |

### 2.3 Foundation-to-feature gaps that are easy to miss

- The app manifest contains no `android:networkSecurityConfig`, so the local Ollama, LM Studio, and llama.cpp presets cannot reach `http://localhost` on Android even though the adapters and fallback guidance exist. This is an explicit M2 carryover.
- `network_security_config.xml` cannot express CIDR ranges for private IPv4. A config that only lists `localhost` and `127.0.0.1` does not satisfy "private-range hosts" on a real LAN. The enforcement point must be decided before implementation.
- `MainActivity` is not `singleTop`, has no `onNewIntent`, and has no shared intent channel. Adding a share filter without warm-start handling will silently drop shares while the app is running.
- `ConversationDrawer` exists but is unused; the production shell is a bottom-navigation scaffold. A tablet sidebar cannot be added by only changing a width check; navigation and drawer state must move into the shell.
- `ChatDrafts` is in-memory by design. M3 cannot claim draft survival without replacing it or adding a persistent layer, and `AttachmentService.cleanupOrphans` treats `messageId: null` draft rows as orphans, so draft persistence and attachment cleanup must be coordinated.
- The existing startup recovery marks stale `streaming` rows cancelled but does not clean orphan attachment files. Both must run before the shell renders to avoid stale storage.
- The release build is unminified. R8 full mode can break kotlinx.serialization, Room, Hilt, or reflection-based libraries; a release smoke test is required, not just `assembleRelease`.
- The current debug APK is 18,545,791 bytes. It is under the 20 MB debug budget, but the release APK and AAB have never been measured.
- `connectedDebugAndroidTest` and `verifyRoborazziDebug` are in the milestone gate but neither task currently has any tests or configuration. A green command today would be falsely reassuring.
- M2 evidence is JVM/Robolectric only. Photo picker, SAF, clipboard, drag-and-drop, Custom Tabs, notifications, backup restore, and process death are device behaviors and cannot be proven by Robolectric alone.
- The onboarding welcome copy still says other providers "are coming later," which contradicts M2. This is a small test-covered defect, not a new feature.
- `ModelService.refreshModels` passes `apiKey = null`, so Anthropic and Gemini discovery falls back instead of using the stored key. This is an M2 deferral with a target of M3.
- The design system already exposes reduced-motion tokens and the shell already calls `enableEdgeToEdge()`, but there is no UI test proving that motion tokens or insets are applied to the actual screens.

## 3. Gap Analysis

Status values:

- **Missing**: no implementation exists.
- **Partial**: foundation exists but the M3 deliverable is not met or not proven.
- **Present**: complete for M3, subject only to regression verification.
- **Blocked**: cannot be completed until a dependency, decision, or environment is available.

### 3.1 Secret storage and privacy hardening

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| S-01 | Tink `AndroidKeysetManager` uses an Android Keystore master key, AES-256-GCM, and ciphertext-only persistence | Implemented in `TinkSecretCipher.androidKeystore` and `SecretStore`; tests use an in-memory keyset | The production Keystore path has never run on a device or emulator | Add an instrumentation test or documented device procedure: encrypt, kill process, relaunch, decrypt; inspect Room for ciphertext only; M3-01 | Connected test or device log, case evidence |
| S-02 | Keyset is never exported and no plaintext key is stored | Code never exports the keyset; `SecretStore` writes only ciphertext | No static or runtime guard proves no plaintext appears in Room, DataStore, logs, or intents | Add a storage/log/intent audit and a ciphertext-only assertion; M3-01 | Instrumentation + source audit |
| S-03 | Key rotation, "Forget key", and "Forget all keys" are covered by tests | Provider-key rotation and remove/forget flows exist and are tested; `SecretStore` has no Tink keyset rotation or versioning | Ambiguity: the milestone may mean provider-key rotation (present) or keyset rotation (missing). No keyset rewrap/versioning test exists | Confirm the interpretation. Recommended: treat provider-key rotation as the requirement, record keyset rotation as future hardening unless the reviewer requires it; M3-01 | Test-name evidence in task handoff |
| S-04 | Backup exclusion is real and verified on another device | Manifest and both XML rule files exclude database, DataStore, and keyset; `BackupRulesTest` is static | No proof that a backup or device transfer cannot restore keys; case 27 has no evidence | Execute a two-device/emulator backup or transfer test; record no key/provider restore; M3-01/M3-07 | Case 27 evidence |
| S-05 | Optional `FLAG_SECURE` on secret-entry and key-detail screens | No `FLAG_SECURE` usage anywhere | Secret entry, onboarding key form, and provider detail can appear in recents and screenshots | Add a `SecureScreen`/window-flag helper around onboarding key entry and provider key detail/rotation; M3-01 | Robolectric window flag test + device screenshot attempt |
| S-06 | No keys in logs, crash reports, debug overlays, screenshots, or analytics | No logging interceptor; only `MainActivity` logs a startup failure; `Redaction` exists | No crash reporter exists, but there is no automated guard against future raw-header logging; screenshots are not yet protected | Add a redacted debug-logging path only if needed, keep release logging off, audit for raw headers, and use fake keys in screenshots; M3-01/M3-05 | Source audit, redaction tests, screenshot review |
| S-07 | Cleartext is limited to localhost/private-range hosts via network security config | No `networkSecurityConfig`; local HTTP presets are unreachable on device | M2 carrover: local Ollama/LM Studio/llama.cpp fail on Android over HTTP | Add network security config and local-host policy; M3-01 | Device check against local preset |
| S-08 | Cleartext use requires an explicit user warning | Custom provider validation accepts `http://` with no warning | A user can enter an arbitrary cleartext host without informed consent | Add validation/warning for non-HTTPS base URLs and refuse public cleartext; M3-01 | Provider validation/UI tests |
| S-09 | Certificate validation stays enabled | No trust-all manager or custom SSL socket factory | No regression guard | Add a static test that the shared client uses platform defaults and no custom trust manager; M3-01 | Unit/static test |
| S-10 | Optional biometric or device-credential gate | Not implemented | Milestone calls this an enhancement, not a blocker | Record as explicitly deferred; do not include in exit-gate claims; M3-07 | Handoff note |

### 3.2 Platform integration

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| P-01 | `ACTION_SEND` + `text/plain` share target prefills the composer | No share filter, no intent parsing | Shared text is dropped; cold and warm starts are both unsupported | Add manifest filter, intent parser, pending-share state, and navigation; M3-02 | Intent parser tests + device share |
| P-02 | Share target does not lose current drafts | Drafts exist only in memory and are per conversation | A share could overwrite the active composer or lose another draft | Open a new conversation for shared text and preserve existing draft stores, or define an explicit non-destructive merge; M3-02 | Draft-preservation test |
| P-03 | Outbound "Share conversation as text/Markdown" | No share action for a conversation | Spec 9.10 includes outbound sharing; the milestone scope only names the inbound share target | Include a safe text/Markdown share action in M3-02, or record an approved deferral if the reviewer treats 9.10 as future scope | UI test + device share |
| P-04 | Open provider documentation in a Custom Tab | `LocalUriHandler` opens the default browser | M3 requires a Custom Tab with a safe fallback | Add `androidx.browser`, validate the URL from the contract, launch a Custom Tab, fall back to the browser; M3-02 | Unit/Compose test + device check |
| P-05 | Deep link `chatui://chat/<id>` opens the matching conversation | No deep-link filter or URI parser | Deep links do not exist; no cold/warm handling | Add `VIEW`/`BROWSABLE` filter for scheme `chatui`, host `chat`, path prefix; parse and route; M3-02 | Manifest/parser/navigation tests |
| P-06 | Invalid or missing deep-link target shows a safe not-found state | Chat screen has a not-found state for missing conversations | No URI validation or cold-start deep-link path reaches it | Validate the ID, route to the chat destination, and let the existing not-found state render; M3-02 | Deep-link test |
| P-07 | Predictive back ordering: close drawer, sheet, dialog, keyboard, then navigate | Manifest enables predictive back; Compose handles some back events | No single ordered back policy across drawer, sheet, dialog, IME, and navigation | Add ordered `BackHandler`/drawer/sheet state handling in the shell and chat; M3-03 | Compose/back tests + device check |
| P-08 | Hardware keyboard shortcuts for common actions | Composer handles Enter/Shift+Enter only | No shell-level shortcuts for search, new chat, stop, copy, or navigation | Add a shortcut map and root key handler without breaking text-field input; M3-03 | Robolectric key tests |
| P-09 | Tablet/foldable layout with permanent sidebar at >= 840 dp | Shell uses bottom navigation at all widths; `ConversationDrawer` is unused | No width-aware layout, no persistent sidebar, and providers/settings are not reachable from a drawer | Build an adaptive shell: permanent sidebar >= 840 dp, modal drawer below, and preserve access to every destination; M3-03 | Compose tests at phone/tablet widths |
| P-10 | Correct window insets and edge-to-edge behavior | `enableEdgeToEdge()` is called; scaffold uses content padding | No IME padding, no explicit safe-drawing/display-cutout handling, no keyboard-over-composer test | Add `WindowInsets`/`imePadding` at the shell and composer boundaries; M3-03 | Compose/device check |
| P-11 | Photo picker, SAF document picker, clipboard image paste, drag-and-drop | Implemented in M2 with validation and attachment service | Device input behavior and ChromeOS/tablet drag-and-drop are unproven | Reuse implementation; add device evidence in the matrix; M3-07 | Device matrix |
| P-12 | Copy message and code-block text | Implemented and tested | No M3 gap; keep regression coverage | No implementation work; M3-05 | Existing tests |
| P-13 | Android 13+ per-app language only if localization exists | No localization configuration | Out of scope by milestone; do not add translations | Record N/A; M3-07 | Handoff note |

### 3.3 Lifecycle and recovery

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| L-01 | Configuration changes do not lose an active stream or partial content | Stream runs in `ChatRunner` on `viewModelScope`; ViewModel survives activity recreation | No test or device evidence proves recreation keeps the stream and partial text | Add a recreate test with an active fake stream; verify partial content and completion; M3-04 | Robolectric/device test |
| L-02 | Process death during streaming preserves partial content and marks the message interrupted | `ChatRunner` persists about every second; `StreamingRecoveryService` recovers stale `streaming` rows | No real process-death test; up to one second of text can be lost by design | Add a device procedure using `am kill`/`am force-stop` and record partial content plus interrupted state; M3-04/M3-07 | Case 26 evidence |
| L-03 | Interrupted message explains what happened and can be regenerated | `InterruptedMessageNotice` and retry/regenerate paths exist | The recovery-to-regenerate flow is not tested end to end on a recovered message | Add a test that a recovered cancelled message can be regenerated without branching; M3-04 | ViewModel/Compose test |
| L-04 | Drafts survive process death where practical | `InMemoryChatDrafts` is process-local | Drafts are lost on process death | Add a persistent draft store keyed by conversation, restore on open, clear on send/discard; M3-04 | Persistence test + device check |
| L-05 | Navigation location and conversation selection survive process death | Last-active conversation is persisted; Navigation Compose saves back-stack state | No cold-start evidence and no deep-link/onboarding interaction test | Reuse saved state; persist the practical subset and verify cold start; M3-04 | Navigation test |
| L-06 | Startup marks leftover `streaming` messages cancelled and cleans orphan attachments | Message recovery runs in `AppStartup`; attachment cleanup only runs when a chat ViewModel opens | Orphan metadata/bytes remain until a chat is opened; startup cleanup is missing | Extend `AppStartup` to run `AttachmentService.cleanupOrphans` with a startup timestamp; coordinate with draft persistence; M3-04 | Startup/persistence tests |
| L-07 | Background behavior is documented honestly | No documentation | Users and reviewers may assume background streaming is guaranteed | Document foreground streaming, short OS grace period, and foreground-service/WorkManager as enhancements; M3-04/M3-07 | Documentation review |
| L-08 | Do not schedule background sync before backup exists | No WorkManager scheduling exists | No gap | Keep out of scope; assert no background sync jobs are registered; M3-04 | Static test/review |

### 3.4 UI tests and screenshots

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| T-01 | Compose UI tests for onboarding, chat/send/stream, model picker, provider management, settings, and error states | Many Robolectric Compose tests exist for these surfaces | Coverage is split across screen and ViewModel tests; no single route-level send/stream test; new M3 surfaces are untested | Audit existing tests, add missing route-level cases, and keep Robolectric as the CI path; M3-05 | `:app:testDebugUnitTest` |
| T-02 | Roborazzi or equivalent screenshots for key screens in light/dark and phone/tablet | No Roborazzi configuration or goldens | No visual regression evidence | Add Roborazzi to the app module, capture onboarding, chat, model picker, provider list/detail, settings, data, and conversation list in four configurations; M3-05 | `:app:verifyRoborazziDebug` |
| T-03 | Tests for date grouping, search, delete/undo, export/import round trip, invalid imports, clear data, and large conversations | Present in M2 tests | No M3 gap except regression coverage | Keep and rerun; add to the evidence matrix; M3-05/M3-07 | Existing test names |
| T-04 | Share intent parsing and deep-link tests | Missing | New intent behavior is unproven | Add cold/warm share tests, deep-link tests, invalid-ID tests, and draft-preservation tests; M3-02/M3-05 | JVM/Robolectric tests |
| T-05 | Process-death recovery UI test or device script | Core recovery unit tests exist | No UI or device proof | Add a repeatable device script or instrumentation test; M3-04/M3-05 | Case 26 evidence |
| T-06 | Large-conversation test proves virtualization or bounded composition | `ChatMessageListTest` composes fewer than 100 of 10,000 rows | No gap | Keep as a gate; rerun after shell changes; M3-05 | Existing test |
| T-07 | `connectedDebugAndroidTest` has meaningful tests | No `src/androidTest` directory | The milestone command currently has no tests and no device target | Add a small instrumentation suite for intents, FLAG_SECURE, insets/config recreation, and a release smoke test; configure a managed device or document the environment blocker; M3-05/M3-07 | `:app:connectedDebugAndroidTest` |
| T-08 | Accessibility semantics and 48 dp targets pass | Many semantics/touch targets exist; reduced-motion tokens exist | No TalkBack pass, no automated minimum-target audit, no tablet/keyboard accessibility evidence | Add content-description and minimum-target tests, a TalkBack checklist, and a reduced-motion check; M3-05/M3-07 | Tests + manual checklist |
| T-09 | Screenshot and test goldens are free of secrets and personal data | No screenshots yet | Screenshots could accidentally include real keys or conversations | Use fake fixture data only in screenshot tests and review goldens; M3-05 | Review |

### 3.5 Performance and release configuration

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| R-01 | Release build is minified and resource-shrunk | `isMinifyEnabled = false`; no shrink | Release is not optimized and mapping is unavailable | Enable R8 minify and shrink resources for release; M3-06 | `:app:assembleRelease` |
| R-02 | ProGuard/R8 rules preserve Room, Hilt, serialization, and Compose behavior | No `proguard-rules.pro` or consumer rules | Minified release may crash at runtime | Add and test keep rules; run a minified release smoke test; M3-06 | Release smoke test |
| R-03 | Mapping artifact exists for crash deobfuscation | No mapping because release is unminified | No deobfuscation evidence | Produce `android/app/build/outputs/mapping/release/mapping.txt` and upload it; M3-06 | Artifact check |
| R-04 | Destructive Room migration is forbidden in release | No fallback configured; `DestructiveMigrationGuardTest` proves failure on a newer schema | Need to confirm the guard survives minification and release packaging | Re-run the guard in release-test or device smoke; M3-06 | Existing test + release check |
| R-05 | Signing policy uses CI secrets and no committed keystore | No signing config; no keystore committed | Release cannot be signed and no policy exists | Add env-driven signing config, fail release when secrets are absent, and document secret names; M3-06/M3-07 | CI release job |
| R-06 | Monotonically increasing `versionCode` and semantic `versionName` | Fixed `versionCode = 1`, `versionName = "0.1.0"` | No release versioning policy | Derive `versionCode` from a monotonic source (for example git commit count or an explicit CI value) and `versionName` from a semantic tag; M3-06 | Build metadata check |
| R-07 | Baseline profile generated and startup verified | No baseline profile plugin, module, or `baseline-prof.txt` | No startup optimization or measurement | Add baseline profile generation, install the profile, and measure cold start; M3-06 | Baseline task + measurement |
| R-08 | Cold start under 1.5 s on a mid-tier device, or documented | Never measured | Budget is unknown | Measure cold start with a recorded device, build type, and method; M3-06/M3-07 | Startup report |
| R-09 | LazyColumn stable keys/content type and no history recomposition during streaming | Implemented and tested | No gap | Keep and rerun after shell changes; M3-06 | Existing tests |
| R-10 | Image decode sizing and Coil thumbnail caching | Message, composer, and Markdown images use `BitmapFactory` full-size decodes; Coil is only an app dependency | Large images can cause memory spikes; Coil is not used for chat thumbnails | Decode at target size or use Coil with request sizing and caching; M3-06 | Unit/instrumentation memory check |
| R-11 | Debug APK under 20 MB and release APK under 15 MB if feasible; record actuals | Debug APK is 18,545,791 bytes; release APK/AAB not built | Release size unknown | Measure both after R8; record actuals and document any miss; M3-06 | Size report |
| R-12 | Release artifacts: signed APK/AAB for internal testing and GitHub Releases | No release workflow or artifacts | No distributable artifact | Add a CI release job and a tag-driven GitHub Release; M3-06 | CI artifacts |
| R-13 | Accessibility baseline: Material 3 semantics, content descriptions, 48 dp targets, TalkBack, reduced motion, optional dynamic color | Partial: many semantics and 48 dp targets; reduced-motion tokens; no dynamic color | No TalkBack pass, no dynamic color, no comprehensive audit | Run the M3 accessibility baseline; dynamic color remains optional; M3-07 | Checklist + tests |
| R-14 | Manual device matrix: Android 8, 12, 14, current, low-RAM, tablet | No device or emulator evidence | Entire matrix is missing; M2 used JVM only | Execute and record each device/emulator, OS, build, results, and blockers; M3-07 | Device matrix report |

### 3.6 Parity, evidence, and M2 carryovers

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| V-01 | `contracts/parity.md` records Android share target, platform-specific rows, and behavior changes | M1/M2 status only; M3 is listed as not delivered | M3 cannot claim completion | Add M3 implementation status and update rows for share target, deep links, adaptive layout, and lifecycle behavior; M3-07 | `make contracts-check` + review |
| V-02 | Case 26 evidence: process death preserves partial content | Unit recovery tests only | No device evidence | Record the kill/relaunch procedure, partial text, interrupted state, and regenerate result; M3-04/M3-07 | M3 verification report |
| V-03 | Case 27 evidence: backup exclusion prevents key restore | Static backup-rule test only | No second-device or transfer evidence | Record backup/transfer and restore result on a second target; M3-01/M3-07 | M3 verification report |
| V-04 | M1/M2 regression evidence after M3 changes | M2 runs are green; M3 has not run | Regression status unknown | Rerun the full Android suite and the M1/M2 critical-case suites; M3-07 | Command evidence |
| V-05 | Local Ollama over cleartext HTTP | M2 deferral; no network security config | Local provider cannot work on device | Close through S-07/S-08 and a device check; M3-01/M3-07 | Device preset check |
| V-06 | Anthropic and Gemini discovery without a stored key | M2 deferral; `ModelService` passes `apiKey = null` | Discovery falls back even when a key exists | Pass the stored key for key-protected native catalogs, or record an approved deferral with a test; small M2 defect, M3-07 | Provider discovery tests |
| V-07 | Stale M1 onboarding copy | Welcome text says non-OpenRouter providers are "coming later" | Contradicts M2 behavior and misleads users | Fix copy and add a test; small M2 defect, M3-07 | Compose copy test |
| V-08 | Android math typesetting | Standalone math renders as bounded raw source; inline math is raw text | This is the documented safe fallback, not typeset math | Accept the fallback for M3 unless scope changes; record in parity/evidence; M3-07 | Handoff note |
| V-09 | Device-level input and visual checks for attachments and data screens | M2 used JVM tests only | Device behavior is unproven | Cover in the device matrix; M3-07 | Device matrix |
| V-10 | M3 verification report and milestone status | `docs/operations/M3/` does not exist | No exit-gate evidence | Create `M3-verification.md`, record commands and manual checks, and update the milestone status; M3-07 | Report review |

### 3.7 Cross-cutting gaps and decisions

| ID | Gap | Impact | Proposed resolution |
| --- | --- | --- | --- |
| X-01 | Share-target draft semantics are undefined | A share can overwrite work or silently change conversations | Decide: open a new conversation with shared text (recommended) or merge into the active composer only when empty; test both cold and warm starts |
| X-02 | Deep-link trust boundary is undefined | A malformed or hostile link could route to stale state or bypass onboarding | Validate scheme/host/path and ID; never include secrets; if onboarding is incomplete, finish onboarding before routing |
| X-03 | Android network security config cannot express private IPv4 CIDR ranges | The literal "localhost/private ranges" requirement is not fully expressible in XML | Decide whether to allow only loopback/emulator/`.local` hosts or enforce an RFC1918 allowlist in the app network layer with base cleartext enabled; document the trade-off |
| X-04 | Removing bottom navigation can regress M2 navigation | Providers, settings, and data screens may become unreachable | Make the adaptive shell preserve every destination and lock it with navigation tests |
| X-05 | Predictive-back ordering spans Compose and Android components | Back may close the wrong layer or exit the app | Use one ordered back policy and test drawer, sheet, dialog, IME, and navigation cases |
| X-06 | Release signing secrets may not exist in the repository or CI | Release gate cannot be met | Provision CI secrets; fail the release job when absent; never substitute a debug key |
| X-07 | Baseline profiles and cold-start budgets are noisy | A single measurement can overclaim performance | Record device, OS, build type, run count, and median; use the same methodology across runs |
| X-08 | Screenshot goldens are sensitive to fonts and rendering | Roborazzi can be flaky across hosts | Pin SDK/qualifiers, use Robolectric, commit goldens, and run on the CI image |
| X-09 | The device matrix requires hardware or managed devices | Evidence may block for environment reasons | Prefer a managed-device or physical-device plan early; record blockers precisely instead of claiming a pass |
| X-10 | Android background restrictions can interrupt streams | Users may expect unlimited background streaming | Document the limitation and treat a foreground service as an enhancement, not a guarantee |
| X-11 | M2 deferrals can expand M3 beyond platform depth | Scope slip | Limit M2 reopenings to V-05/V-06/V-07 and test-covered defects |
| X-12 | Robolectric coverage is not device coverage | Some platform behavior cannot be proven on the JVM | Keep Robolectric as the CI baseline and add a small `androidTest` suite plus the manual matrix |
| X-13 | R8 can break serialization or reflection | Minified release may fail at runtime | Add keep rules and run a minified release smoke test before claiming release readiness |
| X-14 | Release artifacts may exceed the size budget | 15 MB release APK is a soft target | Measure, record actuals, and document any miss with the cause |

## 4. Proposed Work Breakdown

Seven task groups are proposed. They map to the milestone's suggested slices and keep one primary area per task. The executable, ordered task records are in the [M3 task list](M3/README.md), which splits each group into smaller dependent tasks.

### 4.1 Task map

| Wave | ID | Title | Owns | Depends on | Parallel-safe with |
| --- | --- | --- | --- | --- | --- |
| 1 | M3-01 | Secret storage, backup proof, FLAG_SECURE, and network security | `android/core/datastore/secret/`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/xml/`, onboarding/provider key screens, secret/network tests | none | M3-03, M3-04 |
| 1 | M3-03 | Adaptive shell, insets, predictive back, and hardware shortcuts | `android/app/src/main/java/dev/chatui/shell/`, `android/feature/conversations/`, `android/core/designsystem/` layout helpers, shell tests | none | M3-01, M3-04 |
| 1 | M3-04 | Lifecycle, persistent drafts, startup cleanup, and background documentation | `android/feature/chat/ChatDrafts.kt`, `android/core/datastore/`, `android/feature/chat/`, `AppStartup`, recovery/draft tests | none | M3-01, M3-03 |
| 2 | M3-02 | Share target, deep links, Custom Tabs, and outbound share | `android/app/src/main/AndroidManifest.xml` (intent filters), `MainActivity`, shell intent handling, provider docs link, intent/link tests | M3-03 | M3-04 |
| 3 | M3-05 | Compose UI depth, Roborazzi screenshots, instrumentation smoke tests | `android/app/src/test/`, `android/app/src/androidTest/`, app screenshot configuration, test dependencies | M3-01, M3-02, M3-03, M3-04 | M3-06 |
| 3 | M3-06 | Release build, signing, baseline profile, performance, and artifacts | `android/app/build.gradle.kts`, `android/build-logic/`, `android/gradle/`, baseline-profile module, `.github/workflows/android.yml` release job, size/startup evidence | M3-03, M3-04 | M3-05 |
| 4 | M3-07 | Accessibility, device matrix, M2 closeout, parity, and exit-gate evidence | `contracts/parity.md`, `docs/operations/M3/M3-verification.md`, milestone status, device evidence, M2 carryover fixes | all prior tasks | none |

### 4.2 M3-01: Secret storage, backup proof, FLAG_SECURE, and network security

Goal: make the Android secret and transport paths release-safe and verifiable.

Deliverables:

- Device or emulator test proving Tink/Android Keystore encrypt/decrypt survives process restart and that Room contains ciphertext only.
- FLAG_SECURE on onboarding key entry and provider key detail/rotation.
- Network security config plus a local-host cleartext policy and explicit user warning.
- Two-target backup/device-transfer test proving keys and secret stores do not restore.
- Static guard that certificate validation is intact and no raw-header logging interceptor exists.
- Decision record for provider-key rotation versus Tink keyset rotation; map existing tests.

Acceptance criteria:

- Keys are never readable in plaintext from Room, DataStore, logs, or screenshots.
- `allowBackup="false"` and both rule files remain present and are proven on device.
- Cleartext is refused for public hosts and allowed only for the approved local-host policy with a warning.
- Existing provider-key rotation and forget tests remain green.
- No biometric gate is claimed as delivered.

Verification:

```bash
make android-lint
make android-test
make android-assemble
cd android && ./gradlew :app:connectedDebugAndroidTest
```

Plus the case 27 backup evidence and a local-provider device check.

Out of scope: backup/sync features, biometrics, and keyset rotation for old ciphertext unless the decision confirms it.

### 4.3 M3-03: Adaptive shell, insets, predictive back, and hardware shortcuts

Goal: make the shell correct on phones, tablets, foldables, and hardware keyboards.

Deliverables:

- Adaptive shell with a permanent sidebar at width >= 840 dp and a modal drawer below that threshold.
- Every existing destination remains reachable; bottom-navigation behavior is either preserved deliberately or replaced without regression.
- Explicit `WindowInsets` handling for system bars, display cutout, and IME; composer stays above the keyboard.
- Ordered predictive-back policy for drawer, sheet, dialog, keyboard, and navigation.
- Hardware shortcut handler for common actions with a discoverable help surface.

Acceptance criteria:

- Width 839 dp and 840 dp render the intended layouts in Compose tests.
- Drawer and sidebar expose conversations, search, new chat, providers, and settings.
- Back closes the topmost layer before navigating away.
- Shortcuts do not steal text-field input or break Enter-to-send.
- Existing M1/M2 navigation tests remain green.

Verification:

```bash
make android-lint
make android-test
make android-assemble
```

Plus Robolectric qualifier tests and a tablet/foldable device check.

Out of scope: new conversation features and web/Rust keyboard behavior.

### 4.4 M3-02: Share target, deep links, Custom Tabs, and outbound share

Goal: make Android intents and links first-class without losing drafts.

Deliverables:

- `ACTION_SEND` + `text/plain` share filter and cold/warm intent parsing.
- Pending-share state and navigation that preserves existing drafts.
- `chatui://chat/<id>` deep link with validated routing and safe not-found behavior.
- Custom Tab for provider documentation with a browser fallback.
- Outbound conversation share as text/Markdown if confirmed in scope.
- Intent, link, and malformed-input tests.

Acceptance criteria:

- Sharing text while the app is running and after a cold start pre-fills the expected composer.
- No existing draft is overwritten or silently discarded.
- A valid deep link opens the matching conversation; an invalid or missing one shows a safe state.
- No secret or prompt content appears in an intent, URL, or log.
- Custom Tab opens valid contract documentation URLs and falls back safely.

Verification:

```bash
make android-lint
make android-test
make android-assemble
cd android && ./gradlew :app:connectedDebugAndroidTest
```

Plus `adb shell am start` share and deep-link checks on a device or emulator.

Out of scope: HTTPS app links, Play Store app-link verification, and inbound file shares.

### 4.5 M3-04: Lifecycle, persistent drafts, startup cleanup, and background documentation

Goal: make configuration changes, process death, and startup recovery predictable.

Deliverables:

- Persistent per-conversation drafts with clear-on-send/discard and stale-draft cleanup.
- Startup orphan-attachment cleanup coordinated with draft attachment semantics.
- Configuration-change test proving an active stream and partial content survive.
- Device process-death procedure for case 26 and recovered-message regenerate.
- Navigation/last-active restore verification.
- Background behavior documentation that does not overpromise.

Acceptance criteria:

- Activity recreation does not interrupt or lose the active stream.
- Process death mid-stream leaves partial content, marks the message interrupted, and allows regenerate.
- Drafts and the last active conversation survive process death where practical.
- Startup removes orphan metadata and bytes without deleting newly added attachments.
- No background sync job is scheduled.

Verification:

```bash
make android-test
make android-assemble
cd android && ./gradlew :app:connectedDebugAndroidTest
```

Plus the case 26 device/emulator procedure.

Out of scope: foreground-service streaming, WorkManager sync, and guaranteed background survival.

### 4.6 M3-05: Compose UI depth, Roborazzi screenshots, and instrumentation smoke tests

Goal: provide repeatable UI and visual evidence for the M3 surfaces.

Deliverables:

- Compose tests for the M3 additions: share, deep links, drawer/sidebar, shortcuts, drafts, and error states.
- Route-level send/stream test through the ViewModel and screen with a fake adapter.
- Roborazzi screenshot goldens in light/dark and phone/tablet for onboarding, chat, model picker, providers, settings, data, and conversation list.
- `src/androidTest` smoke suite for intents, FLAG_SECURE, configuration recreation, and a minified release smoke case.
- Screenshot and test data that use fake keys and fake conversations only.

Acceptance criteria:

- `:app:verifyRoborazziDebug` passes on a clean checkout.
- Every required screen has phone/tablet and light/dark evidence.
- Intent and deep-link parsing tests cover cold, warm, invalid, and draft-preservation cases.
- The 10,000-row test still passes.
- Instrumentation tests run on the configured device/emulator, or the environment blocker is recorded precisely.

Verification:

```bash
make android-lint
make android-test
cd android && ./gradlew :app:verifyRoborazziDebug :app:connectedDebugAndroidTest
```

Out of scope: the full device matrix, web screenshots, and Play Store screenshots.

### 4.7 M3-06: Release build, signing, baseline profile, performance, and artifacts

Goal: produce a minified, signed, measured, and distributable Android build.

Deliverables:

- R8 minification and resource shrinking with the required keep rules.
- Env-driven release signing using CI secrets only.
- Monotonic `versionCode` and semantic `versionName`.
- Mapping artifact upload and a release smoke test.
- Baseline profile generation, installation, and cold-start measurement.
- Image decode/Coil sizing work and APK/AAB size measurements.
- CI release job producing signed APK/AAB, mapping, screenshot report, and checksums.
- Tag-driven GitHub Release assets.

Acceptance criteria:

- No keystore or password is committed.
- `assembleRelease` and `bundleRelease` pass; the release APK launches and completes a smoke flow.
- Mapping artifact exists and is uploaded.
- Baseline profile is generated; cold start is measured under the recorded budget or documented with the miss.
- Debug APK is <= 20 MB; release APK <= 15 MB or the actual is recorded with a cause.
- Release CI fails rather than signing with a debug key when secrets are absent.

Verification:

```bash
make android-lint
make android-test
make android-assemble
cd android && ./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
```

Plus the baseline profile task, startup measurement, size report, and release smoke run.

Out of scope: Play Store listing, data-safety declarations, deployment, and Rust packaging.

### 4.8 M3-07: Accessibility, device matrix, M2 closeout, parity, and exit-gate evidence

Goal: prove the M3 exit gate and leave a truthful record.

Deliverables:

- Accessibility baseline: semantics, content descriptions, 48 dp targets, reduced motion, TalkBack pass, optional dynamic color.
- Manual device matrix: Android 8, 12, 14, current, one low-RAM device, and one tablet, with results and blockers.
- M2 closeout: local Ollama cleartext, Anthropic/Gemini discovery, stale onboarding copy, math fallback acceptance, and device input checks.
- `contracts/parity.md` M3 status and any platform-specific rows.
- `docs/operations/M3/M3-verification.md` with command results, critical cases 26-27, matrix, and residual risks.
- Milestone status and task index updates.

Acceptance criteria:

- Case 26 and case 27 have recorded device evidence.
- M1/M2 regression suites pass or have an explicit approved exception.
- No release-blocking device issue remains, or it is recorded as a blocker and the milestone is not marked complete.
- Parity is truthful about share target, deep links, adaptive layout, math fallback, and local cleartext.
- Accessibility baseline is recorded; dynamic color and biometrics are not overclaimed.
- The milestone report contains no keys, prompts, conversation content, or personal data.

Verification:

```bash
make contracts-check
make android-lint
make android-test
make android-assemble
cd android && ./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
cd android && ./gradlew :app:connectedDebugAndroidTest :app:verifyRoborazziDebug
```

Plus the manual device matrix, backup/restore test, and baseline-profile/startup report.

Out of scope: the full M4 cross-platform accessibility audit, Play Store publishing, backup/sync, and managed hosting.

## 5. Decisions To Confirm

| Decision | Recommended choice | Why it matters |
| --- | --- | --- |
| Share-target draft behavior | Open a new conversation with the shared text; never overwrite an existing draft | Deterministic and meets "without losing current drafts"; confirm if users expect the active conversation instead |
| Deep link during onboarding | Complete onboarding first, then route to the linked conversation if it exists | Avoids bypassing BYOK setup and avoids a not-found flash |
| Phone navigation model | Permanent sidebar >= 840 dp; modal drawer below; move providers/settings into the drawer or keep a deliberate compact navigation path | The current bottom navigation does not satisfy the spec; removing it without a replacement regresses M2 |
| Cleartext private-range policy | Approve a single, tested policy: either loopback/emulator/`.local` only, or base cleartext enabled with a central RFC1918 allowlist and warning | Android XML cannot express private IPv4 CIDR ranges; the decision must be explicit |
| Outbound conversation share | Include text/Markdown share in M3-02 unless the reviewer declares it future scope | Spec 9.10 lists it, but the milestone deliverable list names only the inbound share target |
| Keyset rotation | Treat provider-key rotation as the milestone requirement; defer Tink keyset rewrap | Avoids scope creep while keeping old ciphertext readable |
| Baseline-profile target | Use a mid-tier physical device if available; otherwise use a recorded emulator profile | Startup budgets are meaningless without hardware and methodology |
| Instrumentation environment | Add a managed device or physical device early; if unavailable, record the gate as blocked | `connectedDebugAndroidTest` cannot pass without a device target |
| Signing secrets | Provision CI secrets before M3-06 completion; fail release when absent | Never substitute a debug key or commit a keystore |
| Dynamic color and biometrics | Optional/deferred; do not claim them as M3 deliverables | The milestone explicitly marks biometrics as an enhancement and dynamic color as optional |

## 6. Verification and Exit Gate

### 6.1 Required commands

```bash
make contracts-check
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
cd android && ./gradlew :app:connectedDebugAndroidTest :app:verifyRoborazziDebug
```

Plus the baseline-profile generation/verification task, the startup measurement, the APK/AAB size report, and the manual device matrix.

### 6.2 Evidence matrix

| Exit-gate requirement | Evidence |
| --- | --- |
| Release build minified, signed through CI secrets, mapping artifact, no committed keystore | M3-06 CI logs, `mapping.txt`, signed APK/AAB checksums, secret audit |
| Keys encrypted with Tink/Android Keystore, excluded from backup, verified not to restore | M3-01 device test + case 27 |
| Cleartext limited to approved local hosts; certificate validation intact | Network security config, validation tests, local-provider device check |
| Share target, photo/document pickers, drag-and-drop, clipboard paste, deep links, predictive back work | M3-02/M3-03 tests + device matrix |
| Configuration changes and process death preserve or clearly interrupt streams | M3-04 recreation test + case 26 |
| Compose UI and Roborazzi suites pass for required screens | M3-05 test report + screenshot goldens |
| Baseline profile generated and cold-start budget met or documented | M3-06 baseline task + startup report |
| TalkBack and 48 dp targets pass; reduced motion respected | M3-07 accessibility checklist + tests |
| Manual device matrix recorded with no release-blocking issues | M3-07 device matrix |
| Critical cases 26-27 and M1/M2 regressions have evidence | M3-07 verification report |
| `contracts/parity.md` reflects delivered Android behavior | Parity diff + `make contracts-check` |

### 6.3 Device matrix template

| Device / emulator | OS | Form factor | RAM | Build | Attachment picker | SAF | Clipboard image | Drag-and-drop | Share | Deep link | Process death | Backup restore | Result |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Android 8 target | API 26 | Phone | Low/mid | Release |  |  |  |  |  |  |  |  |  |
| Android 12 target | API 31 | Phone | Mid | Release |  |  |  |  |  |  |  |  |  |
| Android 14 target | API 34 | Phone | Mid | Release |  |  |  |  |  |  |  |  |  |
| Current target | Latest stable | Phone | Mid | Release |  |  |  |  |  |  |  |  |  |
| Low-RAM target | Current stable | Phone | Low | Release |  |  |  |  |  |  |  |  |  |
| Tablet target | Current stable | Tablet | Mid | Release |  |  |  |  |  |  |  |  |  |

Record the actual hardware, build number, test date, and any blocker. Do not record real API keys, prompts, or conversation content.

## 7. Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Process death is hard to reproduce and easy to overclaim | Case 26 may be marked complete without proof | Require a device procedure with logs and partial-content evidence; make M3-04 responsible for a repeatable script |
| Backup restore cannot be proven on one device | Case 27 remains unproven | Use two targets or a documented device-transfer test; never rely only on manifest flags |
| Android NSC cannot express private CIDR | Local LAN providers may remain unsupported or require a broader cleartext policy | Decide the policy in section 5; document the limitation and test the chosen behavior |
| Adaptive shell regresses M2 navigation | Providers/settings become unreachable | Lock every destination with navigation tests and a device smoke check |
| R8 breaks serialization or Room | Release crashes only after minification | Add keep rules, run a minified release smoke test, and test `assembleRelease` before the final gate |
| Signing secrets are unavailable | Release gate blocked | Provision CI secrets early; fail the job instead of using debug signing |
| Baseline profile and cold-start measurements are noisy | Budget may be misreported | Record device, OS, build, run count, median, and method; use the same procedure for every run |
| Screenshot goldens are flaky across hosts | Roborazzi verification fails intermittently | Pin SDK/qualifiers, use fake data, commit goldens, and run in the CI image |
| Device matrix hardware is unavailable | M3 cannot close | Start device access early; use managed devices or physical devices; record blockers honestly |
| Background streaming is interrupted by the OS | Users lose long responses | Document foreground-only behavior and treat a foreground service as an enhancement |
| M2 deferrals expand M3 | Schedule slip | Limit reopenings to V-05/V-06/V-07 and test-covered defects |
| Release artifacts exceed size budgets | Release gate partially missed | Measure after R8, record actuals, and document the cause rather than hiding the miss |

## 8. Out of Scope

- New product features, provider protocols, or cross-platform behavior.
- Web PWA, deployment, and web accessibility/performance audit (M4).
- Rust packaging and release (M3.5).
- Backup/sync and managed hosting (M5, M6).
- Play Store publishing, store listing, data-safety declarations, and a full privacy policy.
- Localization and translations.
- Biometric or device-credential key gate (optional enhancement).
- Full cross-platform release audit (M4).
- Guaranteed background streaming or background sync before backup exists.

## 9. Definition of Done

M3 is complete only when:

1. Every task M3-01 through M3-07 is complete or has an approved, recorded deferral.
2. The release build is minified, signed through CI secrets, and produces a mapping artifact with no committed keystore.
3. Cases 26 and 27 have device-level evidence in `docs/operations/M3/M3-verification.md`.
4. The device matrix is recorded with no release-blocking issue.
5. `:app:verifyRoborazziDebug`, `:app:connectedDebugAndroidTest`, `lintRelease`, `assembleRelease`, and `bundleRelease` pass or have a recorded environment blocker.
6. Cold start and APK/AAB sizes are measured and either meet the budgets or document the miss.
7. Accessibility baseline and TalkBack results are recorded.
8. `contracts/parity.md` reflects the delivered Android behavior and `make contracts-check` passes.
9. The milestone status table is updated and the follow-up M2 deferrals are closed or explicitly carried with an owner.

## 10. Open Questions

- Does the milestone interpret "key rotation" as provider API key rotation (already present) or Tink keyset rotation (not present)?
- Should outbound conversation sharing be included now, or is only the inbound share target in scope?
- Is the adaptive shell allowed to remove bottom navigation, or must it preserve the current bottom-navigation path alongside the sidebar/drawer?
- Which cleartext policy is acceptable for private LAN IPs given the Android network-security-config limitation?
- Are CI signing secrets and a device/emulator environment available for M3, or must those gates be recorded as blocked?
- Is a `.local` hostname or an explicit LAN IP the supported local-provider workflow for release builds?
