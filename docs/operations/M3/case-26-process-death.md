# Case 26 - Process-death streaming recovery

Repeatable device/emulator procedure for spec section 15.2 case 26. It proves
that killing the app process during a stream preserves the last persisted
partial content, marks the message interrupted instead of `streaming`, shows the
interruption explanation, and allows regeneration without branching history.

Use synthetic fixture text only. Never use a real personal API key, prompt, or
conversation in this procedure or its evidence.

## Environment

| Item | Value used for the recorded run |
| --- | --- |
| Device | `Medium_Phone_API_36.1(AVD) - 16` (`emulator-5554`) |
| API level | 36 |
| Build | debug, `versionName 0.1.0`, `versionCode 1` |
| Host fixture | local OpenAI-compatible SSE server on port `8765` |
| Transport | `adb reverse tcp:8765 tcp:8765` |

Set the SDK path for the commands below:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

## 1. Start the local SSE fixture

The fixture serves a synthetic OpenAI-compatible model and a slow stream. The
model entry uses the OpenRouter-shaped metadata that the current custom-provider
catalog path reads, so the model is selectable without a real provider.

```python
#!/usr/bin/env python3
"""Local OpenAI-compatible SSE fixture for case 26. Synthetic text only."""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
CHUNKS = 40
DELAY = 0.5


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))
        sys.stderr.flush()

    def _json(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") in ("/v1/models", "/models"):
            self._json({
                "object": "list",
                "data": [{
                    "id": "custom-model",
                    "name": "Fixture Model",
                    "object": "model",
                    "created": 0,
                    "owned_by": "fixture",
                    "architecture": {
                        "modality": "text->text",
                        "input_modalities": ["text"],
                        "output_modalities": ["text"],
                    },
                    "supported_parameters": [
                        "temperature", "top_p", "max_tokens", "stop", "stream",
                    ],
                    "context_length": 8192,
                    "top_provider": {
                        "context_length": 8192,
                        "max_completion_tokens": 1024,
                    },
                    "pricing": {"prompt": "0", "completion": "0"},
                }],
            })
            return
        self._json({"error": {"message": "not found"}}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length:
            self.rfile.read(length)
        if self.path.rstrip("/") not in ("/v1/chat/completions", "/chat/completions"):
            self._json({"error": {"message": "not found"}}, 404)
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        def event(obj):
            self.wfile.write(("data: " + json.dumps(obj) + "\n\n").encode())
            self.wfile.flush()

        event({
            "id": "fixture-response-1",
            "object": "chat.completion.chunk",
            "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}],
        })
        for i in range(CHUNKS):
            event({
                "id": "fixture-response-1",
                "object": "chat.completion.chunk",
                "choices": [{
                    "index": 0,
                    "delta": {"content": "synthetic-%02d " % i},
                    "finish_reason": None,
                }],
            })
            time.sleep(DELAY)
        event({
            "id": "fixture-response-1",
            "object": "chat.completion.chunk",
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
        })
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
```

Run it on the host:

```bash
python3 sse_fixture.py 8765
```

Expose it to the emulator:

```bash
adb reverse tcp:8765 tcp:8765
```

## 2. Install and prepare the app

```bash
cd android
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:installDebug
```

For a fresh install, either complete onboarding with a disposable key or use
this test-only seed to skip onboarding. The seed sets only
`onboarding_completed=true`; it contains no key or conversation data.

```bash
python3 - <<'PY'
key = b"onboarding_completed"
value = b"\x08\x01"
entry = b"\x0a" + bytes([len(key)]) + key + b"\x12" + bytes([len(value)]) + value
data = b"\x0a" + bytes([len(entry)]) + entry
open("/tmp/settings.preferences_pb", "wb").write(data)
PY
adb shell am force-stop dev.chatui
adb shell run-as dev.chatui mkdir -p files/datastore
adb push /tmp/settings.preferences_pb /data/local/tmp/settings.preferences_pb
adb shell run-as dev.chatui cp \
  /data/local/tmp/settings.preferences_pb files/datastore/settings.preferences_pb
adb shell am start -n dev.chatui/.MainActivity
```

## 3. Configure the Custom provider

1. Open the navigation drawer, then **Providers**.
2. Scroll to **Add a Custom provider**.
3. Set a synthetic label, for example `Fixture`.
4. Set the base URL to `http://localhost:8765/v1`.
5. Keep authentication on **Bearer** and enter a synthetic placeholder key.
6. Tap **Add Custom provider**.
7. Open the new provider and tap **Refresh models**. The fixture model
   `custom-model` should appear.
8. Return to **Conversations** and start a new chat.

## 4. Kill the process during streaming

1. Send a synthetic message. The fixture streams one chunk every 0.5 seconds
   for about 20 seconds.
2. Wait until partial text is visible and the composer shows **Stop**.
3. Background the app:

   ```bash
   adb shell input keyevent 3
   ```

4. Wait about two seconds, then kill the process:

   ```bash
   adb shell am kill --user 0 dev.chatui
   ```

   `am kill` only kills a background process. If `pidof` still returns a pid,
   wait a moment and repeat. Do not use `am force-stop`; it is not the
   process-death model used here.

5. Confirm the process is gone:

   ```bash
   adb shell pidof dev.chatui
   ```

   The command must print nothing.

## 5. Relaunch and verify

```bash
adb shell am start -n dev.chatui/.MainActivity
```

The last-active conversation restores. Scroll to the interrupted assistant
message and verify:

- The partial synthetic text is still present.
- The message is not stuck at `streaming`; it shows the cancelled state.
- The explanation is visible:
  `The app closed before this response finished. Partial content is preserved.`
- **Retry** and **Regenerate** are available.
- Tapping **Regenerate** starts a new stream in the same assistant row. After it
  completes, the conversation still has the same number of messages; no branch
  or duplicate assistant row is created.

## 6. Recorded result

Recorded 2026-11-06 on `Medium_Phone_API_36.1(AVD) - 16` (API 36,
`emulator-5554`), debug build `0.1.0` (`versionCode 1`).

| Check | Result |
| --- | --- |
| Background process kill during streaming | Pass. `adb shell am kill --user 0 dev.chatui` left no process. |
| Partial content after relaunch | Pass. The persisted synthetic prefix was still rendered. |
| Message status | Pass. Stored as `cancelled` with `finishReason=cancelled`, not `streaming`. |
| Interruption explanation | Pass. The exact `INTERRUPTION_NOTE` text was visible. |
| Regenerate | Pass. The same assistant row streamed again and completed; the conversation kept two messages (one user, one assistant). |
| History branching | Pass. No duplicate assistant row and no new sequence. |

### Persistence cadence

`ChatRunner` persists partial text about once per second
(`DEFAULT_PERSIST_INTERVAL_MS = 1_000`). The fixture emits two chunks per second,
so the maximum text loss from a kill between writes is one persistence interval,
about two fixture chunks. The recorded run preserved the last persisted prefix
and lost only the text produced after the last write.

## 7. Redaction

No real API key, prompt, conversation content, personal data, or signing
material is recorded here. The provider key is a synthetic placeholder, the
prompt is synthetic, and the streamed text is the fixture's `synthetic-NN`
sequence.
