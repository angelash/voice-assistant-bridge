# 唤醒词训练指南

## 目标唤醒词: 小爱同学

### 方法 1: 使用 OpenWakeWord 训练

```bash
# 安装训练工具
pip install openwakeword[train]

# 准备数据
# 1. 录制至少 500 个正样本（说出"小爱同学"）
# 2. 录制至少 500 个负样本（其他语音）
# 3. 准备背景噪声样本

# 训练
python -m openwakeword.train --positive_dir ./positive --negative_dir ./negative --output_model xiao_ai_tong_xue.onnx
```

### 方法 2: 使用 Picovoice Porcupine（推荐）

1. 访问 https://picovoice.ai/console/
2. 创建免费账号
3. 训练自定义唤醒词"小爱同学"
4. 下载 .ppn 模型文件

**优点**: 训练简单，中文支持好，免费额度足够个人使用

### 方法 3: 使用现成唤醒词

OpenWakeWord 内置唤醒词：
- "hey_jarvis" - 默认英文唤醒词
- "alexa" - Alexa 风格
- "hey_mycroft" - Mycroft 风格

**注意**: 这些都是英文唤醒词，中文唤醒词需要自定义训练。

### 方法 4: 临时替代方案

在完成唤醒词训练前，可以使用以下替代方案：

1. **按键唤醒**: 按 Enter 键触发录音（当前 voice_assistant.py 默认模式）
2. **热键唤醒**: 配置系统热键调用 API
3. **语音命令**: 使用通用唤醒词如 "hey jarvis"

## 训练数据收集脚本

```python
# record_samples.py - 录制训练样本
import sounddevice as sd
import numpy as np
from pathlib import Path

def record_sample(duration=2.0, sample_rate=16000):
    """录制单个样本"""
    print("准备录音...")
    recording = sd.rec(int(duration * sample_rate), samplerate=sample_rate, channels=1)
    sd.wait()
    return recording

def collect_samples(output_dir, count=100, wakeword="小爱同学"):
    """收集训练样本"""
    output_dir = Path(output_dir)
    output_dir.mkdir(exist_ok=True)
    
    print(f"将录制 {count} 个样本，唤醒词: {wakeword}")
    
    for i in range(count):
        input(f"[{i+1}/{count}] 按 Enter 开始录制 '{wakeword}'...")
        
        audio = record_sample()
        np.save(output_dir / f"sample_{i:04d}.npy", audio)
        print(f"  已保存 sample_{i:04d}.npy")
    
    print(f"\n完成！样本保存在 {output_dir}")

if __name__ == "__main__":
    import sys
    output = sys.argv[1] if len(sys.argv) > 1 else "./positive_samples"
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 100
    collect_samples(output, count)
```

使用方法：
```bash
# 录制 100 个正样本
python record_samples.py ./positive 100

# 录制 100 个负样本（说其他词汇）
python record_samples.py ./negative 100
```

## 注意事项

- 中文唤醒词训练数据较难获取，建议使用 Porcupine
- 检测阈值需要根据实际环境调整（默认 0.5）
- 噪声环境需要更多训练数据
- 模型文件放在 `wakeword/` 目录下

## 模型使用

训练完成后，将模型文件放入 `wakeword/` 目录：

```
wakeword/
├── xiao_ai_tong_xue.onnx    # OpenWakeWord 模型
├── xiao_ai_tong_xue.ppn     # Porcupine 模型
└── TRAINING.md              # 本文件
```

在 `voice_assistant.py` 中配置：

```python
CONFIG = {
    "wakeword_model": "wakeword/xiao_ai_tong_xue.onnx",  # 或 .ppn
    ...
}
```
