# Voice Assistant Bridge V2 详细设计（Draft）

- 文档版本：`v0.3`
- 更新时间：`2026-03-14`
- 需求来源：`req_v2.md` + 2026-03-14 补充约束
- 设计目标：在现有 V1 基础上升级为会议模式（Meeting OS），覆盖 API、存储、编排与 UI

## 1. 已确认约束（本版冻结）

1. 不采购独立“长时实时转写”付费 API。
2. 会议模式必须是独立开关，人工控制进入/退出。
3. 进入会议模式后，安卓端必须先保证录音音频本地可靠存储。
4. 会议结束后，支持将会议音频上传到 Windows 侧仓库备份。
5. 需要建立会议历史管理（会次列表、回放、检索、重跑加精）。
6. 支持会后对音频做加精转写，并在此阶段做更细粒度说话人区分。
7. 图片保持拍照原分辨率传递；到 OpenClaw 层以图片为主输入，不降级为纯 OCR 文本。
8. V2 交付范围包含 UI（Android + Windows）。
9. STT 采用方案 A；方案 B/C 作为归档备用方案保留。
10. 安卓本地音频默认保留 7 天，且仅在“已成功上传 Windows 备份”前提下执行清理。
11. 会议结束自动触发加精任务，使用后台线程/任务队列处理，不影响主体功能使用。
12. 唤醒词采用“单录音链路复用”方案，禁止为 KWS 单独再开麦克风采集器。

## 2. 当前能力边界（基于现状代码）

### 2.1 Android 侧

1. 当前使用讯飞 `iat`（`domain("iat")`）+ `dwa("wpgs")`，为短时听写链路。
2. 当前代码是“按键开始/停止”的单段听写，并非会议级连续归档。
3. 当前未实现会议录音本地可靠落盘、分片索引、会后批量回传。
4. 当前未实现图片采集上传链路。

### 2.2 Windows 侧

1. V1 已有 `server.py + SQLite + WebSocket`，可复用为 Meeting Hub 核心。
2. 已存在 `faster-whisper` 本地转写能力（`/audio` 与 GUI 本地 fallback）。
3. 当前仍是“消息轮次”视角，不是“会议事件流+历史库”视角。

## 3. V2 总体目标

1. 会议主链路：安卓采集 -> Windows 事件归档 -> 本地分析 + OpenClaw 增强 -> 会后加精 -> 历史管理。
2. 实时链路可降级，但归档链路不可中断。
3. 会中关注“可用与连续”，会后关注“准确与可追溯”。

## 4. 总体架构（V2）

### 4.1 分层

1. Android Edge（采集与本地可靠存储）
2. Windows Meeting Hub（事件总线、状态机、归档、调度）
3. Local Operator（低延迟轻分析）
4. OpenClaw（联网/多模态/深分析）
5. Archive & History（音频仓库、转写稿、报告与检索）
6. UI Layer（Android 会议控件 + Windows 会议工作台）

### 4.2 关键原则

1. 先存音频，再谈实时转写。
2. 先写事件，再做分析。
3. 实时结果可修订，稳定稿可回放。
4. 分层职责不混用：安卓采集、Windows 编排、本地快答、OpenClaw 深答。

## 5. 会议模式设计（人工开关）

### 5.1 状态机

1. `IDLE`（未开会）
2. `MEETING_PREP`（初始化录音与会话）
3. `MEETING_ACTIVE`（持续采集与实时处理）
4. `MEETING_ENDING`（封片、校验、上传）
5. `MEETING_ARCHIVED`（历史入库完成）
6. `MEETING_REFINING`（会后加精任务中）
7. `MEETING_READY`（加精完成可检索）

### 5.2 UI 行为

1. Android 显示“会议模式开关”、本地存储健康、录音时长、缓存队列长度。
2. Android 显示唤醒词状态（监听中/命令窗/冷却中）。
3. Windows 显示会议进行态、实时转写流、上传进度、加精任务进度、历史会次列表。

## 6. 音频链路设计（重点）

### 6.1 会议中（实时链路）

1. Android 连续录音并分片落盘（建议 30s/片，WAV/PCM + manifest）。
2. 同步执行当前 `iat` 实时听写，产出 `partial/final/segment_closed` 事件。
3. 实时文本用于会中显示、快速摘要、会中问答，不作为最终唯一稿源。
4. 当单段听写接近上限时自动切会话（例如 45~50s 一轮），保证持续可用。

### 6.2 会议后（加精链路）

1. Android 将会议音频分片批量上传到 Windows 音频仓库。
2. Windows 做分片完整性校验（checksum、时长连续性）。
3. 会议结束后自动触发“加精转写任务”生成稳定稿（可多引擎重跑）。
4. 在加精阶段执行细粒度说话人区分（弱识别升级）。
5. 加精任务进入后台线程/任务队列，不阻塞主线程和会中基础功能。
6. 生成三层纪要并归档到会议历史。

### 6.3 唤醒词链路（不冲突设计）

1. 唤醒词检测（KWS）与 STT、落盘复用同一条 `AudioRecord` PCM 流。
2. KWS 仅在“会议模式 ON”时启用，会议模式 OFF 彻底关闭。
3. KWS 命中后进入命令窗（建议 6~8 秒），期间暂停继续检测，避免重复触发。
4. 命令窗结束后进入冷却期（建议 2~3 秒）再恢复 KWS。
5. TTS 播报期间抑制 KWS（或 AEC+阈值提升），避免“自唤醒”。
6. Windows 侧默认不启用常驻唤醒词，避免 Android/Windows 双端重复触发。

## 7. 图像链路设计（原图优先）

1. Android 拍照保留原分辨率，上传原图二进制 + 元数据。
2. Windows 仅做轻前处理与索引（不降采样为唯一输入）。
3. OpenClaw 分析输入以原图为主（可附 OCR 辅助文本，但非主输入）。
4. 历史库保存原图、缩略图、分析结果、引用关系（关联时间轴片段）。

## 8. 事件模型（V2）

### 8.1 统一 Envelope

```json
{
  "schema_version": "v2",
  "event_id": "evt-uuid",
  "meeting_id": "mtg-uuid",
  "source": "android|windows|local-operator|openclaw|system",
  "event_type": "stt.partial",
  "seq": 1024,
  "ts_client": "2026-03-14T10:00:00.123Z",
  "ts_server": "2026-03-14T10:00:00.456Z",
  "payload": {}
}
```

### 8.2 事件类型（新增重点）

1. `meeting.mode_on`
2. `meeting.mode_off`
3. `audio.segment.started`
4. `audio.segment.sealed`
5. `audio.segment.uploaded`
6. `audio.segment.upload_failed`
7. `stt.partial`
8. `stt.final`
9. `stt.segment_closed`
10. `transcription.refine.started`
11. `transcription.refine.completed`
12. `diarization.refine.completed`
13. `image.uploaded.original`
14. `wakeword.detected`
15. `wakeword.command_window.started`
16. `wakeword.command_window.ended`
17. `wakeword.cooldown.started`
18. `wakeword.cooldown.ended`
19. `analysis.openclaw.reply`
20. `report.generated`

### 8.3 说话人字段（保持）

```json
{
  "speaker_cluster_id": "spk-1",
  "speaker_confidence": 0.78,
  "speaker_name": null,
  "speaker_name_source": "auto"
}
```

`speaker_name_source`：`auto|manual|history|unknown`

## 9. 归档与历史管理

### 9.1 目录结构

```text
artifacts/meetings/{meeting_id}/
  meta/
    meeting.json
  audio/
    raw/{segment_id}.wav
    raw/{segment_id}.json
    upload_manifest.json
  events/
    events.ndjson
  transcript/
    realtime.ndjson
    refined.jsonl
  analysis/
    rolling_summary.jsonl
    todo_items.jsonl
    risk_items.jsonl
    diarization.jsonl
  media/
    images/original/{image_id}.jpg
    images/thumb/{image_id}.jpg
    images/{image_id}.json
  reports/
    brief.md
    action.md
    deep.md
```

### 9.2 SQLite 表（新增）

1. `meeting_sessions`
2. `meeting_events`（append-only）
3. `audio_segments`（本地路径、checksum、上传状态）
4. `transcription_jobs`（引擎、状态、产物路径、耗时）
5. `meeting_segments_refined`（稳定稿 + 说话人）
6. `media_assets`
7. `reports_index`

## 10. API 设计（V2）

### 10.1 新增接口

1. `POST /v2/meetings`（创建会议）
2. `POST /v2/meetings/{meeting_id}/mode`（`on/off`）
3. `POST /v2/meetings/{meeting_id}/events:batch`
4. `POST /v2/meetings/{meeting_id}/audio:upload`（分片上传）
5. `POST /v2/meetings/{meeting_id}/images:upload`（原图上传）
6. `POST /v2/meetings/{meeting_id}/refine:run`
7. `GET /v2/meetings/{meeting_id}/timeline`
8. `GET /v2/meetings/{meeting_id}/history`
9. `GET /v2/events/stream`（WebSocket）

### 10.2 V1 兼容

1. 保留 `POST /v1/messages`。
2. 保留 `GET /v1/messages/{message_id}`。
3. V1 响应由 V2 投影层兼容输出。

## 11. 实时 STT 与会后加精策略

### 11.1 当前 API（iat）在本项目内可做到的程度

1. 可提供会中低延迟实时文本与动态修正。
2. 可通过“短会话轮转 + 连续拼接”支持会议级连续体验。
3. 但最终准确稿与精细说话人区分应依赖会后加精，不依赖实时稿定稿。

### 11.2 加精引擎策略（Windows）

1. 默认引擎：`faster-whisper`（项目已有基础）。
2. 细粒度说话人区分：会后任务接入 diarization 组件（见第 14 章方案评估）。
3. 保留多引擎重跑能力：同一会议可再次触发加精，生成新版本稿。

## 12. OpenClaw 多模态输入策略

1. 图片分析统一传原图（multipart 或对象存储 URL）。
2. 音频不在会中直接传长音频给 OpenClaw，避免阻塞主链路。
3. OpenClaw 主要消费：会中实时事件摘要 + 会后加精文本 + 原图。

## 13. UI 交付范围（V2 必选）

### 13.1 Android UI

1. 会议模式开关（进入/结束）。
2. 录音状态条（时长、存储占用、异常告警）。
3. 上传队列与重试状态。
4. 拍照上传入口（显示原图尺寸与上传结果）。
5. 唤醒词状态灯（监听/命令窗/冷却）与灵敏度设置。

### 13.2 Windows UI

1. 会议控制台（开始/结束/当前状态）。
2. 实时转写双轨视图（实时流 + 稳定稿）。
3. 历史会议列表（按日期、关键词检索）。
4. 会后任务面板（备份上传、加精、说话人修正、报告查看）。
5. 会中事件流中显示 `wakeword.*` 事件（只读监控）。

## 14. 本地 STT/加精方案评估（供选型）

### 14.1 方案 A：沿用当前实时 API + Windows 会后加精（已选定）

1. 会中：继续使用现有实时听写 API（最小改造、最快落地）。
2. 会后：Windows 运行 `faster-whisper` 生成稳定稿。
3. 细粒度说话人区分：接 `WhisperX + pyannote` 或 `sherpa-onnx diarization`。
4. 优点：与现有代码最兼容、风险最低。
5. 风险：会中中英混说与多人重叠段质量依赖当前 API。

### 14.2 方案 B：sherpa-onnx 统一本地链路（Android + Windows，归档备用）

1. 会中可离线实时，支持跨平台部署。
2. 同栈支持 VAD、ASR、说话人相关能力，便于统一维护。
3. 优点：弱网与离线能力强。
4. 风险：工程集成复杂度与模型调参成本更高。

### 14.3 方案 C：Vosk 轻量离线兜底（归档备用）

1. Android/Windows 均可运行，模型体积小、部署轻。
2. 适合作为“无网兜底”或低资源机型备选。
3. 风险：中文会议复杂场景精度与高级能力通常不如前两方案。

## 15. 可靠性要求

1. 会议进行中，Android 音频分片落盘成功率 `>= 99.99%`。
2. 会后分片上传完整率 `= 100%`（checksum 对账）。
3. 任何增强链路失败不得影响主归档链路。
4. 会议结束后 3 分钟内可看到“可回放历史卡片”。
5. 安卓本地音频仅在“Windows 备份已成功 + 超过 7 天”时允许自动清理。
6. 加精任务默认后台异步执行，主线程无阻塞。
7. 唤醒词与 STT 不得出现麦克风资源冲突（0 次双开冲突）。

## 16. 安全与合规

1. 所有接口 Bearer Token 鉴权。
2. 音频/图片按会议目录隔离并可配置保留天数。
3. 历史数据支持脱敏展示。
4. 禁止在代码中硬编码第三方密钥（迁移到安全配置）。

## 17. 里程碑与下一步

### 17.1 M1（先打底）

1. 会议模式开关（Android + Windows UI）。
2. 安卓本地录音分片与 manifest。
3. Windows 音频仓库入库与历史列表。
4. 音频保留策略（7 天、上传成功前不清理）。

### 17.2 M2（可用闭环）

1. 会后上传 + 校验 + 回放。
2. 会后加精转写任务（`faster-whisper`，自动触发，后台线程）。
3. 稳定稿与报告产出。
4. 说话人细分第一版（会后任务链路）。

### 17.3 M3（质量增强）

1. 说话人细分（diarization）。
2. 图片原图分析与图文对照。
3. 批判模式与术语雷达。

## 18. 决策落地清单（已确认）

1. 主方案采用 14.1（现有实时 API + Windows 会后加精）。
2. 14.2/14.3 作为备案备用，不纳入首期主线开发。
3. 安卓本地音频保留 7 天，且以“已上传 Windows 成功”为清理前置条件。
4. 会议结束自动触发加精，采用后台线程/任务队列异步执行。
5. 唤醒词复用单录音链路，避免与现有 STT 链路冲突。
