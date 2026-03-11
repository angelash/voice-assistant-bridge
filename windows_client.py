#!/usr/bin/env python3
"""
Windows 音频桥接客户端

职责：
- Windows 端录音 / 持续监听
- 调用文字脑服务 `/chat` 或调试接口 `/audio`
- 本地保存返回语音（调试用途）

长期推荐架构：
- Windows 端自己做 STT/TTS
- 主要调用 `/chat`
"""

import argparse
import asyncio
import base64
import logging
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


class AudioBridgeClient:
    def __init__(self, server_url: str = "http://localhost:8765", device_index: Optional[int] = None):
        self.server_url = server_url.rstrip("/")
        self.device_index = device_index
        self.p = pyaudio.PyAudio()
        self.is_running = False

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

    def play_mp3_bytes(self, audio_data: bytes):
        out_path = "reply.mp3"
        with open(out_path, "wb") as f:
            f.write(audio_data)
        print(f"已保存语音回复到 {out_path}（当前脚本未内置 MP3 直接播放，建议用系统播放器打开）")

    async def send_audio(self, audio_data: bytes) -> Optional[dict]:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{self.server_url}/audio",
                    data=audio_data,
                    headers={"Content-Type": "application/octet-stream"},
                    timeout=aiohttp.ClientTimeout(total=90),
                ) as resp:
                    if resp.status == 200:
                        result = await resp.json()
                        tts_b64 = result.get("tts_audio_base64")
                        if tts_b64:
                            self.play_mp3_bytes(base64.b64decode(tts_b64))
                        return result
                    logger.error(f"服务器错误: {resp.status} {await resp.text()}")
                    return None
        except aiohttp.ClientError as e:
            logger.error(f"连接失败: {e}")
            return None

    async def send_text(self, text: str) -> Optional[dict]:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{self.server_url}/chat",
                    json={"text": text},
                    timeout=aiohttp.ClientTimeout(total=90),
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
        print(f"录音完成，发送到服务器 ({len(audio_data)} 字节)...")
        result = await self.send_audio(audio_data)
        if result:
            print(f"识别文本: {result.get('input_text')}")
            print(f"助手回复: {result.get('response_text')}")
        return result

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
                                print(f"语音结束，发送 ({len(speaking_buffer)} 字节)...")
                                result = await self.send_audio(speaking_buffer)
                                if result:
                                    print(f"识别文本: {result.get('input_text')}")
                                    print(f"助手回复: {result.get('response_text')}")
                            speaking_buffer = b""
        except KeyboardInterrupt:
            print("\n停止监听")
        finally:
            stream.stop_stream()
            stream.close()

    def close(self):
        self.p.terminate()


async def main():
    parser = argparse.ArgumentParser(description="Windows 音频桥接客户端")
    parser.add_argument("--server", default="http://localhost:8765", help="服务器地址")
    parser.add_argument("--record", type=float, help="录音时长（秒）")
    parser.add_argument("--continuous", action="store_true", help="持续监听模式")
    parser.add_argument("--list-devices", action="store_true", help="列出音频设备")
    parser.add_argument("--device", type=int, help="指定输入设备索引")
    parser.add_argument("--text", help="直接发送文字到服务端，跳过录音")
    args = parser.parse_args()

    client = AudioBridgeClient(args.server, device_index=args.device)
    if args.list_devices:
        client.list_devices()
        return
    try:
        if args.text:
            result = await client.send_text(args.text)
            if result:
                print(f"助手回复: {result.get('response_text')}")
        elif args.record:
            await client.record_and_send(args.record)
        elif args.continuous:
            await client.continuous_mode()
        else:
            print("音频桥接客户端")
            print("命令: r=录音, c=持续监听, t=文字对话, l=列设备, q=退出")
            while True:
                cmd = input("> ").strip().lower()
                if cmd == 'q':
                    break
                if cmd == 'r':
                    duration = float(input("录音时长（秒）: ") or "5")
                    await client.record_and_send(duration)
                elif cmd == 'c':
                    await client.continuous_mode()
                elif cmd == 't':
                    text = input("输入文字: ").strip()
                    if text:
                        result = await client.send_text(text)
                        if result:
                            print(f"助手回复: {result.get('response_text')}")
                elif cmd == 'l':
                    client.list_devices()
    finally:
        client.close()


if __name__ == "__main__":
    asyncio.run(main())
