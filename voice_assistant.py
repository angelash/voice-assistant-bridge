#!/usr/bin/env python3
"""
Voice Assistant - 接近小爱同学体验
主程序入口

架构: 麦克风 → 唤醒词检测 → STT → LLM → TTS → 扬声器
"""

import asyncio
import queue
import threading
from pathlib import Path
from typing import Optional
import logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# ============ 配置 ============
CONFIG = {
    "wakeword": "小爱同学",
    "sample_rate": 16000,
    "channels": 1,
    "stt_model": "small",  # faster-whisper: tiny, base, small, medium, large
    "tts_voice": "zh-CN-XiaoxiaoNeural",  # edge-tts 中文女声
    "llm_endpoint": "http://localhost:11434/api/chat",  # Ollama
    "llm_model": "qwen2.5:7b",
}

# ============ 状态机 ============
class AssistantState:
    IDLE = "idle"           # 等待唤醒词
    LISTENING = "listening" # 正在录音
    PROCESSING = "processing"  # STT/LLM处理中
    SPEAKING = "speaking"   # TTS播放中

# ============ 音频管理器 ============
class AudioIO:
    """音频输入输出管理"""
    
    def __init__(self):
        self.sample_rate = CONFIG["sample_rate"]
        self.audio_queue = queue.Queue()
        self.is_recording = False
        
    def list_devices(self):
        """列出可用音频设备"""
        try:
            import sounddevice as sd
            devices = sd.query_devices()
            logger.info("可用音频设备:")
            for i, dev in enumerate(devices):
                logger.info(f"  [{i}] {dev['name']} (in:{dev['max_input_channels']}, out:{dev['max_output_channels']})")
            return devices
        except Exception as e:
            logger.error(f"获取音频设备失败: {e}")
            return []
    
    def check_audio_available(self) -> bool:
        """检查音频是否可用"""
        try:
            import sounddevice as sd
            devices = sd.query_devices()
            input_devices = [d for d in devices if d['max_input_channels'] > 0]
            output_devices = [d for d in devices if d['max_output_channels'] > 0]
            
            if not input_devices:
                logger.warning("没有找到输入设备（麦克风）")
                return False
            if not output_devices:
                logger.warning("没有找到输出设备（扬声器）")
                return False
            
            logger.info(f"音频设备就绪: {len(input_devices)} 输入, {len(output_devices)} 输出")
            return True
        except Exception as e:
            logger.error(f"音频检查失败: {e}")
            return False
    
    def record_audio(self, duration: float = 5.0) -> Optional[bytes]:
        """录制指定时长的音频"""
        try:
            import sounddevice as sd
            import numpy as np
            
            logger.info(f"开始录音 {duration} 秒...")
            recording = sd.rec(
                int(duration * self.sample_rate),
                samplerate=self.sample_rate,
                channels=1,
                dtype=np.int16
            )
            sd.wait()
            logger.info("录音完成")
            return recording.tobytes()
        except Exception as e:
            logger.error(f"录音失败: {e}")
            return None
    
    def play_audio(self, audio_data: bytes, sample_rate: int = 24000):
        """播放音频"""
        try:
            import sounddevice as sd
            import numpy as np
            
            audio_array = np.frombuffer(audio_data, dtype=np.int16)
            sd.play(audio_array, samplerate=sample_rate)
            sd.wait()
            logger.info("播放完成")
        except Exception as e:
            logger.error(f"播放失败: {e}")

# ============ STT 模块 ============
class STTEngine:
    """语音转文字"""
    
    def __init__(self, model_size: str = "small"):
        self.model_size = model_size
        self.model = None
    
    def load_model(self):
        """延迟加载模型"""
        if self.model is None:
            try:
                from faster_whisper import WhisperModel
                logger.info(f"加载 Whisper 模型: {self.model_size}")
                self.model = WhisperModel(
                    self.model_size,
                    device="cpu",
                    compute_type="int8"
                )
                logger.info("模型加载完成")
            except ImportError:
                logger.error("请安装 faster-whisper: pip install faster-whisper")
                raise
    
    def transcribe(self, audio_data: bytes) -> Optional[str]:
        """转录音频为文字"""
        self.load_model()
        
        try:
            import numpy as np
            audio_array = np.frombuffer(audio_data, dtype=np.int16).astype(np.float32) / 32768.0
            
            segments, info = self.model.transcribe(
                audio_array,
                language="zh",
                beam_size=5
            )
            
            text = "".join([seg.text for seg in segments])
            logger.info(f"识别结果: {text}")
            return text.strip()
        except Exception as e:
            logger.error(f"转录失败: {e}")
            return None

# ============ TTS 模块 ============
class TTSEngine:
    """文字转语音"""
    
    def __init__(self, voice: str = "zh-CN-XiaoxiaoNeural"):
        self.voice = voice
    
    async def synthesize(self, text: str) -> Optional[bytes]:
        """合成语音"""
        try:
            import edge_tts
            
            communicate = edge_tts.Communicate(text, self.voice)
            audio_data = b""
            
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    audio_data += chunk["data"]
            
            logger.info(f"语音合成完成: {len(audio_data)} 字节")
            return audio_data
        except ImportError:
            logger.error("请安装 edge-tts: pip install edge-tts")
            return None
        except Exception as e:
            logger.error(f"TTS 失败: {e}")
            return None
    
    def synthesize_sync(self, text: str) -> Optional[bytes]:
        """同步接口"""
        return asyncio.run(self.synthesize(text))

# ============ LLM 模块 ============
class LLMClient:
    """大语言模型接口"""
    
    def __init__(self, endpoint: str, model: str):
        self.endpoint = endpoint
        self.model = model

    @staticmethod
    def _build_prompt(user_input: str, history: list) -> str:
        """Build a simple prompt for /api/generate compatibility."""
        lines = []
        for msg in history[-10:]:
            role = msg.get("role", "user")
            content = (msg.get("content") or "").strip()
            if not content:
                continue
            if role == "assistant":
                lines.append(f"助手: {content}")
            else:
                lines.append(f"用户: {content}")
        lines.append(f"用户: {user_input}")
        lines.append("助手:")
        return "\n".join(lines)
    
    async def chat(self, user_input: str, history: list = None) -> str:
        """对话"""
        import aiohttp
        
        messages = list(history or [])
        messages.append({"role": "user", "content": user_input})

        endpoint = self.endpoint.rstrip("/")
        is_chat_api = endpoint.endswith("/api/chat")
        if is_chat_api:
            payload = {
                "model": self.model,
                "messages": messages,
                "stream": False,
            }
        else:
            payload = {
                "model": self.model,
                "prompt": self._build_prompt(user_input, history or []),
                "stream": False,
            }
        
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    self.endpoint,
                    json=payload,
                    timeout=aiohttp.ClientTimeout(total=60)
                ) as resp:
                    if resp.status != 200:
                        body = await resp.text()
                        raise RuntimeError(f"Ollama HTTP {resp.status}: {body}")

                    result = await resp.json(content_type=None)
                    chat_message = (result.get("message") or {}).get("content", "")
                    generate_message = result.get("response", "")
                    output = (chat_message or generate_message or "").strip()
                    return output or "抱歉，我没能理解。"
        except Exception as e:
            logger.error(f"LLM 调用失败: {e}")
            return "抱歉，连接失败了。"

# ============ 主程序 ============
class VoiceAssistant:
    """语音助手主类"""
    
    def __init__(self):
        self.state = AssistantState.IDLE
        self.audio = AudioIO()
        self.stt = STTEngine(CONFIG["stt_model"])
        self.tts = TTSEngine(CONFIG["tts_voice"])
        self.llm = LLMClient(CONFIG["llm_endpoint"], CONFIG["llm_model"])
        self.history = []
    
    def initialize(self):
        """初始化系统"""
        logger.info("=" * 50)
        logger.info("语音助手启动")
        logger.info("=" * 50)
        
        # 检查音频
        if not self.audio.check_audio_available():
            logger.warning("音频设备不可用，将在模拟模式下运行")
            self.simulation_mode = True
        else:
            self.simulation_mode = False
            self.audio.list_devices()
        
        return True
    
    async def process_query(self, text: str) -> str:
        """处理用户查询"""
        logger.info(f"用户: {text}")
        
        # 调用 LLM
        response = await self.llm.chat(text, self.history)
        logger.info(f"助手: {response}")
        
        # 更新历史
        self.history.append({"role": "user", "content": text})
        self.history.append({"role": "assistant", "content": response})
        
        # 保持历史长度
        if len(self.history) > 20:
            self.history = self.history[-20:]
        
        return response
    
    async def speak(self, text: str):
        """语音播放"""
        self.state = AssistantState.SPEAKING
        
        audio_data = await self.tts.synthesize(text)
        if audio_data and not self.simulation_mode:
            self.audio.play_audio(audio_data)
        
        self.state = AssistantState.IDLE
    
    async def run_interactive(self):
        """交互模式（无唤醒词，按回车开始说话）"""
        logger.info("进入交互模式，按回车键开始说话，输入 'quit' 退出")
        
        while True:
            try:
                user_input = input("\n按回车开始说话（或输入问题）> ").strip()
                
                if user_input.lower() == 'quit':
                    break
                
                # 如果直接输入了文字，跳过录音
                if user_input:
                    text = user_input
                elif not self.simulation_mode:
                    # 录音
                    self.state = AssistantState.LISTENING
                    audio_data = self.audio.record_audio(5.0)
                    if not audio_data:
                        continue
                    
                    # STT
                    self.state = AssistantState.PROCESSING
                    text = self.stt.transcribe(audio_data)
                    if not text:
                        logger.warning("未能识别语音")
                        continue
                else:
                    # 模拟模式
                    text = input("模拟模式 - 请输入文字: ")
                
                # 处理并回复
                response = await self.process_query(text)
                await self.speak(response)
                
            except KeyboardInterrupt:
                break
            except Exception as e:
                logger.error(f"处理错误: {e}")
        
        logger.info("再见！")

async def main():
    assistant = VoiceAssistant()
    if assistant.initialize():
        await assistant.run_interactive()

if __name__ == "__main__":
    asyncio.run(main())
