#!/usr/bin/env python3
"""
Voice Assistant Bridge GUI (Windows)

Minimal desktop UI for:
- local / OpenClaw mode switch
- health check
- text chat + TTS playback
- audio device listing
"""

import asyncio
import json
import sys
from datetime import datetime

try:
    from PySide6.QtCore import Qt
    from PySide6.QtWidgets import (
        QApplication,
        QCheckBox,
        QComboBox,
        QGridLayout,
        QGroupBox,
        QHBoxLayout,
        QLineEdit,
        QMainWindow,
        QPushButton,
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


class MainWindow(QMainWindow):
    def __init__(self, cfg: dict):
        super().__init__()
        self.cfg = cfg
        self._busy = False
        self.setWindowTitle("Voice Assistant Bridge")
        self.resize(980, 680)
        self._build_ui()
        self._apply_default_mode()

    def _build_ui(self):
        root = QWidget(self)
        self.setCentralWidget(root)
        outer = QVBoxLayout(root)

        config_box = QGroupBox("连接配置")
        form = QGridLayout(config_box)

        self.mode_combo = QComboBox()
        self.mode_combo.addItem("本地模型 (Ollama)", MODE_LOCAL)
        self.mode_combo.addItem("OpenClaw Gateway", MODE_OPENCLAW)
        self.mode_combo.currentIndexChanged.connect(self._on_mode_changed)

        self.gateway_edit = QLineEdit()
        self.token_edit = QLineEdit()
        self.token_edit.setEchoMode(QLineEdit.Password)
        self.chat_path_edit = QLineEdit()
        self.health_path_edit = QLineEdit()
        self.voice_edit = QLineEdit()
        self.edge_voice_edit = QLineEdit()
        self.auto_start_local = QCheckBox("本地模式自动拉起 server.py")
        self.auto_start_local.setChecked(True)

        form.addWidget(self.mode_combo, 0, 0, 1, 2)
        form.addWidget(self.gateway_edit, 1, 0)
        form.addWidget(self.token_edit, 1, 1)
        form.addWidget(self.chat_path_edit, 2, 0)
        form.addWidget(self.health_path_edit, 2, 1)
        form.addWidget(self.voice_edit, 3, 0)
        form.addWidget(self.edge_voice_edit, 3, 1)
        form.addWidget(self.auto_start_local, 4, 0, 1, 2)

        self.gateway_edit.setPlaceholderText("Gateway URL")
        self.token_edit.setPlaceholderText("Gateway token (optional)")
        self.chat_path_edit.setPlaceholderText("Chat path")
        self.health_path_edit.setPlaceholderText("Health path")
        self.voice_edit.setPlaceholderText("System voice (e.g. Xiaoxiao)")
        self.edge_voice_edit.setPlaceholderText("Edge voice (e.g. zh-CN-XiaoxiaoNeural)")

        actions_box = QGroupBox("操作")
        actions = QHBoxLayout(actions_box)
        self.health_btn = QPushButton("健康检查")
        self.list_devices_btn = QPushButton("列设备")
        self.clear_btn = QPushButton("清空日志")
        actions.addWidget(self.health_btn)
        actions.addWidget(self.list_devices_btn)
        actions.addWidget(self.clear_btn)
        actions.addStretch(1)

        chat_box = QGroupBox("对话")
        chat_layout = QVBoxLayout(chat_box)
        self.log_view = QTextEdit()
        self.log_view.setReadOnly(True)
        self.input_edit = QLineEdit()
        self.input_edit.setPlaceholderText("输入文字后按 Enter 或点击发送")
        self.send_btn = QPushButton("发送")
        send_row = QHBoxLayout()
        send_row.addWidget(self.input_edit, 1)
        send_row.addWidget(self.send_btn)
        chat_layout.addWidget(self.log_view, 1)
        chat_layout.addLayout(send_row)

        outer.addWidget(config_box)
        outer.addWidget(actions_box)
        outer.addWidget(chat_box, 1)

        self.health_btn.clicked.connect(self.on_health_clicked)
        self.list_devices_btn.clicked.connect(self.on_list_devices_clicked)
        self.clear_btn.clicked.connect(self.log_view.clear)
        self.send_btn.clicked.connect(self.on_send_clicked)
        self.input_edit.returnPressed.connect(self.on_send_clicked)

    def _apply_default_mode(self):
        preferred = self.cfg.get("gui_mode", "").strip().lower()
        if not preferred:
            preferred = MODE_LOCAL if self.cfg.get("brain_backend") == "ollama" else MODE_OPENCLAW
        idx = 0 if preferred == MODE_LOCAL else 1
        self.mode_combo.setCurrentIndex(idx)
        self._on_mode_changed()
        self._log("GUI 就绪。")

    def _on_mode_changed(self):
        mode = self.mode_combo.currentData()
        if mode == MODE_LOCAL:
            self.gateway_edit.setText(self.cfg.get("local_gateway_url", LOCAL_LLM_URL))
            self.token_edit.setText(self.cfg.get("local_gateway_token", ""))
            self.chat_path_edit.setText(self.cfg.get("local_chat_path", LOCAL_CHAT_PATH))
            self.health_path_edit.setText(self.cfg.get("local_health_path", LOCAL_HEALTH_PATH))
            self.auto_start_local.setEnabled(True)
        else:
            self.gateway_edit.setText(self.cfg.get("openclaw_gateway_url", "http://127.0.0.1:18789"))
            self.token_edit.setText(self.cfg.get("gateway_token", ""))
            self.chat_path_edit.setText(self.cfg.get("openclaw_chat_path", DEFAULT_CHAT_PATH))
            self.health_path_edit.setText(self.cfg.get("openclaw_health_path", DEFAULT_HEALTH_PATH))
            self.auto_start_local.setChecked(False)
            self.auto_start_local.setEnabled(False)

        self.voice_edit.setText(self.cfg.get("tts_voice", "Xiaoxiao"))
        self.edge_voice_edit.setText(self.cfg.get("tts_edge_voice", "zh-CN-XiaoxiaoNeural"))

    def _log(self, text: str):
        stamp = datetime.now().strftime("%H:%M:%S")
        self.log_view.append(f"[{stamp}] {text}")

    def _set_busy(self, busy: bool):
        self._busy = busy
        self.send_btn.setEnabled(not busy)
        self.health_btn.setEnabled(not busy)
        self.list_devices_btn.setEnabled(not busy)
        if busy:
            self.statusBar().showMessage("处理中...")
        else:
            self.statusBar().clearMessage()

    def _build_client(self) -> AudioBridgeClient:
        return AudioBridgeClient(
            gateway_url=self.gateway_edit.text().strip() or DEFAULT_GATEWAY_URL,
            gateway_token=self.token_edit.text().strip(),
            tts_voice=self.voice_edit.text().strip() or "Xiaoxiao",
            tts_edge_voice=self.edge_voice_edit.text().strip() or "zh-CN-XiaoxiaoNeural",
            chat_path=self.chat_path_edit.text().strip() or DEFAULT_CHAT_PATH,
            health_path=self.health_path_edit.text().strip() or DEFAULT_HEALTH_PATH,
        )

    async def _prepare_client(self, client: AudioBridgeClient):
        mode = self.mode_combo.currentData()
        local_mode = _should_treat_as_local_mode(
            gateway_url=client.gateway_url,
            chat_path=client.chat_path,
            force_local=(mode == MODE_LOCAL),
        )
        await ensure_local_service_if_needed(
            client,
            auto_start_local=self.auto_start_local.isChecked(),
            local_mode=local_mode,
        )

    @asyncSlot()
    async def on_health_clicked(self):
        if self._busy:
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
                self._log("health 失败")
        except Exception as e:
            self._log(f"health 异常: {e}")
        finally:
            client.close()
            self._set_busy(False)

    @asyncSlot()
    async def on_list_devices_clicked(self):
        if self._busy:
            return
        self._set_busy(True)
        client = self._build_client()
        try:
            lines = []
            for i in range(client.p.get_device_count()):
                dev = client.p.get_device_info_by_index(i)
                lines.append(f"[{i}] {dev['name']} (in:{dev['maxInputChannels']}, out:{dev['maxOutputChannels']})")
            if not lines:
                self._log("未检测到音频设备")
            else:
                self._log("音频设备:")
                for line in lines:
                    self._log(line)
        except Exception as e:
            self._log(f"列设备异常: {e}")
        finally:
            client.close()
            self._set_busy(False)

    @asyncSlot()
    async def on_send_clicked(self):
        if self._busy:
            return

        text = self.input_edit.text().strip()
        if not text:
            return

        self._set_busy(True)
        self.input_edit.clear()
        self._log(f"你: {text}")

        client = self._build_client()
        try:
            await self._prepare_client(client)
            result = await client.send_text(text)
            if not result:
                self._log("发送失败")
                return

            reply = AudioBridgeClient._extract_reply_text(result)
            if reply:
                self._log(f"助手: {reply}")
                await asyncio.to_thread(client._speak_text_windows, reply)
            else:
                self._log(json.dumps(result, ensure_ascii=False, indent=2))
        except Exception as e:
            self._log(f"发送异常: {e}")
        finally:
            client.close()
            self._set_busy(False)


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
