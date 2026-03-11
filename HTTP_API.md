# Voice Assistant Bridge HTTP API

用于 Windows 客户端 / 其他工具调用 WSL2 语音桥服务。

默认服务地址：

```text
http://<WSL_HOST>:8765
```

本地测试常用：

```text
http://127.0.0.1:8765
```

## 1. GET /health

检查服务状态。

### Response

```json
{
  "status": "ok",
  "models_loaded": true,
  "llm_endpoint": "http://localhost:11434/api/generate",
  "llm_model": "qwen2.5:7b"
}
```

## 2. POST /chat

文字对话接口。

### Request

```json
{
  "text": "你好，请做个自我介绍"
}
```

### Response

```json
{
  "text": "你好，请做个自我介绍",
  "response": "你好，我是当前语音桥接助手。",
  "tts_audio_base64": "<base64-mp3>",
  "tts_size": 32400
}
```

字段：
- `text`: 输入文本
- `response`: LLM 输出文本
- `tts_audio_base64`: MP3 音频的 Base64
- `tts_size`: MP3 字节数

## 3. POST /audio

音频对话接口。

### Headers

```text
Content-Type: application/octet-stream
```

### Body

原始 PCM 字节流：
- 16kHz
- mono
- 16-bit signed PCM

### Response

```json
{
  "text": "帮我查一下天气",
  "response": "好的，我来帮你看天气。",
  "tts_audio_base64": "<base64-mp3>",
  "tts_size": 30124,
  "tts_content_type": "audio/mpeg"
}
```

## 4. POST /tts

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

## 5. 错误格式

统一返回：

```json
{
  "error": "错误信息"
}
```

## 6. 当前实现说明

当前服务端链路：
- STT: `faster-whisper`
- LLM: `Ollama / qwen2.5:7b`
- TTS: `edge-tts`
- HTTP: `aiohttp`

## 7. 重要限制

当前 `/chat` 和 `/audio` 还没有接入 OpenClaw 主 Agent 本体。

这意味着当前版本：
- 还没有接入 Clawra 的长期记忆
- 还没有接入技能扩展能力
- 还没有接入主会话上下文

后续建议把“回复层”从 Ollama 直连切到 OpenClaw 主 Agent，以实现真正和 Clawra 本体对话。

## 8. 示例

### health

```bash
curl http://127.0.0.1:8765/health
```

### chat

```bash
curl -X POST http://127.0.0.1:8765/chat \
  -H 'Content-Type: application/json' \
  -d '{"text":"你好，请做个自我介绍"}'
```

### tts

```bash
curl -X POST http://127.0.0.1:8765/tts \
  -H 'Content-Type: application/json' \
  -d '{"text":"你好"}' \
  --output hello.mp3
```

## 9. Windows 侧推荐命令

### 文字模式

```bash
python windows_client.py --server http://localhost:8765 --text "你好"
```

### 录音模式

```bash
python windows_client.py --server http://localhost:8765 --record 5
```

### 持续监听模式

```bash
python windows_client.py --server http://localhost:8765 --continuous
```

### 列设备

```bash
python windows_client.py --list-devices
```
