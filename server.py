#!/usr/bin/env python3
"""
语音助手 HTTP 服务器
运行在 WSL2，接收 Windows 客户端发送的音频，并完成 STT -> LLM -> TTS。
"""

import argparse
import asyncio
import base64
import json
import logging

from aiohttp import web

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class VoiceAssistantServer:
    """语音助手服务端"""

    def __init__(self, port: int = 8765):
        self.port = port
        self.stt = None
        self.llm_endpoint = "http://localhost:11434/api/generate"
        self.llm_model = "qwen2.5:7b"
        self.tts_voice = "zh-CN-XiaoxiaoNeural"

    async def init_models(self):
        """延迟加载模型"""
        if self.stt is None:
            try:
                from faster_whisper import WhisperModel
                logger.info("加载 Whisper 模型...")
                self.stt = WhisperModel("small", device="cpu", compute_type="int8")
                logger.info("模型加载完成")
            except ImportError:
                logger.error("请安装 faster-whisper")
                raise

    async def transcribe_audio(self, audio_data: bytes) -> str:
        import numpy as np

        audio_array = np.frombuffer(audio_data, dtype=np.int16).astype(np.float32) / 32768.0
        segments, _info = self.stt.transcribe(audio_array, language="zh", beam_size=5)
        text = "".join([seg.text for seg in segments]).strip()
        logger.info(f"识别结果: {text}")
        return text

    async def ask_llm(self, text: str) -> str:
        import aiohttp

        payload = {
            "model": self.llm_model,
            "prompt": text,
            "stream": False,
        }
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    self.llm_endpoint,
                    json=payload,
                    timeout=aiohttp.ClientTimeout(total=60),
                ) as resp:
                    if resp.status != 200:
                        body = await resp.text()
                        raise RuntimeError(f"LLM HTTP {resp.status}: {body}")
                    result = await resp.json()
                    response_text = (result.get("response") or "").strip()
                    if not response_text:
                        response_text = "抱歉，我这次没有组织好回复。"
                    logger.info(f"LLM回复: {response_text}")
                    return response_text
        except Exception as e:
            logger.error(f"LLM 调用失败: {e}")
            return f"我听到你说：{text}。不过当前语言模型调用失败了。"

    async def synthesize_tts(self, text: str) -> bytes:
        import edge_tts

        communicate = edge_tts.Communicate(text, self.tts_voice)
        tts_audio = b""
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                tts_audio += chunk["data"]
        return tts_audio

    async def handle_audio(self, request: web.Request) -> web.Response:
        """处理音频请求"""
        try:
            await self.init_models()

            audio_data = await request.read()
            logger.info(f"收到音频: {len(audio_data)} 字节")
            if not audio_data:
                return web.json_response({"error": "空音频"}, status=400)

            text = await self.transcribe_audio(audio_data)
            if not text:
                return web.json_response({"error": "无法识别"}, status=400)

            response_text = await self.ask_llm(text)
            tts_audio = await self.synthesize_tts(response_text)

            return web.json_response({
                "text": text,
                "response": response_text,
                "tts_audio_base64": base64.b64encode(tts_audio).decode("ascii"),
                "tts_size": len(tts_audio),
                "tts_content_type": "audio/mpeg",
            })

        except Exception as e:
            logger.exception("处理错误")
            return web.json_response({"error": str(e)}, status=500)

    async def handle_health(self, request: web.Request) -> web.Response:
        """健康检查"""
        return web.json_response({
            "status": "ok",
            "models_loaded": self.stt is not None,
            "llm_endpoint": self.llm_endpoint,
            "llm_model": self.llm_model,
        })

    async def handle_tts(self, request: web.Request) -> web.Response:
        """TTS 请求"""
        try:
            data = await request.json()
            text = data.get("text", "")
            if not text:
                return web.json_response({"error": "需要 text 参数"}, status=400)

            tts_audio = await self.synthesize_tts(text)
            return web.Response(body=tts_audio, content_type="audio/mpeg")

        except Exception as e:
            logger.error(f"TTS 错误: {e}")
            return web.json_response({"error": str(e)}, status=500)

    async def handle_chat(self, request: web.Request) -> web.Response:
        """纯文本对话接口，便于无音频验证链路"""
        try:
            data = await request.json()
            text = (data.get("text") or "").strip()
            if not text:
                return web.json_response({"error": "需要 text 参数"}, status=400)

            response_text = await self.ask_llm(text)
            tts_audio = await self.synthesize_tts(response_text)
            return web.json_response({
                "text": text,
                "response": response_text,
                "tts_audio_base64": base64.b64encode(tts_audio).decode("ascii"),
                "tts_size": len(tts_audio),
            })
        except Exception as e:
            logger.error(f"Chat 错误: {e}")
            return web.json_response({"error": str(e)}, status=500)

    def create_app(self) -> web.Application:
        app = web.Application()
        app.router.add_post("/audio", self.handle_audio)
        app.router.add_post("/tts", self.handle_tts)
        app.router.add_post("/chat", self.handle_chat)
        app.router.add_get("/health", self.handle_health)
        return app

    def run(self):
        app = self.create_app()
        web.run_app(app, host="0.0.0.0", port=self.port)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="语音助手服务器")
    parser.add_argument("--port", type=int, default=8765, help="监听端口")
    args = parser.parse_args()

    server = VoiceAssistantServer(args.port)
    print(f"启动服务器，端口: {args.port}")
    print("端点:")
    print("  POST /audio - 发送音频进行识别并获得语音回复")
    print("  POST /chat  - 发送文字进行对话并获得语音回复")
    print("  POST /tts   - 文字转语音")
    print("  GET  /health - 健康检查")
    server.run()
