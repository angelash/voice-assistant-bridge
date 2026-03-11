#!/usr/bin/env python3
"""
唤醒词检测模块
使用 OpenWakeWord 检测 "Hey Jarvis" 或自定义唤醒词
"""

import logging
import queue
import threading
import time
from pathlib import Path
from typing import Optional, Callable

logger = logging.getLogger(__name__)


def ensure_openwakeword_model(model_name: str = "hey_jarvis") -> bool:
    """Ensure a built-in openwakeword model exists locally."""
    import openwakeword

    model_paths = openwakeword.get_pretrained_model_paths()
    has_local_model = any(
        model_name in Path(path).name and Path(path).exists()
        for path in model_paths
    )
    if has_local_model:
        return True

    logger.info(f"未检测到内置模型，尝试下载: {model_name}")
    try:
        from openwakeword.utils import download_models
        download_models(model_names=[model_name])
    except Exception as e:
        logger.error(f"自动下载唤醒词模型失败: {e}")
        return False

    model_paths = openwakeword.get_pretrained_model_paths()
    return any(
        model_name in Path(path).name and Path(path).exists()
        for path in model_paths
    )


class WakeWordDetector:
    """唤醒词检测器"""
    
    def __init__(self, 
                 model_path: Optional[str] = None,
                 threshold: float = 0.5,
                 on_detected: Optional[Callable] = None):
        """
        初始化唤醒词检测器
        
        Args:
            model_path: 自定义模型路径（可选）
            threshold: 检测阈值 (0.0 - 1.0)
            on_detected: 检测到唤醒词时的回调函数
        """
        self.model_path = model_path
        self.threshold = threshold
        self.on_detected = on_detected
        self.model = None
        self.is_running = False
        self.audio_queue = queue.Queue()
        
    def load_model(self):
        """加载唤醒词模型"""
        try:
            from openwakeword import Model
            
            if self.model_path:
                logger.info(f"加载自定义唤醒词模型: {self.model_path}")
                model_file = Path(self.model_path)
                if not model_file.exists():
                    logger.error(f"自定义模型文件不存在: {model_file}")
                    return False
                self.model = Model(wakeword_models=[str(model_file)])
            else:
                model_name = "hey_jarvis"
                if not ensure_openwakeword_model(model_name):
                    logger.error(f"无法准备内置唤醒词模型: {model_name}")
                    return False
                logger.info(f"加载默认唤醒词模型 ({model_name})")
                self.model = Model(wakeword_models=[model_name])
            
            logger.info("唤醒词模型加载完成")
            return True
        except ImportError:
            logger.error("请安装 openwakeword: pip install openwakeword")
            return False
        except Exception as e:
            logger.error(f"加载模型失败: {e}")
            return False
    
    def process_audio(self, audio_chunk: bytes):
        """添加音频数据到处理队列"""
        self.audio_queue.put(audio_chunk)
    
    def detect(self, audio_data: bytes) -> bool:
        """
        检测音频中是否包含唤醒词
        
        Args:
            audio_data: 16kHz, 16-bit, mono PCM 音频数据
            
        Returns:
            是否检测到唤醒词
        """
        if self.model is None:
            if not self.load_model():
                return False
        
        try:
            import numpy as np
            
            # 转换音频格式
            audio_array = np.frombuffer(audio_data, dtype=np.int16).astype(np.float32) / 32768.0
            
            # 检测
            prediction = self.model.predict(audio_array)
            
            # 检查结果
            for wakeword, score in prediction.items():
                if isinstance(score, float) and score > self.threshold:
                    logger.info(f"检测到唤醒词 '{wakeword}', 置信度: {score:.2f}")
                    return True
            
            return False
            
        except Exception as e:
            logger.error(f"唤醒词检测错误: {e}")
            return False
    
    def start_continuous(self, audio_callback: Callable[[], bytes]):
        """
        启动持续监听模式
        
        Args:
            audio_callback: 获取音频数据的回调函数
        """
        self.is_running = True
        
        def _listen_loop():
            buffer = b""
            chunk_size = 1280  # 80ms @ 16kHz * 2 bytes
            
            while self.is_running:
                try:
                    audio = audio_callback()
                    if audio:
                        buffer += audio
                        
                        # 累积足够数据后检测
                        while len(buffer) >= chunk_size * 20:  # 1.6 秒窗口
                            chunk = buffer[:chunk_size * 20]
                            buffer = buffer[chunk_size:]  # 滑动窗口
                            
                            if self.detect(chunk):
                                if self.on_detected:
                                    self.on_detected()
                                break
                                
                except Exception as e:
                    logger.error(f"监听错误: {e}")
                    time.sleep(0.1)
        
        thread = threading.Thread(target=_listen_loop, daemon=True)
        thread.start()
        logger.info("唤醒词持续监听已启动")
    
    def stop(self):
        """停止监听"""
        self.is_running = False
        logger.info("唤醒词监听已停止")


class WakeWordTrainer:
    """唤醒词训练器（简化版）"""
    
    @staticmethod
    def generate_training_data(output_dir: str, wakeword: str = "小爱同学"):
        """
        生成唤醒词训练数据说明
        
        注意：实际训练需要大量样本，这里只提供指导
        """
        import os
        
        os.makedirs(output_dir, exist_ok=True)
        
        readme = f"""# 唤醒词训练指南

## 目标唤醒词: {wakeword}

### 方法 1: 使用 OpenWakeWord 训练

```bash
# 安装训练工具
pip install openwakeword[train]

# 准备数据
# 1. 录制至少 500 个正样本（说出"{wakeword}"）
# 2. 录制至少 500 个负样本（其他语音）
# 3. 准备背景噪声样本

# 训练
python -m openwakeword.train --positive_dir ./positive --negative_dir ./negative --output_model {wakeword.replace(" ", "_")}.onnx
```

### 方法 2: 使用 Picovoice Porcupine

1. 访问 https://picovoice.ai/console/
2. 创建免费账号
3. 训练自定义唤醒词
4. 下载 .ppn 模型文件

### 方法 3: 使用现成唤醒词

- "Hey Jarvis" (OpenWakeWord 内置)
- "Alexa" (OpenWakeWord 内置)
- "小爱同学" 需要自定义训练

### 注意事项

- 中文唤醒词训练数据较难获取
- 建议使用 Porcupine 或自行录制训练
- 检测阈值需要根据实际环境调整
"""
        
        readme_path = os.path.join(output_dir, "TRAINING.md")
        with open(readme_path, "w", encoding="utf-8") as f:
            f.write(readme)
        
        logger.info(f"训练指南已生成: {readme_path}")
        return readme_path


if __name__ == "__main__":
    # 测试唤醒词检测
    detector = WakeWordDetector(threshold=0.5)
    
    if detector.load_model():
        print("[PASS] 唤醒词模型加载成功")
        print("内置唤醒词: hey_jarvis, alexa, hey_mycroft, etc.")
    else:
        print("[FAIL] 模型加载失败")
