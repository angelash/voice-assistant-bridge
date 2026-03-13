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
import base64
import io
import json
import logging
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Optional
from urllib.parse import urlparse

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
DEFAULT_V1_MESSAGES_PATH = "/v1/messages"
LOCAL_LLM_URL = "http://127.0.0.1:8765"
LOCAL_CHAT_PATH = "/v1/messages"
LOCAL_HEALTH_PATH = "/health"
CONFIG_PATH = Path(__file__).with_name("config.json")


def load_config() -> dict:
    if CONFIG_PATH.exists():
        try:
            return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except Exception as e:
            logger.warning(f"读取配置失败，使用默认值: {e}")
    return {}


def _is_loopback_url(url: str) -> bool:
    try:
        parsed = urlparse(url)
        host = (parsed.hostname or "").lower()
    except Exception:
        return False
    return host in {"127.0.0.1", "localhost", "::1", "0.0.0.0"}


def _url_port(url: str, default_port: int) -> int:
    try:
        parsed = urlparse(url)
        if parsed.port:
            return parsed.port
    except Exception:
        pass
    return default_port


def _should_treat_as_local_mode(gateway_url: str, chat_path: str, force_local: bool) -> bool:
    if force_local:
        return True
    normalized = chat_path.strip().lower()
    return _is_loopback_url(gateway_url) and normalized in {LOCAL_CHAT_PATH, DEFAULT_V1_MESSAGES_PATH}


async def ensure_local_service_if_needed(
    client: "AudioBridgeClient",
    auto_start_local: bool,
    local_mode: bool,
) -> None:
    if not local_mode:
        return

    if await client.health(timeout_sec=2, log_error=False):
        return

    if not auto_start_local:
        logger.warning("本地模式检测到服务不可达，且已禁用自动启动。")
        return

    server_path = Path(__file__).with_name("server.py")
    if not server_path.exists():
        logger.error(f"未找到本地服务入口: {server_path}")
        return

    port = _url_port(client.gateway_url, default_port=8765)
    env = os.environ.copy()
    env.setdefault("VOICE_OPERATOR_ENDPOINT", "http://localhost:11434/api/generate")
    env.setdefault("VOICE_OPERATOR_MODEL", "qwen2.5:7b")

    creationflags = 0
    if os.name == "nt":
        creationflags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW

    try:
        subprocess.Popen(
            [sys.executable, str(server_path), "--port", str(port)],
            cwd=str(server_path.parent),
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=creationflags,
        )
    except Exception as e:
        logger.error(f"自动启动本地服务失败: {e}")
        return

    print(f"检测到本地服务未启动，已自动拉起: {client.gateway_url}{client.chat_path}")
    for _ in range(20):
        await asyncio.sleep(0.5)
        if await client.health(timeout_sec=2, log_error=False):
            print("本地服务已就绪。")
            return
    logger.error("本地服务自动启动后仍不可达，请手动检查 `python server.py --port 8765`。")


class AudioBridgeClient:
    def __init__(
        self,
        gateway_url: str,
        gateway_token: str = "",
        device_index: Optional[int] = None,
        tts_voice: str = "Xiaoxiao",
        tts_edge_voice: str = "zh-CN-XiaoxiaoNeural",
        chat_path: str = DEFAULT_CHAT_PATH,
        health_path: str = DEFAULT_HEALTH_PATH,
    ):
        self.gateway_url = gateway_url.rstrip("/")
        self.gateway_token = gateway_token.strip()
        self.device_index = device_index
        self.tts_voice = tts_voice.strip()
        self.tts_edge_voice = tts_edge_voice.strip() or "zh-CN-XiaoxiaoNeural"
        self.chat_path = chat_path if chat_path.startswith("/") else f"/{chat_path}"
        self.health_path = health_path if health_path.startswith("/") else f"/{health_path}"
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

    async def health(self, timeout_sec: int = 30, log_error: bool = True) -> Optional[dict]:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{self.gateway_url}{self.health_path}",
                    headers=self._headers(json_body=False),
                    timeout=aiohttp.ClientTimeout(total=timeout_sec),
                ) as resp:
                    if resp.status == 200:
                        return await resp.json()
                    if log_error:
                        logger.error(f"健康检查失败: {resp.status} {await resp.text()}")
                    return None
        except (aiohttp.ClientError, asyncio.TimeoutError) as e:
            if log_error:
                logger.error(f"连接失败: {e}")
            return None

    async def _post_json(self, path: str, payload: dict, timeout_sec: int = 120) -> tuple[int, Optional[dict], str]:
        path = path if path.startswith("/") else f"/{path}"
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{self.gateway_url}{path}",
                    json=payload,
                    headers=self._headers(json_body=True),
                    timeout=aiohttp.ClientTimeout(total=timeout_sec),
                ) as resp:
                    text = await resp.text()
                    if resp.status == 200:
                        try:
                            return resp.status, json.loads(text), text
                        except Exception:
                            return resp.status, None, text
                    return resp.status, None, text
        except (aiohttp.ClientError, asyncio.TimeoutError) as e:
            logger.error(f"连接失败: {e}")
            return 0, None, str(e)

    async def _get_json(self, path: str, timeout_sec: int = 30) -> tuple[int, Optional[dict], str]:
        path = path if path.startswith("/") else f"/{path}"
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{self.gateway_url}{path}",
                    headers=self._headers(json_body=False),
                    timeout=aiohttp.ClientTimeout(total=timeout_sec),
                ) as resp:
                    text = await resp.text()
                    if resp.status == 200:
                        try:
                            return resp.status, json.loads(text), text
                        except Exception:
                            return resp.status, None, text
                    return resp.status, None, text
        except (aiohttp.ClientError, asyncio.TimeoutError) as e:
            logger.error(f"连接失败: {e}")
            return 0, None, str(e)

    @staticmethod
    def _source_label(source: str) -> str:
        return {
            "local-operator": "本地接线员",
            "openclaw": "龙虾大脑",
            "system": "系统",
        }.get(source, source)

    @staticmethod
    def _messages_from_v1_submit(result: dict) -> list[dict]:
        messages = []
        local_reply = (result.get("local_reply") or "").strip()
        if local_reply:
            source = result.get("local_source") or "local-operator"
            messages.append(
                {
                    "source": source,
                    "source_label": AudioBridgeClient._source_label(source),
                    "kind": "quick_reply",
                    "text": local_reply,
                }
            )
        return messages

    async def submit_text_v1(
        self,
        text: str,
        *,
        client_id: str = "windows-client",
        session_id: str = "voice-bridge-session",
        source: str = "windows",
        message_id: Optional[str] = None,
    ) -> Optional[dict]:
        payload = {
            "text": text,
            "client_id": client_id,
            "session_id": session_id,
            "source": source,
            "message_id": (message_id or f"msg-{uuid.uuid4().hex}"),
        }
        status, data, body = await self._post_json(DEFAULT_V1_MESSAGES_PATH, payload, timeout_sec=120)
        if status == 200 and data:
            return {"protocol": "v1", **data}
        if status not in {404, 405}:
            logger.error(f"V1 提交失败: {status} {body}")
        return None

    async def get_v1_message_status(self, message_id: str) -> Optional[dict]:
        status, data, body = await self._get_json(f"{DEFAULT_V1_MESSAGES_PATH}/{message_id}", timeout_sec=30)
        if status == 200 and data:
            return data
        if status not in {404, 405}:
            logger.error(f"V1 查询失败: {status} {body}")
        return None

    async def wait_v1_terminal(
        self,
        message_id: str,
        *,
        timeout_sec: int = 180,
        poll_interval: float = 1.0,
    ) -> Optional[dict]:
        started = asyncio.get_running_loop().time()
        while True:
            status = await self.get_v1_message_status(message_id)
            if status:
                state = (status.get("status") or "").upper()
                if state in {"DELIVERED", "FAILED"}:
                    return status
            if asyncio.get_running_loop().time() - started >= timeout_sec:
                return status
            await asyncio.sleep(poll_interval)

    async def send_text(self, text: str) -> Optional[dict]:
        # Preferred V1 flow: quick local reply + async OpenClaw final reply.
        result = await self.submit_text_v1(text)
        if result:
            return {"protocol": "v1", **result}

        # Legacy fallback.
        status, data, body = await self._post_json(self.chat_path, {"text": text}, timeout_sec=120)
        if status == 200 and data:
            return {"protocol": "legacy", **data}
        logger.error(f"服务器错误: {status} {body}")
        return None

    @staticmethod
    def list_tts_voices():
        """列出系统可用 TTS 语音包。"""
        ps_script = r"""
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.GetInstalledVoices() | ForEach-Object {
  $v = $_.VoiceInfo
  Write-Output ($v.Name + " | " + $v.Culture.Name + " | " + $v.Gender + " | " + $v.Age)
}
"""
        encoded_cmd = base64.b64encode(ps_script.encode("utf-16le")).decode("ascii")
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded_cmd],
            check=False,
            capture_output=True,
            text=True,
        )
        output = (proc.stdout or "").strip()
        if proc.returncode != 0:
            err = (proc.stderr or output or "").strip()
            logger.warning(f"获取语音包失败(退出码 {proc.returncode}): {err or '未知错误'}")
            return
        print("\n可用 TTS 语音包:")
        if output:
            for line in output.splitlines():
                line = line.strip()
                if line and not line.startswith("#< CLIXML"):
                    print(f"  - {line}")
        else:
            print("  (未检测到可用语音包)")

    @staticmethod
    def _strip_emoji_for_speech(text: str) -> str:
        """去掉 emoji/符号表情，避免播报异常。"""
        emoji_pattern = re.compile(
            "["
            "\U0001F300-\U0001F5FF"  # symbols & pictographs
            "\U0001F600-\U0001F64F"  # emoticons
            "\U0001F680-\U0001F6FF"  # transport & map
            "\U0001F700-\U0001F77F"
            "\U0001F780-\U0001F7FF"
            "\U0001F800-\U0001F8FF"
            "\U0001F900-\U0001F9FF"
            "\U0001FA00-\U0001FAFF"
            "\U00002700-\U000027BF"
            "\U00002600-\U000026FF"
            "\U0000FE00-\U0000FE0F"  # variation selector
            "\U0001F1E6-\U0001F1FF"  # flags
            "]",
            flags=re.UNICODE,
        )
        cleaned = emoji_pattern.sub("", text)
        cleaned = re.sub(r"\s{2,}", " ", cleaned).strip()
        return cleaned

    @staticmethod
    def _extract_reply_text(result: dict) -> str:
        """从后端 JSON 中提取可展示/朗读的正文。"""
        for key in ("response_text", "reply_text", "text", "answer", "content"):
            value = result.get(key)
            if isinstance(value, str) and value.strip():
                reply = value.strip()
                # 清理类似 [[reply_to_current]] 这种前缀标签
                reply = re.sub(r"^\s*\[\[[^\]]+\]\]\s*", "", reply).strip()
                return reply
        return ""

    @staticmethod
    def _extract_messages(result: dict) -> list[dict]:
        if result.get("protocol") == "v1" or result.get("local_reply") is not None:
            return AudioBridgeClient._messages_from_v1_submit(result)
        messages = result.get("messages")
        if isinstance(messages, list):
            filtered = []
            for item in messages:
                if not isinstance(item, dict):
                    continue
                text = (item.get("text") or "").strip()
                if not text:
                    continue
                source = item.get("source") or "assistant"
                filtered.append(
                    {
                        "source": source,
                        "source_label": item.get("source_label") or AudioBridgeClient._source_label(str(source)),
                        "kind": item.get("kind") or "reply",
                        "text": text,
                    }
                )
            if filtered:
                return filtered
        legacy = AudioBridgeClient._extract_reply_text(result)
        if legacy:
            return [{"source": "assistant", "source_label": "助手", "kind": "reply", "text": legacy}]
        return []

    @staticmethod
    def _decode_mp3_to_mono_float32(audio_data: bytes):
        import av
        import numpy as np

        chunks = []
        target_rate = 24000
        with av.open(io.BytesIO(audio_data), mode="r") as container:
            audio_stream = next((s for s in container.streams if s.type == "audio"), None)
            if audio_stream is None:
                raise ValueError("未找到音频流")
            target_rate = audio_stream.rate or 24000
            resampler = av.audio.resampler.AudioResampler(
                format="fltp",
                layout="mono",
                rate=target_rate,
            )
            for frame in container.decode(audio=0):
                out = resampler.resample(frame)
                if not isinstance(out, list):
                    out = [out]
                for out_frame in out:
                    arr = out_frame.to_ndarray()
                    if arr.ndim == 2:
                        arr = arr[0]
                    chunks.append(arr.astype(np.float32, copy=False))
        if not chunks:
            raise ValueError("音频解码失败")
        pcm = np.concatenate(chunks)
        return np.clip(pcm, -1.0, 1.0), target_rate

    def _speak_text_edge(self, text: str) -> bool:
        async def synthesize() -> bytes:
            import edge_tts

            communicate = edge_tts.Communicate(text, self.tts_edge_voice)
            audio = b""
            async for chunk in communicate.stream():
                if chunk.get("type") == "audio":
                    audio += chunk["data"]
            return audio

        try:
            import sounddevice as sd

            audio_data = asyncio.run(synthesize())
            if not audio_data:
                return False
            audio_array, sample_rate = self._decode_mp3_to_mono_float32(audio_data)
            sd.play(audio_array, samplerate=sample_rate)
            sd.wait()
            print(f"播报语音: {self.tts_edge_voice} (edge-tts)")
            return True
        except Exception as e:
            logger.warning(f"edge-tts 播放失败，回退系统语音: {e}")
            return False

    def _speak_text_windows(self, text: str):
        """使用 Windows 系统 TTS 朗读文本。"""
        speak_text = self._strip_emoji_for_speech(text)
        if not speak_text:
            return
        if self._speak_text_edge(speak_text):
            return
        # 通过 PowerShell + System.Speech.Synthesis 调用系统语音
        # 使用 EncodedCommand 避免中文和引号转义问题
        ps_script = r"""
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.Rate = 0
$s.SetOutputToDefaultAudioDevice()
$voiceName = $env:BRIDGE_TTS_VOICE
$voiceOK = $false
$voices = $s.GetInstalledVoices()
if (-not [string]::IsNullOrWhiteSpace($voiceName)) {
  try {
    $s.SelectVoice($voiceName)
    $voiceOK = $true
  } catch {}
}
$selected = $s.Voice.Name
if (-not $voiceOK -and -not [string]::IsNullOrWhiteSpace($voiceName)) {
  $fuzzy = $voices | Where-Object { $_.VoiceInfo.Name -like ("*" + $voiceName + "*") } | Select-Object -First 1
  if ($fuzzy) {
    $s.SelectVoice($fuzzy.VoiceInfo.Name)
    $voiceOK = $true
    $selected = $s.Voice.Name
  }
}
if (-not $voiceOK) {
  $preferred = $voices | Where-Object {
    $_.VoiceInfo.Name -match '(?i)xiaoxiao|xiaoyi|xiaoyou'
  } | Select-Object -First 1
  if ($preferred) {
    $s.SelectVoice($preferred.VoiceInfo.Name)
    $voiceOK = $true
    $selected = $s.Voice.Name
  }
}
if (-not $voiceOK) {
  $zhFemale = $voices | Where-Object {
    $_.VoiceInfo.Culture.Name -like 'zh*' -and $_.VoiceInfo.Gender -eq 'Female'
  } | Select-Object -First 1
  if ($zhFemale) {
    $s.SelectVoice($zhFemale.VoiceInfo.Name)
    $voiceOK = $true
    $selected = $s.Voice.Name
  }
}
if (-not $voiceOK) {
  $zhAny = $voices | Where-Object { $_.VoiceInfo.Culture.Name -like 'zh*' } | Select-Object -First 1
  if ($zhAny) {
    $s.SelectVoice($zhAny.VoiceInfo.Name)
    $selected = $s.Voice.Name
  }
}
Write-Output ("TTS_VOICE=" + $selected)
$text = $env:BRIDGE_TTS_TEXT
if ([string]::IsNullOrWhiteSpace($text)) { exit 2 }
$s.Speak($text)
"""
        encoded_cmd = base64.b64encode(ps_script.encode("utf-16le")).decode("ascii")
        env = os.environ.copy()
        env["BRIDGE_TTS_TEXT"] = speak_text
        env["BRIDGE_TTS_VOICE"] = self.tts_voice
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded_cmd],
            check=False,
            capture_output=True,
            text=True,
            env=env,
        )
        out = (proc.stdout or "").strip()
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("TTS_VOICE="):
                print(f"播报语音: {line.replace('TTS_VOICE=', '', 1)}")
                break
        if proc.returncode != 0:
            err = (proc.stderr or out or "").strip()
            logger.warning(f"TTS 播放失败(退出码 {proc.returncode}): {err or '未知错误'}")

    async def print_and_speak_reply(self, result: dict):
        printed = set()

        for msg in self._extract_messages(result):
            text = msg["text"]
            source = str(msg.get("source") or "")
            key = (msg["source"], text)
            if key in printed:
                continue
            printed.add(key)
            print(f"[{msg['source_label']}] {text}")
            if source != "local-operator":
                await asyncio.to_thread(self._speak_text_windows, text)

        if result.get("protocol") == "v1":
            message_id = (result.get("message_id") or "").strip()
            status = (result.get("status") or "").upper()
            if message_id and status not in {"DELIVERED", "FAILED"}:
                terminal = await self.wait_v1_terminal(message_id, timeout_sec=180, poll_interval=1.0)
                if terminal:
                    for msg in self._extract_messages(terminal):
                        text = msg["text"]
                        source = str(msg.get("source") or "")
                        key = (msg["source"], text)
                        if key in printed:
                            continue
                        printed.add(key)
                        print(f"[{msg['source_label']}] {text}")
                        if source != "local-operator":
                            await asyncio.to_thread(self._speak_text_windows, text)
                    if (terminal.get("status") or "").upper() == "FAILED":
                        err = (terminal.get("last_error") or "openclaw_failed").strip()
                        print(f"[系统] 龙虾大脑回复失败：{err}")
                else:
                    print("[系统] 终答等待超时，稍后可重试查询。")
            return

        if not printed:
            # 若未找到正文字段，回退输出原始 JSON 便于排查
            print(json.dumps(result, ensure_ascii=False, indent=2))

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
    parser.add_argument("--chat-path", default=cfg.get("chat_path", DEFAULT_CHAT_PATH), help="文字对话接口路径")
    parser.add_argument("--health-path", default=cfg.get("health_path", DEFAULT_HEALTH_PATH), help="健康检查接口路径")
    parser.add_argument("--local-llm", action="store_true", help="一键切换到本地大模型服务(127.0.0.1:8765)")
    parser.add_argument("--no-auto-start-local", action="store_true", help="本地模式下不自动拉起 server.py")
    parser.add_argument("--record", type=float, help="录音时长（秒）")
    parser.add_argument("--continuous", action="store_true", help="持续监听模式")
    parser.add_argument("--list-devices", action="store_true", help="列出音频设备")
    parser.add_argument("--device", type=int, help="指定输入设备索引")
    parser.add_argument("--text", help="直接发送文字到 Gateway，跳过录音")
    parser.add_argument("--health", action="store_true", help="调用 /api/voice-brain/health")
    parser.add_argument("--voice", default=cfg.get("tts_voice", "Xiaoxiao"), help="指定 TTS 语音包名称（例如 Xiaoxiao / Microsoft Huihui Desktop）")
    parser.add_argument("--edge-voice", default=cfg.get("tts_edge_voice", "zh-CN-XiaoxiaoNeural"), help="指定 edge-tts 语音（例如 zh-CN-XiaoxiaoNeural）")
    parser.add_argument("--session-id", default=cfg.get("openclaw_session_id", "voice-bridge-session"), help="会话 ID")
    parser.add_argument("--client-id", default=cfg.get("client_id", "windows-cli"), help="客户端 ID")
    parser.add_argument("--list-voices", action="store_true", help="列出系统可用 TTS 语音包")
    args = parser.parse_args()

    gateway = args.gateway
    token = args.token
    chat_path = args.chat_path
    health_path = args.health_path
    if args.local_llm:
        gateway = cfg.get("local_gateway_url", LOCAL_LLM_URL)
        token = cfg.get("local_gateway_token", "")
        chat_path = cfg.get("local_chat_path", LOCAL_CHAT_PATH)
        health_path = cfg.get("local_health_path", LOCAL_HEALTH_PATH)
        print(f"已切换到本地大模型模式: {gateway}{chat_path}")

    local_mode = _should_treat_as_local_mode(
        gateway_url=gateway,
        chat_path=chat_path,
        force_local=args.local_llm,
    )

    client = AudioBridgeClient(
        gateway,
        gateway_token=token,
        device_index=args.device,
        tts_voice=args.voice,
        tts_edge_voice=args.edge_voice,
        chat_path=chat_path,
        health_path=health_path,
    )
    if args.list_devices:
        client.list_devices()
        return
    if args.list_voices:
        client.list_tts_voices()
        return
    try:
        await ensure_local_service_if_needed(
            client,
            auto_start_local=(not args.no_auto_start_local),
            local_mode=local_mode,
        )
        if args.health:
            result = await client.health()
            if result:
                print(json.dumps(result, ensure_ascii=False, indent=2))
        elif args.text:
            result = await client.submit_text_v1(
                args.text,
                client_id=args.client_id,
                session_id=args.session_id,
                source="windows",
            )
            if not result:
                result = await client.send_text(args.text)
            if result:
                await client.print_and_speak_reply(result)
        elif args.record:
            await client.record_and_send(args.record)
        elif args.continuous:
            await client.continuous_mode()
        else:
            print("Windows 语音壳客户端")
            print("命令: h=health, t=文字对话, r=录音, c=持续监听, l=列设备, v=列语音包, q=退出")
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
                        result = await client.submit_text_v1(
                            text,
                            client_id=args.client_id,
                            session_id=args.session_id,
                            source="windows",
                        )
                        if not result:
                            result = await client.send_text(text)
                        if result:
                            await client.print_and_speak_reply(result)
                elif cmd == 'r':
                    duration = float(input("录音时长（秒）: ") or "5")
                    await client.record_and_send(duration)
                elif cmd == 'c':
                    await client.continuous_mode()
                elif cmd == 'l':
                    client.list_devices()
                elif cmd == 'v':
                    client.list_tts_voices()
    finally:
        client.close()


if __name__ == "__main__":
    asyncio.run(main())


