#!/usr/bin/env python3
"""
Windows 音频桥接客户端

职责：
- Windows 端录音 / 持续监听
- 调用 OpenClaw Gateway 原生 voice-brain 插件接口
- 当前主接口：/api/voice-brain/chat

长期推荐架构：
- Windows 端自己做 wakeword / STT / TTS
- 主要调用 Gateway 的 /api/voice-brain/chat
"""

import argparse
import asyncio
import json
import logging
from pathlib import Path
from typing import Optional

try:
    import aiohttp
    import pyaudio
except ImportError:
    print("请安装依赖: pip install pyaudio aiohttp")
    raise SystemExit(1)

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

CHUNK = 1024
FORMAT = pyaudio.paInt16
CHANNELS = 1
RATE = 16000

DEFAULT_GATEWAY_URL = "http://127.0.0.1:18789"
DEFAULT_CHAT_PATH = "/api/voice-brain/chat"
DEFAULT_HEALTH_PATH = "/api/voice-brain/health"
CONFIG_PATH = Path(__file__).with_name("config.json")


def load_config() -> dict:
    if CONFIG_PATH.exists():
        try:
            return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except Exception as e:
            logger.warning(f"读取配置失败，使用默认值: {e}")
    return {}


class AudioBridgeClient:
    def __init__(self, gateway_url: str, gateway_token: str = "", device_index: Optional[int] = None):
        self.gateway_url = gateway_url.rstrip("/")
        self.gateway_token = gateway_token.strip()
        self.device_index = device_index
        self.p = pyaudio.PyAudio()
        self.is_running = False

    def _headers(self, json_body: bool = True) -> dict:
        headers = {}
        if self.gateway_token:
            headers["Authorization"] = f"Bearer {self.gateway_token}"
        if json_body:
            headers["Content-Type"] = "application/json"
        return headers

    def list_devices(self):
        print("\n可用音频设备:")
        for i in range(self.p.get_device_count()):
            dev = self.p.get_device_info_by_index(i)
            print(f"  [{i}] {dev['name']} (in:{dev['maxInputChannels']}, out:{dev['maxOutputChannels']})")

    def _open_input_stream(self):
        kwargs = dict(format=FORMAT, channels=CHANNELS, rate=RATE, input=True, frames_per_buffer=CHUNK)
        if self.device_index is not None:
            kwargs["input_device_index"] = self.device_index
        return self.p.open(**kwargs)

    async def health(self) -> Optional[dict]:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{self.gateway_url}{DEFAULT_HEALTH_PATH}",
                    headers=self._headers(json_body=False),
                    timeout=aiohttp.ClientTimeout(total=30),
                ) as resp:
                    if resp.status == 200:
                        return await resp.json()
                    logger.error(f"健康检查失败: {resp.status} {await resp.text()}")
                    return None
        except aiohttp.ClientError as e:
            logger.error(f"连接失败: {e}")
            return None

    async def send_text(self, text: str) -> Optional[dict]:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{self.gateway_url}{DEFAULT_CHAT_PATH}",
                    json={"text": text},
                    headers=self._headers(json_body=True),
                    timeout=aiohttp.ClientTimeout(total=120),
                ) as resp:
                    if resp.status == 200:
                        return await resp.json()
                    logger.error(f"服务器错误: {resp.status} {await resp.text()}")
                    return None
        except aiohttp.ClientError as e:
            logger.error(f"连接失败: {e}")
            return None

    async def record_and_send(self, duration: float = 5.0):
        stream = self._open_input_stream()
        frames = []
        print(f"录音中... ({duration}秒)")
        for _ in range(0, int(RATE / CHUNK * duration)):
            frames.append(stream.read(CHUNK, exception_on_overflow=False))
        stream.stop_stream()
        stream.close()
        audio_data = b"".join(frames)
        print(f"录音完成，共 {len(audio_data)} 字节。")
        print("当前 Gateway 主接口是文本接口 /api/voice-brain/chat。请先在 Windows 侧做 STT，再把文字发给 send_text().")
        return None

    async def continuous_mode(self):
        self.is_running = True
        stream = self._open_input_stream()
        print("持续监听模式 (Ctrl+C 停止)...")
        silence_count = 0
        is_speaking = False
        speaking_buffer = b""
        try:
            while self.is_running:
                chunk = stream.read(CHUNK, exception_on_overflow=False)
                import numpy as np
                energy = abs(np.frombuffer(chunk, dtype=np.int16)).mean()
                if energy > 500:
                    if not is_speaking:
                        is_speaking = True
                        print("检测到语音开始...")
                    speaking_buffer += chunk
                    silence_count = 0
                else:
                    if is_speaking:
                        silence_count += 1
                        if silence_count > 30:
                            is_speaking = False
                            if len(speaking_buffer) > RATE * 0.5:
                                print(f"检测到一段语音 ({len(speaking_buffer)} 字节)。当前请在 Windows 侧接 STT 后再调用 /api/voice-brain/chat。")
                            speaking_buffer = b""
        except KeyboardInterrupt:
            print("\n停止监听")
        finally:
            stream.stop_stream()
            stream.close()

    def close(self):
        self.p.terminate()


async def main():
    cfg = load_config()
    parser = argparse.ArgumentParser(description="Windows 音频桥接客户端")
    parser.add_argument("--gateway", default=cfg.get("gateway_url", DEFAULT_GATEWAY_URL), help="Gateway 地址")
    parser.add_argument("--token", default=cfg.get("gateway_token", ""), help="Gateway Bearer token")
    parser.add_argument("--record", type=float, help="录音时长（秒）")
    parser.add_argument("--continuous", action="store_true", help="持续监听模式")
    parser.add_argument("--list-devices", action="store_true", help="列出音频设备")
    parser.add_argument("--device", type=int, help="指定输入设备索引")
    parser.add_argument("--text", help="直接发送文字到 Gateway，跳过录音")
    parser.add_argument("--health", action="store_true", help="调用 /api/voice-brain/health")
    args = parser.parse_args()

    client = AudioBridgeClient(args.gateway, gateway_token=args.token, device_index=args.device)
    if args.list_devices:
        client.list_devices()
        return
    try:
        if args.health:
            result = await client.health()
            if result:
                print(json.dumps(result, ensure_ascii=False, indent=2))
        elif args.text:
            result = await client.send_text(args.text)
            if result:
                print(json.dumps(result, ensure_ascii=False, indent=2))
        elif args.record:
            await client.record_and_send(args.record)
        elif args.continuous:
            await client.continuous_mode()
        else:
            print("Windows 语音壳客户端")
            print("命令: h=health, t=文字对话, r=录音, c=持续监听, l=列设备, q=退出")
            while True:
                cmd = input("> ").strip().lower()
                if cmd == 'q':
                    break
                if cmd == 'h':
                    result = await client.health()
                    if result:
                        print(json.dumps(result, ensure_ascii=False, indent=2))
                elif cmd == 't':
                    text = input("输入文字: ").strip()
                    if text:
                        result = await client.send_text(text)
                        if result:
                            print(json.dumps(result, ensure_ascii=False, indent=2))
                elif cmd == 'r':
                    duration = float(input("录音时长（秒）: ") or "5")
                    await client.record_and_send(duration)
                elif cmd == 'c':
                    await client.continuous_mode()
                elif cmd == 'l':
                    client.list_devices()
    finally:
        client.close()


if __name__ == "__main__":
    asyncio.run(main())
