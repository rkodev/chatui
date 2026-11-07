# M3 Scope Decisions

- Status: Confirmed 2026-11-06; one downstream decision blocked (CI signing secrets).
- Owner: M3 milestone owner.
- Source plan: [M3 Android Depth Implementation Plan](../M3-android-depth-implementation-plan.md) sections 5, 7, and 10.
- Scope: Records decisions only. No application code, build file, manifest, or test is changed by this file.
- Secret policy: This file records secret names and availability only. It contains no key, password, keystore path, prompt, conversation, or personal data.

## Decision summary

| # | Decision | Outcome | Affected tasks |
| --- | --- | --- | --- |
| 1 | Share-target draft behavior | New conversation with shared text; preserve all existing drafts | M3-02.1, M3-04.2, M3-05.4 |
| 2 | Outbound conversation share | Active in M3-02.4; text and Markdown only | M3-02.4, M3-05.4, M3-07.5 |
| 3 | Adaptive navigation | Permanent sidebar at >= 840 dp; modal drawer below; bottom navigation removed | M3-03.1, M3-03.2, M3-03.3, M3-03.4, M3-05.2, M3-05.4, M3-07.3 |
| 4 | Cleartext policy for local providers | Base cleartext enabled with a central RFC1918/private-host allowlist and warning | M3-01.4, M3-01.5, M3-07.4, M3-07.5 |
| 5 | Keyset rotation | Provider API-key rotation satisfies M3; Tink keyset rewrap deferred | M3-01.1, M3-01.3, M3-07.5 |
| 6 | Baseline-profile device | Local emulator `Medium_Phone_API_36.1` | M3-06.3, M3-07.1 |
| 7 | Instrumentation environment | Local emulator `Medium_Phone_API_36.1`; no physical device | M3-05.1, M3-05.2, M3-05.4, M3-07.3 |
| 8 | Signing secrets | CI secrets unavailable; release-signing gate blocked | M3-06.2, M3-06.4, M3-07.5 |

## 1. Share-target draft behavior

- **Decision:** A share target intent opens a new conversation containing the shared text. Every existing draft, including the active conversation's draft, is preserved unchanged.
- **Rationale:** This is deterministic, satisfies the milestone requirement that sharing must not lose current drafts, and avoids overwriting work in the active composer. It also behaves the same on cold and warm starts.
- **Rules:**
  - Accept `text/plain` only. If multiple items are shared, use the first `text/plain` item and ignore unsupported types.
  - If the shared text is empty or whitespace-only, do not create a conversation; return to the current screen.
  - Do not merge into the active composer, even when it is empty.
  - The new conversation is not automatically sent; the user reviews and sends it.
- **Date:** 2026-11-06.
- **Owner:** M3 milestone owner; implemented by M3-02.1.
- **Impact:** M3-02.1 implements intake; M3-04.2 must persist the pre-existing drafts across the share; M3-05.4 covers cold-start, warm-start, and existing-draft cases; M3-07.3 includes a device smoke check.

## 2. Outbound conversation share

- **Decision:** Outbound conversation share is **active** in M3-02.4. It shares the current conversation as plain text and as Markdown through the Android Sharesheet.
- **Rationale:** Spec 9.10 lists outbound sharing, and the plan says to include it unless the reviewer explicitly declares it future scope. No reviewer deferral was recorded, so it stays in M3.
- **Scope limits:**
  - Text and Markdown only. No attachment export, no JSON export, no database export, and no provider credentials.
  - The share payload is generated from the visible conversation and must not include hidden system prompts or secrets.
  - If M3-02.4 cannot be completed, it must be explicitly marked `Cancelled` with a reason in the task file and index; it must not be silently dropped.
- **Date:** 2026-11-06.
- **Owner:** M3 milestone owner; implemented by M3-02.4.
- **Impact:** M3-02.4 implements the share; M3-05.4 covers empty, long, and Markdown conversations; M3-07.5 records the exit evidence.

## 3. Adaptive navigation

- **Decision:** Use a permanent navigation sidebar at width >= 840 dp and a modal navigation drawer below 840 dp. Remove the bottom navigation bar; do not retain it as a secondary path.
- **Rationale:** The spec requires an adaptive shell, and the current bottom navigation does not satisfy it. Keeping both a bottom bar and a sidebar/drawer would duplicate navigation, consume vertical space, and create two sources of truth for the selected destination.
- **Reachability rule:** Every existing M2 destination must remain reachable from the sidebar or drawer, including chat, providers, settings, data/export-import, and search. No destination may be removed or hidden behind an unreachable screen.
- **State rule:** The selected destination and the drawer open/closed state survive configuration changes and are restored after process death as specified by M3-04.3.
- **Date:** 2026-11-06.
- **Owner:** M3 milestone owner; implemented by M3-03.1.
- **Impact:** M3-03.1 builds the shell; M3-03.2 handles insets and IME; M3-03.3 orders predictive back for the drawer; M3-03.4 adds shortcuts; M3-05.2 and M3-05.4 lock every destination with navigation tests; M3-07.3 includes a phone and tablet smoke check.

## 4. Cleartext policy for local providers

- **Decision:** Enable base cleartext in the Android network security config and enforce a central private-host allowlist in the application network layer, with a user-visible warning when a cleartext local provider is configured.
- **Rationale:** Local providers such as Ollama commonly run on a LAN address, not only on loopback. Android network-security-config XML cannot express private IPv4 CIDR ranges, so a declarative RFC1918 allowlist is impossible. Enabling base cleartext and enforcing the allowlist in one central policy keeps LAN providers usable while rejecting public cleartext hosts.
- **Allowed cleartext hosts:**
  - Loopback: `localhost`, `127.0.0.1`, `::1`.
  - Android emulator host loopback: `10.0.2.2`.
  - mDNS hostnames ending in `.local`.
  - RFC1918 IPv4: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`.
  - IPv6 unique-local `fc00::/7` and link-local `fe80::/10`.
- **Denied cleartext hosts:** Every other host, including public IPv4/IPv6 addresses, CGNAT `100.64.0.0/10`, and any hostname that resolves to a public address. HTTPS remains allowed for all hosts.
- **Enforcement:**
  - `network_security_config.xml` sets `base-config cleartextTrafficPermitted="true"` with a comment that the XML cannot express CIDR ranges. This is the platform-level enabler, not the allowlist.
  - A single central policy in the network layer validates every provider base URL before a cleartext request. It accepts only the allowed literals, `.local` names, and addresses inside the allowed ranges. It rejects the request with a user-visible error otherwise.
  - The policy checks resolved addresses, not only the typed hostname, so a `.local` name that resolves to a public address is rejected.
  - M3-01.5 adds the cleartext warning and the certificate-validation guard. The warning is shown when a cleartext local provider is saved or used.
  - Unit tests cover allow and deny cases for every range, loopback, emulator loopback, `.local`, public addresses, and a `.local` name resolving to a public address.
- **Android network-security-config limitation:** XML domain entries cannot express RFC1918, IPv6 ULA, or link-local CIDR ranges. The base cleartext flag is therefore required, and the real allowlist must live in application code. This trade-off is accepted and must be documented in M3-01.4 and the exit evidence.
- **Date:** 2026-11-06.
- **Owner:** M3 milestone owner; implemented by M3-01.4 and M3-01.5.
- **Impact:** M3-01.4 implements the config and central policy; M3-01.5 implements the warning and guard; M3-07.4 verifies platform input behavior; M3-07.5 records the policy and tests as exit evidence. The M2 local Ollama deferral is satisfied for loopback, emulator, `.local`, and RFC1918 LAN addresses.

## 5. Keyset rotation

- **Decision:** Provider API-key rotation satisfies the M3 key-rotation requirement. Tink keyset rewrap is deferred and is not an M3 deliverable.
- **Rationale:** Provider-key rotation already exists and is the user-facing rotation workflow. Tink keyset rewrap would add scope without changing the user-visible guarantee, and old ciphertext remains readable under the current keyset.
- **Deferral:** Tink keyset rewrap is recorded as a post-M3 security follow-up. It must not be claimed as complete in M3 evidence.
- **Date:** 2026-11-06.
- **Owner:** M3 milestone owner; M3-01.1 and M3-01.3 record the provider-key rotation proof.
- **Impact:** M3-01.1 proves ciphertext-only storage and provider-key rotation; M3-01.3 audits key lifecycle and logging; M3-07.5 records the deferral so the exit gate does not overclaim keyset rewrap.

## 6. Baseline-profile device

- **Decision:** Use the local emulator `Medium_Phone_API_36.1` as the recorded baseline-profile and startup-benchmark target. A physical mid-tier device is not available.
- **Rationale:** The preflight recorded seven AVDs and no connected physical device. A recorded emulator profile is reproducible and is the plan's fallback when no mid-tier physical device is available.
- **Methodology:**
  - Record the AVD name, API level, ABI, build type, and run count with every result.
  - Use three cold-start runs and report the median, not a single best run.
  - Use the same procedure for every comparison.
  - If a physical mid-tier device becomes available, it takes precedence and the emulator result is retained as secondary evidence.
- **Date:** 2026-11-06.
- **Owner:** M3 milestone owner; implemented by M3-06.3.
- **Impact:** M3-06.3 generates the baseline profile and benchmark; M3-07.1 records the performance and size evidence. A missing emulator at execution time blocks those tasks, not the decision.

## 7. Instrumentation environment

- **Decision:** Run `connectedDebugAndroidTest` on the local emulator `Medium_Phone_API_36.1`. No physical device is available.
- **How it runs:**
  - Boot the AVD with `ANDROID_HOME=$HOME/Android/Sdk $HOME/Android/Sdk/emulator/emulator -avd Medium_Phone_API_36.1`.
  - Wait for `adb wait-for-device` and a completed boot.
  - Run the instrumentation task with `ANDROID_HOME=$HOME/Android/Sdk` set, using the Gradle `connectedDebugAndroidTest` task for the app module.
  - Record the AVD name, API level, ABI, and test result in `M3-verification.md`.
- **Fallback:** If the emulator cannot boot or the instrumentation task cannot run, mark M3-05.1, M3-05.2, M3-05.4, and M3-07.3 `Blocked` with the exact failure. Do not claim a pass from JVM/Robolectric tests alone.
- **Date:** 2026-11-06.
- **Owner:** M3 milestone owner; M3-05.1 owns the harness and the first run.
- **Impact:** M3-05.1 sets up the harness; M3-05.2 and M3-05.4 add instrumentation coverage; M3-07.3 uses the same environment for the device matrix.

## 8. Signing secrets

- **Decision:** CI signing secrets are **unavailable**. The CI-signed release gate is blocked until they are provisioned. Release jobs must fail when the secrets are absent; a debug key must never be substituted.
- **Required secret names (names only, no values):**
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`
- **Rationale:** The preflight found no signing configuration or secret references in `.github/workflows/android.yml`. A `.jks` exists outside the repository but its purpose and credentials are unverified, so it cannot be treated as the release keystore.
- **Required action:** The repository administrator must provision the four secrets in CI and verify the release keystore identity. M3-06.2 and M3-06.4 must then run a signed release build and record the result without printing secret values.
- **Date:** 2026-11-06.
- **Owner:** Repository administrator and the M3-06.2 / M3-06.4 owner.
- **Impact:** M3-06.2 and M3-06.4 are blocked for signed-release verification; M3-07.5 cannot claim a signed release exit gate until the secrets exist. This is a downstream blocker, not a blocker for the other M3 implementation tasks.

## Unresolved items and owners

| Item | Status | Owner | Affected tasks |
| --- | --- | --- | --- |
| CI signing secrets and release keystore identity | Blocked: secrets unavailable; keystore unverified | Repository administrator; M3-06.2 / M3-06.4 owner | M3-06.2, M3-06.4, M3-07.5 |
| Physical mid-tier device for baseline profile and device matrix | Unavailable; emulator fallback recorded | M3-07.3 owner | M3-06.3, M3-07.1, M3-07.3 |
| `.local` mDNS resolution on Android | To be validated during implementation; RFC1918 literal addresses remain supported if it fails | M3-01.4 owner | M3-01.4, M3-01.5 |
| Tink keyset rewrap | Deferred to post-M3 security follow-up | Post-M3 security owner | M3-01.1, M3-01.3, M3-07.5 |

## Review against the M3 plan

- Section 5 decisions: share-target behavior, phone navigation, cleartext policy, outbound share, keyset rotation, baseline-profile target, instrumentation environment, and signing secrets are all recorded above.
- Section 10 open questions: key rotation is provider-key rotation; outbound share is active; bottom navigation is removed with every destination preserved; cleartext uses base cleartext plus a central RFC1918/private-host allowlist; signing secrets are unavailable and recorded as a downstream blocker; `.local` and RFC1918 addresses are the supported local-provider workflow.
- Section 7 risks addressed: X-03 (NSC CIDR limitation), X-04 (navigation regression), X-06 (signing secrets), X-07 (baseline methodology), and X-09 (device availability) have explicit decisions or blockers.
- No secret, key, password, keystore path, prompt, conversation, or personal data is recorded in this file.
