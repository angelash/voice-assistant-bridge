# Voice Assistant Bridge V1 需求归档（更新时间：2026-03-13）
适用范围：`f:\workspace\voice-assistant-bridge`

## 1. 目标
构建一条“Android 语音入口 + Windows 桥接 + OpenClaw 大脑”的稳定对话链路，满足：

1. 安卓负责语音转文本（STT）和文本转语音（TTS）。
2. Windows 统一接收文本并转发到 OpenClaw。
3. 本地模型仅作为“接线员”，用于即时响应与链路可用性反馈，不再做路由决策。
4. 对话展示支持追加显示和来源标识。
5. OpenClaw 反馈异常时可探测、重试、失败可见。

## 2. 已冻结决策
1. 展示策略：追加显示，不覆盖历史；所有回复显示来源。
2. 路由策略：每次消息都发送到 OpenClaw，不再由本地模型分类决策。
3. 本地模型职责：
   - 生成即时“接线员”回复；
   - 执行链路可用性探测并反馈异常状态。
4. 超时与重试：单次超时 `30` 秒，最大重试 `5` 次。
5. Android 双链路：
   - Wi-Fi SSID 等于 `4399` 时走局域网；
   - 其他网络走公网穿透链路。
6. Android 固化地址：
   - 局域网：`http://10.3.91.22:8765`
   - 公网：`http://voice-bridge.iepose.cn`
7. Android 工程基线：独立工程 `android/AudioBridgeClient`，技术方案与原参考工程保持一致。

## 3. 角色与职责
### 3.1 Android 客户端
1. 采集语音并转写成文本（STT）。
2. 将文本请求发送到 Windows Bridge（自动选择 LAN/TUNNEL）。
3. 接收本地接线员回复和 OpenClaw 终答并追加显示。
4. 对需要播报的文本执行清洗后再进行 TTS。

### 3.2 Windows Bridge
1. 对外提供统一消息接口（`/v1/messages`、`/v1/messages/{message_id}`、`/v1/events`）。
2. 本地接线员先回一条快速响应，再进入 OpenClaw 转发流程。
3. 维护状态机、重试、失败记录和事件推送。
4. 统一来源标签（本地接线员 / 龙虾大脑 / 系统）。

### 3.3 Local Operator（本地模型）
1. 仅负责 quick reply（不输出路由决策）。
2. 在链路异常时配合系统返回可感知反馈文案。

### 3.4 OpenClaw
1. 负责最终复杂回复。
2. 返回终答文本，由桥接层追加到同一轮对话中。

## 4. 对话链路（当前版）
```text
Android STT / Windows 文本输入
  -> Windows Bridge /v1/messages
  -> Local Operator quick reply（本地接线员）
  -> OpenClaw 健康探测（失败则上报状态，但仍进入转发队列）
  -> 转发 OpenClaw（每轮必转发）
  -> 成功到 DELIVERED 并追加终答
  -> 失败到 RETRYING（最多 5 次，单次 30 秒）-> FAILED
```

## 5. 状态机
1. `NEW`
2. `LOCAL_REPLIED`
3. `FORWARDED`
4. `WAITING_OPENCLAW`
5. `RETRYING`
6. `OPENCLAW_RECEIVED`
7. `DELIVERED`
8. `FAILED`

## 6. 消息展示与来源要求
1. 本地接线员消息：来源 `local-operator`，显示标签 `本地接线员`。
2. OpenClaw 消息：来源 `openclaw`，显示标签 `龙虾大脑`。
3. 系统错误消息：来源 `system`，显示标签 `系统`。
4. 失败时不得覆盖或删除本地接线员消息，必须追加失败提示。

## 7. Android 文本与语音清洗规则
### 7.1 展示清洗
1. 去除 `[[...]]` 前缀标签（如 `[[reply_to_current]]`）。
2. 保留正文语义和来源标识。

### 7.2 播报清洗（对齐 Windows 端）
1. 去除 `emoji` / 符号表情字符。
2. 去除常见 Markdown 控制符（如 `` ` * _ ~ # > ``）。
3. 合并多余空白，避免 TTS 播放异常。

## 8. 可靠性要求
1. 所有转发请求携带 `message_id`（幂等和追踪）。
2. 失败重试需记录 `retry_count` 与 `last_error`。
3. 服务重启后可恢复 pending 消息的继续转发。

## 9. 验收要点
1. 每次提交都产生本地 quick reply 并进入 OpenClaw 转发。
2. OpenClaw 可达时，最终状态进入 `DELIVERED` 并追加终答。
3. OpenClaw 不可达时，按 30s/5 次策略重试，最终 `FAILED` 且有错误提示。
4. Android 在 `SSID=4399` 时命中 LAN 地址，否则命中公网地址。
5. Android 播报文本经过与 Windows 对齐的格式和表情清洗。
