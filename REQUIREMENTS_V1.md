# Voice Assistant Bridge V1 需求规格（归档）

更新时间：2026-03-13  
适用范围：`voice-assistant-bridge` 仓库（Windows 端 + Android 端）

## 1. 背景与目标

本项目将从“单端语音桥接”升级为“多端输入 + 双脑协同 + 可靠转发”架构：

1. Windows 端本地模型负责低延迟响应与路由决策（接线员角色）。
2. OpenClaw（龙虾）负责复杂推理与扩展能力调用（大脑角色）。
3. 若 OpenClaw 反馈丢失，需要可检测、可重发、可追踪。
4. 新增 Android 端，Android 只做 STT/TTS，文本传入 Windows 后走与 Windows 本地输入一致的流程。

## 2. 冻结决策（已确认）

1. 回复策略：采用“追加显示”，并显示来源标记，不覆盖已有回复。
2. 网络链路：支持双链路。
3. 局域网环境：走局域网链路（按 Android 当前连接 Wi-Fi 进行判定）。
4. 公网环境：走穿透链路。
5. Android 端需支持单独配置局域网/穿透链路参数。
6. 路由策略：由 Windows 本地模型进行分类决策。
7. 超时与重试：单次超时 30 秒，最大重试 5 次。

## 3. 角色与职责

### 3.1 Android 客户端

1. 语音输入（STT）与语音播报（TTS）。
2. 文本消息上行到 Windows Bridge。
3. 接收文本结果并显示/播报。
4. 负责链路选择（局域网/穿透）和链路配置管理。

### 3.2 Windows Bridge

1. 统一文本入口（Windows 本地输入 + Android 输入）。
2. 本地模型快速首答 + 路由判定。
3. 转发 OpenClaw、处理 ACK/超时/重试。
4. 汇总并下发“本地首答 + OpenClaw 终答（追加）”。
5. 维护消息状态机、去重与日志。

### 3.3 Local LLM（Windows）

1. 生成低延迟首答（operator quick reply）。
2. 输出路由决策：是否转发 OpenClaw。

### 3.4 OpenClaw（龙虾）

1. 处理复杂任务、扩展能力、工具链调用。
2. 返回可追加展示的终答内容。

## 4. 端到端流程

```text
Windows/Android 文本输入
  -> Windows Bridge
  -> Local LLM: quick reply + route decision
  -> 立即返回本地首答（source=local-operator）
  -> 若 decision=forward:
       发送到 OpenClaw（带 message_id）
       等待反馈（30s timeout）
       超时则重试，最多 5 次
  -> 收到 OpenClaw 终答后追加返回（source=openclaw）
```

## 5. 展示与交互要求

1. 聊天窗口按时间顺序追加消息，不覆盖历史。
2. 每条助手消息必须带来源标签：
3. `[本地接线员]`（本地模型首答）
4. `[龙虾大脑]`（OpenClaw 终答）
5. 若 OpenClaw 最终失败，追加一条失败状态提示，不删除本地首答。

## 6. 路由决策要求（本地模型分类）

## 6.1 分类输出（逻辑要求）

本地模型至少输出以下语义字段：

1. `quick_reply`: 可直接显示的低延迟答复。
2. `decision`: `local_only` 或 `forward_openclaw`。
3. `reason`: 分类依据（用于日志与调试）。
4. `confidence`: 0~1（可选但建议保留）。

## 6.2 最低可用策略

1. `local_only`：闲聊、短问答、低风险解释类。
2. `forward_openclaw`：需要工具、复杂推理、上下文长链路、外部能力调用。

## 7. 可靠性与重发机制

## 7.1 标识与幂等

每个会话消息需具备：

1. `session_id`
2. `turn_id`
3. `message_id`（全局唯一）
4. `client_id`

OpenClaw 转发必须携带 `message_id`，用于去重和重放保护。

## 7.2 状态机

建议状态：

1. `NEW`
2. `LOCAL_REPLIED`
3. `FORWARDED`
4. `WAITING_OPENCLAW`
5. `RETRYING`
6. `OPENCLAW_RECEIVED`
7. `DELIVERED`
8. `FAILED`

## 7.3 超时与重试策略（冻结参数）

1. 单次等待超时：30 秒。
2. 最大重试次数：5 次。
3. 重试触发条件：未收到 OpenClaw 反馈或反馈不可解析。
4. 重试需记录：`retry_count`、最后错误、最后发送时间。
5. 达到上限后标记 `FAILED`，并向前端追加失败消息。

## 8. Android 双链路支持需求

## 8.1 配置项（Android 端）

1. 局域网链路：
2. `lan_base_url`
3. `lan_auth_token`
4. `lan_wifi_match_rule`（至少支持 SSID）
5. 穿透链路：
6. `tunnel_base_url`
7. `tunnel_auth_token`
8. `tunnel_enabled`

## 8.2 链路选择逻辑

1. 若当前 Wi-Fi 命中 `lan_wifi_match_rule`，优先走局域网。
2. 否则走穿透链路。
3. 若首选链路失败，可按策略回退到另一链路（建议可配置开关）。

## 9. Windows 对外接口（建议）

## 9.1 POST `/v1/messages`

用途：提交文本，返回受理结果与本地首答（若已生成）。

请求字段建议：

1. `client_id`
2. `session_id`
3. `text`
4. `source`（`android`/`windows`）
5. `timestamp`

响应字段建议：

1. `message_id`
2. `accepted`
3. `local_reply`（可空）
4. `local_source`（固定 `local-operator`）
5. `status`

## 9.2 GET `/v1/messages/{message_id}`

用途：查询当前 turn 状态、重试信息和终答。

## 9.3 WS `/v1/events`（可选但推荐）

用途：推送异步事件（OpenClaw 终答、重试、失败）。

## 10. 日志与可观测性

每个 turn 必须可追踪：

1. `message_id/session_id/client_id`
2. 本地决策结果与置信度
3. OpenClaw 发送次数与每次耗时
4. 最终状态（成功/失败）
5. 失败原因（超时、网络、解析、后端错误）

## 11. 非功能要求（V1）

1. 本地首答低延迟：目标 P95 小于 1.5 秒（可按设备调整）。
2. 重启可恢复：未完成 turn 建议持久化（如 SQLite）。
3. 并发安全：同一 `session_id` 内保持顺序一致性。
4. 向后兼容：不破坏现有 Windows CLI/GUI 基本使用。

## 12. 实施里程碑（建议）

1. M1：Windows 双阶段回复 + 来源标记 + 本地分类路由。
2. M2：OpenClaw 超时检测 + 5 次重试 + 去重。
3. M3：Android 双链路配置与文本接入。
4. M4：联调与压测（局域网/穿透两场景）。

## 13. 非目标（V1 不做）

1. Android 端直接接入 OpenClaw（绕过 Windows）不在本期。
2. 复杂权限系统与多租户管理不在本期。
3. 端到端音频流转（非文本形态）不在本期。

