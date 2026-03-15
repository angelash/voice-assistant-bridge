# V2 问题归档（执行中）

更新时间：2026-03-14

## 本轮必须处理（P0/P1）

| ID | 优先级 | 模块 | 问题描述 | 目标状态 | 当前状态 |
|---|---|---|---|---|---|
| V2-001 | P0 | 后端 `v2_api.py` | 会议结束自动加精仅判断“存在已上传分片”，未要求“全部分片已上传” | 修复为“全部上传后才自动创建加精任务” | 已完成 |
| V2-002 | P0 | Windows GUI `windows_gui.py` | 会议模式按钮未接通 `/v2/meetings` 与 `/v2/meetings/{id}/mode`，仍为 TODO | 开始/结束会议均调用后端并处理失败回滚 | 已完成 |
| V2-003 | P1 | Android `PcmDistributionBus.kt` | `KwsDetectorConsumer` 为空实现（TODO） | 落地最小可用检测（第一版链路可通） | 已完成（双音节能量触发最小实现） |
| V2-004 | P1 | Android `MainActivity.kt` | 会议模式未显式启停 KWS consumer；会后上传路径依赖当前 meetingId 存在脆弱点 | 修复启停联动 + 使用明确 meetingId 路径 | 已完成 |
| V2-005 | P1 | Android `MeetingManager.kt` | 未实现“仅在已上传前提下保留7天并清理本地” | 增加上传完成标记 + 7天清理函数，并在会后上传完成触发 | 已完成 |

## 已归档待后续（非阻塞首版链路）

| ID | 优先级 | 模块 | 问题描述 | 当前策略 | 当前状态 |
|---|---|---|---|---|---|
| V2-006 | P2 | 图像分析链路 | `images/{id}:analyze` 仍为占位提示，未直连 OpenClaw worker | 已接入异步 worker（可回退到基础分析） | 已完成 |
| V2-007 | P2 | 文案/UI | 部分 GUI 文本存在编码污染（乱码） | 不阻塞主链路，后续统一清洗 | 已完成 |

## 本轮新增修复（P0/P1）

| ID | 优先级 | 模块 | 问题描述 | 目标状态 | 当前状态 |
|---|---|---|---|---|---|
| V2-008 | P0 | Android `MainActivity.kt` / `MeetingManager.kt` | 本地会议 ID 未与服务端 `/v2/meetings` 建立一致，会后上传命中 `meeting_not_found` | 会议开启先创建远端会话并复用远端 `meeting_id` | 已完成 |
| V2-009 | P0 | Android `UploadQueueManager.kt` | 重试任务到时后缺少调度唤醒，且“队列完成”回调可能在失败分片场景误触发上传完成路径 | 增加重试定时唤醒；失败场景不标记 uploaded，不删除本地音频 | 已完成 |
| V2-010 | P1 | 后端 `meeting.py` / `v2_api.py` | 事件 `seq` 可空导致 timeline 增量拉取不稳定；`limit/offset/after_seq` 非法值可触发 500 | 事件序号自动递增；查询参数非法返回 400 | 已完成 |
| V2-011 | P1 | 后端 Worker `transcription_worker.py` / `image_analysis_worker.py` | 线程池任务串行消费，且转写线程内直接 `asyncio.create_task` 存在 event loop 风险 | 并发调度至 `max_workers`；事件发布回到主事件循环路径 | 已完成 |

## 执行记录

- 2026-03-14: 建立问题归档基线，开始按 P0 -> P1 顺序修复。
- 2026-03-14: 完成 V2-001~V2-005，并新增回归测试覆盖“部分分片未上传时不自动加精”。
- 2026-03-14: 完成 V2-006：`/images/:analyze` 改为异步入队，`server.py` 挂载 `ImageAnalysisWorker`。
- 2026-03-14: 使用 JDK17 完成 Android `:app:compileDebugKotlin` 编译验证（通过）。
- 2026-03-14: V2-007 继续推进，完成 `windows_gui.py` 文案清洗；`windows_meeting_gui.py` 源码为 UTF-8 正常文本，终端显示乱码为读取编码问题。
- 2026-03-14: 完成 V2-007 收尾：源码级乱码扫描通过；新增图片分析 worker 回归测试，`pytest test_v2_api.py` 全量通过。
- 2026-03-14: 组合回归通过：`pytest test_v2_api.py test_stability.py`（67 passed）。
- 2026-03-14: 完成 V2-008~V2-011 修复；新增回归用例覆盖事件序号自增、参数非法值 400、跨会议分片上传拦截。
- 2026-03-14: 回归通过：`pytest test_v2_api.py test_stability.py`（71 passed）；当前环境 Android 编译因 Java 8 与 AGP 8.2.2 不匹配未复验（需 JDK 11+/建议 17）。
