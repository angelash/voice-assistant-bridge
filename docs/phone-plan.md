任务：在 voice-assistant-bridge 工程上实现 Android Phone Agent MVP

一、背景说明

我之前做过一个 voice-assistant-bridge 工程，目标是把 Android / PC / 本地模型 / OpenClaw 等能力串起来，形成一个统一的语音助手桥接系统。

最近我在看 Looki L1 这类 AI 挂脖设备，它比较接近我想要的“随身 Agent”方向：随身佩戴、第一视角/近身采集、离线缓存、联网后同步、必要时实时辅助。但 Looki L1 本身成熟度还不够，而且我不想用眼镜形态：我本来就戴眼镜，智能眼镜会冲突，重眼镜体验也差。更理想的形态是挂脖、胸夹、吊坠、AI Pin 这类设备。

短期不直接做硬件，先在现有 voice-assistant-bridge 工程上做一版“基于 Android 手机 App 的随身 Agent MVP”。手机先承担：
1. 随身输入端；
2. 视觉/语音采集端；
3. 离线缓存端；
4. Bridge 网关；
5. 未来挂脖硬件的同步中转端。

长期结构是：

Looki-like 挂脖硬件 / ESP32S3 / OmiGlass-like 设备
  -> Wi-Fi / BLE
  -> Android App
  -> voice-assistant-bridge
  -> 本地模型 / OpenClaw / PC 工具 / 记忆系统

现在先不做硬件，不做实时视频流，不做全天候后台偷拍式采集。先把 Android App 和 Bridge 的可靠消息链路、离线队列、图片附件上传、手动拍照分析跑通。

二、核心目标

在现有 voice-assistant-bridge 工程基础上新增 Android 手机客户端，先实现一个稳定的 MVP：

1. Android App 可以配置 Bridge 地址。
2. Android App 可以调用 Bridge 的 health 接口检查连接。
3. Android App 可以发送文本消息到 Bridge。
4. Android App 可以显示 Bridge 的本地首答和最终回复。
5. Android App 可以通过 WebSocket 或轮询接收消息状态更新。
6. Android App 有本地离线队列：断网、Bridge 不在线时消息不丢，恢复后自动补发。
7. Android App 后续预留图片/音频 artifact 上传能力。
8. 第二阶段接入手机拍照：拍照后上传图片，再发消息让 Bridge 分析图片。
9. 第三阶段再做语音输入、低频视觉日志、外部挂脖设备同步。

三、重要约束

请先完整读取当前仓库结构，不要根据猜测乱改。

执行前请先检查：
1. 当前仓库实际目录结构；
2. 是否已有 README / ARCHITECTURE / HTTP_API / REQUIREMENTS / server.py / Android 相关目录；
3. 现有 Bridge HTTP API 和 WebSocket API 的真实定义；
4. 是否已有 Android 客户端代码；
5. 是否已有消息状态机、SQLite、队列、OpenClaw worker、image_analysis_worker 等相关实现。

如果实际工程和本说明中的假设不一致，以实际代码为准，优先复用已有结构。不要推翻已有架构。

四、产品定位

这个 Android App 不是普通聊天 App，而是“随身 Agent 客户端”。

它的定位是：

1. 手机端输入器：
   - 文本输入；
   - 后续支持按键语音输入；
   - 后续支持拍照提问。

2. 手机端采集器：
   - 手动拍照；
   - 手动录音；
   - 后续支持低频、明确开启的视觉日志。

3. 手机端网关：
   - 与 PC / Bridge 通信；
   - 管理本地离线队列；
   - 未来与挂脖硬件通过 Wi-Fi/BLE 同步。

4. 手机端状态展示器：
   - 显示 Bridge 是否在线；
   - 显示消息是否已发送、已接受、处理中、完成、失败；
   - 显示本地首答和最终回答。

五、非目标

第一版不要做这些：

1. 不做全天候后台录像。
2. 不做隐藏式采集。
3. 不做运动相机接入。
4. 不做 USB 摄像头接入。
5. 不做 Wi-Fi Direct / BLE 设备同步。
6. 不做复杂记忆系统 UI。
7. 不做完整语音唤醒。
8. 不做实时视频流。
9. 不大改 Bridge 主链路。
10. 不重构无关代码。

第一版只做稳定文本链路 + 本地队列 + UI 骨架。

六、推荐技术栈

Android 端建议：

- Kotlin
- Jetpack Compose
- Room
- WorkManager
- OkHttp 或 Ktor Client
- WebSocket
- DataStore
- CameraX，第二阶段再接
- Foreground Service，后续实时连接/采集时再接

服务端继续沿用当前 voice-assistant-bridge 里的技术栈。不要为了 Android MVP 重写 Bridge。

七、整体架构

建议结构：

Android App
  - UI 层
    - SettingsScreen
    - ChatScreen
    - CaptureScreen，第二阶段
  - Domain 层
    - SendMessageUseCase
    - SyncPendingMessagesUseCase
    - UploadArtifactUseCase，第二阶段
  - Data 层
    - BridgeApi
    - MessageRepository
    - ArtifactRepository，第二阶段
    - SettingsRepository
  - Local DB
    - messages
    - artifacts
    - pending_requests
    - settings
  - Background
    - SyncPendingMessagesWorker
    - UploadArtifactsWorker，第二阶段
    - EventStreamService，WebSocket 稳定后接

Bridge Server
  - 保留已有 /v1/messages 主链路
  - 保留已有消息状态机
  - 后续新增 /v1/artifacts
  - 后续让 message 支持 artifact 引用

八、Android 目录建议

如果仓库当前没有 Android 目录，建议新增：

android/PhoneAgent/

或：

android/AudioBridgeClient/

内部结构：

android/PhoneAgent/
  settings.gradle.kts
  build.gradle.kts
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/angelash/phoneagent/
        MainActivity.kt
        App.kt

        data/
          api/
            BridgeApi.kt
            BridgeApiModels.kt
            EventStreamClient.kt
          db/
            AppDatabase.kt
            MessageDao.kt
            ArtifactDao.kt
            PendingRequestDao.kt
          repository/
            MessageRepository.kt
            ArtifactRepository.kt
            SettingsRepository.kt

        domain/
          SendMessageUseCase.kt
          SyncPendingMessagesUseCase.kt
          UploadArtifactUseCase.kt

        service/
          BridgeForegroundService.kt
          EventStreamService.kt

        worker/
          SyncPendingMessagesWorker.kt
          UploadArtifactsWorker.kt

        ui/
          ChatScreen.kt
          SettingsScreen.kt
          CaptureScreen.kt
          components/

        model/
          Message.kt
          MessageStatus.kt
          Artifact.kt
          BridgeEvent.kt
          AppSettings.kt

第一版可以不把所有文件都建满，但结构要预留清楚。

九、Bridge API 适配

请先检查实际仓库 API。如果已有类似接口，优先复用。

第一版需要接入：

1. Health Check

GET /health

用于 SettingsScreen 检查 Bridge 是否可用。

2. 发送消息

POST /v1/messages

建议请求结构，按现有服务端实际字段调整：

{
  "text": "用户输入内容",
  "client_id": "android-phone",
  "session_id": "daily-agent-20260610",
  "source": "android",
  "message_id": "uuid"
}

要求：
- message_id 由 Android 端生成 UUID；
- 发送失败要进入本地 pending；
- 重试时保持相同 message_id，保证幂等；
- client_id 默认 android-phone，可在设置里改；
- source 固定 android；
- session_id 可以按日期生成，也可以由用户选择当前会话。

3. 查询消息状态

GET /v1/messages/{message_id}

用于补偿查询和 UI 刷新。

4. 事件流

GET /v1/events

如果 Bridge 已有 WebSocket，优先接入 WebSocket。
如果暂时不稳定，先用轮询兜底。

事件类型建议兼容：
- accepted
- local_reply
- forwarded
- waiting_openclaw
- retrying
- openclaw_reply
- delivered
- failed

Android UI 收到事件后，按 message_id 更新本地消息。

十、Android 本地数据库设计

第一版 Room 表：

MessageEntity:
- id: String，Android 本地 id，可以等于 message_id
- messageId: String
- sessionId: String
- clientId: String
- source: String
- role: user / assistant / system
- text: String
- localStatus: pending / sending / sent / failed
- bridgeStatus: new / accepted / local_replied / waiting_openclaw / delivered / failed
- createdAt: Long
- updatedAt: Long
- errorMessage: String?

PendingRequestEntity:
- id: String
- requestType: send_message / upload_artifact
- payloadJson: String
- retryCount: Int
- nextRetryAt: Long
- createdAt: Long
- updatedAt: Long
- lastError: String?

SettingsEntity 或 DataStore:
- bridgeBaseUrl
- apiToken，可选
- clientId
- defaultSessionId
- useWebSocket
- allowMobileNetworkSync
- allowAutoCapture，第一版默认 false

第二阶段再加：

ArtifactEntity:
- artifactId: String?
- localId: String
- sessionId: String
- clientId: String
- type: image / audio / file
- localPath: String
- mimeType: String
- sizeBytes: Long
- captureTs: Long
- uploadStatus: pending / uploading / uploaded / failed
- bridgeArtifactId: String?
- relatedMessageId: String?
- metaJson: String?

十一、UI 需求

第一版 UI 保持简单。

1. SettingsScreen

字段：
- Bridge 地址，例如 http://192.168.1.100:8000
- Client ID，默认 android-phone
- Session ID，默认 daily-agent-yyyyMMdd
- Token，可选
- Health Check 按钮
- 连接状态展示

验收：
- 可以保存设置；
- 关闭 App 重开后设置不丢；
- 点 Health Check 可以显示成功/失败和错误原因。

2. ChatScreen

功能：
- 文本输入框；
- 发送按钮；
- 消息列表；
- 消息状态显示；
- 支持本地 pending 消息；
- 支持失败后手动重试；
- 收到 local_reply 时先显示；
- 收到最终回复时追加或更新。

消息展示建议：
- 用户消息：右侧；
- 助手消息：左侧；
- 状态小字：sending / accepted / local replied / waiting / delivered / failed；
- 失败消息显示重试按钮。

3. CaptureScreen，第二阶段

暂不实现，先留入口或 TODO。
第二阶段再加：
- 拍照按钮；
- 图片预览；
- “拍照并提问”快捷操作；
- 上传状态。

十二、第一阶段开发任务

Phase 0：仓库检查

Codex 先输出当前工程结构摘要：
1. 根目录有哪些关键文件；
2. Bridge 服务入口在哪里；
3. 现有 HTTP API 如何定义；
4. 是否已有 Android 工程；
5. 如果已有 Android 工程，当前状态如何。

不要先写代码，先确认结构。

Phase 1：Android 工程骨架

目标：
- 创建 Android App；
- 能编译运行；
- 有 SettingsScreen 和 ChatScreen；
- 使用 Compose；
- 支持保存 Bridge 地址。

验收：
- Android Studio 可打开；
- Gradle build 成功；
- App 可启动；
- Settings 能保存和读取。

Phase 2：Health Check + 文本消息发送

目标：
- 实现 BridgeApi；
- 接入 /health；
- 接入 /v1/messages；
- ChatScreen 可发送文本；
- 本地保存用户消息和服务端返回。

验收：
- Bridge 在线时，发送消息成功；
- Bridge 不在线时，UI 不崩溃；
- 错误原因可见；
- message_id 使用 UUID；
- client_id/source/session_id 正确传递。

Phase 3：本地离线队列

目标：
- 引入 Room；
- 发送失败的消息进入 pending；
- WorkManager 定期或网络恢复后补发；
- 重试保持相同 message_id。

验收：
- 断开 Bridge 后发送消息，显示 pending；
- 恢复 Bridge 后自动补发；
- 不重复创建多条用户消息；
- 重启 App 后 pending 仍存在。

Phase 4：事件流 / 状态更新

目标：
- 接入 /v1/events WebSocket；
- 如果 WebSocket 不可用，用轮询兜底；
- 按 message_id 更新消息状态和回复内容。

验收：
- local_reply 能尽快显示；
- final reply 能追加或更新；
- failed 状态能显示；
- WebSocket 断开后能自动重连或降级轮询。

十三、第二阶段：图片 Artifact

第二阶段再做，不要混进第一阶段。

Bridge 侧新增：

POST /v1/artifacts

multipart/form-data:
- file
- artifact_type: image / audio / file
- client_id
- session_id
- message_id，可选
- capture_ts
- meta_json，可选

返回：

{
  "ok": true,
  "artifact_id": "img_xxx",
  "type": "image",
  "url": "/v1/artifacts/img_xxx"
}

新增：

GET /v1/artifacts/{artifact_id}

messages 支持：

{
  "text": "帮我分析这张图",
  "client_id": "android-phone",
  "session_id": "daily-agent",
  "source": "android",
  "message_id": "msg-xxx",
  "artifacts": [
    {
      "artifact_id": "img_xxx",
      "type": "image"
    }
  ]
}

Android 侧新增：
- CameraX 拍照；
- 本地保存图片；
- 上传 artifact；
- 上传成功后发送 message；
- 上传失败进入 pending；
- 支持“拍照并提问”。

第二阶段验收：
- 手机能拍照；
- 图片能上传 Bridge；
- message 能引用图片；
- Bridge 能保存图片文件和元数据；
- 即使图片上传失败，也不会丢本地文件。

十四、第三阶段：语音输入

第三阶段再做：

1. 按住说话；
2. 录音文件本地保存；
3. 先用系统 SpeechRecognizer 或简单 STT；
4. 转文本后走 /v1/messages；
5. 原始音频作为 artifact 可选上传。

不要第一版就做常驻唤醒词。
不要第一版就做后台持续录音。

十五、第四阶段：视觉日志 / Looki-like 模式

这是后续方向，不是当前 MVP。

目标：
- 明确用户开启后，手机低频采集；
- 比如每 30 秒或 60 秒采一张低分辨率图片；
- 只在前台服务开启、有明显通知时工作；
- 本地缓存；
- Wi-Fi 下同步；
- Bridge 做摘要、检索、问答。

注意：
- 默认关闭；
- 必须有明显 UI 状态；
- 必须能一键暂停；
- 必须能清空本地数据；
- 不做隐藏式采集。

十六、未来挂脖硬件预留

Android App 后续要支持外部设备，但当前不实现。

未来设备形态：
- ESP32S3 / OmiGlass-like / 自制挂脖设备；
- 有摄像头；
- 有麦克风；
- 有本地 SD 卡；
- Wi-Fi / BLE；
- 离线采集；
- 靠近手机后同步。

Android 端未来新增 ExternalDeviceSource 抽象：

CaptureSource:
- phone_camera
- phone_mic
- external_neck_camera
- external_audio_node

未来同步流程：
1. BLE 发现设备；
2. Wi-Fi SoftAP 或同局域网连接；
3. 拉取 manifest；
4. 增量同步图片/音频；
5. 写入 ArtifactEntity；
6. 上传 Bridge；
7. 用户可以问“刚才我看到什么”。

当前阶段只需要在数据模型里不要把 source 写死成 phone。

十七、隐私和安全要求

必须遵守：

1. 不做隐藏式后台摄像头。
2. 不做默认后台录音。
3. 采集必须由用户明确触发，或在用户明确开启的前台服务中执行。
4. 如果启用持续采集，必须有常驻通知。
5. 本地图片、音频要能删除。
6. Settings 里要有“清空本地数据”入口，后续实现。
7. Bridge API 如果暴露在局域网，建议支持 token。
8. Android 不要把 token 打进日志。
9. 错误日志不要记录完整用户隐私内容。
10. 不要上传通讯录、相册、定位等无关数据。

十八、开发风格要求

1. 小步提交，不要一次性大改。
2. 优先跑通 MVP，不要过早抽象。
3. 不要重构无关 Windows 客户端。
4. 不要改动 OpenClaw 主逻辑，除非 API 必须适配。
5. 新增代码要有清晰命名。
6. 网络请求要有超时。
7. 所有失败都要能在 UI 或日志里看到原因。
8. 保持 message_id 幂等。
9. 离线队列要避免重复发送。
10. 如果某个接口实际不存在，先做适配层或 TODO，不要硬编码错误路径。

十九、第一版最终验收标准

MVP 完成时，应该能做到：

1. 打开 Android App；
2. 设置 Bridge 地址；
3. 点 Health Check，能看到 Bridge 在线；
4. 在 ChatScreen 输入一句话；
5. App 生成 message_id 并调用 /v1/messages；
6. Bridge 返回后，App 显示消息状态；
7. 如果有 local_reply，App 能显示本地首答；
8. 如果有最终回复，App 能显示最终回复；
9. 断开 Bridge 后发送消息，App 显示 pending；
10. 恢复 Bridge 后，pending 消息自动补发；
11. App 重启后，历史消息和 pending 队列不丢；
12. Gradle build 通过；
13. 不影响现有 Bridge 服务和 PC 客户端。

二十、建议 Codex 先执行的第一步

请先不要直接写完整功能。

先做以下动作：
1. 扫描仓库结构；
2. 总结当前已有能力；
3. 找到 Bridge 服务入口和 API 定义；
4. 判断是否已有 Android 目录；
5. 给出最小改动计划；
6. 然后开始 Phase 1。

如果实际仓库没有清晰 API 文档，请先从 server.py 或路由注册代码里反推接口。
如果实际仓库已有 Android 工程，请在现有工程上继续，不要新建重复工程。