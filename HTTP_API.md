# Voice Brain HTTP API

当前已验证可用的 Gateway 原生插件接口为：

- `GET /api/voice-brain/health`
- `POST /api/voice-brain/chat`

基础地址：

```text
http://127.0.0.1:18789
```

需要携带 Gateway token：

```text
Authorization: Bearer <gateway-token>
```

## 1. GET /api/voice-brain/health

### Response

```json
{
  "status": "ok",
  "role": "text-brain-plugin",
  "route": "/api/voice-brain/chat",
  "backend": "openclaw",
  "sessionId": "voice-bridge-session"
}
```

## 2. POST /api/voice-brain/chat

### Request

```json
{
  "text": "你好"
}
```

### Response

```json
{
  "ok": true,
  "input_text": "你好",
  "response_text": "你好。",
  "reply_backend": "openclaw",
  "session_id": "voice-bridge-session"
}
```

## 推荐架构

- Windows 端负责：wakeword / STT / TTS / 播放
- Gateway 插件负责：文字智能处理

也就是：

- Windows = 耳朵 + 嘴巴
- OpenClaw = 脑子

## PowerShell 示例

### health

```powershell
$headers = @{ Authorization = "Bearer <gateway-token>" }
Invoke-RestMethod -Uri "http://127.0.0.1:18789/api/voice-brain/health" -Method Get -Headers $headers
```

### chat

```powershell
$headers = @{
  Authorization = "Bearer <gateway-token>"
  "Content-Type" = "application/json"
}

$body = @{ text = "你好" } | ConvertTo-Json
Invoke-RestMethod -Uri "http://127.0.0.1:18789/api/voice-brain/chat" -Method Post -Headers $headers -Body $body
```
