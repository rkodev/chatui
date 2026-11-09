# M3 Verification Evidence

- Status: Baseline recorded 2026-11-06; M3 complete 2026-11-10. Sections 9-23 record task evidence; section 22 records the device matrix and the fixed deep-link defect, section 23 closes the M2 carryovers, and section 24 records the final exit gate.
- Owner: M3-00.1 recorded the preflight environment and baseline.
- Scope: Android M3 preflight, baseline commands, measurements, and environment blockers.
- Source plan: [M3 Android Depth Implementation Plan](../M3-android-depth-implementation-plan.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)

This file is the shared evidence location for the M3 exit gate. Do not mark a
row passed until the command or manual check has actually run. Record only
status codes, headers, timestamps, test names, sizes, and conclusions. Never
record an API key, a real prompt, a real conversation, signing material, or
personal data.

## 1. M2 entry criteria

M2 closed on 2026-11-06. The final exit gate passed with no blocker; see
[M2 verification](../M2/M2-verification.md) sections 10-13 and
[`contracts/parity.md`](../../../contracts/parity.md).

M2 baseline carried into M3:

- Android JVM/Robolectric tests: 781 tests, 0 failures, 0 errors, 0 skipped.
- Debug APK: 18,545,791 bytes at M2 close.
- No device or emulator was used for M2 evidence.

M2 deferrals that target M3 (from M2 verification section 11 and parity):

| Item | M3 impact |
| --- | --- |
| Android local Ollama over cleartext HTTP | Needs a network security config; tracked by M3-01.4/M3-01.5. |
| Android Anthropic and Gemini discovery without a key | Follow-up owner; target M3. |
| Android math typesetting | Standalone math renders as bounded raw source; M3 depth item. |
| Android device-level input and visual checks | Photo picker, SAF, clipboard paste, drag-and-drop, and visual screens need device evidence; M3-07.3. |
| Web case 23 automated coverage | Web gap only; Android has an automated equivalent. Not M3 Android work. |
| Live provider smoke and valid-key success | M4 release check. |
| Local and Custom CORS | M4 release check. |

Section 23 records the M3-07.4 closeout for these items: the local cleartext,
Anthropic/Gemini discovery, math fallback, and device-input items are closed or
explicitly deferred with an owner.

## 2. Verification environment

Recorded 2026-11-06 from the repository root.

| Item | Value |
| --- | --- |
| Host | Linux x86_64, kernel 7.0.0-42-generic (Ubuntu) |
| JDK | OpenJDK 21.0.9 (Temurin 21.0.9+10-LTS) |
| Gradle | 9.8.1 (Kotlin 2.4.10) |
| Android SDK | Present at `$HOME/Android/Sdk`; `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset |
| Android platforms | android-28, 29, 30, 31, 33, 34, 35, 36, 37.0 |
| Build tools | 34.0.0, 35.0.0, 36.0.0, 36.1.0 |
| adb | Present at `$HOME/Android/Sdk/platform-tools/adb`; not on `PATH` |
| emulator | Present at `$HOME/Android/Sdk/emulator/emulator`; not on `PATH` |
| AVDs | `Medium_Phone_API_36.1`, `Medium_Tablet`, `Pixel`, `Pixel_9_Pro_XL`, `Small_Phone`, `Small_Phone_2`, `Wear_OS_Small_Round` |
| Connected devices | None (`adb devices` listed no devices) |
| Node.js | 22.21.1 (contract tooling) |

Environment note: the Android Gradle build fails with `SDK location not found`
unless `ANDROID_HOME` is set. The baseline commands below were run with
`ANDROID_HOME=$HOME/Android/Sdk`. No `android/local.properties` was created and
no repository file was changed to work around this.

## 3. Baseline command results

Commands ran from the repository root on 2026-11-06.

| Command | Result | Bounded evidence |
| --- | --- | --- |
| `make contracts-check` | Pass | `Contract validation passed.` |
| `make android-lint` | Pass | `BUILD SUCCESSFUL in 2m 46s`; 439 actionable tasks. |
| `make android-test` | Pass | `BUILD SUCCESSFUL in 1m 44s`; 781 tests, 0 failures, 0 errors, 0 skipped. |
| `make android-assemble` | Pass | `BUILD SUCCESSFUL in 12s`; debug APK produced. |

`make contracts-check` first failed with `Cannot find package 'ajv'` because the
contract tooling dependencies were not installed. `make contracts-install`
installed them (`scripts/node_modules`, gitignored), and the check then passed.
This is an environment setup step, not a product failure.

## 4. Baseline measurements

| Measurement | Value | Source |
| --- | --- | --- |
| Android unit tests | 781 tests, 0 failures, 0 errors, 0 skipped | Aggregated `android/**/build/test-results/*/*.xml` after `make android-test` |
| Debug APK size | 18,693,445 bytes | `ls -l android/app/build/outputs/apk/debug/app-debug.apk` |
| M2 debug APK size | 18,545,791 bytes | M2 verification section 1 |

The debug APK grew by 147,654 bytes since M2 close. It remains under the 20 MB
debug budget noted in the M3 plan.

## 5. Device and emulator availability

| Check | Result |
| --- | --- |
| `command -v adb` | Not on `PATH`; binary present in the SDK |
| `adb devices` | No devices attached |
| `command -v emulator` | Not on `PATH`; binary present in the SDK |
| `emulator -list-avds` | Seven AVDs listed, including phone and tablet images |

No device is connected. Emulator binaries and AVDs are available but were not
booted for this preflight. Device-dependent M3 tasks (M3-07.3 device matrix,
case 26 process death, case 27 backup restore, and any instrumentation run) are
not blocked by missing emulator images, but they still require a booted
emulator or physical device at execution time.

## 6. Release environment availability

Recorded without printing any secret value.

| Item | Status | Evidence |
| --- | --- | --- |
| CI signing secrets | unavailable | `.github/workflows/android.yml` has no signing configuration or secret references. |
| Keystore path outside the repository | available | A `.jks` file exists outside the repository; it was not opened or verified as the release keystore. |
| Baseline-profile / benchmark device or emulator | available | Emulator binary and AVDs are present; no device was booted. |

## 7. Blockers and owners

| Blocker | Impact | Owner / target |
| --- | --- | --- |
| `ANDROID_HOME` unset and SDK tools not on `PATH` | Android Gradle commands fail until the environment is configured. Workaround: set `ANDROID_HOME=$HOME/Android/Sdk`. | Developer environment; M3-00.2 or release CI setup |
| No connected device or booted emulator | Device-level evidence cannot be produced yet. Emulator images exist, so this is a scheduling step, not a missing asset. | M3-07.3 device matrix owner |
| CI signing secrets unavailable | Signed release builds and M3-06.2/M3-06.4 cannot be verified in CI yet. | M3-06.2 / M3-06.4 owner |
| Release keystore not verified | A `.jks` exists outside the repository but its purpose and credentials are unverified. | M3-06.2 owner |

No blocker prevents the M3 implementation tasks from starting. The signing and
device items become blocking only for the release and device-evidence tasks
that depend on them.

## 8. Redaction review

- No API key, prompt, conversation content, personal data, or signing material
  is recorded in this file.
- The keystore check records only that a path exists outside the repository; no
  path, alias, or password is recorded.
- Test names, sizes, and command output are non-sensitive.

## 9. M3-01.1 Android Keystore and ciphertext-only proof

Recorded 2026-11-06 on `Medium_Phone_API_36.1(AVD) - 16` (API 36), debug build.
Only the synthetic sentinel `m3-sentinel-not-a-real-key` was used.

### 9.1 Automated instrumentation proof

Command:

```bash
cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest
```

Result: `BUILD SUCCESSFUL`; 6 tests, 0 failures, 0 errors, 1 skipped. The skipped
test is the manual two-phase restart proof described in section 9.2.

| Test | Result | What it proves |
| --- | --- | --- |
| `KeystoreProofTest.secondCipherInstanceDecryptsAndroidKeystoreCiphertext` | Pass | `TinkSecretCipher.androidKeystore` encrypts the sentinel and a second cipher instance decrypts it. |
| `KeystoreProofTest.roomStoresOnlyCiphertextForSentinel` | Pass | `SecretStore` writes a `SecretEntity` whose `ciphertext` differs from and does not contain the sentinel. |
| `KeystoreProofTest.keysetPreferencesDoNotContainSentinel` | Pass | `shared_prefs/chatui_secret_keyset_prefs.xml` exists and its bytes do not contain the sentinel. |

### 9.2 Process-restart procedure and result

The restart proof is deliberately separate from the in-process test. It uses a
file-backed `m3-keystore-restart.db` and the production Keystore-backed keyset.

```bash
adb shell am instrument -w \
  -e class dev.chatui.security.KeystoreRestartProofTest#restartPhase \
  -e phase store dev.chatui.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop dev.chatui
adb shell am instrument -w \
  -e class dev.chatui.security.KeystoreRestartProofTest#restartPhase \
  -e phase decrypt dev.chatui.test/androidx.test.runner.AndroidJUnitRunner
```

Result: store phase `OK (1 test)`; `adb shell pidof dev.chatui` returned no
process after force-stop; decrypt phase `OK (1 test)`. The second phase ran in a
new app process and decrypted the sentinel from the persisted Room row, proving
the keyset is loaded from persistent Keystore-backed storage.

### 9.3 Unit-test regression

Command:

```bash
ANDROID_HOME=$HOME/Android/Sdk make android-test
```

Result: `BUILD SUCCESSFUL`; 781 tests, 0 failures, 0 errors, 0 skipped.

### 9.4 Source and log audit

Searched `android/**/*.kt` for `Log.`, `println`, `printStackTrace`,
`System.out`, `System.err`, `HttpLoggingInterceptor`, `addInterceptor`, and
`loggingInterceptor`.

- The only production log is `MainActivity.kt:46`,
  `Log.w(TAG, "Startup recovery failed", error)`. The message is content-free
  and the exception is a startup recovery failure, not a key or request header.
- At the time of this audit, `AppModule.provideOkHttpClient()` installed no
  interceptors or network interceptors. M3-01.4 later adds the cleartext policy
  interceptor; see section 12. No logging interceptor is installed.
- `println` appears only inside code-block fixture strings in
  `ChatCodeBlockTest`, not in production logging.
- No `printStackTrace`, `System.out`, or `System.err` appears in production
  sources.
- `TinkSecretCipher` exception messages are generic and do not include
  plaintext; `TinkSecretCipherTest` and `SecretStoreTest` assert that failure
  messages do not contain the plaintext.

### 9.5 Redaction

No real key, request header, prompt, or personal data is recorded in this
section. The sentinel is synthetic and exists only in test sources and test
output.

## 10. M3-01.2 Backup exclusion and case 27 evidence

Recorded 2026-11-06. Only the synthetic sentinel
`m3-backup-sentinel-not-a-real-key` was used. No real provider key, prompt,
conversation content, or personal data was created or recorded.

### 10.1 Targets and method

| Target | AVD | API | Role |
| --- | --- | --- | --- |
| `emulator-5554` | `Medium_Phone_API_36.1` | 36 | Source with seeded sentinel data |
| `emulator-5556` | `Small_Phone` | 31 | Second target / fresh install |

Method: Android Backup Manager (`bmgr`) with the platform backup transport.
The source target had Backup Manager enabled (`bmgr enable true`) so the test
exercised the real backup path rather than a globally disabled service. The
second target was installed fresh and checked before and after a restore
attempt.

No API 26-30 system image is installed in this environment. The
`fullBackupContent` path is therefore covered by the static `BackupRulesTest`
assertions, not by a runtime backup on API 26-30. The API 31+ path was exercised
on both API 31 and API 36.

### 10.2 Source target: seeded data and backup attempt

The manual `BackupSeedTest` phase seeded the production `chatui.db`, the
`files/datastore/settings.preferences_pb` DataStore, and the Keystore-backed
secret store with a synthetic provider, conversation, and sentinel secret.

```bash
adb -s emulator-5554 shell am instrument -w \
  -e class dev.chatui.security.BackupSeedTest#backupPhase \
  -e phase seed dev.chatui.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell bmgr enable true
adb -s emulator-5554 shell bmgr backupnow dev.chatui
adb -s emulator-5554 shell bmgr fullbackup dev.chatui
adb -s emulator-5554 shell bmgr list sets
```

Results:

- Seed phase: `OK (1 test)`.
- `bmgr backupnow dev.chatui`: `Package dev.chatui with result: Backup is not allowed`; `Backup finished with result: Success`.
- `bmgr fullbackup dev.chatui`: `Performing full transport backup`; no restore set was created.
- `bmgr list sets`: `No restore sets`.
- Plaintext scan of `databases/chatui.db`, `files/datastore/settings.preferences_pb`, and `shared_prefs/chatui_secret_keyset_prefs.xml`: 0 hits for the sentinel.
- `BackupSeedTest` `verify` phase after the backup attempts: `OK (1 test)`, proving the source data was still present and readable.

### 10.3 Second target: restore attempt and fresh-install check

The second target was installed fresh, then checked for restore sets and app
data before any seeding.

```bash
adb -s emulator-5556 install -r app-debug.apk
adb -s emulator-5556 install -r app-debug-androidTest.apk
adb -s emulator-5556 shell bmgr list sets
adb -s emulator-5556 shell bmgr backupnow dev.chatui
adb -s emulator-5556 shell run-as dev.chatui ls -la databases files shared_prefs
adb -s emulator-5556 shell am instrument -w \
  -e class dev.chatui.security.BackupSeedTest#backupPhase \
  -e phase verifyAbsent dev.chatui.test/androidx.test.runner.AndroidJUnitRunner
```

Results:

- `bmgr list sets`: `No restore sets`. There was no source backup set to restore because the source backup was refused.
- `bmgr backupnow dev.chatui` on the fresh target: `Backup is not allowed`.
- `run-as dev.chatui ls`: `databases`, `files`, and `shared_prefs` did not exist.
- `verifyAbsent`: `OK (1 test)`. The test asserts no sentinel secret, no seeded provider, no seeded conversation, `onboardingCompleted == false`, and no default provider or last-active conversation.

The API 31 target was also used to exercise the `dataExtractionRules` path with
seeded data:

```bash
adb -s emulator-5556 shell am instrument -w \
  -e class dev.chatui.security.BackupSeedTest#backupPhase \
  -e phase seed dev.chatui.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell bmgr enable true
adb -s emulator-5556 shell bmgr backupnow dev.chatui
```

Result: `Package dev.chatui with result: Backup is not allowed`; `Backup
finished with result: Success`. The target was then cleared with
`adb -s emulator-5556 shell pm clear dev.chatui` and `verifyAbsent` passed
again.

### 10.4 Static rule and regression checks

- `AndroidManifest.xml` still has `android:allowBackup="false"`,
  `android:dataExtractionRules="@xml/data_extraction_rules"`, and
  `android:fullBackupContent="@xml/backup_rules"`.
- `backup_rules.xml` still excludes `chatui.db` (plus `-shm`, `-wal`,
  `-journal`), `datastore/`, and `chatui_secret_keyset_prefs.xml`.
- `data_extraction_rules.xml` still excludes the same paths under both
  `<cloud-backup>` and `<device-transfer>`.
- `BackupRulesTest`: pass (3 tests).
- `make android-test`: `BUILD SUCCESSFUL`.
- `:app:connectedDebugAndroidTest`: `BUILD SUCCESSFUL`; 7 tests, 0 failures,
  0 errors, 2 skipped. The skipped tests are the manual `BackupSeedTest` and
  `KeystoreRestartProofTest` phases.

### 10.5 Limitations and decisions

- `adb backup` was attempted on the source target, but the emulator's System UI
  became unresponsive at the confirmation dialog. The platform `bmgr` path was
  used instead and produced the definitive `Backup is not allowed` result.
- No API 26-30 image is available, so the `fullBackupContent` runtime path
  could not be exercised. The static rule test remains the guard for that path.
- Device-to-device transfer was not exercised because the available emulators
  are not signed into a common account and the D2D transport requires a
  setup-wizard transfer flow. The `device-transfer` exclusions are present in
  `data_extraction_rules.xml` and are covered by `BackupRulesTest`.
- Onboarding was simulated by writing the same persisted state the real flow
  writes (`onboardingCompleted`, default provider/model, provider row, secret,
  conversation). The real onboarding path validates the key with `GET /key`, so
  a synthetic sentinel cannot complete it. The seeded state is exactly the data
  that must not restore, so the backup-exclusion result is unaffected.
- No manifest or XML rule was weakened to make a test pass.

## 11. M3-01.3 FLAG_SECURE, key lifecycle, and logging audit

Recorded 2026-11-06. Only synthetic test keys are referenced; no real key,
header, prompt, or personal data is recorded here.

### 11.1 SecureScreen and screen coverage

- Added `dev.chatui.core.designsystem.SecureScreen`. It resolves the hosting
  `Activity`/`Window` from `LocalView`, adds
  `WindowManager.LayoutParams.FLAG_SECURE` in a `DisposableEffect`, and clears
  the flag on dispose only when this composable added it. A window that was
  already secure is left untouched.
- `OnboardingScreen` wraps only the `KEY_ENTRY` step. The welcome/provider
  picker step remains unsecured and usable.
- `ProviderDetailScreen` wraps the whole detail surface, including the key
  metadata panel, rotation field, and remove-key action.
- No stored key is rendered. The detail screen shows only non-secret `/key`
  metadata, and both key inputs use `PasswordVisualTransformation`.
- No biometric or device-credential gate was added or claimed.

### 11.2 Window-flag tests

| Test | Result | What it proves |
| --- | --- | --- |
| `SecureScreenTest.adds flag secure while composed and removes it on dispose` | Pass | The flag is set while composed and cleared when the secure subtree leaves composition. |
| `SecureScreenTest.does not clear a flag that the host already set` | Pass | Disposal does not clear a flag the composable did not add. |
| `OnboardingScreenTest.key entry step sets flag secure` | Pass | The onboarding key-entry step sets `FLAG_SECURE`. |
| `OnboardingScreenTest.welcome step does not set flag secure` | Pass | The welcome step stays usable and unsecured. |
| `ProviderDetailScreenTest.provider detail sets flag secure` | Pass | Provider key detail/rotation sets `FLAG_SECURE`. |

### 11.3 Key lifecycle regression

Existing tests already cover the required lifecycle paths; no storage behavior
was changed.

| Path | Test | Result |
| --- | --- | --- |
| Provider key rotation | `ProviderServiceTest.rotateKey replaces the secret and clears key metadata` | Pass |
| Remove key | `ProviderServiceTest.removeKey deletes the secret and clears key metadata` | Pass |
| Forget one key | `SecretStoreTest.forget key deletes one secret` | Pass |
| Forget all keys | `SecretStoreTest.forget all deletes every secret` | Pass |

### 11.4 Logging audit

Searched production sources under `android/**/src/main` for `Log.`, `println`,
`printStackTrace`, `System.out`, `System.err`, `HttpLoggingInterceptor`,
`addInterceptor`, and `loggingInterceptor`.

- The only production log is `MainActivity.kt:46`,
  `Log.w(TAG, "Startup recovery failed", error)`. The message is content-free
  and the exception is a startup recovery failure, not a key or request header.
- At the time of this audit, `AppModule.provideOkHttpClient()` installed no
  interceptors or network interceptors. M3-01.4 later adds the cleartext policy
  interceptor; see section 12. No logging interceptor is installed.
- No request/response body dump or debug logging interceptor exists.
- Provider adapters route error text through `redactSecret` before building
  `ProviderRequestException` messages, and `Redaction` covers authorization,
  `x-api-key`, and `x-goog-api-key` headers plus bare bearer tokens.
- No `println`, `printStackTrace`, `System.out`, or `System.err` appears in
  production sources.

Conclusion: no raw secret header or key value is logged, and no logging
interceptor is installed.

### 11.5 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 786 tests, 0 failures, 0 errors, 0 skipped. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL`; 7 tests, 0 failures, 0 errors, 2 skipped (manual restart/backup phases). |

The five new Robolectric tests account for the increase from the 781-test M2
baseline to 786.

## 12. M3-01.4 Network security config and local cleartext policy

Recorded 2026-11-06 on `Medium_Phone_API_36.1(AVD) - 16` (API 36), debug build.
No API key, prompt, conversation, or personal data is recorded here.

### 12.1 Policy

- `AndroidManifest.xml` declares
  `android:networkSecurityConfig="@xml/network_security_config"`.
- `res/xml/network_security_config.xml` sets
  `base-config cleartextTrafficPermitted="true"` as the platform-level enabler.
  The file documents that Android network-security-config XML cannot express
  RFC1918, IPv6 ULA, or link-local CIDR ranges.
- The real allowlist is `dev.chatui.core.network.CleartextHostPolicy`, applied
  to every request by `CleartextPolicyInterceptor` on the shared production
  OkHttp client. It allows:
  - loopback: `localhost`, `127.0.0.0/8`, `::1`;
  - Android emulator host loopback: `10.0.2.2`;
  - mDNS names ending in `.local`;
  - RFC1918 IPv4: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`;
  - IPv6 unique-local `fc00::/7` and link-local `fe80::/10`.
- It refuses every other cleartext host, including public IPv4/IPv6, CGNAT
  `100.64.0.0/10`, and a `.local` or `localhost` name that resolves to a public
  address. HTTPS is always allowed and is never resolved or inspected.
- No trust-all manager, custom SSL socket factory, or certificate-validation
  bypass was added. HTTPS behavior is unchanged.

### 12.2 Automated tests

| Test | Result | What it proves |
| --- | --- | --- |
| `CleartextHostPolicyTest.allows loopback and emulator literals` | Pass | `localhost`, `127.0.0.1`, `::1`, and `10.0.2.2` are allowed. |
| `CleartextHostPolicyTest.allows RFC1918 IPv4 ranges` | Pass | All three RFC1918 ranges are allowed at their boundaries. |
| `CleartextHostPolicyTest.allows IPv6 unique-local and link-local ranges` | Pass | `fc00::/7` and `fe80::/10` are allowed. |
| `CleartextHostPolicyTest.allows mDNS local names` | Pass | `.local` names are allowed. |
| `CleartextHostPolicyTest.refuses public and out-of-range hosts` | Pass | Public, CGNAT, link-local IPv4, and out-of-range addresses are refused. |
| `CleartextHostPolicyTest.https is always allowed and non-http schemes are refused` | Pass | HTTPS is unaffected; non-http schemes are refused. |
| `CleartextHostPolicyTest.local names are checked against their resolved addresses` | Pass | A `.local` name resolving to a public address is refused. |
| `CleartextHostPolicyTest.localhost is checked against its resolved addresses` | Pass | `localhost` resolving to a public address is refused. |
| `CleartextHostPolicyTest.literal addresses are not resolved` | Pass | Literal private addresses do not trigger DNS. |
| `CleartextHostPolicyTest.interceptor refuses public cleartext before the request is sent` | Pass | The interceptor throws before OkHttp opens a connection. |
| `CleartextHostPolicyTest.interceptor allows loopback cleartext` | Pass | An allowed loopback request reaches the server. |
| `ShellManifestTest.manifest references the network security config` | Pass | The manifest attribute is present. |
| `ShellManifestTest.network security config enables base cleartext as the platform enabler` | Pass | The XML contains the approved base config and limitation comment. |
| `AppModuleTest.provides a shared HTTP client with only the cleartext policy interceptor` | Pass | The production client has exactly the cleartext policy interceptor and no logging interceptor. |

### 12.3 Local-provider device check

The host had Ollama listening on `127.0.0.1:11434`. The emulator reached it
through the emulator host loopback:

```bash
adb shell 'printf "GET /api/tags HTTP/1.0\r\nHost: 10.0.2.2\r\n\r\n" | nc -w 5 10.0.2.2 11434'
```

Result: `HTTP/1.0 200 OK`, `Content-Type: application/json`, body
`{"models":[]}`.

The instrumentation test
`LocalProviderCleartextTest.localOllamaPresetReachesHostServer` then used the
production `CleartextPolicyInterceptor` and `OllamaAdapter` against
`http://10.0.2.2:11434/v1`. Result: pass in 1.038 s. This closes the M2 local
Ollama cleartext deferral for the emulator host loopback.

Limitations recorded honestly:

- LM Studio (`127.0.0.1:1234`) and llama.cpp (`127.0.0.1:8080`) were not
  running, so those presets were not exercised on device. The policy covers
  their loopback and RFC1918 addresses by the same tests.
- `.local` mDNS resolution was not exercised on the emulator. The policy
  resolves `.local` names and refuses a public result; RFC1918 literal
  addresses remain the supported fallback if mDNS is unavailable.
- The device check proves the emulator loopback path, not a physical-device LAN
  path. RFC1918 literals are covered by unit tests.

### 12.4 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-lint` | `BUILD SUCCESSFUL`; 439 actionable tasks. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 799 tests, 0 failures, 0 errors, 0 skipped. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-assemble` | `BUILD SUCCESSFUL`; debug APK produced. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL`; 8 tests, 0 failures, 0 errors, 2 skipped (manual restart/backup phases). |

The 13 new JVM/Robolectric tests account for the increase from 786 to 799.

## 13. M3-01.5 Cleartext warning and certificate-validation guard

### 13.1 Behavior

`dev.chatui.core.providers.assessBaseUrlSecurity` is a pure classifier for a
Custom provider base URL. It returns one of:

- `SecureHttps` for `https://` URLs, with no cleartext warning.
- `AllowedLocalCleartext` for `http://` URLs whose host is approved by
  `CleartextHostPolicy`, with the `CLEARTEXT_LOCAL_WARNING` copy.
- `RefusedCleartext` for public `http://` hosts, invalid URLs, and non-http(s)
  schemes, with the `CLEARTEXT_REFUSED_ERROR` copy.

`ProviderService.createCustomProvider` refuses `RefusedCleartext` before any
write, so a public cleartext provider cannot be created. The Custom provider
form shows the warning or refusal copy live as the URL changes, and provider
detail shows the same warning for a stored local-cleartext provider. The copy
contains no key, header value, or URL query data.

The classifier is DNS-free. A `.local` name is accepted by name and is resolved
again by `CleartextPolicyInterceptor`, which refuses it if it resolves to a
public address. This keeps the UI responsive and preserves the M3-01.4 policy.

### 13.2 Certificate-validation guard

`AppModuleTest` now asserts that the shared production client:

- has exactly the `CleartextPolicyInterceptor` and no network interceptors;
- uses OkHttp's default `OkHostnameVerifier`;
- has a non-null default `sslSocketFactory`;
- has no `sslSocketFactory`, `hostnameVerifier`, `trustManager`,
  `networkInterceptors`, `.interceptors(`, or `HttpLoggingInterceptor` in the
  `provideOkHttpClient()` source.

No trust-all manager, hostname-verifier override, or logging interceptor was
added.

### 13.3 Automated tests

| Test | Result | What it proves |
| --- | --- | --- |
| `BaseUrlSecurityTest.https is secure and has no cleartext warning` | Pass | HTTPS is classified as secure. |
| `BaseUrlSecurityTest.approved local http hosts produce a warning` | Pass | Loopback, emulator, RFC1918, and `.local` HTTP hosts warn. |
| `BaseUrlSecurityTest.public cleartext is refused with actionable copy` | Pass | Public HTTP hosts are refused with the error copy. |
| `BaseUrlSecurityTest.invalid and non-http schemes are refused` | Pass | Invalid and non-http input is refused. |
| `BaseUrlSecurityTest.warning and error copy contain no secrets` | Pass | Copy contains no key or auth material. |
| `ProviderServiceTest.createCustomProvider refuses public cleartext with actionable copy` | Pass | Creation is refused before any write. |
| `ProviderServiceTest.createCustomProvider accepts approved local cleartext` | Pass | Approved local HTTP providers can still be created. |
| `ProviderListViewModelTest.custom form warns for approved local cleartext` | Pass | The form exposes the warning. |
| `ProviderListViewModelTest.custom form refuses public cleartext` | Pass | The form exposes the refusal copy. |
| `ProviderListViewModelTest.custom form shows no cleartext warning for https` | Pass | HTTPS produces no warning. |
| `ProviderListScreenTest.shows the local cleartext warning and refusal copy` | Pass | The warning is rendered in the Custom form. |
| `ProviderListScreenTest.shows the refused cleartext copy` | Pass | The refusal copy is rendered in the Custom form. |
| `ProviderDetailScreenTest.shows the local cleartext warning for a custom provider` | Pass | The warning is rendered in provider detail. |
| `AppModuleTest.provides a shared HTTP client with only the cleartext policy interceptor` | Pass | Default hostname verifier and SSL socket factory remain in effect. |
| `AppModuleTest.shared HTTP client source has no custom trust manager or logging interceptor` | Pass | The client source has no validation bypass or logging interceptor. |

### 13.4 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-lint` | `BUILD SUCCESSFUL`; 439 actionable tasks. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 813 tests, 0 failures, 0 errors, 0 skipped. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-assemble` | `BUILD SUCCESSFUL`; debug APK produced. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL`; 8 tests, 0 failures, 0 errors, 2 skipped (manual restart/backup phases). |

The 14 new JVM/Robolectric tests account for the increase from 799 to 813.

## 14. M3-04.1 Configuration-change stream survival

Recorded 2026-11-06 on `Medium_Phone_API_36.1(AVD) - 16` (API 36,
emulator-5554), debug build. Only synthetic conversation and message data was
used.

### 14.1 Automated recreation proof

`ConfigChangeStreamTest.activeStreamSurvivesActivityRecreation` hosts the real
`ChatRoute` in a debug-only test activity with a controlled
`FakeProviderAdapter`. It starts a send, waits for the first `partial` delta,
calls `ActivityScenario.recreate()`, and then emits a second delta.

| Assertion | Result |
| --- | --- |
| The same assistant message id remains after recreation | Pass |
| The partial text remains visible after recreation | Pass |
| The second delta updates the same message | Pass |
| The fake adapter was not cancelled | Pass |
| Exactly one assistant message exists at completion | Pass |

### 14.2 Real rotation proof

`ConfigChangeStreamTest.realOrientationChangeKeepsStream` forces a real
landscape configuration change with `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`
and waits for the activity to recreate. The same assertions pass: the partial
text survives, the adapter is not cancelled, the second delta arrives, and no
duplicate assistant row is created.

This is the device rotation evidence for the task. It exercises a real
configuration change rather than only `ActivityScenario.recreate()`.

### 14.3 Test harness decision

The test activity is in `android/app/src/debug/`, not `androidTest`. An activity
declared in the test APK resolves to the `dev.chatui.test` process, and
`ActivityScenario` refuses to launch it from the target app process
(`Intent in process dev.chatui resolved to different process dev.chatui.test`).
The debug-only activity is test-only and is not part of release builds. No
production streaming architecture changed.

### 14.4 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 930 tests, 0 failures, 0 errors, 0 skipped (aggregated test-results XML). |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL`; 10 tests, 0 failures, 0 errors, 2 skipped (existing manual restart/backup phases). `ConfigChangeStreamTest` ran 2 tests, 0 failures. |

### 14.5 CI availability

The repository CI workflow runs `lintDebug testDebugUnitTest assembleDebug`; it
does not boot an emulator or run `connectedDebugAndroidTest`. The
configuration-change test is therefore a local instrumentation test on
`Medium_Phone_API_36.1(AVD) - 16`. CI device availability remains the
environment blocker for connected tests, as recorded in section 5. The test
compiles in CI through `:app:compileDebugAndroidTestKotlin` only when that task
is run; the current CI command does not include it.

### 14.6 Redaction

No API key, prompt, conversation content, personal data, or signing material is
recorded in this section. The test uses synthetic `Hello` / `partial` text only.

## 15. M3-04.3 Navigation and last-active restore

Recorded 2026-11-06 on `Medium_Phone_API_36.1(AVD) - 16` (API 36,
emulator-5554), debug build. Only synthetic conversation ids were used.

### 15.1 Cold-start routing order

`resolveShellStartDestination` in `ShellNavHost.kt` resolves the start
destination in this order:

1. Onboarding when `onboardingCompleted` is false.
2. The conversation list when an external intent (deep link or share) is
   pending, so the intent collector can route it without a last-active race.
3. The last-active conversation when `getLastActiveConversation()` returns one.
4. The conversation list otherwise.

`getLastActiveConversation()` clears the persisted pointer when the
conversation no longer exists, so a deleted conversation never opens a
permanent not-found screen.

### 15.2 Automated proof

`ShellNavigationTest` covers the routing matrix and the stale-pointer path.

| Test | Result | Evidence |
| --- | --- | --- |
| `completed onboarding restores the last active conversation` | Pass | Existing conversation id becomes the chat route. |
| `resolver restores an existing last active conversation` | Pass | Store-backed resolver returns the chat route. |
| `resolver falls back to the list and clears a deleted last active pointer` | Pass | Missing conversation returns the list and the pointer becomes null. |
| `pending deep link takes precedence over last active restore` | Pass | Pending intent selects the list so the collector routes the link. |
| `resolver prefers a pending deep link over last active restore` | Pass | Store-backed resolver selects the list. |
| `onboarding takes precedence over pending deep link and last active` | Pass | Incomplete setup selects onboarding. |
| `resolver keeps onboarding ahead of a pending deep link` | Pass | Store-backed resolver selects onboarding. |
| `a consumed deep link does not override last active restore on recreation` | Pass | A consumed link is not pending, so recreation restores the last-active chat without duplicate navigation. |

`ShellThemeTest` still renders the shell through `ChatUiApp` after the
asynchronous start-destination resolution, proving the shell composes normally
when no last-active conversation exists.

### 15.3 Persistence and safety

- Opening a chat still writes `lastActiveConversationId` from the chat
  destination.
- The write is guarded so a deep link to a missing conversation renders the
  existing not-found state instead of throwing.
- No new persistence was added. Provider keys, secrets, and transient dialog
  state are not stored.
- `rememberNavController()` remains the back-stack owner, so Navigation Compose
  saved state restores the practical back stack.

### 15.4 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-lint` | `BUILD SUCCESSFUL`; 439 actionable tasks. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 702 `testDebugUnitTest` tests, 0 failures, 0 errors, 0 skipped. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-assemble` | `BUILD SUCCESSFUL`; debug APK produced. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL`; 10 tests, 0 failures, 0 errors, 2 skipped (existing manual restart/backup phases). |

### 15.5 Redaction

No API key, prompt, conversation content, personal data, or signing material is
recorded in this section. The tests use synthetic conversation ids only.

## 16. M3-04.4 Process-death streaming recovery and case 26

Recorded 2026-11-06 on `Medium_Phone_API_36.1(AVD) - 16` (API 36,
`emulator-5554`), debug build `0.1.0` (`versionCode 1`). Only synthetic fixture
text and a synthetic placeholder key were used.

### 16.1 Procedure

The repeatable procedure is in
[case-26-process-death.md](case-26-process-death.md). It starts a local
OpenAI-compatible SSE fixture on host port `8765`, exposes it with
`adb reverse tcp:8765 tcp:8765`, configures a Custom provider at
`http://localhost:8765/v1`, starts a long stream, backgrounds the app, and kills
the process with `adb shell am kill --user 0 dev.chatui`. It does not use
`am force-stop`.

### 16.2 Result

| Check | Result | Evidence |
| --- | --- | --- |
| Background process kill during streaming | Pass | `adb shell pidof dev.chatui` printed nothing after the kill. |
| Partial content preserved after relaunch | Pass | The persisted synthetic prefix was rendered in the restored conversation. |
| Message not stuck at `streaming` | Pass | The stored row was `cancelled` with `finishReason=cancelled`. |
| Interruption explanation visible | Pass | `The app closed before this response finished. Partial content is preserved.` was rendered under the message. |
| Regenerate works | Pass | Regenerate streamed again in the same assistant row and completed. |
| No branch or duplicate history | Pass | The conversation kept one user and one assistant message; the assistant sequence was reused. |

The recovery path is the existing `ChatRunner` persistence,
`StreamingRecoveryService`, `AppStartup`, and `InterruptedMessageNotice`
implementation. The procedure exposed no recovery or persistence defect, so no
production code or regression test was changed.

### 16.3 Persistence cadence

`ChatRunner` persists partial text about once per second
(`DEFAULT_PERSIST_INTERVAL_MS = 1_000`). The fixture emits two chunks per second,
so the maximum text loss from a kill between writes is one persistence interval,
about two fixture chunks. The recorded run preserved the last persisted prefix
and lost only text produced after the last write.

### 16.4 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 702 `testDebugUnitTest` tests, 0 failures, 0 errors, 0 skipped. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL`; 10 tests, 0 failures, 0 errors, 2 skipped (existing manual restart/backup phases). |

### 16.5 Redaction

No real API key, prompt, conversation content, personal data, or signing
material is recorded in this section. The provider key was a synthetic
placeholder, the prompt was synthetic, and the streamed text was the fixture's
`synthetic-NN` sequence.

## 17. M3-04.5 Startup orphan-attachment cleanup

Recorded 2026-11-06. Only synthetic attachment names and bytes were used.

### 17.1 Behavior

- `AppStartup` now depends on an `AttachmentCleanup` interface. The production
  binding in `AppModule.provideAttachmentCleanup` delegates to the shared
  `AttachmentService.cleanupOrphans`, so startup and chat use the same orphan
  rules and file-deletion logic.
- `AppStartup.run()` captures one startup instant before recovery, runs
  `recovery.recoverInterruptedMessages()`, then calls
  `attachmentCleanup.cleanupOrphans(startedAt)`.
- Cleanup runs inside the existing mutex/`completed` guard, so it runs exactly
  once per process even with concurrent callers.
- A cleanup failure is non-fatal. Only the exception class name is recorded in
  `AppStartup.lastCleanupFailure`; no message, path, or attachment content is
  stored. The shell still renders.
- A recovery failure keeps its existing behavior and still attempts cleanup
  before the exception propagates to `MainActivity`.
- Attachments created at or after the captured startup instant are never
  treated as orphans. Attachments bound to an existing message survive.

### 17.2 Automated tests

| Test | Result | What it proves |
| --- | --- | --- |
| `AppStartupTest.runs cleanup after recovery with the startup timestamp` | Pass | Cleanup runs after recovery and receives the captured startup instant. |
| `AppStartupTest.cleanup runs once per process` | Pass | Repeated calls do not repeat cleanup. |
| `AppStartupTest.a failed cleanup does not block the shell and is recorded` | Pass | A cleanup failure is swallowed, recorded as a class name, and not retried. |
| `AppStartupTest.cleanup is still attempted when recovery fails` | Pass | Recovery failure does not skip cleanup. |
| `AttachmentServiceTest.startup cleanup removes orphan metadata and bytes but keeps referenced and new attachments` | Pass | Orphan metadata and orphan bytes are deleted; a message-bound attachment and an attachment created after the cutoff survive. |
| `AppModuleTest.provides attachment cleanup backed by the attachment service` | Pass | The DI binding delegates to `AttachmentService.cleanupOrphans`. |

### 17.3 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-lint` | `BUILD SUCCESSFUL`; 439 actionable tasks. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 953 aggregated tests, 0 failures, 0 errors, 0 skipped. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-assemble` | `BUILD SUCCESSFUL`; debug APK produced. |

### 17.4 Redaction

No API key, prompt, conversation content, personal data, or signing material is
recorded in this section. Attachment fixtures use synthetic names and bytes.

## 18. M3-04.6 Background behavior documentation

Recorded 2026-11-06. Documentation-only task; no production code changed.

### 18.1 Document

[android-background-behavior.md](android-background-behavior.md) records:

- streaming while foregrounded, including configuration-change survival;
- the best-effort short grace period when backgrounded, subject to OS limits;
- process-death behavior: partial content preserved, message marked
  interrupted, regenerate available;
- no foreground service in M3;
- no WorkManager or background sync before backup exists;
- user-facing expectations and known limitations.

The document states explicitly that long background responses are not
guaranteed and that a foreground service is a future enhancement.

### 18.2 Audit: no background service or sync

Command:

```bash
rg -n "WorkManager|ForegroundService|startForeground" android/app android/feature android/core || true
```

Result: no matches in production sources. `AndroidManifest.xml` declares only
`MainActivity` and no service, receiver, or WorkManager initializer. No
foreground service, notification, wake lock, or background sync job was added.

### 18.3 Redaction

No API key, prompt, conversation content, personal data, or signing material is
recorded in this section or in the background-behavior document.

## 19. M3-06.3 Baseline profile and startup benchmark

Recorded 2026-11-06 on `Medium_Phone_API_36.1(AVD) - 16` (API 36, x86_64,
1080x2400). The emulator was the available device; physical mid-tier validation
remains in M3-07.3.

### 19.1 Configuration

- `androidx.baselineprofile` plugin `1.5.0` applied to `:app` and the new
  `:baselineprofile` module.
- `androidx.profileinstaller:profileinstaller:1.4.1` added to `:app`.
- `androidx.benchmark:benchmark-macro-junit4:1.5.0` and
  `androidx.test.uiautomator:uiautomator:2.4.1` added to `:baselineprofile`.
- `:app` declares `baselineProfile(project(":baselineprofile"))` and
  `baselineProfile { saveInSrc = true }`.
- The rule exercises cold start, the compact-layout drawer, and the
  keyboard-shortcuts dialog, and sets `includeInStartupProfile = true`.

### 19.2 Generated profile

| Item | Value |
| --- | --- |
| Baseline profile | `android/app/src/release/generated/baselineProfiles/baseline-prof.txt` |
| Baseline profile size | 26,995 lines; 1,954 `dev/chatui` entries |
| Startup profile | `android/app/src/release/generated/baselineProfiles/startup-prof.txt` |
| Startup profile size | 26,995 lines; 1,954 `dev/chatui` entries |
| APK profile assets | `assets/dexopt/baseline.prof`, `assets/dexopt/baseline.profm` |
| Generation command | `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:generateBaselineProfile` |
| Plugin tasks run | `:baselineprofile:connectedNonMinifiedReleaseAndroidTest`, `:baselineprofile:collectNonMinifiedReleaseBaselineProfile` |

The task name produced by this plugin version is
`:baselineprofile:connectedBenchmarkReleaseAndroidTest`, not
`:baselineprofile:connectedBenchmarkAndroidTest`. It completes successfully;
the baseline-profile test is skipped in the `benchmarkRelease` variant because
generation uses `nonMinifiedRelease`.

### 19.3 Startup measurement

Build type: `release` (R8 minified, resource shrunk), signed with a temporary
local measurement keystore outside the repository. The keystore and passwords
were not committed; release signing policy remains M3-06.2.

Procedure:

1. Install the release APK.
2. Trigger profile installation with
   `adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n dev.chatui/androidx.profileinstaller.ProfileInstallReceiver`
   (result=1).
3. Apply the profile with `adb shell cmd package compile -m speed-profile dev.chatui`
   (`Success`; `dumpsys package` then reports `[status=speed-profile]`).
4. For each run: `adb shell am force-stop dev.chatui`, then
   `adb shell am start -W -n dev.chatui/.MainActivity`.

| Run | TotalTime (ms) | LaunchState |
| --- | --- | --- |
| 1 | 713 | COLD |
| 2 | 743 | COLD |
| 3 | 730 | COLD |
| 4 | 888 | COLD |
| 5 | 831 | COLD |

Median: **743 ms**. Budget: 1,500 ms. Result: met on the recorded emulator.
The first launch after install, before the profile was applied, was 794 ms and
is not part of the five-run set.

### 19.4 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleRelease` | `BUILD SUCCESSFUL` |
| `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:generateBaselineProfile` | `BUILD SUCCESSFUL`; profile and startup profile generated |
| `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` | `BUILD SUCCESSFUL`; test skipped in `benchmarkRelease` (expected) |

### 19.5 Redaction

No API key, prompt, conversation content, personal data, or signing material is
recorded in this section. The measurement keystore path and passwords are not
recorded.

## 20. M3-07.1 Performance and size evidence

Recorded 2026-11-06 on `Medium_Phone_API_36.1(AVD) - 16` (API 36, x86_64,
1080x2400, headless emulator with `swiftshader_indirect` GPU). The emulator is
the available device; physical mid-tier validation remains M3-07.3. Only
synthetic data was used.

### 20.1 Cold start

Build type: `release` (R8 minified, resource shrunk), signed with a throwaway
local measurement keystore outside the repository. The keystore and passwords
were deleted after measurement and are not recorded. Release signing policy
remains M3-06.2/M3-06.4.

Profile state: the baseline profile was installed and compiled before the
measured runs.

```bash
adb install -r app-release.apk
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
  -n dev.chatui/androidx.profileinstaller.ProfileInstallReceiver   # result=1
adb shell cmd package compile -m speed-profile dev.chatui          # Success
adb shell dumpsys package dev.chatui | grep status                 # [status=speed-profile]
```

Procedure: one warm-up launch after install (excluded), then five runs of
`adb shell am force-stop dev.chatui` followed by
`adb shell am start -W -n dev.chatui/.MainActivity`.

| Run | TotalTime (ms) | WaitTime (ms) | LaunchState |
| --- | --- | --- | --- |
| 1 | 990 | 1191 | COLD |
| 2 | 762 | 780 | COLD |
| 3 | 1098 | 1195 | COLD |
| 4 | 693 | 704 | COLD |
| 5 | 1296 | 1423 | COLD |

Median: **990 ms**. Budget: 1,500 ms. Result: **met** on the recorded emulator.

Methodology note: the host load average was 4.65 at measurement. Earlier runs
taken while the host load average was about 7.7 produced 1.1-2.3 s totals, so
this emulator is sensitive to host CPU contention. The table above is one
contiguous set taken after the profile was applied and after the host load had
settled; no run was discarded from that set.

### 20.2 Large-conversation bounded composition

Command:

```bash
cd android && ANDROID_HOME=$HOME/Android/Sdk \
  ./gradlew :feature:chat:testDebugUnitTest \
  --tests 'dev.chatui.feature.chat.ChatMessageListTest'
```

Result: `BUILD SUCCESSFUL`; 4 tests, 0 failures, 0 errors, 0 skipped.

| Test | Result | What it proves |
| --- | --- | --- |
| `ten thousand message history composes only visible rows` | Pass | A 10,000-message history composes fewer than 100 rows and the newest message is visible (spec 15.2 case 23). |
| `token updates recompose only the streaming row` | Pass | A streaming token update does not recompose history rows. |
| `history rows keep stable identity when a message is updated in place` | Pass | In-place updates keep stable keys. |
| `duplicate-prone ids keep stable identity and do not collide with the streaming row` | Pass | Keys come from `Message.id`; the streaming row replaces rather than duplicates a history key. |

The bounded-composition threshold is fewer than 100 composed rows, as recorded
by M3-05.5. No production code changed for this evidence.

### 20.3 Streaming recomposition

The `token updates recompose only the streaming row` case above is the
streaming-recomposition result. It passes on the Robolectric/JVM path. No
device frame-time measurement was taken; this task gates bounded composition
and history recomposition, not frame timing.

### 20.4 Image decode and caching

Unit evidence: `ChatMessageItemTest.local image requests are bounded to the
display size` passes. It asserts the Coil request uses the local `File` as data
and a `Size(1080, 960)` size resolver, matching the `BoxWithConstraints`-bounded
message-image request from M3-06.5.

Device check: the debug build was installed and a synthetic conversation was
seeded with one image message. The image was a 6000x4000 PNG (24 MP,
162,948 bytes on disk; about 96 MB if decoded at full size) stored at
`files/attachments/<sha256>`. The app restored the last-active conversation and
rendered the image.

| Check | Result |
| --- | --- |
| Image node rendered | `uiautomator` reports an `ImageView` with `content-desc="large.png"` at 932x621 px. |
| Full-size decode avoided | The rendered bounds are far below 6000x4000; the request is bounded by the display size. |
| No OOM or crash | `logcat` has no `OutOfMemoryError`, `FATAL EXCEPTION`, or `AndroidRuntime` entry after launch. |
| Memory after render | `dumpsys meminfo dev.chatui`: TOTAL PSS 118,460 KB, Java heap 22,868 KB, native heap 19,248 KB. |

The memory figures are consistent with a bounded decode rather than a 96 MB
full-size bitmap. The Coil request uses `File` data and a size-aware memory
cache key, as implemented in M3-06.5. The seeded conversation used synthetic
data only; the seed's `paramsJson` was set to a valid `ChatParams` JSON so the
conversation could open. No product code changed.

### 20.5 Artifact sizes

Measured with `ls -l` on the actual build outputs.

| Artifact | Bytes | MiB | Budget | Result |
| --- | --- | --- | --- | --- |
| Debug APK (`app-debug.apk`) | 18,734,354 | 17.87 | 20 MB | Met |
| Release APK, signed measurement build (`app-release.apk`) | 2,907,246 | 2.77 | 15 MB | Met |
| Release APK, unsigned local build (`app-release-unsigned.apk`) | 2,894,958 | 2.76 | 15 MB | Met |
| Release AAB (`app-release.aab`) | 7,042,442 | 6.72 | none | Recorded |

Largest release contributors: `classes.dex` 2,677,480 bytes, `classes2.dex`
2,313,436 bytes, `resources.arsc` 152,908 bytes, and
`assets/dexopt/baseline.prof` 13,191 bytes. The signed APK is 12,288 bytes
larger than the unsigned APK because of the signature block.

The signed APK was produced with a throwaway local measurement keystore outside
the repository. CI signing secrets remain unavailable, so the CI-signed release
artifact is still the M3-06.4 blocker; this section records the local
measurement build only.

### 20.6 Budget summary

| Budget | Target | Measured | Result |
| --- | --- | --- | --- |
| Cold start | under 1,500 ms | 990 ms median | Met |
| Debug APK | under 20 MB | 17.87 MiB | Met |
| Release APK | under 15 MB | 2.77 MiB | Met |

No budget was missed, so no cause or follow-up owner is required. No security
or required functionality was removed to hit a budget.

### 20.7 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 993 aggregated tests, 0 failures, 0 errors, 0 skipped. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:assembleRelease :app:bundleRelease` | `BUILD SUCCESSFUL`; debug APK, release APK, and release AAB produced. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :feature:chat:testDebugUnitTest --tests 'dev.chatui.feature.chat.ChatMessageListTest'` | `BUILD SUCCESSFUL`; 4 tests, 0 failures. |
| `adb shell am start -W -n dev.chatui/.MainActivity` (five runs) | COLD each run; totals 990, 762, 1098, 693, 1296 ms; median 990 ms. |

### 20.8 Redaction

No real API key, prompt, conversation content, personal data, or signing
material is recorded in this section. The seeded provider key is the synthetic
`m3-backup-sentinel-not-a-real-key` sentinel, the conversation is synthetic, and
the image is a generated gradient. The measurement keystore path and passwords
are not recorded.

## 21. M3-07.2 Accessibility baseline and TalkBack

Recorded 2026-11-06 on `Medium_Phone_API_36.1(AVD) - 16` (API 36), debug build.
Only synthetic provider, conversation, and message data was used.

### 21.1 Semantics and content-description audit

| Surface | Interactive element | Accessible name | Result |
| --- | --- | --- | --- |
| Shell | Compact menu icon button | `Open navigation` (added) | Pass |
| Shell | Keyboard-shortcuts icon button | `Keyboard shortcuts` | Pass |
| Shell | Sidebar/drawer destinations | Visible text labels | Pass |
| Conversations | Row overflow icon button | `Conversation actions` (added) | Pass |
| Conversations | Row select target | Merged title and timestamp | Pass |
| Conversations | Search result row | Merged title and match context | Pass |
| Chat | Share, Settings, model chip | Visible text labels | Pass |
| Chat | Message copy/regenerate/edit | `Copy message`, `Regenerate response`, `Edit last message` | Pass |
| Chat | Message row | `Assistant message` / `You message` | Pass |
| Chat | Image and file attachments | Attachment name / `File attachment <name>` | Pass |
| Chat | Composer attach, paste, send, stop | Visible text labels | Pass |
| Chat | Error retry and details | `Retry`, `Details` | Pass |
| Onboarding | Provider rows | Merged provider label and status | Pass |
| Providers | Manage, add, retry, save | Visible text labels | Pass |
| Settings | Rows, chips, switches | Visible text labels | Pass |

Two icon-only buttons rendered a glyph without an accessible name and were
fixed: the compact shell menu button and the conversation row-actions button.
Both now expose a content description, clear the glyph from semantics, and use
`Modifier.size(dimens.touchTargetMin)`. Provider rows and conversation/search
rows now merge descendant text so the clickable node carries the visible label.

### 21.2 Streaming announcements

`ChatViewModel.announcementFor` emits only on status transitions:
`Response started`, `Response complete`, `Response cancelled`, and
`Response failed. <message>`. Token deltas do not change the announcement.
`ChatScreen` exposes the announcement through a 1 dp node with
`liveRegion = LiveRegionMode.Polite`. `ChatViewModelTest` proves the announcement
does not change on token updates, and `ChatScreenTest` now asserts the polite
live-region property as well as the text.

### 21.3 48 dp touch-target audit

`Dimens.touchTargetMin` is 48 dp and `DesignSystemTest` asserts it. The audit
found the three Material 3 `IconButton`s laid out at 40 dp even though the
minimum-interactive-size touch target was larger. They now use
`Modifier.size(dimens.touchTargetMin)` so the layout and touch target are both
48 dp. Text buttons, chips, switches, and the composer already use
`heightIn(min = touchTargetMin)` or Material 3 minimum interactive sizing.

New tests assert the measured height is at least 48 dp for the chat header
actions, the composer send button, and the conversation row-actions button.

### 21.4 TalkBack pass

TalkBack (`com.google.android.marvin.talkback`) was installed on the emulator
and enabled with:

```bash
adb shell settings put secure enabled_accessibility_services \
  com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
adb shell settings put secure accessibility_enabled 1
```

`dumpsys accessibility` reported the service bound with `FEEDBACK_SPOKEN`.
Focus was moved with `KEYCODE_TAB` and the focused node was read from
`uiautomator dump`. Observed focus order and names:

| Flow | Focused nodes observed |
| --- | --- |
| Onboarding welcome | Provider rows (OpenRouter, OpenAI, Anthropic, ...), `Open navigation`, `Keyboard shortcuts` |
| Chat | `Share conversation`, `Settings`, model chip, `Choose another model`, `Open navigation`, `Keyboard shortcuts` |
| Drawer | `Close navigation menu`, Chats, Archived, New chat, Search conversations, conversation row, `Conversation actions`, Conversations, Providers, Settings, Data, Backup |
| Providers | Provider label, `Manage`, `Add provider`, preset rows |
| Settings | Appearance, Chat defaults, Providers, Data, Backup, About and privacy |
| Error state | Model-unavailable banner and `Choose another model` action |

The bottom-sheet model picker and conversation-settings sheet are separate
windows that `uiautomator dump` does not expose. Their controls are text buttons
and labelled fields, and `ModelPickerContentTest` / `ConversationSettingsSheet`
tests cover their content. No TalkBack blocker was found.

### 21.5 Reduced motion

Set `animator_duration_scale`, `transition_animation_scale`, and
`window_animation_scale` to `0`, force-stopped, and relaunched the app. The chat
screen rendered in 1,482 ms with the header, availability banner, empty state,
and composer all visible. `ChatUiTheme` reads the system animator scale through
`rememberReducedMotion` and maps it to `ChatUiMotion.Reduced` (zero durations).
The app has no production animation that could hide content, so reduced motion
cannot remove content. Scales were restored to `1` after the check.

### 21.6 Dynamic color and translations

Dynamic color remains **optional / not implemented**. `ChatUiTheme` uses the
fixed light/dark color schemes from `Color.kt`; no `dynamicLightColorScheme` or
`dynamicDarkColorScheme` call exists. No translations or localized resources
were added.

### 21.7 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-lint` | `BUILD SUCCESSFUL`; 439 actionable tasks. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 997 aggregated tests, 0 failures, 0 errors, 0 skipped. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL`; 10 tests, 0 failures, 0 errors, 2 skipped (manual restart/backup phases). |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:verifyRoborazziDebug` | `BUILD SUCCESSFUL`; screenshot goldens unchanged. |

The 4 new JVM/Robolectric tests account for the increase from 993 to 997.

### 21.8 Redaction

No API key, prompt, conversation content, personal data, or signing material is
recorded in this section. The seeded provider key is the synthetic
`m3-backup-sentinel-not-a-real-key` sentinel and the conversation is synthetic.

## 22. M3-07.3 Manual device matrix

Recorded 2026-11-10 across six emulators. Only synthetic providers, keys,
prompts, conversations, and attachment bytes were used. Physical devices and
ChromeOS hardware were not available; every result below is an emulator result
and is labelled as such.

### 22.1 Build and method

| Item | Value |
| --- | --- |
| Build | Debug APK `android/app/build/outputs/apk/debug/app-debug.apk` |
| Package | `dev.chatui` |
| Version | `versionName 0.1.0-dev`, `versionCode 215` |
| SDK | `minSdk 26`, `targetSdk 37`, `compileSdk 37` |
| Release artifact | Not used. `app-release-unsigned.apk` cannot be installed; the signed release needs CI signing secrets that are still unavailable (M3-06.4). The task permits a debug build that differs only in signing/minification. |
| Local provider | Host OpenAI-compatible SSE fixture on `127.0.0.1:8765`, reached with `adb reverse tcp:8765 tcp:8765`, configured as a Custom provider at `http://localhost:8765/v1` with a synthetic placeholder key. |
| Data | Synthetic onboarding seed, synthetic prompts, synthetic `synthetic-NN` stream text, a generated gradient PNG, and a one-line text file. |

Each emulator was booted headless with `-no-window -no-audio -no-boot-anim
-gpu swiftshader_indirect -no-snapshot`. One emulator ran at a time. The
provider was configured through the real Providers UI and models were refreshed
through the real `Refresh models` action.

### 22.2 Matrix

| Device / emulator | OS | Form factor | RAM | Build | Attachment picker | SAF | Clipboard image | Drag-and-drop | Share | Deep link | Process death | Backup restore | Result |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `m3_api26_phone` | Android 8.0.0 / API 26 | Phone 1080x2400 | AVD 2 GB | Debug | Pass (DocumentsUI image picker fallback) | Pass | Blocked (no URI on clipboard) | Not available | Pass | Pass (B1 fixed; re-verified) | Pass | Pass (`Backup is not allowed`) | Pass |
| `m3_api31_phone` | Android 12 / API 31 | Phone 1080x2400 | AVD 2 GB | Debug | Pass (DocumentsUI fallback) | Pass | Blocked (no URI on clipboard) | Not available | Pass | Pass (B1 fixed; re-verified) | Pass | Pass (`Backup is not allowed`) | Pass |
| `m3_api34_phone` | Android 14 / API 34 | Phone 1080x2400 | 2,534,120 kB | Debug | Pass (system photo picker) | Pass | Blocked (no URI on clipboard) | Not available | Pass | Pass (B1 fixed; re-verified) | Pass | Pass (`Backup is not allowed`) | Pass |
| `Pixel_10_Pro` | Android 17 / API 37 | Phone 1280x2856 | AVD 2 GB | Debug | Pass (system photo picker) | Pass | Blocked (no URI on clipboard) | Not available | Pass | Pass (B1 fixed; re-verified) | Pass | Pass (`Backup is not allowed`) | Pass |
| `m3_lowram34` | Android 14 / API 34 | Phone 1080x2400 | AVD 1 GB configured; 2,534,116 kB reported | Debug | Pass (system photo picker) | Pass | Blocked (no URI on clipboard) | Not available | Pass | Pass (B1 fixed; re-verified) | Pass | Pass (`Backup is not allowed`) | Pass |
| `m3_tablet` | Android 17 / API 37 | Tablet 2560x1600 @ 320 dpi (1280x800 dp) | AVD 1907 MB | Debug | Pass (system photo picker) | Pass | Blocked (no URI on clipboard) | Not available (no cross-app drag injection) | Pass | Pass (B1 fixed; re-verified) | Pass | Pass (`Backup is not allowed`) | Pass |

B1 was fixed on 2026-11-10. The cold-start deep link was re-run on all six
targets and no longer crashes; a missing conversation renders the not-found
state. The existing-conversation path was re-verified on `m3_api34_phone`.
Every row is now **Pass** for the matrix items; B2 and G1 remain open
verification items and are not hidden in the pass claim.

### 22.3 Item evidence

| Item | Result | Evidence |
| --- | --- | --- |
| Onboarding and key entry | Pass on all six | The welcome step rendered the provider picker; the key-entry step rendered `Connect OpenRouter`, the masked key field, and `Connect`. Validated on API 26, 31, 34, 37, low-RAM, and tablet. No real key was entered. |
| Chat send / stream / stop | Pass on all six | Each target sent a synthetic prompt to the local fixture, rendered streaming `synthetic-NN` text with a visible `Stop` button, and stopped with `Response cancelled. Partial content is preserved.` |
| Photo picker | Pass on all six | API 34/37 and the tablet opened the system `PhotoPickerActivity`/photopicker and attached the selected image. API 26/31 fall back to the DocumentsUI image picker and also attached the image. The text-only model showed the expected image-capability warning. |
| SAF document picker | Pass on all six | The picker accepted the supported `text/*` file (`matrix.txt`) and rejected the image, which matches the `OpenMultipleDocuments` mime filter. The file attached and could be removed. |
| Clipboard image paste | Blocked on all six | The Paste control is present. No available emulator app places an image **URI** on the clipboard. Chrome `Copy image` produces a bitmap clip, and the URI-based `pasteImage` path does not attach it. See blocker B2. |
| Drag-and-drop | Not available | The emulator does not expose a way to inject a cross-app `DragAndDropEvent` carrying `ClipData`; the drop target is present in the composer. Needs a physical tablet or ChromeOS. See gap G1. |
| Share target | Pass on all six | `am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT ...` created a new conversation with the synthetic text pre-filled as the draft and left the existing draft untouched. |
| Deep links | Pass on all six | A cold-start `chatui://chat/<uuidv7>` now opens the chat destination without crashing. A missing conversation renders `Conversation not found`; an existing conversation was re-verified on `m3_api34_phone`. Rejected links (non-v7 ids) are correctly ignored. Warm delivery to a running app works. B1 is closed. |
| Predictive back / back ordering | Pass where applicable | Manifest keeps `android:enableOnBackInvokedCallback="true"`. Back from chat returns to the conversation list on API 26, 31, 34, and 37. API 26/31 have no predictive-back animation. The headless emulator cannot visually confirm the predictive-back preview on API 34/37. |
| Keyboard shortcuts | Pass on API 34; partial on API 31 | API 34: `Ctrl+K` opened the drawer/search and `Ctrl+Shift+O` created and opened a new chat from the list. API 31: `Ctrl+Shift+O` worked; `Ctrl+K` was not observed through `adb input`. API 26 `input` has no `keycombination`, so shortcuts were not injected there. Unit/Compose tests remain the automated guard. |
| Process death | Pass on all six | Backgrounded an active stream, ran `adb shell am kill --user 0 dev.chatui` until `pidof` was empty, relaunched, and saw the persisted synthetic prefix, `The app closed before this response finished. Partial content is preserved.`, `Response cancelled`, and `Regenerate`. See case 26 in section 16. |
| Backup exclusion | Pass on all six | `bmgr backupnow dev.chatui` returned `Package dev.chatui with result: Backup is not allowed` and `Backup finished with result: Success`. See case 27 in section 10. |
| Adaptive layout and insets | Pass | Phone widths show the compact drawer with `Open navigation`; the tablet (1280x800 dp) shows the permanent sidebar with Chats, Archived, New chat, search, and the Conversations/Providers/Settings/Data/Backup destinations. Tapping the composer on every target raised it above the IME (`mInputShown=true`), proving IME insets are applied. |

### 22.4 Release blockers and gaps

| ID | Severity | Issue | Owner | Next action |
| --- | --- | --- | --- | --- |
| B1 | **Closed** | Cold-start deep link to an existing conversation crashed at `ChatUiApp.kt:384` (`navigateToShellRoute`) because the pending intent was collected before the `NavController` had a graph. Fixed on 2026-11-10 by capturing the resolved start destination in the effect key and block, so the pending-intent collector cannot run until the shell has composed the `NavHost`. Re-verified on all six targets. | Android shell/navigation owner (M3-02.2/M3-04.3 follow-up) | Closed. Regression tests: `ColdStartDeepLinkTest` for an existing and a missing conversation. |
| B2 | **High** | Clipboard image paste is not verified and appears URI-only: Chrome `Copy image` creates a bitmap clip and the Paste action attaches nothing. | Attachments/input owner (M3-07.4 or a new follow-up) | Add bitmap-clip support to `pasteImage` or test with an app that puts an image URI on the clipboard on a physical device. Record the supported sources in the product copy. |
| G1 | Medium (verification gap) | Drag-and-drop was not exercised because the emulator cannot inject a cross-app drag with `ClipData`. | Physical tablet/ChromeOS owner (M3-07.5 or M4 device audit) | Run the drag check on a physical tablet or ChromeOS before claiming platform input parity. |

B1 is closed. B2 and G1 remain open verification items; they are not hidden in
the pass claim.

### 22.5 Limitations

- All results are emulator results. No physical phone, tablet, or ChromeOS
  device was available. Results are not claimed to cover real hardware,
  vendor skins, or real photo/document providers.
- The low-RAM emulator is configured for 1 GB but the Android 14 image reports
  about 2.5 GB `MemTotal`; it is the lowest-memory target available, not a true
  1 GB device.
- The photo pickers at API 26/31 use the DocumentsUI fallback rather than the
  modern system photo picker.
- Predictive-back animation and cross-app drag-and-drop cannot be observed on a
  headless emulator.
- The app was installed as the debug build because the signed release artifact
  needs CI signing secrets that remain unavailable. The debug and release builds
  differ only in signing/minification as permitted by the task.
- Backup exclusion was exercised with the platform `bmgr` path on every target.
  Device-to-device transfer was not repeated on each target; section 10 records
  why the D2D transport was not available.

### 22.6 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-lint` | `BUILD SUCCESSFUL`; 439 actionable tasks. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 997 aggregated tests, 0 failures, 0 errors, 0 skipped. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-assemble` | `BUILD SUCCESSFUL`; debug APK produced. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | Pass on the phone emulator (section 21 record). |
| `adb shell bmgr backupnow dev.chatui` on each target | `Backup is not allowed` on all six. |
| `adb shell am kill --user 0 dev.chatui` mid-stream on each target | `pidof` empty; relaunch showed partial content and the interruption notice on all six. |
| `adb shell am start -W -a android.intent.action.VIEW -d chatui://chat/<id>` | Pass on all six after the B1 fix; missing conversation rendered `Conversation not found`, existing conversation opened on `m3_api34_phone`. |

### 22.7 Redaction

No API key, real prompt, conversation content, personal data, or signing
material is recorded in this section. Provider keys were the synthetic
`synthetic-placeholder-key`; prompts and streamed text were synthetic; the
image was a generated gradient; the text file was one synthetic line. The
device matrix records hardware/emulator identifiers and test outcomes only.


## 23. M3-07.4 M2 carryover closeout

Recorded 2026-11-10 on `m3_api34_phone` (Android 14 / API 34), debug build
`0.1.0-dev`. Only synthetic keys, prompts, and local fixture data were used.

### 23.1 Anthropic and Gemini keyed discovery

`ModelService.refreshModels` now resolves the provider's stored `secretId`
through `SecretStore` and passes the key in `ProviderContext` when the preset's
`modelDiscovery.public` flag is false. Public catalogs (OpenRouter, Ollama, LM
Studio, and llama.cpp) still receive no key, so first-run discovery remains
keyless. A missing or unreadable key still falls through to the existing cached
or fallback catalog instead of failing the refresh.

| Test | Result | What it proves |
| --- | --- | --- |
| `ModelServiceTest.refreshModels passes the stored key for a key-protected catalog` | Pass | An Anthropic provider's stored key reaches the gateway context. |
| `ModelServiceTest.refreshModels keeps public OpenRouter discovery keyless` | Pass | OpenRouter discovery still passes `apiKey = null`. |
| `ModelServiceTest.refreshModels degrades when a key-protected catalog has no stored key` | Pass | A key-protected provider with no key degrades to the fallback catalog. |

The existing degraded-refresh tests still pass, so cached and fallback behavior
is unchanged.

### 23.2 Onboarding copy

The welcome copy no longer says non-OpenRouter providers are "coming later". It
now reads: "OpenRouter is recommended for this preview. You can add other
providers from Providers after setup." This matches M2 behavior, where the
first-run flow is OpenRouter-first and other enabled presets are managed from
the Providers screen.

| Test | Result | What it proves |
| --- | --- | --- |
| `OnboardingScreenTest.welcome copy does not claim other providers are coming later` | Pass | The welcome surface contains the new copy and no "coming later" claim. |

### 23.3 Local Ollama, LM Studio, and llama.cpp cleartext

The M3-01.4 policy and device check already cover the emulator host loopback.
This task re-ran the app-level Ollama check and added reachability checks for
the other two local presets.

| Check | Result | Evidence |
| --- | --- | --- |
| Ollama through the production adapter | Pass | `LocalProviderCleartextTest.localOllamaPresetReachesHostServer` passed on `m3_api34_phone` against `http://10.0.2.2:11434/v1`. |
| LM Studio cleartext reachability | Pass | A disposable local server on host `127.0.0.1:1234` returned `HTTP/1.0 200 OK` with a JSON model list from the emulator at `10.0.2.2:1234`. |
| llama.cpp cleartext reachability | Pass | A disposable local server on host `127.0.0.1:8080` returned `HTTP/1.0 200 OK` with a JSON model list from the emulator at `10.0.2.2:8080`. |

The disposable servers were stopped after the check. The app-level Ollama test
proves the production `CleartextPolicyInterceptor` and `OllamaAdapter` path; the
LM Studio and llama.cpp checks prove the same loopback cleartext route is
reachable. No trust-all manager or certificate bypass was added.

### 23.4 Math fallback

Math remains the documented safe fallback: standalone `$$` blocks and fenced
`math`/`latex`/`tex` blocks render their exact source in a bounded block, inline
`$...$` stays raw text, and no math typesetting library is bundled. The behavior
is documented in `ChatMarkdown.kt` and covered by existing tests.

| Test | Result |
| --- | --- |
| `ChatMarkdownTest.renders inline math as raw source without breaking the message` | Pass |
| `ChatMarkdownTest.renders standalone block math as a raw-source block` | Pass |
| `ChatMarkdownTest.renders fenced math as a raw-source block` | Pass |
| `ChatMarkdownTest.keeps incomplete streaming math as raw text` | Pass |
| `MarkdownSupportTest.splits standalone block math and leaves inline math as text` | Pass |
| `MarkdownSupportTest.leaves unterminated block math and fenced code untouched` | Pass |

No production code changed for this item.

### 23.5 Attachment input evidence

The device matrix in section 22.3 already records the attachment input paths.
This task links that evidence instead of duplicating the tests.

| Input path | Result | Evidence |
| --- | --- | --- |
| Photo picker | Pass on all six targets | Section 22.3, "Photo picker". |
| SAF document picker | Pass on all six targets | Section 22.3, "SAF document picker". |
| Clipboard image paste | Blocked | Section 22.3 and blocker B2. |
| Drag-and-drop | Not available on emulators | Section 22.3 and gap G1. |

### 23.6 Carryover closeout

| M2 carryover | Status | Owner / evidence |
| --- | --- | --- |
| Android local Ollama over cleartext HTTP | Closed | M3-01.4 section 12.3 and section 23.3. |
| Android Anthropic and Gemini discovery without a key | Closed | Section 23.1. |
| Android math typesetting | Closed as the documented raw-source fallback | Section 23.4. |
| Android device-level input and visual checks | Closed for photo picker and SAF; clipboard image paste deferred; drag-and-drop deferred | Section 22.3, B2, and G1. |
| Web case 23 automated coverage | Deferred (web-only, not M3 Android work) | M4 polish or follow-up. |
| Live provider smoke and valid-key success | Deferred to M4 release check | M4 release check. |
| Local and Custom CORS | Deferred to M4 release check | M4 release check. |

Clipboard image paste (B2) and drag-and-drop (G1) remain open verification
items with owners in section 22.4. They are not claimed as passing.

### 23.7 Verification commands

| Command | Result |
| --- | --- |
| `ANDROID_HOME=$HOME/Android/Sdk make android-lint` | `BUILD SUCCESSFUL`; 439 actionable tasks. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-test` | `BUILD SUCCESSFUL`; 1001 aggregated tests, 0 failures, 0 errors, 0 skipped. |
| `ANDROID_HOME=$HOME/Android/Sdk make android-assemble` | `BUILD SUCCESSFUL`; debug APK produced. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL`; 10 tests, 0 failures, 0 errors, 2 skipped (manual restart/backup phases). |
| `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.chatui.security.LocalProviderCleartextTest` | `BUILD SUCCESSFUL`; Ollama cleartext test passed. |

The 4 new JVM/Robolectric tests account for the increase from 997 to 1001.

### 23.8 Redaction

No real API key, prompt, conversation content, personal data, or signing
material is recorded in this section. The provider key was the synthetic
`sk-test-secret-key`; the local servers returned synthetic model ids only.

## 24. M3-07.5 Parity, regressions, and exit evidence

Recorded 2026-11-10 on `m3_api34_phone` (Android 14 / API 34), debug build
`0.1.0-dev`. Only synthetic data was used. This is the final M3 gate record.

### 24.1 Exit decision

**M3 exit gate is met.** Release blocker B1 from section 22.4 was fixed on
2026-11-10. The pending-intent collector now captures the resolved start
destination in its effect key and block, so it cannot navigate before the shell
has composed the `NavHost` and set the navigation graph. The cold-start deep
link was re-run on all six device-matrix targets with no crash; a missing
conversation renders the not-found state, and an existing conversation was
re-verified on `m3_api34_phone`. The milestone status table is updated to
Complete.

### 24.2 Gate command results

| Command | Result | Bounded evidence |
| --- | --- | --- |
| `make contracts-check` | Pass | `Contract validation passed.` |
| `make android-lint` | Pass | `BUILD SUCCESSFUL`; 439 actionable tasks. |
| `make android-test` | Pass | `BUILD SUCCESSFUL`; 1003 aggregated tests, 0 failures, 0 errors, 0 skipped. |
| `make android-assemble` | Pass | `BUILD SUCCESSFUL`; debug APK produced. |
| `./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease` | Pass | `BUILD SUCCESSFUL`; release lint, unit tests, unsigned release APK, and release AAB produced. |
| `./gradlew :app:connectedDebugAndroidTest` | Pass | `BUILD SUCCESSFUL`; 10 tests, 0 failures, 0 errors, 2 skipped (manual restart/backup phases). |
| `./gradlew :app:verifyRoborazziDebug` | Pass after golden update | `BUILD SUCCESSFUL`; 4 onboarding goldens updated for the M3-07.4 copy change, then verified. |
| `./gradlew :app:testDebugUnitTest --tests 'dev.chatui.ColdStartDeepLinkTest'` | Pass | 2 tests, 0 failures; cold-start deep link to an existing and a missing conversation. |
| Cold-start deep-link device re-run on all six matrix targets | Pass | No `FATAL EXCEPTION` or `Navigation graph has not been set`; missing conversation rendered `Conversation not found`; existing conversation opened on `m3_api34_phone`. |

The first `verifyRoborazziDebug` run failed on `onboardingPhone` and
`onboardingTablet` because the M3-07.4 welcome-copy change was not reflected in
the goldens. The four onboarding goldens (phone/tablet, light/dark) were
regenerated with `:app:recordRoborazziDebug`; the verify run then passed. This
is a test-fixture update, not a product regression.

### 24.3 M1 and M2 Android regressions

`make android-test` runs the full Android JVM/Robolectric suite, including the
M1 and M2 provider, content, persistence, search, export/import, attachment, and
settings tests. Result: 1003 tests, 0 failures, 0 errors, 0 skipped. No M1 or M2
Android regression was found. The 2 new `ColdStartDeepLinkTest` cases bring the
M3-07.4 count from 1001 to 1003.

### 24.4 Cases 26 and 27

| Case | Evidence | Result |
| --- | --- | --- |
| Case 26 process-death streaming recovery | Section 16 and [case-26-process-death.md](case-26-process-death.md) | Pass; partial content preserved, message marked cancelled, regenerate works. |
| Case 27 backup exclusion | Section 10 | Pass; `Backup is not allowed` on the source and fresh targets, no sentinel in plaintext files. |

### 24.5 Release artifacts, mapping, and measurements

| Artifact | Bytes | SHA-256 |
| --- | --- | --- |
| Debug APK (`app-debug.apk`) | 18,827,705 | `f99cb553b061d70a16d924d9b754963004037abb4c459659021069a755fd18b6` |
| Release APK, unsigned (`app-release-unsigned.apk`) | 2,894,958 | `abc1e1aeee3b369e345c2e1cd21fe206be787ae7bd4b674e2f7f410f1cae700e` |
| Release AAB (`app-release.aab`) | 7,043,129 | `d3c7b3f83cc2808f945022adfb182cf66a4305795bcf97f2190940e6631417c8` |
| R8 mapping (`mapping/release/mapping.txt`) | 59,491,325 | `9fbd936c3c0c7f4bbd8c33df711282c652127936b29ce38c341469b38213655a` |

The signed release APK was not produced because CI signing secrets remain
unavailable (M3-06.4). The unsigned release APK and AAB are the local build
outputs. Startup median remains **990 ms** against the 1,500 ms budget, and the
size budgets remain met (debug 17.96 MiB under 20 MB; release 2.77 MiB under
15 MB) as recorded in section 20. No budget was missed.

### 24.6 Screenshots

`./gradlew :app:verifyRoborazziDebug` passes with the updated onboarding
goldens. The screenshot suite covers onboarding, chat, model picker, provider
list/detail, settings, data, and conversation list in light/dark and
phone/tablet configurations, as recorded by M3-05.3.

### 24.7 Accessibility and device matrix

- Accessibility baseline and TalkBack: section 21.
- Manual device matrix: section 22.
- M2 carryover closeout: section 23.

The device matrix is **Pass** after the B1 fix and the six-target deep-link
re-run. Photo picker, SAF, share target, process death, backup exclusion,
adaptive layout, IME insets, and back ordering passed on every target. Clipboard
image paste (B2) and drag-and-drop (G1) remain open verification items.

### 24.8 Residual risks and approved deferrals

| Item | Status | Owner / target |
| --- | --- | --- |
| B1 cold-start deep-link crash | **Closed** | Fixed in `ChatUiApp.kt`; regression tests in `ColdStartDeepLinkTest`; re-verified on all six targets. |
| B2 clipboard image paste is URI-only | Open high-severity verification item | Attachments/input owner (M3-07.4 follow-up or M4) |
| G1 drag-and-drop not exercised | Open verification gap | Physical tablet/ChromeOS owner (M4 device audit) |
| CI signing secrets unavailable | Open release blocker for signed artifacts | M3-06.2/M3-06.4 owner |
| No physical device or ChromeOS hardware | Environment limitation | M4 device audit |
| Math typesetting | Accepted safe fallback (raw source) | Documented in section 23.4 |
| Predictive-back animation | Not visually confirmed on headless emulator | M4 device audit |

### 24.9 Redaction

No real API key, prompt, conversation content, personal data, or signing
material is recorded in this section. The deep-link id is synthetic, the
provider key is the synthetic `sk-test-secret-key`, and the artifacts are local
build outputs.
