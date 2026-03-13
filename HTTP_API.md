# Voice Bridge HTTP API (V1)

默认本地地址：

- `http://127.0.0.1:8765`

鉴权：

- 如配置了 token，走 `Authorization: Bearer <token>`

## 1. POST `/v1/messages`

提交文本消息，返回本地接线员首答并进入异步流程。

请求示例：

```json
{
  "text": "帮我整理今天的工作计划",
  "client_id": "windows-cli",
  "session_id": "voice-bridge-session",
  "source": "windows",
  "message_id": "msg-123"
}
```

响应示例：

```json
{
  "ok": true,
  "accepted": true,
  "deduped": false,
  "message_id": "msg-123",
  "status": "WAITING_OPENCLAW",
  "decision": "forward_openclaw",
  "local_reply": "收到，我先快速处理。",
  "local_source": "local-operator",
  "local_source_label": "本地接线员",
  "retry": {
    "count": 0,
    "max": 5,
    "timeout_sec": 30
  }
}
```

## 2. GET `/v1/messages/{message_id}`

查询消息状态和来源消息列表（追加展示用）。

响应示例：

```json
{
  "ok": true,
  "message_id": "msg-123",
  "status": "DELIVERED",
  "messages": [
    {
      "source": "local-operator",
      "source_label": "本地接线员",
      "kind": "quick_reply",
      "text": "收到，我先快速处理。"
    },
    {
      "source": "openclaw",
      "source_label": "龙虾大脑",
      "kind": "final_reply",
      "text": "这是详细计划..."
    }
  ],
  "retry": {
    "count": 1,
    "max": 5,
    "timeout_sec": 30
  }
}
```

## 3. GET `/v1/events` (WebSocket)

用于推送：

- `accepted`
- `local_reply`
- `forwarded`
- `waiting_openclaw`
- `retrying`
- `openclaw_reply`
- `delivered`
- `failed`

支持 query 过滤：

- `session_id`
- `client_id`

## 4. 兼容接口

- `GET /health`
- `POST /chat`
- `POST /audio`
- `POST /tts`
