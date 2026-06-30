# Phone Agent 生活可用补齐设计

日期：2026-06-30

## 目标

把 Android Phone Agent 从工程验证工具推进到可日常使用的手机助手形态。本轮只复用手机自身摄像头、麦克风和喇叭，不接外部挂脖设备。

## 需求分析

1. 低延迟视觉流
   - 现状是定时保存单帧，再把每帧作为独立图片消息上传分析。
   - 目标是建立连续视觉会话：更短采样间隔、滚动关键帧窗口、多帧上下文一次提交给龙虾分析。
   - 不做隐藏采集。启动必须由用户触发，并运行在 Android 前台服务通知内。

2. Artifact 管理
   - 现状远端服务有 artifact 表，Android 本地没有完整索引和管理 UI。
   - 目标是在 Android 本地记录每个附件的来源、类型、大小、上传状态、远端 artifact id、关联消息和错误。
   - UI 需要展示附件列表、存储占用、单个删除、按时间清理。

3. 持续采集策略
   - 现状缺少用户可调的请求限流、摘要合并、长期存储策略。
   - 目标提供可配置的实时采样间隔、定时观察间隔、摘要合并帧数、单次采集上限和本地保留天数。
   - 达到上限、上传失败、权限/策略不满足时直接提示用户，不静默降级。

4. 日常产品形态
   - 现状偏工程工具，按钮和状态文案暴露较多技术词。
   - 目标把主要入口调整为“对话 / 看见听见 / 附件 / 状态 / 设置”，首屏操作更像每天自然使用的手机助手。

## 设计

### 视觉流

- Android CameraX Analyzer 使用 `STRATEGY_KEEP_ONLY_LATEST` 保持低延迟。
- “实时看见”默认每 2 秒接受一帧；“定时观察”默认每 60 秒接受一帧。
- 每帧先作为 image artifact 上传并写入本地附件索引。
- 每累计 N 帧触发一次视觉摘要消息，消息引用最近 N 个 artifact。服务端把多张图片按时间顺序放进同一次 OpenClaw 图片请求。
- 每次会话生成 `stream_id`，所有帧 meta 内带 `stream_id`、`mode`、`frame_index`。

### Artifact 数据

Android 新增 `phone_agent_artifacts` 表：

- `localId`：本地附件 id。
- `bridgeArtifactId`：上传成功后的远端 id。
- `sessionId`、`clientId`：归属会话。
- `artifactType`、`mimeType`、`filename`、`localPath`、`sizeBytes`。
- `captureTs`：采集时间。
- `uploadStatus`：`QUEUED`、`UPLOADING`、`UPLOADED`、`FAILED`。
- `relatedMessageId`：关联消息。
- `source`：来源，如 `camera-photo`、`audio-recording`、`visual-stream`。
- `metaJson`、`lastError`、`createdAt`、`updatedAt`。

### 清理策略

- App 设置提供“本地附件保留天数”。
- 采集服务启动和每次上传后触发一次过期清理。
- 附件 UI 提供 7 天、30 天和按设置清理。
- 清理只删除 App 私有目录中的本地文件和本地索引，不删除远端；远端仍通过“清空远端会话”处理。

### 失败处理

- 权限、前台服务、上传、龙虾不可用、达到采集上限都会更新状态并弹出提示。
- 实时采集上传失败后停止当前摄像头采集，避免持续产生失败请求和本地垃圾数据。
- 不用“假成功”或占位摘要填充 UI。

## 本轮非目标

- 不接外部挂脖设备。
- 不实现原生 RTSP/WebRTC 视频服务器。
- 不在后台自动启动摄像头或麦克风。
- 不把失败请求伪装成本地成功结果。

## 自测计划

1. 后端单测：artifact 上传、图片多帧请求、V1/V2 回归。
2. Android 单测与构建：`testDebugUnitTest`、`assembleDebug`。
3. 真机 adb：
   - 安装 APK。
   - 启动 Phone Agent。
   - 检查附件页、设置项、采集按钮可见。
   - 通过 `adb reverse` 连接本地 Bridge。
   - 启动/停止实时看见和语音唤醒，确认状态与错误提示真实可见。
