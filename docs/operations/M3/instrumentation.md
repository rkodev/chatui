# Android instrumentation tests

The app module has an `androidTest` source set with a Compose smoke test and
shared fake provider/context/message fixtures.

## Prerequisite

- Android SDK with `platform-tools` and `emulator` on `PATH`, or set
  `ANDROID_HOME` to the SDK root.
- One connected device or a booted emulator. The M3 decision record selects the
  local `Medium_Phone_API_36.1` AVD.

Start the selected emulator:

```bash
emulator -avd Medium_Phone_API_36.1
adb wait-for-device
```

Confirm the target:

```bash
adb devices
```

## Commands

Compile the instrumentation sources without a device:

```bash
cd android && ./gradlew :app:compileDebugAndroidTestKotlin
```

Run the instrumentation suite on the connected device or emulator:

```bash
cd android && ./gradlew :app:connectedDebugAndroidTest
```

The HTML report is written to
`android/app/build/reports/androidTests/connected/debug/index.html`.

## Shared fixtures

Reusable fakes live in `android/app/src/sharedTest/kotlin/dev/chatui/testing/`
and are added to both the unit-test and instrumentation source sets:

- `FakeProviderAdapter` - controllable `StreamEvent` flow.
- `FakeChatContextResolver` and `FakeChatConversationSource`.
- `FakeChatMessageStore`.
- Synthetic `testProvider`, `testModel`, `testConversation`, `testMessage`, and
  `testChatContext` builders.

Use only synthetic data. Never add real API keys, prompts, or conversations.
