#!/usr/bin/env python3
"""
Voice Assistant Bridge GUI (Windows)

Main UI:
- Mode switch (Local/OpenClaw)
- Voice input mode switch (hold-to-talk / toggle-record)
- Health check
- Text chat + TTS playback
- Voice input (record -> backend /audio if available -> local STT fallback)

Advanced settings are in a separate Settings dialog.
"""

import asyncio
import json
import sys
import threading
import time
import wave
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Optional

import aiohttp
from friendly_errors import (
    attach_friendly_message,
    build_exception_result,
    friendly_exception_message,
    friendly_result_message,
)

try:
    import numpy as np
    import pyaudio
except ImportError:
    print("Missing audio dependencies. Install with: pip install pyaudio numpy")
    raise SystemExit(1)

try:
    from PySide6.QtWidgets import (
        QApplication,
        QCheckBox,
        QComboBox,
        QDialog,
        QDialogButtonBox,
        QFormLayout,
        QGroupBox,
        QHBoxLayout,
        QLabel,
        QLineEdit,
        QMainWindow,
        QMessageBox,
        QProgressBar,
        QPushButton,
        QSpinBox,
        QSlider,
        QTabWidget,
        QTextEdit,
        QListWidget,
        QListWidgetItem,
        QVBoxLayout,
        QWidget,
    )
    from PySide6.QtCore import Qt, QTimer
    from qasync import QEventLoop, asyncSlot
except ImportError:
    print("Missing GUI dependencies. Install with: pip install PySide6 qasync")
    raise SystemExit(1)

from windows_client import (
    AudioBridgeClient,
    CONFIG_PATH,
    DEFAULT_CHAT_PATH,
    DEFAULT_GATEWAY_URL,
    DEFAULT_HEALTH_PATH,
    LOCAL_CHAT_PATH,
    LOCAL_HEALTH_PATH,
    LOCAL_LLM_URL,
    _should_treat_as_local_mode,
    ensure_local_service_if_needed,
    load_config,
)

MODE_LOCAL = "local"
MODE_OPENCLAW = "openclaw"
VOICE_MODE_HOLD = "hold"
VOICE_MODE_TOGGLE = "toggle"
CHUNK = 1024
RATE = 16000


def _now() -> str:
    return datetime.now().strftime("%H:%M:%S")


def _parse_api_datetime(raw: str) -> Optional[datetime]:
    text = (raw or "").strip()
    if not text:
        return None
    normalized = text.replace("Z", "+00:00") if text.endswith("Z") else text
    try:
        return datetime.fromisoformat(normalized)
    except ValueError:
        pass
    for fmt in (
        "%Y-%m-%dT%H:%M:%S.%f",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S.%f",
        "%Y-%m-%d %H:%M:%S",
    ):
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    return None


def _format_api_datetime(raw: str, fallback: str = "n/a") -> str:
    parsed = _parse_api_datetime(raw)
    if parsed is None:
        text = (raw or "").strip()
        if text:
            return text.replace("T", " ").replace("Z", "")[:19]
        return fallback
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone().strftime("%Y-%m-%d %H:%M:%S")


def _derive_audio_path(chat_path: str) -> str:
    path = (chat_path or "").strip()
    if not path.startswith("/"):
        path = "/" + path
    if path.endswith("/chat"):
        return f"{path[:-5]}/audio"
    return "/audio"


class AudioRecorder:
    def __init__(self, rate: int = RATE, chunk: int = CHUNK):
        self.rate = rate
        self.chunk = chunk
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._frames: list[bytes] = []
        self._lock = threading.Lock()
        self.error: Optional[str] = None

    @property
    def is_recording(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self, max_seconds: int = 0) -> bool:
        if self.is_recording:
            return False
        self._stop_event.clear()
        with self._lock:
            self._frames = []
        self.error = None
        self._thread = threading.Thread(target=self._record_loop, args=(max_seconds,), daemon=True)
        self._thread.start()
        return True

    def stop(self) -> bytes:
        if not self.is_recording:
            with self._lock:
                return b"".join(self._frames)
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=2.0)
        with self._lock:
            audio = b"".join(self._frames)
            self._frames = []
        return audio

    def _record_loop(self, max_seconds: int):
        p = pyaudio.PyAudio()
        stream = None
        started_at = time.time()
        try:
            stream = p.open(
                format=pyaudio.paInt16,
                channels=1,
                rate=self.rate,
                input=True,
                frames_per_buffer=self.chunk,
            )
            while not self._stop_event.is_set():
                data = stream.read(self.chunk, exception_on_overflow=False)
                with self._lock:
                    self._frames.append(data)
                if max_seconds > 0 and (time.time() - started_at) >= max_seconds:
                    break
        except Exception as e:
            self.error = str(e)
        finally:
            if stream is not None:
                try:
                    stream.stop_stream()
                    stream.close()
                except Exception:
                    pass
            p.terminate()


class SettingsDialog(QDialog):
    def __init__(self, settings: dict, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.settings = settings
        self.setWindowTitle("设置")
        self.resize(640, 470)
        self._build_ui()
        self._load_values()

    def _build_ui(self):
        root = QVBoxLayout(self)
        tabs = QTabWidget()
        root.addWidget(tabs)

        local_tab = QWidget()
        local_form = QFormLayout(local_tab)
        self.local_gateway_edit = QLineEdit()
        self.local_token_edit = QLineEdit()
        self.local_token_edit.setEchoMode(QLineEdit.Password)
        self.local_chat_path_edit = QLineEdit()
        self.local_health_path_edit = QLineEdit()
        self.auto_start_local_chk = QCheckBox("本地模式自动拉起 server.py")
        local_form.addRow("Local Gateway", self.local_gateway_edit)
        local_form.addRow("Local Token", self.local_token_edit)
        local_form.addRow("Local Chat Path", self.local_chat_path_edit)
        local_form.addRow("Local Health Path", self.local_health_path_edit)
        local_form.addRow("", self.auto_start_local_chk)
        tabs.addTab(local_tab, "本地模式")

        openclaw_tab = QWidget()
        openclaw_form = QFormLayout(openclaw_tab)
        self.oc_gateway_edit = QLineEdit()
        self.oc_token_edit = QLineEdit()
        self.oc_token_edit.setEchoMode(QLineEdit.Password)
        self.oc_chat_path_edit = QLineEdit()
        self.oc_health_path_edit = QLineEdit()
        openclaw_form.addRow("OpenClaw Gateway", self.oc_gateway_edit)
        openclaw_form.addRow("OpenClaw Token", self.oc_token_edit)
        openclaw_form.addRow("OpenClaw Chat Path", self.oc_chat_path_edit)
        openclaw_form.addRow("OpenClaw Health Path", self.oc_health_path_edit)
        tabs.addTab(openclaw_tab, "OpenClaw")

        voice_tab = QWidget()
        voice_form = QFormLayout(voice_tab)
        self.tts_voice_edit = QLineEdit()
        self.tts_edge_voice_edit = QLineEdit()
        self.record_seconds_spin = QSpinBox()
        self.record_seconds_spin.setRange(1, 60)
        self.stt_model_edit = QLineEdit()
        voice_form.addRow("System TTS Voice", self.tts_voice_edit)
        voice_form.addRow("Edge TTS Voice", self.tts_edge_voice_edit)
        voice_form.addRow("录音最大秒数(防止忘停)", self.record_seconds_spin)
        voice_form.addRow("本地 STT 模型", self.stt_model_edit)
        tabs.addTab(voice_tab, "语音")

        button_box = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        button_box.accepted.connect(self.accept)
        button_box.rejected.connect(self.reject)
        root.addWidget(button_box)

    def _load_values(self):
        self.local_gateway_edit.setText(self.settings.get("local_gateway_url", LOCAL_LLM_URL))
        self.local_token_edit.setText(self.settings.get("local_gateway_token", ""))
        self.local_chat_path_edit.setText(self.settings.get("local_chat_path", LOCAL_CHAT_PATH))
        self.local_health_path_edit.setText(self.settings.get("local_health_path", LOCAL_HEALTH_PATH))
        self.auto_start_local_chk.setChecked(bool(self.settings.get("auto_start_local", True)))

        self.oc_gateway_edit.setText(self.settings.get("openclaw_gateway_url", "http://127.0.0.1:18789"))
        self.oc_token_edit.setText(self.settings.get("openclaw_gateway_token", self.settings.get("gateway_token", "")))
        self.oc_chat_path_edit.setText(self.settings.get("openclaw_chat_path", DEFAULT_CHAT_PATH))
        self.oc_health_path_edit.setText(self.settings.get("openclaw_health_path", DEFAULT_HEALTH_PATH))

        self.tts_voice_edit.setText(self.settings.get("tts_voice", "Xiaoxiao"))
        self.tts_edge_voice_edit.setText(self.settings.get("tts_edge_voice", "zh-CN-XiaoxiaoNeural"))
        self.record_seconds_spin.setValue(int(self.settings.get("voice_input_seconds", 5)))
        self.stt_model_edit.setText(self.settings.get("stt_model_size", "small"))

    def to_settings(self) -> dict:
        updated = dict(self.settings)
        updated["local_gateway_url"] = self.local_gateway_edit.text().strip() or LOCAL_LLM_URL
        updated["local_gateway_token"] = self.local_token_edit.text().strip()
        updated["local_chat_path"] = self.local_chat_path_edit.text().strip() or LOCAL_CHAT_PATH
        updated["local_health_path"] = self.local_health_path_edit.text().strip() or LOCAL_HEALTH_PATH
        updated["auto_start_local"] = self.auto_start_local_chk.isChecked()

        updated["openclaw_gateway_url"] = self.oc_gateway_edit.text().strip() or "http://127.0.0.1:18789"
        updated["openclaw_gateway_token"] = self.oc_token_edit.text().strip()
        updated["openclaw_chat_path"] = self.oc_chat_path_edit.text().strip() or DEFAULT_CHAT_PATH
        updated["openclaw_health_path"] = self.oc_health_path_edit.text().strip() or DEFAULT_HEALTH_PATH

        updated["tts_voice"] = self.tts_voice_edit.text().strip() or "Xiaoxiao"
        updated["tts_edge_voice"] = self.tts_edge_voice_edit.text().strip() or "zh-CN-XiaoxiaoNeural"
        updated["voice_input_seconds"] = int(self.record_seconds_spin.value())
        updated["stt_model_size"] = self.stt_model_edit.text().strip() or "small"
        return updated


class MainWindow(QMainWindow):
    def __init__(self, cfg: dict):
        super().__init__()
        self.settings = self._normalize_settings(cfg)
        self._busy = False
        self._whisper_model = None
        self._recorder = AudioRecorder()
        self._watch_tasks: set[asyncio.Task] = set()
        self._printed_by_message: dict[str, set[tuple[str, str]]] = {}
        self._meeting_active = False
        self._meeting_id: Optional[str] = None
        self._audio_play_thread: Optional[threading.Thread] = None
        self._audio_play_stop = threading.Event()

        self.setWindowTitle("Voice Assistant Bridge")
        self.resize(930, 670)
        self._build_ui()
        self._apply_mode(self.settings.get("gui_mode", MODE_LOCAL))
        self._apply_voice_mode(self.settings.get("voice_input_mode", VOICE_MODE_HOLD))
        self._refresh_controls()
        self._log("GUI ready.")
        QTimer.singleShot(300, lambda: asyncio.create_task(self._refresh_meeting_history()))

    @staticmethod
    def _normalize_settings(cfg: dict) -> dict:
        settings = dict(cfg)
        settings.setdefault("gui_mode", MODE_LOCAL if cfg.get("brain_backend") == "ollama" else MODE_OPENCLAW)
        settings.setdefault("voice_input_mode", VOICE_MODE_HOLD)
        settings.setdefault("auto_start_local", True)
        settings.setdefault("local_gateway_url", cfg.get("gateway_url", LOCAL_LLM_URL))
        settings.setdefault("local_gateway_token", "")
        settings.setdefault("local_chat_path", cfg.get("chat_path", LOCAL_CHAT_PATH))
        settings.setdefault("local_health_path", cfg.get("health_path", LOCAL_HEALTH_PATH))
        settings.setdefault("openclaw_gateway_url", "http://127.0.0.1:18789")
        settings.setdefault("openclaw_gateway_token", cfg.get("gateway_token", ""))
        settings.setdefault("openclaw_chat_path", DEFAULT_CHAT_PATH)
        settings.setdefault("openclaw_health_path", DEFAULT_HEALTH_PATH)
        settings.setdefault("tts_voice", cfg.get("tts_voice", "Xiaoxiao"))
        settings.setdefault("tts_edge_voice", cfg.get("tts_edge_voice", "zh-CN-XiaoxiaoNeural"))
        settings.setdefault("voice_input_seconds", int(cfg.get("voice_input_seconds", 5)))
        settings.setdefault("stt_model_size", cfg.get("stt_model_size", "small"))
        return settings

    def _build_ui(self):
        root = QWidget(self)
        self.setCentralWidget(root)
        outer = QVBoxLayout(root)

        top = QHBoxLayout()
        self.mode_combo = QComboBox()
        self.mode_combo.addItem("本地模式", MODE_LOCAL)
        self.mode_combo.addItem("OpenClaw", MODE_OPENCLAW)
        self.mode_combo.currentIndexChanged.connect(self.on_mode_changed)

        self.voice_mode_combo = QComboBox()
        self.voice_mode_combo.addItem("按住说话", VOICE_MODE_HOLD)
        self.voice_mode_combo.addItem("开关录音", VOICE_MODE_TOGGLE)
        self.voice_mode_combo.currentIndexChanged.connect(self.on_voice_mode_changed)

        self.health_btn = QPushButton("健康检查")
        self.settings_btn = QPushButton("设置")
        self.clear_btn = QPushButton("清空")

        top.addWidget(QLabel("模式:"))
        top.addWidget(self.mode_combo)
        top.addWidget(QLabel("语音输入:"))
        top.addWidget(self.voice_mode_combo)
        top.addWidget(self.health_btn)
        top.addStretch(1)
        top.addWidget(self.settings_btn)
        top.addWidget(self.clear_btn)

        chat_box = QGroupBox("对话")
        chat_layout = QVBoxLayout(chat_box)
        self.log_view = QTextEdit()
        self.log_view.setReadOnly(True)
        self.input_edit = QLineEdit()
        self.input_edit.setPlaceholderText("输入文本后回车或点击发送")
        self.voice_btn = QPushButton()
        self.send_btn = QPushButton("发送")
        bottom = QHBoxLayout()
        bottom.addWidget(self.input_edit, 1)
        bottom.addWidget(self.voice_btn)
        bottom.addWidget(self.send_btn)
        chat_layout.addWidget(self.log_view, 1)
        chat_layout.addLayout(bottom)

        # Meeting Mode Control Panel
        meeting_box = QGroupBox("会议模式")
        meeting_layout = QVBoxLayout(meeting_box)
        meeting_top = QHBoxLayout()
        self.meeting_mode_btn = QPushButton("开始会议")
        self.meeting_refresh_btn = QPushButton("刷新历史")
        self.meeting_status_label = QLabel("空闲")
        self.meeting_info_label = QLabel("")
        meeting_top.addWidget(self.meeting_mode_btn)
        meeting_top.addWidget(self.meeting_refresh_btn)
        meeting_top.addWidget(self.meeting_status_label)
        meeting_top.addWidget(self.meeting_info_label, 1)
        meeting_layout.addLayout(meeting_top)
        self.meeting_history_list = QListWidget()
        self.meeting_history_list.setMaximumHeight(120)
        self.meeting_history_list.itemDoubleClicked.connect(self.on_meeting_history_item_double_clicked)
        meeting_layout.addWidget(self.meeting_history_list)
        self.meeting_mode_btn.clicked.connect(self.on_meeting_toggle)
        self.meeting_refresh_btn.clicked.connect(self.on_refresh_meeting_history)

        outer.addLayout(top)
        outer.addWidget(meeting_box)
        outer.addWidget(chat_box, 1)

        self.health_btn.clicked.connect(self.on_health_clicked)
        self.settings_btn.clicked.connect(self.on_settings_clicked)
        self.clear_btn.clicked.connect(self.log_view.clear)
        self.send_btn.clicked.connect(self.on_send_clicked)
        self.input_edit.returnPressed.connect(self.on_send_clicked)

        # Voice button supports two interaction styles:
        # - hold mode: use pressed/released
        # - toggle mode: use clicked
        self.voice_btn.pressed.connect(self.on_voice_pressed)
        self.voice_btn.released.connect(self.on_voice_released)
        self.voice_btn.clicked.connect(self.on_voice_clicked)

    def _apply_mode(self, mode: str):
        mode = mode if mode in {MODE_LOCAL, MODE_OPENCLAW} else MODE_LOCAL
        self.settings["gui_mode"] = mode
        self.mode_combo.blockSignals(True)
        self.mode_combo.setCurrentIndex(0 if mode == MODE_LOCAL else 1)
        self.mode_combo.blockSignals(False)

    def _apply_voice_mode(self, mode: str):
        mode = mode if mode in {VOICE_MODE_HOLD, VOICE_MODE_TOGGLE} else VOICE_MODE_HOLD
        self.settings["voice_input_mode"] = mode
        self.voice_mode_combo.blockSignals(True)
        self.voice_mode_combo.setCurrentIndex(0 if mode == VOICE_MODE_HOLD else 1)
        self.voice_mode_combo.blockSignals(False)

    def _log(self, text: str):
        self.log_view.append(f"[{_now()}] {text}")

    def _refresh_controls(self):
        recording = self._recorder.is_recording
        mode = self.voice_mode_combo.currentData()

        self.send_btn.setEnabled((not self._busy) and (not recording))
        self.health_btn.setEnabled((not self._busy) and (not recording))
        self.settings_btn.setEnabled((not self._busy) and (not recording))
        self.mode_combo.setEnabled((not self._busy) and (not recording))
        self.voice_mode_combo.setEnabled((not self._busy) and (not recording))
        self.input_edit.setEnabled((not self._busy) and (not recording))
        self.voice_btn.setEnabled(not self._busy)
        self.meeting_mode_btn.setEnabled(not self._busy)
        self.meeting_refresh_btn.setEnabled(not self._busy)

        if self._busy:
            self.voice_btn.setText("处理中...")
        elif mode == VOICE_MODE_HOLD:
            self.voice_btn.setText("松开结束" if recording else "按住说话")
        else:
            self.voice_btn.setText("结束录音" if recording else "开始录音")

        # Update meeting mode button text
        self.meeting_mode_btn.setText("结束会议" if self._meeting_active else "开始会议")

        if self._busy:
            self.statusBar().showMessage("处理中...")
        elif recording:
            self.statusBar().showMessage("录音中...")
        elif self._meeting_active:
            self.statusBar().showMessage(f"会议中: {self._meeting_id[:16] if self._meeting_id else ''}...")
        else:
            self.statusBar().clearMessage()

    def _set_busy(self, busy: bool):
        self._busy = busy
        self._refresh_controls()

    def _active_connection(self) -> dict:
        mode = self.mode_combo.currentData()
        if mode == MODE_LOCAL:
            return {
                "mode": MODE_LOCAL,
                "gateway": self.settings.get("local_gateway_url", LOCAL_LLM_URL),
                "token": self.settings.get("local_gateway_token", ""),
                "chat_path": self.settings.get("local_chat_path", LOCAL_CHAT_PATH),
                "health_path": self.settings.get("local_health_path", LOCAL_HEALTH_PATH),
                "auto_start_local": bool(self.settings.get("auto_start_local", True)),
            }
        return {
            "mode": MODE_OPENCLAW,
            "gateway": self.settings.get("openclaw_gateway_url", "http://127.0.0.1:18789"),
            "token": self.settings.get("openclaw_gateway_token", ""),
            "chat_path": self.settings.get("openclaw_chat_path", DEFAULT_CHAT_PATH),
            "health_path": self.settings.get("openclaw_health_path", DEFAULT_HEALTH_PATH),
            "auto_start_local": False,
        }

    def _build_client(self) -> AudioBridgeClient:
        conn = self._active_connection()
        return AudioBridgeClient(
            gateway_url=conn["gateway"] or DEFAULT_GATEWAY_URL,
            gateway_token=conn["token"],
            tts_voice=self.settings.get("tts_voice", "Xiaoxiao"),
            tts_edge_voice=self.settings.get("tts_edge_voice", "zh-CN-XiaoxiaoNeural"),
            chat_path=conn["chat_path"] or DEFAULT_CHAT_PATH,
            health_path=conn["health_path"] or DEFAULT_HEALTH_PATH,
        )

    async def _prepare_client(self, client: AudioBridgeClient):
        conn = self._active_connection()
        local_mode = _should_treat_as_local_mode(
            gateway_url=client.gateway_url,
            chat_path=client.chat_path,
            force_local=(conn["mode"] == MODE_LOCAL),
        )
        await ensure_local_service_if_needed(
            client,
            auto_start_local=conn["auto_start_local"],
            local_mode=local_mode,
        )
        if local_mode:
            health = await client.health(timeout_sec=2, log_error=False)
            if not health:
                raise RuntimeError(client.friendly_last_error("本地服务未就绪，请先启动 server.py 后重试。"))

    async def _render_messages(self, client: AudioBridgeClient, payload: dict, message_id: Optional[str] = None):
        messages = AudioBridgeClient._extract_messages(payload)
        printed = self._printed_by_message.setdefault(message_id or "", set())
        for item in messages:
            text = (item.get("text") or "").strip()
            if not text:
                continue
            source = str(item.get("source") or "assistant")
            label = item.get("source_label") or AudioBridgeClient._source_label(source)
            key = (source, text)
            if key in printed:
                continue
            printed.add(key)
            self._log(f"[{label}] {text}")
            if source != "local-operator":
                await asyncio.to_thread(client._speak_text_windows, text)

    async def _watch_v1_terminal(self, message_id: str):
        client = self._build_client()
        try:
            await self._prepare_client(client)
            status = await client.wait_v1_terminal(message_id, timeout_sec=180, poll_interval=1.0)
            if not status:
                self._log(f"[系统] {client.friendly_last_error('等待龙虾大脑最终回复超时，请稍后重试。')}")
                return
            await self._render_messages(client, status, message_id=message_id)
            if (status.get("status") or "").upper() == "FAILED":
                self._log(
                    f"[系统] {friendly_result_message({'error': status.get('last_error') or 'openclaw_failed'}, '龙虾大脑回复失败，请稍后重试。')}"
                )
        except Exception as e:
            self._log(f"[系统] {friendly_exception_message(e, action='等待龙虾大脑回复')}")
        finally:
            client.close()
            self._printed_by_message.pop(message_id, None)

    def _spawn_watch_task(self, message_id: str):
        task = asyncio.create_task(self._watch_v1_terminal(message_id))
        self._watch_tasks.add(task)

        def _cleanup(done: asyncio.Task):
            self._watch_tasks.discard(done)

        task.add_done_callback(_cleanup)

    async def _chat_with_text(self, client: AudioBridgeClient, text: str):
        result = await client.send_text(text)
        if not result:
            self._log(f"[系统] {client.friendly_last_error('发送失败，请稍后重试。')}")
            return

        if result.get("protocol") == "v1":
            message_id = (result.get("message_id") or "").strip()
            await self._render_messages(client, result, message_id=message_id)
            state = (result.get("status") or "").upper()
            if message_id and state not in {"DELIVERED", "FAILED"}:
                self._spawn_watch_task(message_id)
            elif state == "FAILED":
                self._log(
                    f"[系统] {friendly_result_message({'error': result.get('last_error') or 'openclaw_failed'}, '龙虾大脑回复失败，请稍后重试。')}"
                )
            return

        reply = AudioBridgeClient._extract_reply_text(result)
        if reply:
            self._log(f"[助手] {reply}")
            await asyncio.to_thread(client._speak_text_windows, reply)
            return
        self._log(json.dumps(result, ensure_ascii=False, indent=2))

    async def _send_audio_to_backend(self, client: AudioBridgeClient, audio_data: bytes) -> Optional[dict]:
        audio_path = _derive_audio_path(client.chat_path)
        headers = client._headers(json_body=False)
        headers.setdefault("Content-Type", "application/octet-stream")
        url = f"{client.gateway_url}{audio_path}"
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    url,
                    data=audio_data,
                    headers=headers,
                    timeout=aiohttp.ClientTimeout(total=180),
                ) as resp:
                    if resp.status != 200:
                        return None
                    return await resp.json()
        except Exception:
            return None

    def _transcribe_local_blocking(self, audio_data: bytes) -> str:
        from faster_whisper import WhisperModel

        if self._whisper_model is None:
            model_size = self.settings.get("stt_model_size", "small")
            self._whisper_model = WhisperModel(model_size, device="cpu", compute_type="int8")

        audio_array = np.frombuffer(audio_data, dtype=np.int16).astype(np.float32) / 32768.0
        segments, _ = self._whisper_model.transcribe(audio_array, language="zh", beam_size=5)
        return "".join(seg.text for seg in segments).strip()

    async def _process_voice_audio(self, audio_data: bytes):
        if not audio_data:
            self._log("录音为空。")
            return
        if self._recorder.error:
            self._log(f"录音异常: {self._recorder.error}")

        self._set_busy(True)
        try:
            client = self._build_client()
            try:
                await self._prepare_client(client)
                audio_result = await self._send_audio_to_backend(client, audio_data)
                if audio_result:
                    input_text = (audio_result.get("input_text") or "").strip()
                    if input_text:
                        self._log(f"你(语音): {input_text}")
                    reply = AudioBridgeClient._extract_reply_text(audio_result)
                    if reply:
                        self._log(f"助手: {reply}")
                        await asyncio.to_thread(client._speak_text_windows, reply)
                        return

                self._log("后端语音接口不可用，切换本地 STT...")
                text = await asyncio.to_thread(self._transcribe_local_blocking, audio_data)
                if not text:
                    self._log("本地 STT 失败。")
                    return
                self._log(f"你(语音): {text}")
                await self._chat_with_text(client, text)
            finally:
                client.close()
        except Exception as e:
            self._log(f"[系统] {friendly_exception_message(e, action='处理语音输入')}")
        finally:
            self._set_busy(False)

    def _save_settings(self):
        out = load_config()
        out.update(self.settings)
        out["gateway_url"] = self.settings.get("local_gateway_url", LOCAL_LLM_URL)
        out["gateway_token"] = self.settings.get("local_gateway_token", "")
        out["chat_path"] = self.settings.get("local_chat_path", LOCAL_CHAT_PATH)
        out["health_path"] = self.settings.get("local_health_path", LOCAL_HEALTH_PATH)
        out["brain_backend"] = "ollama" if self.settings.get("gui_mode") == MODE_LOCAL else "openclaw"
        Path(CONFIG_PATH).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")

    def _start_recording(self):
        if self._busy or self._recorder.is_recording:
            return
        max_seconds = int(self.settings.get("voice_input_seconds", 5))
        if self._recorder.start(max_seconds=max_seconds):
            mode = self.voice_mode_combo.currentData()
            if mode == VOICE_MODE_HOLD:
                self._log("开始录音，松开按钮结束。")
            else:
                self._log("开始录音，再点一次按钮结束。")
            self._refresh_controls()

    async def _stop_recording_and_process(self):
        if not self._recorder.is_recording:
            return
        self._log("停止录音，处理中...")
        audio_data = await asyncio.to_thread(self._recorder.stop)
        self._refresh_controls()
        await self._process_voice_audio(audio_data)

    @asyncSlot()
    async def on_health_clicked(self):
        if self._busy or self._recorder.is_recording:
            return
        self._set_busy(True)
        client = self._build_client()
        try:
            await self._prepare_client(client)
            result = await client.health()
            if result:
                self._log("health ok")
                self._log(json.dumps(result, ensure_ascii=False, indent=2))
            else:
                self._log(f"[系统] {client.friendly_last_error('健康检查失败，请检查服务状态。')}")
        except Exception as e:
            self._log(f"[系统] {friendly_exception_message(e, action='健康检查')}")
        finally:
            client.close()
            self._set_busy(False)

    @asyncSlot()
    async def on_send_clicked(self):
        if self._busy or self._recorder.is_recording:
            return
        text = self.input_edit.text().strip()
        if not text:
            return
        self.input_edit.clear()
        self._log(f"你: {text}")

        self._set_busy(True)
        client = self._build_client()
        try:
            await self._prepare_client(client)
            await self._chat_with_text(client, text)
        except Exception as e:
            self._log(f"[系统] {friendly_exception_message(e, action='发送消息')}")
        finally:
            client.close()
            self._set_busy(False)

    # hold mode: press to start
    def on_voice_pressed(self):
        if self.voice_mode_combo.currentData() == VOICE_MODE_HOLD:
            self._start_recording()

    # hold mode: release to stop
    def on_voice_released(self):
        if self.voice_mode_combo.currentData() == VOICE_MODE_HOLD and self._recorder.is_recording:
            asyncio.create_task(self._stop_recording_and_process())

    # toggle mode: click to start/stop
    @asyncSlot()
    async def on_voice_clicked(self):
        if self.voice_mode_combo.currentData() != VOICE_MODE_TOGGLE:
            return
        if self._busy:
            return
        if self._recorder.is_recording:
            await self._stop_recording_and_process()
        else:
            self._start_recording()

    def on_mode_changed(self):
        mode = self.mode_combo.currentData()
        self.settings["gui_mode"] = mode
        self._save_settings()
        self._log(f"已切换模式: {'本地模型' if mode == MODE_LOCAL else 'OpenClaw'}")

    def on_voice_mode_changed(self):
        mode = self.voice_mode_combo.currentData()
        self.settings["voice_input_mode"] = mode
        self._save_settings()
        self._refresh_controls()
        self._log(f"语音输入模式: {'按住说话' if mode == VOICE_MODE_HOLD else '开关录音'}")

    def on_settings_clicked(self):
        if self._busy or self._recorder.is_recording:
            return
        dialog = SettingsDialog(self.settings, self)
        if dialog.exec() == QDialog.Accepted:
            self.settings = dialog.to_settings()
            self._save_settings()
            self._log("设置已保存。")

    def _v2_base_url(self) -> str:
        conn = self._active_connection()
        return (conn.get("gateway") or DEFAULT_GATEWAY_URL).rstrip("/")

    def _v2_headers(self) -> dict[str, str]:
        conn = self._active_connection()
        token = (conn.get("token") or "").strip()
        if not token:
            return {}
        return {"Authorization": f"Bearer {token}"}

    async def _ensure_v2_service_ready(self) -> Optional[str]:
        """Ensure local service is up before calling V2 meeting APIs."""
        conn = self._active_connection()
        if conn.get("mode") != MODE_LOCAL:
            return None

        probe_client = self._build_client()
        try:
            await self._prepare_client(probe_client)
            return None
        except Exception as e:
            return friendly_exception_message(e, action="准备本地服务")
        finally:
            probe_client.close()

    async def _request_v2_json(self, method: str, path: str, payload: Optional[dict] = None, action: str = "请求服务") -> dict:
        prepare_err = await self._ensure_v2_service_ready()
        if prepare_err:
            return {
                "ok": False,
                "error": "service_prepare_failed",
                "message": prepare_err,
            }

        url = f"{self._v2_base_url()}{path}"
        headers = self._v2_headers()
        try:
            async with aiohttp.ClientSession() as session:
                async with session.request(
                    method,
                    url,
                    json=payload,
                    headers=headers,
                    timeout=aiohttp.ClientTimeout(total=20),
                ) as resp:
                    text = await resp.text()
                    try:
                        data = json.loads(text) if text.strip() else {}
                    except Exception:
                        data = {
                            "ok": False,
                            "status": resp.status,
                            "error": text.strip() or f"http_{resp.status}",
                            "detail": text,
                        }
                    if not isinstance(data, dict):
                        data = {"ok": resp.status < 400, "data": data}
                    if resp.status >= 400 and data.get("ok") is True:
                        data["ok"] = False
                    if resp.status >= 400 or data.get("ok") is False:
                        data.setdefault("ok", False)
                        data.setdefault("status", resp.status)
                        data.setdefault("detail", text)
                        return attach_friendly_message(data, default=f"{action}失败，请稍后重试。")
                    data.setdefault("ok", True)
                    return data
        except Exception as e:
            return build_exception_result(e, action=action)

    async def _post_v2_json(self, path: str, payload: dict, action: str = "请求服务") -> dict:
        return await self._request_v2_json("POST", path, payload=payload, action=action)

    async def _get_v2_json(self, path: str, action: str = "读取数据") -> dict:
        return await self._request_v2_json("GET", path, action=action)

    async def _patch_v2_json(self, path: str, payload: dict, action: str = "保存数据") -> dict:
        return await self._request_v2_json("PATCH", path, payload=payload, action=action)

    @staticmethod
    def _parse_meta_json(raw_meta: object) -> dict:
        if not isinstance(raw_meta, str) or not raw_meta.strip():
            return {}
        try:
            parsed = json.loads(raw_meta)
            return parsed if isinstance(parsed, dict) else {}
        except Exception:
            return {}

    @staticmethod
    def _extract_model_text(data: object) -> str:
        if isinstance(data, dict):
            for key in ("response", "text", "content", "reply_text", "answer"):
                val = data.get(key)
                if isinstance(val, str) and val.strip():
                    return val.strip()
            msg = data.get("message")
            if isinstance(msg, dict):
                val = msg.get("content")
                if isinstance(val, str) and val.strip():
                    return val.strip()
        if isinstance(data, str):
            return data.strip()
        return ""

    async def _generate_meeting_title(self, transcript_text: str) -> str:
        text = (transcript_text or "").strip()
        if not text:
            return "未命名会议"

        endpoint = (self.settings.get("ollama_endpoint") or "http://127.0.0.1:11434/api/generate").strip()
        model = (self.settings.get("ollama_model") or "qwen2.5:7b").strip()
        prompt = (
            "请基于以下会议转写内容生成一个简短会议标题。\n"
            "要求：仅输出标题本身，不要解释；不超过16个汉字；避免标点。\n"
            f"转写内容：\n{text[:4000]}"
        )
        payload = {"model": model, "prompt": prompt, "stream": False}
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    endpoint,
                    json=payload,
                    timeout=aiohttp.ClientTimeout(total=25),
                ) as resp:
                    if resp.status != 200:
                        raise RuntimeError(f"http {resp.status}")
                    data = await resp.json(content_type=None)
            title = self._extract_model_text(data).replace("\n", " ").strip()
            title = title.strip("。；;,.，:：\"'`")
            if len(title) > 24:
                title = title[:24].rstrip("。；;,.，:：\"'`")
            return title or self._fallback_title(text)
        except Exception:
            return self._fallback_title(text)

    @staticmethod
    def _fallback_title(text: str) -> str:
        cleaned = " ".join((text or "").split())
        if not cleaned:
            return "未命名会议"
        return cleaned[:16].strip() or "未命名会议"

    @staticmethod
    def _build_refined_transcript(segments: list[dict]) -> str:
        if not segments:
            return ""
        lines: list[str] = []
        for seg in segments:
            speaker = (seg.get("speaker_name") or seg.get("speaker_cluster_id") or "speaker").strip()
            text = (seg.get("text") or "").strip()
            if not text:
                continue
            start_ts = float(seg.get("start_ts") or 0.0)
            mins = int(start_ts // 60)
            secs = int(start_ts % 60)
            lines.append(f"[{mins:02d}:{secs:02d}] {speaker}: {text}")
        return "\n".join(lines)

    def _resolve_audio_path(self, segment: dict, meeting_id: str) -> Optional[Path]:
        local_path = (segment.get("local_path") or "").strip()
        if local_path:
            p = Path(local_path)
            if not p.is_absolute():
                p = Path(__file__).resolve().parent / p
            if p.exists():
                return p

        seg_id = (segment.get("segment_id") or "").strip()
        if seg_id:
            p2 = Path(__file__).resolve().parent / "artifacts" / "meetings" / meeting_id / "audio" / "raw" / f"{seg_id}.wav"
            if p2.exists():
                return p2
        return None

    def _ui_log_from_worker(self, message: str):
        QTimer.singleShot(0, lambda: self._log(message))

    @staticmethod
    def _format_mmss(seconds: float) -> str:
        sec = max(0, int(seconds))
        return f"{sec // 60:02d}:{sec % 60:02d}"

    def _audio_duration_seconds(self, file_path: Path, sample_rate: int = 48000, channels: int = 1) -> float:
        try:
            with open(file_path, "rb") as f:
                head = f.read(12)
            is_wav = head.startswith(b"RIFF") and b"WAVE" in head
            if is_wav:
                with wave.open(str(file_path), "rb") as wf:
                    framerate = wf.getframerate() or 1
                    return float(wf.getnframes()) / float(framerate)
            size = max(0, file_path.stat().st_size)
            bytes_per_sec = max(1, sample_rate * channels * 2)
            return float(size) / float(bytes_per_sec)
        except Exception:
            return 0.0

    def _stop_audio_playback(self):
        self._audio_play_stop.set()
        if self._audio_play_thread and self._audio_play_thread.is_alive():
            self._audio_play_thread.join(timeout=1.0)
        self._audio_play_thread = None

    def _play_audio_file(
        self,
        file_path: Path,
        sample_rate: int = 48000,
        channels: int = 1,
        start_ratio: float = 0.0,
        on_progress: Optional[Callable[[float], None]] = None,
        on_finished: Optional[Callable[[], None]] = None,
    ):
        self._stop_audio_playback()
        self._audio_play_stop.clear()
        start_ratio = max(0.0, min(0.999, float(start_ratio or 0.0)))

        def _run():
            p = pyaudio.PyAudio()
            stream = None
            try:
                with open(file_path, "rb") as f:
                    head = f.read(12)
                is_wav = head.startswith(b"RIFF") and b"WAVE" in head
                if is_wav:
                    with wave.open(str(file_path), "rb") as wf:
                        total_frames = max(1, int(wf.getnframes()))
                        start_frame = int(total_frames * start_ratio)
                        wf.setpos(max(0, min(total_frames - 1, start_frame)))
                        frames_played = start_frame
                        stream = p.open(
                            format=p.get_format_from_width(wf.getsampwidth()),
                            channels=wf.getnchannels(),
                            rate=wf.getframerate(),
                            output=True,
                        )
                        chunk = 2048
                        while not self._audio_play_stop.is_set():
                            data = wf.readframes(chunk)
                            if not data:
                                break
                            frame_width = max(1, wf.getsampwidth() * wf.getnchannels())
                            frames_played += len(data) // frame_width
                            stream.write(data)
                            if on_progress:
                                try:
                                    on_progress(min(1.0, frames_played / total_frames))
                                except Exception:
                                    pass
                else:
                    with open(file_path, "rb") as rf:
                        total_bytes = max(1, file_path.stat().st_size)
                        frame_width = max(2, channels * 2)
                        start_byte = int(total_bytes * start_ratio)
                        start_byte -= (start_byte % frame_width)
                        start_byte = max(0, min(total_bytes - frame_width, start_byte))
                        rf.seek(start_byte)
                        played_bytes = start_byte
                        stream = p.open(
                            format=pyaudio.paInt16,
                            channels=channels,
                            rate=sample_rate,
                            output=True,
                        )
                        chunk = 4096
                        while not self._audio_play_stop.is_set():
                            data = rf.read(chunk)
                            if not data:
                                break
                            stream.write(data)
                            played_bytes += len(data)
                            if on_progress:
                                try:
                                    on_progress(min(1.0, played_bytes / total_bytes))
                                except Exception:
                                    pass
            except Exception as exc:
                self._ui_log_from_worker(f"[会议] 音频播放失败: {exc}")
            finally:
                try:
                    if stream is not None:
                        stream.stop_stream()
                        stream.close()
                except Exception:
                    pass
                p.terminate()
                if on_finished:
                    try:
                        on_finished()
                    except Exception:
                        pass

        self._audio_play_thread = threading.Thread(target=_run, daemon=True)
        self._audio_play_thread.start()

    @asyncSlot(QListWidgetItem)
    async def on_meeting_history_item_double_clicked(self, item: QListWidgetItem):
        meeting_id = (item.data(Qt.UserRole) or "").strip()
        if not meeting_id:
            return
        await self._open_meeting_detail_dialog(meeting_id)

    async def _open_meeting_detail_dialog(self, meeting_id: str):
        meeting_resp = await self._get_v2_json(f"/v2/meetings/{meeting_id}", action="读取会议详情")
        if not meeting_resp.get("ok"):
            QMessageBox.warning(
                self,
                "错误",
                friendly_result_message(meeting_resp, "读取会议详情失败，请稍后重试。"),
            )
            return

        refined_resp = await self._get_v2_json(f"/v2/meetings/{meeting_id}/refined", action="读取精修分段")
        jobs_resp = await self._get_v2_json(f"/v2/meetings/{meeting_id}/transcription", action="读取转写任务")

        meeting = meeting_resp.get("meeting", {}) or {}
        audio_segments = meeting_resp.get("audio_segments", []) or []
        refined_segments = refined_resp.get("segments", []) if refined_resp.get("ok") else []
        jobs = jobs_resp.get("jobs", []) if jobs_resp.get("ok") else []

        meta = self._parse_meta_json(meeting.get("meta_json"))
        transcript_text = (meta.get("transcript_text") or "").strip()
        if not transcript_text:
            transcript_text = self._build_refined_transcript(refined_segments)

        meeting_name = (meta.get("meeting_name") or "").strip()
        if not meeting_name and transcript_text:
            meeting_name = await self._generate_meeting_title(transcript_text)

        dialog = QDialog(self)
        dialog.setWindowTitle(f"会议详情 - {meeting_id[:20]}...")
        dialog.resize(1080, 820)
        layout = QVBoxLayout(dialog)

        form = QFormLayout()
        meeting_id_edit = QLineEdit(meeting_id)
        meeting_id_edit.setReadOnly(True)
        meeting_name_edit = QLineEdit(meeting_name)
        form.addRow("会议ID", meeting_id_edit)
        form.addRow("会议名称", meeting_name_edit)
        layout.addLayout(form)

        gen_title_btn = QPushButton("基于文本生成标题")
        layout.addWidget(gen_title_btn)

        summary_lines = [
            f"status: {meeting.get('status', 'unknown')}",
            f"client_id: {meeting.get('client_id', 'unknown')}",
            f"created_at: {_format_api_datetime(meeting.get('created_at', ''), fallback='')}",
            f"started_at: {_format_api_datetime(meeting.get('started_at', ''), fallback='')}",
            f"ended_at: {_format_api_datetime(meeting.get('ended_at', ''), fallback='')}",
            f"audio_segments: {len(audio_segments)}",
            f"transcription_jobs: {len(jobs)}",
            f"refined_segments: {len(refined_segments)}",
        ]
        summary_view = QTextEdit()
        summary_view.setReadOnly(True)
        summary_view.setMaximumHeight(130)
        summary_view.setPlainText("\n".join(summary_lines))
        layout.addWidget(summary_view)

        transcription_box = QGroupBox("转写任务（弹窗内手动触发）")
        transcription_layout = QVBoxLayout(transcription_box)
        transcription_btn_row = QHBoxLayout()
        run_stt_btn = QPushButton("手动STT")
        retry_stt_btn = QPushButton("重试STT")
        cancel_stt_btn = QPushButton("取消排队任务")
        refresh_stt_btn = QPushButton("刷新进度")
        transcription_btn_row.addWidget(run_stt_btn)
        transcription_btn_row.addWidget(retry_stt_btn)
        transcription_btn_row.addWidget(cancel_stt_btn)
        transcription_btn_row.addWidget(refresh_stt_btn)
        transcription_layout.addLayout(transcription_btn_row)

        transcription_status_label = QLabel("任务状态: -")
        transcription_progress = QProgressBar()
        transcription_progress.setRange(0, 100)
        transcription_progress.setValue(0)
        transcription_job_label = QLabel("")
        transcription_jobs_view = QTextEdit()
        transcription_jobs_view.setReadOnly(True)
        transcription_jobs_view.setMaximumHeight(100)
        transcription_layout.addWidget(transcription_status_label)
        transcription_layout.addWidget(transcription_progress)
        transcription_layout.addWidget(transcription_job_label)
        transcription_layout.addWidget(transcription_jobs_view)
        layout.addWidget(transcription_box)

        transcript_edit = QTextEdit()
        transcript_edit.setPlainText(transcript_text)
        transcript_edit.setPlaceholderText("会议转写文本（可人工修改）")
        layout.addWidget(transcript_edit, 2)

        refined_box = QGroupBox("分段精修（可逐段改文案/说话人）")
        refined_layout = QHBoxLayout(refined_box)
        refined_list = QListWidget()
        refined_list.setMinimumWidth(450)
        refined_layout.addWidget(refined_list, 2)

        refined_editor_col = QVBoxLayout()
        refined_meta_form = QFormLayout()
        refined_segment_id_edit = QLineEdit("")
        refined_segment_id_edit.setReadOnly(True)
        refined_time_label = QLabel("-")
        refined_cluster_label = QLabel("-")
        refined_speaker_edit = QLineEdit("")
        refined_speaker_edit.setPlaceholderText("输入说话人名称")
        refined_meta_form.addRow("分段ID", refined_segment_id_edit)
        refined_meta_form.addRow("时间段", refined_time_label)
        refined_meta_form.addRow("聚类ID", refined_cluster_label)
        refined_meta_form.addRow("说话人", refined_speaker_edit)
        refined_editor_col.addLayout(refined_meta_form)

        refined_text_edit = QTextEdit()
        refined_text_edit.setPlaceholderText("分段文本")
        refined_text_edit.setMaximumHeight(140)
        refined_editor_col.addWidget(refined_text_edit)

        refined_btn_row = QHBoxLayout()
        save_segment_text_btn = QPushButton("保存该段文本")
        save_segment_speaker_btn = QPushButton("联动改名")
        refresh_refined_btn = QPushButton("刷新分段")
        refined_btn_row.addWidget(save_segment_text_btn)
        refined_btn_row.addWidget(save_segment_speaker_btn)
        refined_btn_row.addWidget(refresh_refined_btn)
        refined_editor_col.addLayout(refined_btn_row)

        refined_hint_label = QLabel("提示: 有聚类ID时会联动修改该说话人全部分段；无聚类ID仅修改当前分段。")
        refined_hint_label.setWordWrap(True)
        refined_editor_col.addWidget(refined_hint_label)
        refined_editor_col.addStretch(1)
        refined_layout.addLayout(refined_editor_col, 3)
        layout.addWidget(refined_box, 2)

        audio_box = QGroupBox("语音文件")
        audio_layout = QVBoxLayout(audio_box)
        audio_list = QListWidget()
        for seg in sorted(audio_segments, key=lambda x: int(x.get("seq") or 0)):
            seq = seg.get("seq")
            st = seg.get("upload_status", "unknown")
            size_kb = int((seg.get("size_bytes") or 0) / 1024)
            dur_ms = int(seg.get("duration_ms") or 0)
            dur = f"{dur_ms / 1000:.1f}s" if dur_ms > 0 else "n/a"
            path = (seg.get("local_path") or "").strip()
            file_name = Path(path).name if path else "(no-path)"
            txt = f"#{seq} | {st} | {size_kb}KB | {dur} | {file_name}"
            row = QListWidgetItem(txt)
            row.setData(Qt.UserRole, seg)
            audio_list.addItem(row)
        audio_layout.addWidget(audio_list)

        audio_file_label = QLabel("未选择音频")
        audio_layout.addWidget(audio_file_label)
        audio_progress_row = QHBoxLayout()
        audio_slider = QSlider(Qt.Horizontal)
        audio_slider.setRange(0, 1000)
        audio_slider.setValue(0)
        audio_slider.setEnabled(False)
        audio_time_label = QLabel("00:00 / 00:00")
        audio_progress_row.addWidget(audio_slider, 1)
        audio_progress_row.addWidget(audio_time_label)
        audio_layout.addLayout(audio_progress_row)

        audio_btn_row = QHBoxLayout()
        play_btn = QPushButton("播放选中")
        stop_btn = QPushButton("停止播放")
        audio_btn_row.addWidget(play_btn)
        audio_btn_row.addWidget(stop_btn)
        audio_layout.addLayout(audio_btn_row)
        layout.addWidget(audio_box, 1)

        status_label = QLabel("")
        layout.addWidget(status_label)

        button_row = QHBoxLayout()
        save_btn = QPushButton("保存会议名称/正文")
        close_btn = QPushButton("关闭")
        button_row.addWidget(save_btn)
        button_row.addWidget(close_btn)
        layout.addLayout(button_row)

        transcript_dirty = False
        suppress_transcript_dirty = False
        poll_timer = QTimer(dialog)
        poll_timer.setInterval(1800)
        jobs_state: dict[str, object] = {
            "jobs": list(jobs),
            "latest_id": "",
            "latest_status": "",
        }
        refined_state: dict[str, list[dict]] = {"segments": list(refined_segments)}
        audio_state: dict[str, object] = {
            "path": None,
            "duration": 0.0,
            "ratio": 0.0,
            "dragging": False,
            "user_stopped": False,
        }

        def _on_transcript_changed():
            nonlocal transcript_dirty
            if suppress_transcript_dirty:
                return
            transcript_dirty = True

        transcript_edit.textChanged.connect(_on_transcript_changed)

        def _dialog_alive() -> bool:
            return dialog.isVisible()

        def _set_summary_text():
            current_jobs = jobs_state.get("jobs") or []
            current_refined = refined_state.get("segments") or []
            summary_view.setPlainText(
                "\n".join(
                    [
                        f"status: {meeting.get('status', 'unknown')}",
                        f"client_id: {meeting.get('client_id', 'unknown')}",
                        f"created_at: {_format_api_datetime(meeting.get('created_at', ''), fallback='')}",
                        f"started_at: {_format_api_datetime(meeting.get('started_at', ''), fallback='')}",
                        f"ended_at: {_format_api_datetime(meeting.get('ended_at', ''), fallback='')}",
                        f"audio_segments: {len(audio_segments)}",
                        f"transcription_jobs: {len(current_jobs)}",
                        f"refined_segments: {len(current_refined)}",
                    ]
                )
            )

        def _is_meeting_ended() -> bool:
            status = str(meeting.get("status") or "").strip().upper()
            return status in {"MEETING_ENDING", "MEETING_ARCHIVED", "MEETING_READY"}

        def _segment_item_text(seg: dict) -> str:
            seq = int(seg.get("seq") or 0)
            start_ts = float(seg.get("start_ts") or 0.0)
            end_ts = float(seg.get("end_ts") or 0.0)
            speaker = (seg.get("speaker_name") or seg.get("speaker_cluster_id") or "unknown").strip()
            text = " ".join(str(seg.get("text") or "").split())
            if len(text) > 80:
                text = text[:80] + "..."
            return f"#{seq:03d} [{self._format_mmss(start_ts)}-{self._format_mmss(end_ts)}] {speaker} | {text}"

        def _get_selected_refined_segment() -> Optional[dict]:
            item = refined_list.currentItem()
            if item is None:
                return None
            seg = item.data(Qt.UserRole)
            return seg if isinstance(seg, dict) else None

        def _load_refined_editor(seg: Optional[dict]):
            has_seg = isinstance(seg, dict) and bool(seg)
            save_segment_text_btn.setEnabled(has_seg)
            save_segment_speaker_btn.setEnabled(has_seg)
            if not has_seg:
                refined_segment_id_edit.setText("")
                refined_time_label.setText("-")
                refined_cluster_label.setText("-")
                refined_speaker_edit.setText("")
                refined_text_edit.setPlainText("")
                save_segment_speaker_btn.setText("联动改名")
                return

            seg_id = str(seg.get("segment_ref_id") or "").strip()
            start_ts = float(seg.get("start_ts") or 0.0)
            end_ts = float(seg.get("end_ts") or 0.0)
            cluster_id = (seg.get("speaker_cluster_id") or "").strip()
            speaker_name = (seg.get("speaker_name") or "").strip()
            if not speaker_name:
                speaker_name = cluster_id or ""
            refined_segment_id_edit.setText(seg_id)
            refined_time_label.setText(f"{self._format_mmss(start_ts)} - {self._format_mmss(end_ts)}")
            refined_cluster_label.setText(cluster_id or "(none)")
            refined_speaker_edit.setText(speaker_name)
            refined_text_edit.setPlainText((seg.get("text") or "").strip())
            save_segment_speaker_btn.setText("联动改名" if cluster_id else "保存说话人(单段)")

        def _set_refined_list(segments: list[dict], keep_selected: bool = True):
            selected_id = ""
            if keep_selected and refined_list.currentItem() is not None:
                selected_seg = refined_list.currentItem().data(Qt.UserRole) or {}
                selected_id = str(selected_seg.get("segment_ref_id") or "").strip()

            refined_state["segments"] = list(segments)
            refined_list.blockSignals(True)
            refined_list.clear()
            row_to_select = -1
            for idx, seg in enumerate(segments):
                row = QListWidgetItem(_segment_item_text(seg))
                row.setData(Qt.UserRole, seg)
                refined_list.addItem(row)
                if selected_id and str(seg.get("segment_ref_id") or "").strip() == selected_id:
                    row_to_select = idx
            refined_list.blockSignals(False)
            if row_to_select >= 0:
                refined_list.setCurrentRow(row_to_select)
            elif refined_list.count() > 0:
                refined_list.setCurrentRow(0)
            else:
                _load_refined_editor(None)
            _set_summary_text()

        def _set_audio_ratio(ratio: float, from_user_drag: bool = False):
            ratio_f = max(0.0, min(1.0, float(ratio or 0.0)))
            audio_state["ratio"] = ratio_f
            duration = float(audio_state.get("duration") or 0.0)
            current_sec = duration * ratio_f
            if not from_user_drag:
                audio_slider.blockSignals(True)
                audio_slider.setValue(int(ratio_f * 1000))
                audio_slider.blockSignals(False)
            audio_time_label.setText(f"{self._format_mmss(current_sec)} / {self._format_mmss(duration)}")

        def _set_audio_file(seg: Optional[dict]) -> bool:
            if not seg:
                audio_state["path"] = None
                audio_state["duration"] = 0.0
                audio_state["ratio"] = 0.0
                audio_file_label.setText("未选择音频")
                audio_slider.setEnabled(False)
                _set_audio_ratio(0.0)
                return False

            fp = self._resolve_audio_path(seg, meeting_id)
            if not fp:
                audio_state["path"] = None
                audio_state["duration"] = 0.0
                audio_state["ratio"] = 0.0
                audio_file_label.setText("音频文件不存在")
                audio_slider.setEnabled(False)
                _set_audio_ratio(0.0)
                return False

            old_path = audio_state.get("path")
            is_same_file = isinstance(old_path, Path) and old_path == fp
            audio_state["path"] = fp
            audio_state["duration"] = self._audio_duration_seconds(fp)
            if not is_same_file:
                audio_state["ratio"] = 0.0
            audio_slider.setEnabled(True)
            audio_file_label.setText(f"{fp.name} | {fp}")
            _set_audio_ratio(float(audio_state.get("ratio") or 0.0))
            return True

        def _render_jobs(job_list: list[dict]):
            jobs_state["jobs"] = list(job_list)
            jobs_state["latest_id"] = ""
            jobs_state["latest_status"] = ""
            _set_summary_text()

            if not job_list:
                if _is_meeting_ended():
                    transcription_status_label.setText("任务状态: 无（可手动启动）")
                else:
                    transcription_status_label.setText("任务状态: 无（会议未结束）")
                transcription_progress.setValue(0)
                transcription_job_label.setText("")
                transcription_jobs_view.setPlainText("(无转写任务)")
                run_stt_btn.setEnabled(_is_meeting_ended())
                retry_stt_btn.setEnabled(False)
                cancel_stt_btn.setEnabled(False)
                poll_timer.stop()
                return

            lines: list[str] = []
            for job in job_list[:8]:
                jid = str(job.get("job_id") or "")[:16]
                st = str(job.get("status") or "unknown")
                p = int(job.get("progress_percent") or 0)
                ct = _format_api_datetime(str(job.get("created_at") or ""), fallback="n/a")
                lines.append(f"{ct} | {jid}... | {st} | {p}%")
            transcription_jobs_view.setPlainText("\n".join(lines))

            latest = job_list[0]
            latest_id = str(latest.get("job_id") or "")
            latest_status = str(latest.get("status") or "unknown").strip().lower()
            latest_progress = int(latest.get("progress_percent") or 0)
            latest_progress = max(0, min(100, latest_progress))
            if latest_status == "success":
                latest_progress = 100

            jobs_state["latest_id"] = latest_id
            jobs_state["latest_status"] = latest_status

            transcription_status_label.setText(f"任务状态: {latest_status}")
            transcription_progress.setValue(latest_progress)
            extra = ""
            if latest_status == "failed":
                extra = f" | 错误: {str(latest.get('error_message') or 'unknown')[:60]}"
            elif latest_status == "success":
                out = str(latest.get("output_path") or "").strip()
                extra = f" | 输出: {Path(out).name}" if out else ""
            transcription_job_label.setText(f"最新任务: {latest_id[:24]}...{extra}")

            running = latest_status in {"queued", "running"}
            can_cancel = latest_status == "queued" and bool(latest_id)
            if running:
                if not poll_timer.isActive():
                    poll_timer.start()
            else:
                poll_timer.stop()
            ended = _is_meeting_ended()
            run_stt_btn.setEnabled(ended and (not running))
            retry_stt_btn.setEnabled(ended and (not running))
            cancel_stt_btn.setEnabled(can_cancel)

        async def _refresh_refined_data(update_transcript: bool, keep_selected: bool = True):
            nonlocal suppress_transcript_dirty
            if not _dialog_alive():
                return

            refined_result = await self._get_v2_json(f"/v2/meetings/{meeting_id}/refined")
            if not refined_result.get("ok"):
                status_label.setText(friendly_result_message(refined_result, "读取分段失败，请稍后重试。"))
                return
            segments = refined_result.get("segments", []) or []
            _set_refined_list(segments, keep_selected=keep_selected)

            if not update_transcript:
                return
            latest_text = self._build_refined_transcript(segments).strip()
            if not latest_text:
                return
            if transcript_dirty:
                status_label.setText("分段已更新，但正文已人工编辑，未自动覆盖。")
                return
            current = transcript_edit.toPlainText().strip()
            if current == latest_text:
                return
            suppress_transcript_dirty = True
            transcript_edit.setPlainText(latest_text)
            suppress_transcript_dirty = False
            status_label.setText("转写分段已同步到正文。")

        async def _refresh_transcription_jobs(refresh_refined: bool):
            if not _dialog_alive():
                return
            jobs_result = await self._get_v2_json(f"/v2/meetings/{meeting_id}/transcription", action="读取转写任务")
            if not jobs_result.get("ok"):
                transcription_status_label.setText(
                    f"任务状态: {friendly_result_message(jobs_result, '读取失败，请稍后重试。')}"
                )
                return
            current_jobs = jobs_result.get("jobs", []) or []
            _render_jobs(current_jobs)
            if refresh_refined and current_jobs:
                latest_status = str(current_jobs[0].get("status") or "").strip().lower()
                if latest_status == "success":
                    await _refresh_refined_data(update_transcript=True)

        async def _trigger_manual_stt(from_retry: bool = False):
            if not _is_meeting_ended():
                status_label.setText("会议未结束，不能启动手动STT。")
                run_stt_btn.setEnabled(False)
                retry_stt_btn.setEnabled(False)
                return
            run_stt_btn.setEnabled(False)
            retry_stt_btn.setEnabled(False)
            action_name = "重试STT" if from_retry else "手动STT"
            status_label.setText(f"正在提交{action_name}任务...")
            model_name = (self.settings.get("stt_model_size") or "small").strip() or "small"
            result = await self._post_v2_json(
                f"/v2/meetings/{meeting_id}/transcription:run",
                {"model": model_name},
                action=action_name,
            )
            if result.get("ok"):
                jid = str(result.get("job_id") or "")
                self._log(f"[会议] {action_name}任务已入队: {jid}")
                status_label.setText(f"{action_name}已入队: {jid[:24]}...")
            else:
                err = str(result.get("error") or "unknown")
                if err == "job_in_progress":
                    status_label.setText("已有任务在执行中，已切换到进度跟踪。")
                elif err == "meeting_not_ended":
                    status_label.setText("会议未结束，不能启动手动STT。")
                else:
                    status_label.setText(friendly_result_message(result, f"{action_name}失败，请稍后重试。"))
            await _refresh_transcription_jobs(refresh_refined=True)

        async def _cancel_latest_job():
            latest_id = str(jobs_state.get("latest_id") or "").strip()
            latest_status = str(jobs_state.get("latest_status") or "").strip().lower()
            if not latest_id:
                status_label.setText("没有可取消的任务。")
                return
            if latest_status != "queued":
                status_label.setText(f"仅支持取消排队任务，当前状态: {latest_status}")
                return

            cancel_stt_btn.setEnabled(False)
            status_label.setText(f"正在取消任务: {latest_id[:24]}...")
            result = await self._post_v2_json(
                f"/v2/transcription/{latest_id}:cancel",
                {},
                action="取消转写任务",
            )
            if result.get("ok"):
                status_label.setText(f"已取消任务: {latest_id[:24]}...")
                self._log(f"[会议] 已取消STT任务: {latest_id}")
            else:
                status_label.setText(friendly_result_message(result, "取消任务失败，请稍后重试。"))
            await _refresh_transcription_jobs(refresh_refined=False)

        async def _save_selected_segment_text():
            nonlocal transcript_dirty
            seg = _get_selected_refined_segment()
            if not seg:
                status_label.setText("请先选择要修改的分段。")
                return
            seg_id = str(seg.get("segment_ref_id") or "").strip()
            text = refined_text_edit.toPlainText().strip()
            if not text:
                status_label.setText("分段文本不能为空。")
                return

            save_segment_text_btn.setEnabled(False)
            result = await self._patch_v2_json(
                f"/v2/meetings/{meeting_id}/refined/{seg_id}",
                {"text": text},
                action="保存分段文本",
            )
            save_segment_text_btn.setEnabled(True)
            if not result.get("ok"):
                status_label.setText(friendly_result_message(result, "保存分段文本失败，请稍后重试。"))
                return

            transcript_dirty = False
            status_label.setText("分段文本已保存。")
            await _refresh_refined_data(update_transcript=True, keep_selected=True)

        async def _save_selected_segment_speaker():
            nonlocal transcript_dirty
            seg = _get_selected_refined_segment()
            if not seg:
                status_label.setText("请先选择要修改的分段。")
                return
            new_name = refined_speaker_edit.text().strip()
            if not new_name:
                status_label.setText("说话人名称不能为空。")
                return

            seg_id = str(seg.get("segment_ref_id") or "").strip()
            cluster_id = (seg.get("speaker_cluster_id") or "").strip()
            save_segment_speaker_btn.setEnabled(False)
            if cluster_id:
                result = await self._patch_v2_json(
                    f"/v2/meetings/{meeting_id}/speakers/{cluster_id}",
                    {
                        "speaker_name": new_name,
                        "changed_by": "windows-gui",
                        "notes": f"segment_ref_id={seg_id}",
                    },
                    action="保存说话人名称",
                )
            else:
                result = await self._patch_v2_json(
                    f"/v2/meetings/{meeting_id}/refined/{seg_id}",
                    {"speaker_name": new_name, "changed_by": "windows-gui"},
                    action="保存说话人名称",
                )
            save_segment_speaker_btn.setEnabled(True)
            if not result.get("ok"):
                status_label.setText(friendly_result_message(result, "保存说话人失败，请稍后重试。"))
                return

            transcript_dirty = False
            if cluster_id:
                updated_count = result.get("segments_updated")
                status_label.setText(f"说话人联动改名已保存，影响 {updated_count} 段。")
            else:
                status_label.setText("单段说话人名称已保存。")
            await _refresh_refined_data(update_transcript=True, keep_selected=True)

        def _on_audio_progress_from_worker(ratio: float):
            QTimer.singleShot(0, lambda r=ratio: _on_audio_progress_ui(r))

        def _on_audio_progress_ui(ratio: float):
            if not _dialog_alive():
                return
            if bool(audio_state.get("dragging")):
                return
            _set_audio_ratio(ratio)

        def _on_audio_finished_from_worker():
            QTimer.singleShot(0, _on_audio_finished_ui)

        def _on_audio_finished_ui():
            if not _dialog_alive():
                return
            if bool(audio_state.get("user_stopped")):
                audio_state["user_stopped"] = False
                return
            _set_audio_ratio(1.0)
            fp = audio_state.get("path")
            if isinstance(fp, Path):
                status_label.setText(f"播放完成: {fp.name}")

        def _play_selected(start_ratio: Optional[float] = None):
            current = audio_list.currentItem()
            if current is None:
                status_label.setText("请选择一段音频")
                return

            seg = current.data(Qt.UserRole) or {}
            if not _set_audio_file(seg):
                status_label.setText("音频文件不存在或路径不可用")
                return
            fp = audio_state.get("path")
            if not isinstance(fp, Path):
                status_label.setText("音频路径无效")
                return

            ratio = float(audio_state.get("ratio") or 0.0) if start_ratio is None else float(start_ratio)
            ratio = max(0.0, min(0.999, ratio))
            _set_audio_ratio(ratio)
            audio_state["user_stopped"] = False

            self._play_audio_file(
                fp,
                start_ratio=ratio,
                on_progress=_on_audio_progress_from_worker,
                on_finished=_on_audio_finished_from_worker,
            )
            duration = float(audio_state.get("duration") or 0.0)
            status_label.setText(f"播放中: {fp.name} ({self._format_mmss(duration)})")

        def _stop_play():
            audio_state["user_stopped"] = True
            self._stop_audio_playback()
            status_label.setText("已停止播放")

        def _on_audio_selection_changed():
            current = audio_list.currentItem()
            seg = current.data(Qt.UserRole) if current is not None else None
            _set_audio_file(seg)

        def _on_audio_slider_pressed():
            audio_state["dragging"] = True

        def _on_audio_slider_released():
            audio_state["dragging"] = False
            ratio = float(audio_slider.value()) / 1000.0
            _set_audio_ratio(ratio, from_user_drag=True)
            if isinstance(audio_state.get("path"), Path):
                _play_selected(start_ratio=ratio)

        def _on_audio_slider_changed(v: int):
            if bool(audio_state.get("dragging")):
                _set_audio_ratio(float(v) / 1000.0, from_user_drag=True)

        async def _regen_title():
            src = transcript_edit.toPlainText().strip()
            if not src:
                status_label.setText("转写文本为空，无法生成标题")
                return
            gen_title_btn.setEnabled(False)
            title = await self._generate_meeting_title(src)
            meeting_name_edit.setText(title)
            status_label.setText("已生成标题，可继续人工修改")
            gen_title_btn.setEnabled(True)

        async def _save_changes():
            save_btn.setEnabled(False)
            payload = {
                "meeting_name": meeting_name_edit.text().strip(),
                "transcript_text": transcript_edit.toPlainText().strip(),
            }
            result = await self._patch_v2_json(f"/v2/meetings/{meeting_id}", payload, action="保存会议详情")
            if result.get("ok"):
                status_label.setText("保存成功")
                self._log(f"[会议] 已保存详情: {meeting_id}")
                await self._refresh_meeting_history()
            else:
                status_label.setText(friendly_result_message(result, "保存失败，请稍后重试。"))
            save_btn.setEnabled(True)

        play_btn.clicked.connect(lambda: _play_selected())
        stop_btn.clicked.connect(_stop_play)
        run_stt_btn.clicked.connect(lambda: asyncio.create_task(_trigger_manual_stt(False)))
        retry_stt_btn.clicked.connect(lambda: asyncio.create_task(_trigger_manual_stt(True)))
        cancel_stt_btn.clicked.connect(lambda: asyncio.create_task(_cancel_latest_job()))
        refresh_stt_btn.clicked.connect(lambda: asyncio.create_task(_refresh_transcription_jobs(refresh_refined=True)))
        gen_title_btn.clicked.connect(lambda: asyncio.create_task(_regen_title()))
        save_btn.clicked.connect(lambda: asyncio.create_task(_save_changes()))
        close_btn.clicked.connect(dialog.accept)
        audio_list.itemDoubleClicked.connect(lambda _: _play_selected())
        audio_list.currentItemChanged.connect(lambda *_: _on_audio_selection_changed())
        audio_slider.sliderPressed.connect(_on_audio_slider_pressed)
        audio_slider.sliderReleased.connect(_on_audio_slider_released)
        audio_slider.valueChanged.connect(_on_audio_slider_changed)
        refined_list.currentItemChanged.connect(lambda *_: _load_refined_editor(_get_selected_refined_segment()))
        save_segment_text_btn.clicked.connect(lambda: asyncio.create_task(_save_selected_segment_text()))
        save_segment_speaker_btn.clicked.connect(lambda: asyncio.create_task(_save_selected_segment_speaker()))
        refresh_refined_btn.clicked.connect(lambda: asyncio.create_task(_refresh_refined_data(update_transcript=False)))
        poll_timer.timeout.connect(lambda: asyncio.create_task(_refresh_transcription_jobs(refresh_refined=True)))

        _set_refined_list(refined_segments, keep_selected=False)
        _on_audio_selection_changed()
        _render_jobs(jobs)
        if jobs:
            latest_status = str(jobs[0].get("status") or "").strip().lower()
            if latest_status in {"queued", "running"} and not poll_timer.isActive():
                poll_timer.start()
            elif latest_status == "success":
                await _refresh_refined_data(update_transcript=True)
        else:
            ended = _is_meeting_ended()
            run_stt_btn.setEnabled(ended)
            retry_stt_btn.setEnabled(ended)
            cancel_stt_btn.setEnabled(False)

        dialog.exec()
        poll_timer.stop()
        self._stop_audio_playback()

    async def _refresh_meeting_history(self):
        result = await self._get_v2_json("/v2/meetings?limit=20", action="读取会议历史")
        self.meeting_history_list.clear()
        if not result.get("ok"):
            self.meeting_info_label.setText(friendly_result_message(result, "读取历史会议失败，请稍后重试。"))
            return

        meetings = result.get("meetings", []) or []
        if not meetings:
            self.meeting_info_label.setText("暂无历史会议")
            return

        for row in meetings:
            meeting_id = (row.get("meeting_id") or "").strip()
            created_at = _format_api_datetime(row.get("created_at") or "", fallback="n/a")
            status = (row.get("status") or "unknown").strip()
            if not meeting_id:
                continue
            meta = self._parse_meta_json(row.get("meta_json"))
            meeting_name = (meta.get("meeting_name") or "").strip()
            prefix = f"{meeting_name} | " if meeting_name else ""
            item = QListWidgetItem(f"{created_at} | {status} | {prefix}{meeting_id[:18]}...")
            item.setData(Qt.UserRole, meeting_id)
            self.meeting_history_list.addItem(item)

        self.meeting_info_label.setText(f"历史会议: {len(meetings)} 条")

    @asyncSlot()
    async def on_refresh_meeting_history(self):
        if self._busy or self._recorder.is_recording:
            return
        await self._refresh_meeting_history()

    @asyncSlot()
    async def on_meeting_toggle(self):
        """Toggle meeting mode on/off."""
        if self._busy or self._recorder.is_recording:
            return
        self._set_busy(True)
        try:
            if self._meeting_active:
                await self._end_meeting()
            else:
                await self._start_meeting()
        finally:
            self._set_busy(False)

    async def _start_meeting(self):
        """Start a new meeting session on server and switch mode on."""
        create_result = await self._post_v2_json(
            "/v2/meetings",
            {"client_id": "windows-gui", "meta": {"source": "windows_gui"}},
            action="创建会议",
        )
        if not create_result.get("ok"):
            self.meeting_status_label.setText("启动失败")
            self.meeting_info_label.setText(friendly_result_message(create_result, "创建会议失败，请稍后重试。"))
            self._log(f"[会议] {friendly_result_message(create_result, '创建会议失败，请稍后重试。')}")
            return

        meeting_id = (create_result.get("meeting_id") or "").strip()
        if not meeting_id:
            self.meeting_status_label.setText("启动失败")
            self.meeting_info_label.setText("创建会议失败，请稍后重试。")
            self._log("[会议] 创建会议失败，服务未返回 meeting_id")
            return

        mode_result = await self._post_v2_json(
            f"/v2/meetings/{meeting_id}/mode",
            {"mode": "on"},
            action="开启会议模式",
        )
        if not mode_result.get("ok"):
            self.meeting_status_label.setText("启动失败")
            self.meeting_info_label.setText(friendly_result_message(mode_result, "开启会议失败，请稍后重试。"))
            self._log(f"[会议] {friendly_result_message(mode_result, '开启会议失败，请稍后重试。')}")
            return

        self._meeting_id = meeting_id
        self._meeting_active = True
        self.meeting_mode_btn.setText("结束会议")
        self.meeting_status_label.setText("会议中")
        self.meeting_info_label.setText(f"ID: {meeting_id[:16]}...")
        self._log(f"[会议] 会议开始: {meeting_id}")
        await self._refresh_meeting_history()

    async def _end_meeting(self):
        """End the current meeting session on server."""
        if not self._meeting_active or not self._meeting_id:
            return

        meeting_id = self._meeting_id
        mode_result = await self._post_v2_json(
            f"/v2/meetings/{meeting_id}/mode",
            {"mode": "off"},
            action="结束会议",
        )
        if not mode_result.get("ok"):
            self.meeting_status_label.setText("结束失败")
            self.meeting_info_label.setText(friendly_result_message(mode_result, "结束会议失败，请稍后重试。"))
            self._log(f"[会议] {friendly_result_message(mode_result, '结束会议失败，请稍后重试。')}")
            return

        self._meeting_active = False
        self._meeting_id = None
        self.meeting_mode_btn.setText("开始会议")
        self.meeting_status_label.setText("空闲")
        self.meeting_info_label.setText("")
        self._log(f"[会议] 会议结束: {meeting_id}")
        await self._refresh_meeting_history()

    def closeEvent(self, event):
        try:
            self._stop_audio_playback()
            if self._recorder.is_recording:
                self._recorder.stop()
            for task in list(self._watch_tasks):
                task.cancel()
            self._save_settings()
        except Exception as e:
            QMessageBox.warning(self, "保存失败", f"配置保存失败: {e}")
        super().closeEvent(event)


def main():
    cfg = load_config()
    app = QApplication(sys.argv)
    window = MainWindow(cfg)
    window.show()

    loop = QEventLoop(app)
    asyncio.set_event_loop(loop)
    with loop:
        loop.run_forever()


if __name__ == "__main__":
    main()
