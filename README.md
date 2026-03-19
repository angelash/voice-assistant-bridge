# Voice Assistant Bridge

Windows 语音助手桥接工程（V1）：

- 本地模型做低延迟接线员回复
- 本地模型做路由决策（是否转发 OpenClaw）
- OpenClaw 负责深度能力与扩展
- 反馈丢失可检测并自动重发（30 秒超时，最多 5 次）
- 统一文本入口，Windows/Android 输入走同一管线

## 核心组件

- `server.py`：Bridge 服务（V1 接口 + 状态机 + 重试 + SQLite 持久化）
- `windows_client.py`：Windows CLI 客户端
- `windows_gui.py`：Windows GUI 客户端（文本/语音输入）
- `config.json`：本地与 OpenClaw 配置
- `REQUIREMENTS_V1.md`：需求归档

## 快速启动

1. 安装依赖

```powershell
pip install -r requirements.txt
```

2. 启动 Bridge 服务（本地）

```powershell
python server.py --port 8765
```

3. 启动 Windows GUI

```powershell
run_windows_gui.bat
```

4. 或使用 CLI（本地模式）

```powershell
run_windows_client_local.bat --text "你好"
```

## V1 API（本地服务）

- `POST /v1/messages`：提交文本，立即返回本地首答和消息状态
- `GET /v1/messages/{message_id}`：查询终态与来源消息列表
- `GET /v1/events`：WebSocket 事件流（可选）
- 兼容保留：`POST /chat`、`POST /audio`、`POST /tts`、`GET /health`

## 配置重点

`config.json` 中重点字段：

- `local_chat_path`: 建议 `/v1/messages`
- `openclaw_forward_timeout`: 默认 `30`
- `openclaw_max_retries`: 默认 `5`
- `openclaw_retry_backoff`: 默认 `1.5`
- `bridge_db_path`: 消息状态持久化数据库

## Android 基线

当前仓库内独立 Android 工程路径：

- `F:\workspace\voice-assistant-bridge\android\AudioBridgeClient`

约束：实现方案与原基线工程保持一致，但后续开发仅在本仓库内进行。

## Environment Smoke Check (V2 Meetings)

Use this before Android/Windows meeting tests to ensure local service readiness and verify V2 meeting APIs end-to-end.

```powershell
python scripts/meeting_env_check.py --base-url http://127.0.0.1:8765
```

Optional flags:

- `--no-auto-start` disable local auto-start when health check fails
- `--stop-started-server` stop server when the script exits
