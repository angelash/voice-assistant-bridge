# AudioBridgeClient Android

Phone Agent is the Android client for `voice-assistant-bridge`. It uses the phone camera, microphone, speaker, local storage, and the Bridge HTTP/WebSocket API as the current wearable-agent stand-in.

## Current Capabilities

- Bridge settings, health check, token, client ID, and daily session ID.
- Text messages through `/v1/messages`, with local status, final status, retry, and WebSocket/ polling updates.
- Room-backed offline queue. Failed sends keep the same `message_id` and retry through WorkManager.
- System camera capture, CameraX preview capture, and gallery image upload through `/v1/artifacts`.
- Audio recording as an audio artifact. Bridge transcribes the uploaded audio before forwarding it for the final reply.
- Short voice input through Bridge STT at `/v1/audio/transcriptions`.
- Foreground-service camera modes:
  - background capture: one frame every 30 seconds
  - frame stream: one frame every 3 seconds
- Foreground-service wake listening. Silent chunks are skipped locally, and successful wake hits enter a cooldown window.
- Local TTS playback for final replies.
- Local cleanup and remote session cleanup through `DELETE /v1/sessions/{session_id}`.
- Diagnostics tab for Bridge status, event stream, queue counts, network, battery, sync policy, and capture state.

## Safety Defaults

- Continuous camera capture is off by default.
- Starting camera capture requires the explicit `allowAutoCapture` setting.
- Continuous capture on battery is blocked unless `allowCaptureOnBattery` is enabled.
- If mobile/计量 network sync is disabled, foreground sends and background retry stay pending instead of uploading.
- Camera and microphone capture run as a visible foreground service with a persistent notification.
- No placeholder image/audio analysis is generated. Backend failures are surfaced as errors.

## Build And Test

From this directory:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

From the repository root, the matching backend tests are:

```powershell
python -m unittest test_server_v1.py test_v2_api
```

## Local Device Smoke Test

Start Bridge on the workstation, then connect the phone through ADB:

```powershell
adb reverse tcp:8765 tcp:8765
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Use `http://127.0.0.1:8765` as the Bridge address on the phone when `adb reverse` is active.
