#!/usr/bin/env python3
"""
Voice Assistant Brain Server

定位：文字智能处理层。
- Windows 端负责：唤醒词 / STT / TTS / 播放
- 本服务负责：接收文本 -> 调回复后端 -> 返回文本

兼容保留：/audio、/tts（调试用途）
主入口：/chat
"""

import argparse
import asyncio
import base64
import json
import logging
import os
from aiohttp import web

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class VoiceAssistantServer:
    def __init__(self, port: int = 8765):
        self.port = port
        self.stt = None
        self.reply_backend = os.getenv("VOICE_REPLY_BACKEND", "openclaw").strip().lower()
        self.llm_endpoint = os.getenv("VOICE_OLLAMA_ENDPOINT", "http://localhost:11434/api/generate")
        self.llm_model = os.getenv("VOICE_OLLAMA_MODEL", "qwen2.5:7b")
        self.openclaw_session_id = os.getenv("VOICE_OPENCLAW_SESSION_ID", "voice-bridge-session")
        self.openclaw_timeout = int(os.getenv("VOICE_OPENCLAW_TIMEOUT", "120"))
        self.tts_voice = os.getenv("VOICE_TTS_VOICE", "zh-CN-XiaoxiaoNeural")

    async def init_models(self):
        if self.stt is None:
            from faster_whisper import WhisperModel
            logger.info("加载 Whisper 模型...")
            self.stt = WhisperModel("small", device="cpu", compute_type="int8")
            logger.info("模型加载完成")

    async def transcribe_audio(self, audio_data: bytes) -> str:
        import numpy as np
        await self.init_models()
        audio_array = np.frombuffer(audio_data, dtype=np.int16).astype(np.float32) / 32768.0
        segments, _info = self.stt.transcribe(audio_array, language="zh", beam_size=5)
        text = "".join([seg.text for seg in segments]).strip()
        logger.info(f"识别结果: {text}")
        return text

    async def ask_ollama(self, text: str) -> str:
        import aiohttp
        payload = {"model": self.llm_model, "prompt": text, "stream": False}
        async with aiohttp.ClientSession() as session:
            async with session.post(self.llm_endpoint, json=payload, timeout=aiohttp.ClientTimeout(total=60)) as resp:
                if resp.status != 200:
                    body = await resp.text()
                    raise RuntimeError(f"LLM HTTP {resp.status}: {body}")
                result = await resp.json()
                response_text = (result.get("response") or "").strip() or "抱歉，我这次没有组织好回复。"
                logger.info(f"Ollama回复: {response_text}")
                return response_text

    def _extract_json_text(self, output: str) -> dict:
        start = output.find("{")
        if start < 0:
            raise RuntimeError(f"未找到 JSON 输出: {output[:500]}")
        return json.loads(output[start:])

    async def ask_openclaw(self, text: str) -> str:
        cmd = (
            f'openclaw agent --session-id {self.openclaw_session_id} '
            f'--message {json.dumps(text)} --json --timeout {self.openclaw_timeout} 2>/dev/null'
        )
        proc = await asyncio.create_subprocess_shell(
            cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=os.getcwd(),
        )
        stdout, stderr = await proc.communicate()
        if proc.returncode != 0:
            err = stderr.decode("utf-8", errors="ignore")
            out = stdout.decode("utf-8", errors="ignore")
            raise RuntimeError(f"openclaw agent failed: code={proc.returncode}, stderr={err}, stdout={out[:1000]}")
        parsed = self._extract_json_text(stdout.decode("utf-8", errors="ignore"))
        payloads = (parsed.get("result") or {}).get("payloads") or []
        texts = [p.get("text", "") for p in payloads if isinstance(p, dict) and p.get("text")]
        response_text = "\n".join(t.strip() for t in texts if t.strip()) or "抱歉，我这次没有拿到有效回复。"
        logger.info(f"OpenClaw回复(session={self.openclaw_session_id}): {response_text}")
        return response_text

    async def ask_backend(self, text: str) -> str:
        try:
            if self.reply_backend == "openclaw":
                return await self.ask_openclaw(text)
            return await self.ask_ollama(text)
        except Exception as e:
            logger.error(f"{self.reply_backend} 回复后端失败: {e}")
            if self.reply_backend != "ollama":
                logger.info("回退到 ollama 后端")
                try:
                    return await self.ask_ollama(text)
                except Exception as e2:
                    logger.error(f"ollama 回退失败: {e2}")
            return f"我听到你说：{text}。不过当前回复后端暂时失败了。"

    async def synthesize_tts(self, text: str) -> bytes:
        import edge_tts
        communicate = edge_tts.Communicate(text, self.tts_voice)
        tts_audio = b""
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                tts_audio += chunk["data"]
        return tts_audio

    def _chat_payload(self, text: str, response_text: str) -> dict:
        return {
            "ok": True,
            "input_text": text,
            "response_text": response_text,
            "reply_backend": self.reply_backend,
            "session_id": self.openclaw_session_id if self.reply_backend == "openclaw" else None,
        }

    async def handle_chat(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            text = (data.get("text") or "").strip()
            if not text:
                return web.json_response({"ok": False, "error": "需要 text 参数"}, status=400)
            response_text = await self.ask_backend(text)
            return web.json_response(self._chat_payload(text, response_text))
        except Exception as e:
            logger.error(f"Chat 错误: {e}")
            return web.json_response({"ok": False, "error": str(e)}, status=500)

    async def handle_audio(self, request: web.Request) -> web.Response:
        try:
            audio_data = await request.read()
            logger.info(f"收到音频: {len(audio_data)} 字节")
            if not audio_data:
                return web.json_response({"ok": False, "error": "空音频"}, status=400)
            text = await self.transcribe_audio(audio_data)
            if not text:
                return web.json_response({"ok": False, "error": "无法识别"}, status=400)
            response_text = await self.ask_backend(text)
            payload = self._chat_payload(text, response_text)
            tts_audio = await self.synthesize_tts(response_text)
            payload.update({
                "tts_audio_base64": base64.b64encode(tts_audio).decode("ascii"),
                "tts_size": len(tts_audio),
                "tts_content_type": "audio/mpeg",
                "debug_interface": True,
            })
            return web.json_response(payload)
        except Exception as e:
            logger.exception("处理错误")
            return web.json_response({"ok": False, "error": str(e)}, status=500)

    async def handle_tts(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            text = data.get("text", "")
            if not text:
                return web.json_response({"ok": False, "error": "需要 text 参数"}, status=400)
            tts_audio = await self.synthesize_tts(text)
            return web.Response(body=tts_audio, content_type="audio/mpeg")
        except Exception as e:
            logger.error(f"TTS 错误: {e}")
            return web.json_response({"ok": False, "error": str(e)}, status=500)

    async def handle_health(self, request: web.Request) -> web.Response:
        return web.json_response({
            "status": "ok",
            "role": "text-brain",
            "reply_backend": self.reply_backend,
            "ollama_endpoint": self.llm_endpoint,
            "ollama_model": self.llm_model,
            "openclaw_session_id": self.openclaw_session_id,
            "tts_voice": self.tts_voice,
            "debug_audio_supported": True,
        })

    def create_app(self) -> web.Application:
        app = web.Application()
        app.router.add_post("/chat", self.handle_chat)
        app.router.add_get("/health", self.handle_health)
        app.router.add_post("/audio", self.handle_audio)
        app.router.add_post("/tts", self.handle_tts)
        return app

    def run(self):
        web.run_app(self.create_app(), host="0.0.0.0", port=self.port)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="语音助手文字脑服务")
    parser.add_argument("--port", type=int, default=8765, help="监听端口")
    args = parser.parse_args()

    server = VoiceAssistantServer(args.port)
    print(f"启动文字脑服务，端口: {args.port}")
    print(f"回复后端: {server.reply_backend}")
    print("主接口:")
    print("  POST /chat   - 文本输入 -> 文本回复")
    print("  GET  /health - 健康检查")
    print("兼容调试接口:")
    print("  POST /audio  - PCM音频 -> 文本/语音回复")
    print("  POST /tts    - 文字转语音")
    server.run()
