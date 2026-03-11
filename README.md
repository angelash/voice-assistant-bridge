# Voice Assistant Bridge (Windows Shell)

这是 **Windows 端语音壳**。

## 当前正式对接方式

Windows 端现在直接对接 **OpenClaw Gateway 原生插件 `voice-brain`**。

已验证可用的接口：
- `GET /api/voice-brain/health`
- `POST /api/voice-brain/chat`

默认配置写在：
- `config.json`

## 先做什么

### 1. 测试 Gateway 插件接口

PowerShell：

```powershell
.\test_voice_brain.ps1
```

### 2. 用 Python 客户端测 health

```powershell
python windows_client.py --health
```

### 3. 用 Python 客户端测文字对话

```powershell
python windows_client.py --text "你好"
```

## 当前职责

Windows 侧负责：
- wakeword
- 录音
- STT
- TTS
- 播放

Gateway 插件负责：
- 文本智能处理

## 注意

当前 `record` / `continuous` 还只是录音壳，**不会直接把音频发给 Gateway**。

因为当前正式架构已经收敛为：
- Windows 侧先做 STT
- 再把文本发给 `/api/voice-brain/chat`
