# Voice Assistant Bridge (Windows Shell)

这是 **Windows 端语音壳**。

职责：
- 麦克风录音
- 持续监听 / 语音片段切分
- 调用 OpenClaw 文字脑服务
- （当前调试状态下）保存返回语音文件

不负责：
- 记忆
- 技能
- 工具调用
- 主 Agent 回复生成

这些由 OpenClaw 侧的 `voice-text-brain` skill 负责。

## 当前文件

- `windows_client.py`：Windows 端测试客户端

## 推荐长期架构

- Windows 端负责 wakeword / STT / TTS
- OpenClaw 端只负责 `/chat`
