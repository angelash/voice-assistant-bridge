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

## 默认链路

**当前默认就是走 OpenClaw。**

- 脑侧 backend：`openclaw`
- 专用 session：`voice-bridge-session`
- Windows 客户端默认请求：`http://localhost:8765`

## 当前文件

- `windows_client.py`：Windows 端测试客户端
- `config.json`：默认配置（已设置为 OpenClaw）
- `run_windows_client.bat`：Windows 端启动脚本
- `run_text_brain_openclaw.sh`：WSL/OpenClaw 脑侧启动脚本
- `HTTP_API.md`：文字脑接口说明
- `ARCHITECTURE.md`：架构说明

## 推荐启动方式

### 1. WSL / OpenClaw 侧启动文字脑

```bash
bash run_text_brain_openclaw.sh
```

### 2. Windows 侧测试文字请求

```bat
run_windows_client.bat --text "你好"
```

### 3. Windows 侧测试录音

```bat
run_windows_client.bat --record 5
```

## 推荐长期架构

- Windows 端负责 wakeword / STT / TTS
- OpenClaw 端只负责 `/chat`
