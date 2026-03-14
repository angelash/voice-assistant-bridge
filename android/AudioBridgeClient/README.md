# AudioBridgeClient（Android）

## 最小说明

- **minSdk**：21（尽量低兼容）
- **默认端口**：21347（可在 UI 改）
- 当前仅落了工程骨架与 UI 占位；协议/传输/音频链路会按 `docs/02-推进计划（里程碑+工作包）.md` 逐步推进。

## 第一次导入（Android Studio）

1. 用 Android Studio 打开本目录：`src/android/AudioBridgeClient`
2. 如果提示缺少 `gradle-wrapper.jar`：
   - 先运行一次 `gradlew.bat --version`（会触发 wrapper）
   - 或者让 Android Studio 自动修复/重新生成 wrapper

