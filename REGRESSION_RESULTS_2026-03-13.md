# Regression Results (2026-03-13)

Scope:

- Voice Bridge V1 (`server.py`)
- Windows CLI (`windows_client.py`)
- Android baseline project (`AudioBridgeClient`)

## Environment

- Date: 2026-03-13
- OpenClaw gateway health: `http://127.0.0.1:18789/api/voice-brain/health` = `200`
- Local bridge port: `8765`

## Cases Executed

1. V1 local-only path
- API: `POST /v1/messages` + `GET /v1/messages/{id}`
- Input: short greeting
- Result: `decision=local_only`, `status=DELIVERED`, source message includes `local-operator`.

2. V1 forward path (real OpenClaw)
- API: `POST /v1/messages` + poll status
- Input: explicit forward text (`请转发给openclaw...`)
- Result: `decision=forward_openclaw`, initial `WAITING_OPENCLAW`, terminal `DELIVERED`, messages include both:
  - `local-operator` quick reply
  - `openclaw` final reply

3. Idempotency / dedupe
- API: two submits with same `message_id`
- Result:
  - first submit `deduped=false`
  - second submit `deduped=true`
  - terminal state remains single message flow.

4. Retry/failure behavior (timeout injection)
- Method: direct server object test with `forward_timeout=2`, `forward_max_retries=3`
- Input: forced forward text
- Result: `status=FAILED`, `retry_count=3`, `last_error=TimeoutError`.

5. WebSocket event stream
- API: `GET /v1/events?session_id=...&client_id=...`
- Result sequence observed:
  - `accepted`
  - `local_reply`
  - `forwarded`
  - `waiting_openclaw`
  - `openclaw_reply`
  - `delivered`

6. Windows CLI end-to-end
- Command: `python windows_client.py --local-llm --text "...forward..." ...`
- Result:
  - printed `[本地接线员] ...`
  - appended `[龙虾大脑] ...`
  - both TTS playback succeeded (edge-tts).

## Android Build Verification

1. Issue found and fixed
- `gradle/wrapper/gradle-wrapper.jar` was invalid (HTML content, not jar).
- Replaced with valid Gradle 8.2 wrapper jar.

2. Java requirement
- AGP 8.2.2 requires Java 11+.
- Build must use JDK 17 (system default Java 8 fails).

3. Build checks
- `:app:compileDebugKotlin` passed with JDK 17.
- `:app:assembleDebug` passed with JDK 17.

## Known Gaps (Manual Device Validation Needed)

1. Android on-device STT permission/runtime UX not verified on physical device.
2. Android TTS playback continuity under background/lockscreen not verified on physical device.
3. LAN/Tunnel auto-switch validation requires real Wi-Fi and tunnel environments.
