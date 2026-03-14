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
from datetime import datetime
from pathlib import Path
from typing import Optional

import aiohttp

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
        QPushButton,
        QSpinBox,
        QTabWidget,
        QTextEdit,
        QVBoxLayout,
        QWidget,
    )
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
        self.setWindowTitle("璁剧疆")
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
        self.auto_start_local_chk = QCheckBox("鏈湴妯″紡鑷姩鎷夎捣 server.py")
        local_form.addRow("Local Gateway", self.local_gateway_edit)
        local_form.addRow("Local Token", self.local_token_edit)
        local_form.addRow("Local Chat Path", self.local_chat_path_edit)
        local_form.addRow("Local Health Path", self.local_health_path_edit)
        local_form.addRow("", self.auto_start_local_chk)
        tabs.addTab(local_tab, "鏈湴妯″紡")

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
        voice_form.addRow("褰曢煶鏈€澶х鏁?闃叉蹇樺仠)", self.record_seconds_spin)
        voice_form.addRow("鏈湴STT妯″瀷", self.stt_model_edit)
        tabs.addTab(voice_tab, "璇煶")

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

        self.setWindowTitle("Voice Assistant Bridge")
        self.resize(930, 670)
        self._build_ui()
        self._apply_mode(self.settings.get("gui_mode", MODE_LOCAL))
        self._apply_voice_mode(self.settings.get("voice_input_mode", VOICE_MODE_HOLD))
        self._refresh_controls()
        self._log("GUI ready.")

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
        self.mode_combo.addItem("鏈湴妯″瀷", MODE_LOCAL)
        self.mode_combo.addItem("OpenClaw", MODE_OPENCLAW)
        self.mode_combo.currentIndexChanged.connect(self.on_mode_changed)

        self.voice_mode_combo = QComboBox()
        self.voice_mode_combo.addItem("按住说话", VOICE_MODE_HOLD)
        self.voice_mode_combo.addItem("开关录音", VOICE_MODE_TOGGLE)
        self.voice_mode_combo.currentIndexChanged.connect(self.on_voice_mode_changed)

        self.health_btn = QPushButton("健康检查")
        self.settings_btn = QPushButton("璁剧疆")
        self.clear_btn = QPushButton("娓呯┖")

        top.addWidget(QLabel("妯″紡:"))
        top.addWidget(self.mode_combo)
        top.addWidget(QLabel("璇煶杈撳叆:"))
        top.addWidget(self.voice_mode_combo)
        top.addWidget(self.health_btn)
        top.addStretch(1)
        top.addWidget(self.settings_btn)
        top.addWidget(self.clear_btn)

        chat_box = QGroupBox("瀵硅瘽")
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
        meeting_layout = QHBoxLayout(meeting_box)
        self.meeting_mode_btn = QPushButton("开始会议")
        self.meeting_status_label = QLabel("空闲")
        self.meeting_info_label = QLabel("")
        meeting_layout.addWidget(self.meeting_mode_btn)
        meeting_layout.addWidget(self.meeting_status_label)
        meeting_layout.addWidget(self.meeting_info_label, 1)
        self.meeting_mode_btn.clicked.connect(self.on_meeting_toggle)

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

        if self._busy:
            self.voice_btn.setText("澶勭悊涓?..")
        elif mode == VOICE_MODE_HOLD:
            self.voice_btn.setText("鏉惧紑缁撴潫" if recording else "鎸変綇璇磋瘽")
        else:
            self.voice_btn.setText("结束录音" if recording else "开始录音")

        # Update meeting mode button text
        self.meeting_mode_btn.setText("结束会议" if self._meeting_active else "开始会议")

        if self._busy:
            self.statusBar().showMessage("澶勭悊涓?..")
        elif recording:
            self.statusBar().showMessage("褰曢煶涓?..")
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
                self._log(f"[系统] 消息 {message_id} 等待终答超时。")
                return
            await self._render_messages(client, status, message_id=message_id)
            if (status.get("status") or "").upper() == "FAILED":
                err = (status.get("last_error") or "openclaw_failed").strip()
                self._log(f"[系统] 龙虾大脑回复失败：{err}")
        except Exception as e:
            self._log(f"[系统] 终答监听异常: {e}")
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
            self._log("发送失败。")
            return

        if result.get("protocol") == "v1":
            message_id = (result.get("message_id") or "").strip()
            await self._render_messages(client, result, message_id=message_id)
            state = (result.get("status") or "").upper()
            if message_id and state not in {"DELIVERED", "FAILED"}:
                self._spawn_watch_task(message_id)
            elif state == "FAILED":
                err = (result.get("last_error") or "openclaw_failed").strip()
                self._log(f"[系统] 龙虾大脑回复失败：{err}")
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
            self._log(f"褰曢煶寮傚父: {self._recorder.error}")

        self._set_busy(True)
        try:
            client = self._build_client()
            try:
                await self._prepare_client(client)
                audio_result = await self._send_audio_to_backend(client, audio_data)
                if audio_result:
                    input_text = (audio_result.get("input_text") or "").strip()
                    if input_text:
                        self._log(f"浣?璇煶): {input_text}")
                    reply = AudioBridgeClient._extract_reply_text(audio_result)
                    if reply:
                        self._log(f"鍔╂墜: {reply}")
                        await asyncio.to_thread(client._speak_text_windows, reply)
                        return

                self._log("鍚庣璇煶鎺ュ彛涓嶅彲鐢紝鍒囨崲鏈湴STT...")
                text = await asyncio.to_thread(self._transcribe_local_blocking, audio_data)
                if not text:
                    self._log("本地 STT 失败。")
                    return
                self._log(f"浣?璇煶): {text}")
                await self._chat_with_text(client, text)
            finally:
                client.close()
        except Exception as e:
            self._log(f"璇煶杈撳叆寮傚父: {e}")
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
        self._log("鍋滄褰曢煶锛屽鐞嗕腑...")
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
                self._log("health 澶辫触")
        except Exception as e:
            self._log(f"health 寮傚父: {e}")
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
        self._log(f"浣? {text}")

        self._set_busy(True)
        client = self._build_client()
        try:
            await self._prepare_client(client)
            await self._chat_with_text(client, text)
        except Exception as e:
            self._log(f"鍙戦€佸紓甯? {e}")
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

    def on_meeting_toggle(self):
        """Toggle meeting mode on/off."""
        if self._meeting_active:
            self._end_meeting()
        else:
            self._start_meeting()

    def _start_meeting(self):
        """Start a new meeting session."""
        import uuid
        self._meeting_id = f"mtg-{uuid.uuid4().hex[:24]}"
        self._meeting_active = True
        self.meeting_mode_btn.setText("结束会议")
        self.meeting_status_label.setText("会议中")
        self.meeting_info_label.setText(f"ID: {self._meeting_id[:16]}...")
        self._log(f"[会议] 会议开始: {self._meeting_id}")
        # TODO: Call /v2/meetings API to register meeting on server

    def _end_meeting(self):
        """End the current meeting session."""
        if not self._meeting_active:
            return
        meeting_id = self._meeting_id
        self._meeting_active = False
        self._meeting_id = None
        self.meeting_mode_btn.setText("开始会议")
        self.meeting_status_label.setText("空闲")
        self.meeting_info_label.setText("")
        self._log(f"[会议] 会议结束: {meeting_id}")
        # TODO: Call /v2/meetings/{id}/mode API to end meeting on server

    def closeEvent(self, event):
        try:
            if self._recorder.is_recording:
                self._recorder.stop()
            for task in list(self._watch_tasks):
                task.cancel()
            self._save_settings()
        except Exception as e:
            QMessageBox.warning(self, "淇濆瓨澶辫触", f"閰嶇疆淇濆瓨澶辫触: {e}")
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

