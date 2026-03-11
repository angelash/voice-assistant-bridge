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

---

## 1. GET /health

### Response

```json
{
  "status": "ok",
  "role": "text-brain",
  "reply_backend": "openclaw",
  "ollama_endpoint": "http://localhost:11434/api/generate",
  "ollama_model": "qwen2.5:7b",
  "openclaw_session_id": "voice-bridge-session",
  "tts_voice": "zh-CN-XiaoxiaoNeural",
  "debug_audio_supported": true
}
```

---

## 2. POST /chat （主接口）

Windows 端将 STT 后的文字发到这里。

### Request

```json
{
  "text": "帮我查一下今天下午的天气"
}
```

### Response

```json
{
  "ok": true,
  "input_text": "帮我查一下今天下午的天气",
  "response_text": "我来帮你看一下今天下午的天气。",
  "reply_backend": "openclaw",
  "session_id": "voice-bridge-session"
}
```

### 字段说明

- `input_text`: 输入文本
- `response_text`: 智能处理后的文本回复
- `reply_backend`: 当前使用的回复后端
- `session_id`: 当后端为 `openclaw` 时，对应专用语音 session

---

## 3. POST /audio（兼容调试）

仅用于调试。服务端会自行做 STT/TTS。

### Headers

```text
Content-Type: application/octet-stream
```

### Body

原始 PCM：
- 16kHz
- mono
- 16-bit signed PCM

### Response

```json
{
  "ok": true,
  "input_text": "帮我查天气",
  "response_text": "好的，我来帮你查天气。",
  "reply_backend": "openclaw",
  "session_id": "voice-bridge-session",
  "tts_audio_base64": "<base64-mp3>",
  "tts_size": 30124,
  "tts_content_type": "audio/mpeg",
  "debug_interface": true
}
```

---

## 4. POST /tts（兼容调试）

单独文字转语音。

### Request

```json
{
  "text": "你好，我是 Clawra"
}
```

### Response

- `Content-Type: audio/mpeg`
- body 为 MP3 二进制

---

## 5. openclaw backend 的工作方式

当：

```bash
VOICE_REPLY_BACKEND=openclaw
```

服务端会调用：

```bash
openclaw agent --session-id <固定专用session> --message <用户文本> --json
```

这意味着：
- 所有语音文本进入一个固定专用 session
- 这个 session 可持续积累上下文
- 后续更适合接入记忆 / 技能 / 工具能力

---

## 6. 推荐调用方式

### Windows 端流程

1. 本地唤醒词命中
2. 本地录音
3. 本地 STT
4. 调 `/chat`
5. 拿到 `response_text`
6. 本地 TTS 播放

也就是：

> Windows = 耳朵 + 嘴巴  
> OpenClaw = 脑子

---

## 7. 示例

### health

```bash
curl http://127.0.0.1:8765/health
```

### chat

```bash
curl -X POST http://127.0.0.1:8765/chat \
  -H 'Content-Type: application/json' \
  -d '{"text":"你好，帮我做个自我介绍"}'
```

### 启动 openclaw backend

```bash
export VOICE_REPLY_BACKEND=openclaw
export VOICE_OPENCLAW_SESSION_ID=voice-bridge-session
python server.py --port 8765
```

### 回退到 ollama backend

```bash
export VOICE_REPLY_BACKEND=ollama
python server.py --port 8765
```
