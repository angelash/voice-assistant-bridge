# Voice Assistant Architecture

## 分层

### 1. Windows Voice Shell
位置：
- 开发源：`/home/shash/clawd/apps/voice-assistant-bridge-windows/`
- Windows 交付：`F:\workspace\voice-assistant-bridge`

职责：
- wakeword
- 录音
- STT
- 本地 TTS
- 本地播放
- 调用文字脑接口

### 2. OpenClaw Text Brain
位置：
- `skills/voice-text-brain/`

职责：
- `/chat` 接口
- backend 切换（`openclaw` / `ollama`）
- 专用语音 session
- 文本智能处理

### 3. Legacy Sandbox
位置：
- `voice-assistant/`

职责：
- 保留早期实验材料
- 不再承担最终产品主架构职责

## 主链路

```text
Windows Voice Shell
  -> STT
  -> POST /chat
  -> OpenClaw Text Brain
  -> response_text
  -> Windows TTS/playback
```
