# Voice Assistant - 接近小爱同学体验

本地语音助手方案，目标：**唤醒词 → 语音识别 → LLM对话 → 语音合成播放**

## 快速开始

```bash
# 1. 安装依赖
pip install -r requirements.txt

# 2. 测试系统
python test_audio.py

# 3. 启动助手
python voice_assistant.py
```

## 架构

```
┌─────────┐    ┌──────────────┐    ┌─────────────────┐    ┌──────┐    ┌─────────┐
│ 麦克风  │───▶│ 唤醒词检测   │───▶│ STT (Whisper)   │───▶│ LLM  │───▶│ 扬声器  │
└─────────┘    │ OpenWakeWord │    │ faster-whisper  │    │Ollama│    │         │
               └──────────────┘    └─────────────────┘    └──────┘    └─────────┘
                                                               │
                                                               ▼
                                                        ┌─────────────┐
                                                        │ TTS (Edge)  │
                                                        │ edge-tts    │
                                                        └─────────────┘
```

## 组件选型

| 组件 | 方案 | 状态 | 备注 |
|------|------|------|------|
| 唤醒词 | OpenWakeWord | ✅ | 开源免费，支持自定义训练 |
| STT | faster-whisper | ✅ | 比 openai-whisper 快 4x，CPU 友好 |
| TTS | edge-tts | ✅ | 微软 Edge TTS，高质量中文女声 |
| LLM | Ollama (Qwen2.5) | ✅ | 本地运行，中文能力强 |
| 音频 I/O | sounddevice | ✅ | 跨平台录音和播放 |

## 文件结构

```
voice-assistant/
├── README.md              # 本文件
├── requirements.txt       # Python 依赖
├── setup.sh               # 安装脚本
├── voice_assistant.py     # 主程序
├── test_audio.py          # 系统测试
├── wakeword.py            # 唤醒词模块
├── server.py              # HTTP 服务器（WSL2 模式）
├── windows_client.py      # Windows 客户端（WSL2 模式）
└── wakeword/              # 唤醒词模型目录
```

## 使用方式

### 模式 1: 直接运行（Linux/Mac/Windows 原生）

```bash
# 确保 Ollama 运行中
ollama serve

# 启动语音助手
python voice_assistant.py
```

### 模式 2: WSL2 + Windows 音频桥接

WSL2 默认无法访问 Windows 音频设备，使用客户端-服务器架构：

```bash
# WSL2 端：启动服务器
python server.py --port 8765

# Windows 端：运行客户端
python windows_client.py --server http://localhost:8765 --continuous
```

需要将 WSL2 端口转发到 Windows：
```powershell
# Windows PowerShell (管理员)
netsh interface portproxy add v4tov4 listenaddress=127.0.0.1 listenport=8765 connectaddress=<WSL2_IP> connectport=8765
```

## 配置

编辑 `voice_assistant.py` 中的 `CONFIG` 字典：

```python
CONFIG = {
    "wakeword": "小爱同学",           # 唤醒词
    "sample_rate": 16000,             # 音频采样率
    "stt_model": "small",             # Whisper 模型大小
    "tts_voice": "zh-CN-XiaoxiaoNeural",  # TTS 声音
    "llm_endpoint": "http://localhost:11434/api/generate",
    "llm_model": "qwen2.5:7b",        # Ollama 模型
}
```

### 可用 TTS 声音

```bash
# 列出中文声音
edge-tts --list-voices | grep zh-CN

# 常用中文声音
zh-CN-XiaoxiaoNeural      # 女声，自然
zh-CN-YunxiNeural         # 男声
zh-CN-YunyangNeural       # 男声，新闻风格
```

### Whisper 模型大小

| 模型 | 参数量 | 内存 | 速度 | 精度 |
|------|--------|------|------|------|
| tiny | 39M | ~1GB | 最快 | 一般 |
| base | 74M | ~1GB | 快 | 较好 |
| small | 244M | ~2GB | 中等 | 好 |
| medium | 769M | ~5GB | 慢 | 很好 |
| large | 1550M | ~10GB | 最慢 | 最好 |

## 唤醒词训练

默认使用 "hey_jarvis" 唤醒词。如需自定义（如"小爱同学"）：

**查看训练指南**: `cat wakeword/TRAINING.md`

**注意**：中文唤醒词训练需要大量样本，建议：
1. 使用 Porcupine (https://picovoice.ai) 在线训练（推荐）
2. 自行录制 500+ 样本训练

## LLM 后端

### Ollama（推荐）

```bash
# 安装
curl https://ollama.ai/install.sh | sh

# 下载中文模型
ollama pull qwen2.5:7b

# 启动服务
ollama serve
```

### 其他选项

- **LM Studio**: 图形界面，支持多种模型
- **vLLM**: 高性能推理服务
- **OpenAI API**: 修改 `llm_endpoint` 即可

## 故障排除

### 音频设备不可用

```bash
# 检查设备
python -c "import sounddevice; print(sounddevice.query_devices())"

# WSL2 用户
# 1. 确保 PulseAudio 在 Windows 运行
# 2. 或使用 windows_client.py
```

如果是在 WSL2 中看到 `Error querying device -1`，通常不是代码问题，而是当前 Linux 环境根本没有可见的音频输入/输出设备。

### OpenWakeWord 安装失败（Linux + Python 3.12）

原因：`openwakeword` 在 Linux 上依赖 `tflite-runtime`，而当前环境缺少 Python 3.12 对应的可用发行包。

可选方案：

```bash
# 方案 1：先安装其余依赖（当前 requirements.txt 已自动跳过该组合下的 openwakeword）
pip install -r requirements.txt

# 方案 2：若必须使用 openwakeword，改用 Python 3.11 虚拟环境
python3.11 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

临时影响：语音识别 / 对话 / TTS 可以先跑通，但唤醒词功能需要改用 Python 3.11 或其他后端（如 Porcupine）。

### OpenWakeWord 已安装但加载模型失败

如果报错类似：

```text
Could not open .../site-packages/openwakeword/resources/models/alexa_v0.1.tflite
```

说明当前安装的 `openwakeword` 包里可能没有带上内置模型资源。这时有三种处理方式：

1. 先跳过唤醒词，只验证 STT / LLM / TTS 主链路
2. 重新安装 `openwakeword`，检查 wheel 是否完整
3. 直接使用你自己训练或下载的唤醒词模型，通过 `wakeword.py` 的 `model_path` 加载

### Whisper 模型下载慢

```bash
# 手动下载模型
export HF_ENDPOINT=https://hf-mirror.com
pip install faster-whisper
```

### 内存不足

```bash
# 使用更小的模型
CONFIG["stt_model"] = "tiny"  # 或 "base"
```

## 性能优化

1. **GPU 加速**: 安装 CUDA 版 PyTorch，模型会自动使用 GPU
2. **量化**: faster-whisper 默认使用 int8 量化
3. **流式处理**: 大音频可分块处理减少延迟

## 下一步

- [ ] 集成唤醒词（需训练"小爱同学"模型）
- [ ] 添加对话历史持久化
- [ ] 支持多轮对话上下文
- [ ] 添加智能家居控制（通过 OpenClaw）
- [ ] 语音活动检测 (VAD) 优化

## 相关技能

- `tts` (内置): OpenClaw TTS 工具
- `weather`: 天气查询
- `gog`: Google 服务集成

---

Made with ❤️ by Clawra
