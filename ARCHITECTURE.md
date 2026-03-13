# Voice Assistant Bridge Architecture (V1)

## 1. 分层

### 1.1 Client Layer

- Windows GUI / CLI
- Android App（基线：`AudioBridgeClient`）
- 职责：输入文本、展示追加回复、语音采集与播报

### 1.2 Bridge Layer (`server.py`)

- 统一入口：`/v1/messages`
- 本地接线员：快速首答 + 路由决策
- 可靠转发：OpenClaw 超时检测与重试
- 状态追踪：SQLite 持久化
- 事件推送：`/v1/events`

### 1.3 Brain Layer

- Local Operator（Ollama）
- OpenClaw（深度能力与扩展）

## 2. 主链路

```text
Input text (Windows / Android)
  -> POST /v1/messages
  -> Local operator quick reply + decision
  -> append [本地接线员]
  -> if forward_openclaw:
       send to OpenClaw with message_id
       timeout 30s, retry <= 5
  -> append [龙虾大脑] or [系统失败提示]
```

## 3. 状态机

- `NEW`
- `LOCAL_REPLIED`
- `FORWARDED`
- `WAITING_OPENCLAW`
- `RETRYING`
- `OPENCLAW_RECEIVED`
- `DELIVERED`
- `FAILED`

## 4. 一致性与可靠性

- 每条消息包含 `session_id + turn_id + message_id + client_id`
- 同一 `session_id` 使用串行锁保证顺序
- 至少一次投递语义 + 幂等 `message_id`
- 未完成消息重启后自动恢复重试
