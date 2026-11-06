# Android Background Behavior

- Status: Current for M3
- Scope: What Android streaming does and does not guarantee in this app.
- Source: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 9.4 and 9.9;
  [M3 plan](../M3-android-depth-implementation-plan.md) sections 3.3 and 4.5.
- Related evidence: [M3 verification](M3-verification.md) sections 14 and 16;
  [case 26 process death](case-26-process-death.md).

This document describes the behavior that exists in M3. It deliberately does
not promise guaranteed background streaming. Long responses that must survive
backgrounding are a future enhancement, not an M3 feature.

## 1. Streaming while foregrounded

While the app is visible and the chat screen is open, a response streams
normally:

- `ChatRunner` owns one provider stream per conversation and runs it in the
  chat `ViewModel`'s `viewModelScope`.
- Stream events are reduced into a `StreamingState` and rendered as partial
  text.
- Partial text is persisted to Room about once per second
  (`DEFAULT_PERSIST_INTERVAL_MS = 1_000`), and again on completion,
  cancellation, error, and explicit pause.
- A configuration change such as rotation does not interrupt the stream,
  because the `ViewModel` survives activity recreation. This is covered by
  `ConfigChangeStreamTest` (M3 verification section 14).

Foreground streaming is the supported, tested path.

## 2. Best-effort grace period when backgrounded

When the app moves to the background, the process usually keeps running for a
short time. During that window the stream may continue, because nothing in the
app cancels it on `onStop` or on a lifecycle transition:

- The stream is not tied to the activity lifecycle, so backgrounding alone does
  not stop it.
- The OS may keep the process alive and let the network call continue, or it
  may freeze, throttle, or kill the process at any time. This is outside the
  app's control.
- There is no foreground service and no wake lock, so the app has no elevated
  priority while backgrounded.

Treat this as a best-effort grace period, not a guarantee. A short response may
finish; a long response may be interrupted when the OS reclaims the process.

## 3. Process death during streaming

If Android kills the app process mid-stream, the stream stops. The app does not
try to resume it in the background. On the next launch:

- The last persisted partial text is preserved. Because persistence happens
  about once per second, up to roughly one second of text produced after the
  last write can be lost. This is a deliberate trade-off, not a bug.
- `AppStartup` runs `InterruptedMessageRecovery` before the shell renders. Any
  message left in the `streaming` state is marked `cancelled` with
  `finishReason=cancelled`, so it is never stuck as if it were still running.
- The chat shows an interruption notice explaining that the app closed before
  the response finished and that partial content is preserved.
- The user can regenerate the response. Regeneration reuses the same assistant
  message row and sequence, so it does not branch or duplicate history.

This path is documented and exercised by the case 26 procedure and recorded in
M3 verification section 16.

## 4. No foreground service in M3

M3 does not add a foreground service, a persistent notification, or a wake
lock. There is no `startForeground` call and no `ForegroundService` component.

Consequences:

- The app cannot claim to keep streaming reliably while backgrounded or while
  the screen is off.
- The OS is free to stop the process, and the process-death behavior in
  section 3 applies.

A foreground service with a persistent notification is the intended future
enhancement if long background responses become a requirement. It is out of
scope for M3.

## 5. No WorkManager or background sync before backup exists

M3 does not schedule any WorkManager job and does not run background sync.
There is no `WorkManager` dependency usage, no periodic worker, and no
background upload or download of conversations.

Background sync is intentionally deferred until a backup feature exists. When
backup is implemented, it is expected to use WorkManager with network and
battery constraints, as described in spec section 9.9. That work is not part of
M3.

## 6. User-facing expectations and known limitations

What users can expect:

- Responses stream while the chat is open and the app is in the foreground.
- Rotating the device or recreating the activity does not lose the active
  stream or its partial text.
- If the app is backgrounded briefly, a response may keep streaming, but this
  is not guaranteed.
- If the app is killed, the partial response is kept, the message is marked
  interrupted, and the user can regenerate it.

Known limitations:

- Long background responses are not guaranteed. The OS may stop the process.
- Up to about one second of streamed text can be lost on process death.
- There is no notification or foreground service keeping the stream alive.
- There is no background sync, retry queue, or offline continuation.
- Streaming does not resume automatically after process death; the user must
  regenerate.

## 7. Audit: no background service or sync was added

The M3 implementation was checked for background components:

```bash
rg -n "WorkManager|ForegroundService|startForeground" android/app android/feature android/core || true
```

Result: no matches in production sources. The manifest declares only
`MainActivity`; it has no service, receiver, or WorkManager initializer. No
foreground service, notification, wake lock, or background sync job exists in
M3.

## 8. Redaction

This document contains no API key, prompt, conversation content, personal data,
or signing material. It describes behavior only.
