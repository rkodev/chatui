# M3 Verification Evidence

- Status: Baseline recorded 2026-11-06; M3 implementation in progress. Sections 9-12 record completed task evidence.
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
