# Voice Assistant Brain HTTP API

**推荐架构：**
- Windows 端负责：唤醒词 / STT / TTS / 音频播放
- 本服务负责：**文本智能处理**（记忆 / 技能 / 工具 / 大模型回复）

因此，**主接口是 `/chat`**。
`/audio` 和 `/tts` 仅作为兼容调试接口保留。

## 回复后端切换

支持两种后端：
- `openclaw`：推荐，走专用语音 session
- `ollama`：保底/回退链路

环境变量：

```bash
VOICE_REPLY_BACKEND=openclaw
VOICE_OPENCLAW_SESSION_ID=voice-bridge-session
VOICE_OPENCLAW_TIMEOUT=120
VOICE_OLLAMA_ENDPOINT=http://localhost:11434/api/generate
VOICE_OLLAMA_MODEL=qwen2.5:7b
```

## GET /health

返回当前角色、backend 和 session 配置。

## POST /chat

主接口。

Request:

```json
{"text":"帮我查一下今天下午天气"}
```

Response:

```json
{
  "ok": true,
  "input_text": "帮我查一下今天下午天气",
  "response_text": "我来帮你看一下今天下午的天气。",
  "reply_backend": "openclaw",
  "session_id": "voice-bridge-session"
}
```

## POST /audio

仅调试用。接收 PCM 音频并在服务端执行 STT/TTS。

## POST /tts

仅调试用。文字转音频。
