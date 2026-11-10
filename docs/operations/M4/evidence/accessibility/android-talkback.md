# Android accessibility notes (M4-01.1)

Date: 2026-11-10
Client: Android (required).
Device/emulator: none connected in this environment.
TalkBack version: not available (no device/emulator).
Reduced-motion method: Compose `ChatUiTheme(reducedMotion = true)` in the
Roborazzi screenshot tests; the app also follows the operating-system setting.

## Baseline

M3 Android accessibility work is the baseline and was not reimplemented. The
M3 exit gate is complete and recorded in
[M3-verification.md](../../M3/M3-verification.md) section 24.

## Checks re-run

| Check | Evidence | Result |
| --- | --- | --- |
| 48 dp touch targets | `feature/chat/.../ChatScreenTest.kt` header/composer actions; `feature/conversations/.../ConversationListScreenTest.kt` actions button | Pass |
| Accessible names / content descriptions | `ConversationListScreenTest` asserts `Conversation actions`; chat live region test asserts the announcement | Pass |
| Polite live region for streaming status | `ChatScreenTest` asserts `LiveRegionMode.Polite` and the announcement text | Pass |
| Predictive back ordering | `app/src/test/java/dev/chatui/ShellManifestTest.kt` asserts `android:enableOnBackInvokedCallback="true"`; shell back handling is covered by M3 tests | Pass |
| Reduced motion | `app/src/test/java/dev/chatui/screenshots/ScreenshotTest.kt` renders with `reducedMotion = true` | Pass |
| Dynamic color | Optional and not implemented; recorded as optional per the release decision | Recorded |

## Commands

```bash
make android-lint   # passed
make android-test   # passed
cd android && ./gradlew :app:connectedDebugAndroidTest
```

`connectedDebugAndroidTest` could not run because no device or emulator is
connected (`DeviceException: No connected devices!`). This is an environment
limitation, not a code failure. The Robolectric/Compose unit tests above cover
the semantics, touch targets, live region, predictive-back manifest flag, and
reduced-motion rendering. A physical-device TalkBack pass remains a release
gate item for the device-evidence task, not a regression in this task.

## Dynamic color

Dynamic color is optional per spec section 3.8 and is not implemented. The
release decision does not require it for phase 1.
