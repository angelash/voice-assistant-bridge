#!/usr/bin/env python3
"""
Voice Assistant Bridge - Windows Meeting Console UI

Provides:
- Meeting mode controls (start/end meeting)
- Real-time transcription view (dual-track: live + stable)
- Event stream display
- Meeting history list
"""

import asyncio
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

import aiohttp

try:
    from PySide6.QtWidgets import (
        QApplication,
        QComboBox,
        QHBoxLayout,
        QLabel,
        QMainWindow,
        QMessageBox,
        QPushButton,
        QSplitter,
        QTextEdit,
        QVBoxLayout,
        QWidget,
        QListWidget,
        QListWidgetItem,
        QGroupBox,
        QFrame,
    )
    from PySide6.QtCore import Qt, QTimer, Signal, Slot
    from PySide6.QtGui import QColor, QFont
    from qasync import QEventLoop, asyncSlot
except ImportError:
    print("Missing GUI dependencies. Install with: pip install PySide6 qasync")
    raise SystemExit(1)

from windows_client import load_config, CONFIG_PATH

# Meeting status constants
MEETING_STATUS_IDLE = "IDLE"
MEETING_STATUS_ACTIVE = "MEETING_ACTIVE"
MEETING_STATUS_ENDING = "MEETING_ENDING"


def _now() -> str:
    return datetime.now().strftime("%H:%M:%S")


class MeetingClient:
    """HTTP client for V2 Meeting API"""

    def __init__(self, base_url: str = "http://127.0.0.1:8765"):
        self.base_url = base_url.rstrip("/")
        self._session: Optional[aiohttp.ClientSession] = None

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession()
        return self._session

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()

    async def create_meeting(self, client_id: str = "windows-client") -> dict:
        session = await self._get_session()
        async with session.post(
            f"{self.base_url}/v2/meetings",
            json={"client_id": client_id},
        ) as resp:
            return await resp.json()

    async def set_meeting_mode(self, meeting_id: str, mode: str) -> dict:
        session = await self._get_session()
        async with session.post(
            f"{self.base_url}/v2/meetings/{meeting_id}/mode",
            json={"mode": mode},
        ) as resp:
            return await resp.json()

    async def list_meetings(self, limit: int = 20) -> dict:
        session = await self._get_session()
        async with session.get(
            f"{self.base_url}/v2/meetings?limit={limit}",
        ) as resp:
            return await resp.json()

    async def get_meeting(self, meeting_id: str) -> dict:
        session = await self._get_session()
        async with session.get(
            f"{self.base_url}/v2/meetings/{meeting_id}",
        ) as resp:
            return await resp.json()

    async def get_timeline(self, meeting_id: str, after_seq: int = 0) -> dict:
        session = await self._get_session()
        async with session.get(
            f"{self.base_url}/v2/meetings/{meeting_id}/timeline?after_seq={after_seq}",
        ) as resp:
            return await resp.json()


class MeetingConsoleWidget(QWidget):
    """Meeting mode control panel"""

    # Signals
    meeting_started = Signal(str)  # meeting_id
    meeting_ended = Signal(str)    # meeting_id
    status_changed = Signal(str)  # status message

    def __init__(self, client: MeetingClient, parent=None):
        super().__init__(parent)
        self.client = client
        self.current_meeting_id: Optional[str] = None
        self.current_status = MEETING_STATUS_IDLE
        self._poll_timer = QTimer(self)
        self._poll_timer.timeout.connect(self._poll_meeting_status)
        
        self._build_ui()

    def _build_ui(self):
        layout = QVBoxLayout(self)
        
        # Status bar
        status_frame = QFrame()
        status_frame.setFrameStyle(QFrame.StyledPanel)
        status_layout = QHBoxLayout(status_frame)
        
        self.status_label = QLabel("状态: 未开始")
        self.status_label.setStyleSheet("font-weight: bold; font-size: 14px;")
        self.duration_label = QLabel("时长: 00:00:00")
        
        status_layout.addWidget(self.status_label)
        status_layout.addStretch()
        status_layout.addWidget(self.duration_label)
        
        # Control buttons
        btn_layout = QHBoxLayout()
        self.start_btn = QPushButton("开始会议")
        self.start_btn.setStyleSheet("background-color: #4CAF50; color: white; padding: 10px; font-size: 14px;")
        self.end_btn = QPushButton("结束会议")
        self.end_btn.setStyleSheet("background-color: #f44336; color: white; padding: 10px; font-size: 14px;")
        self.end_btn.setEnabled(False)
        
        self.start_btn.clicked.connect(self._on_start_clicked)
        self.end_btn.clicked.connect(self._on_end_clicked)
        
        btn_layout.addWidget(self.start_btn)
        btn_layout.addWidget(self.end_btn)
        
        # Event log
        log_group = QGroupBox("事件流")
        log_layout = QVBoxLayout(log_group)
        self.event_log = QTextEdit()
        self.event_log.setReadOnly(True)
        self.event_log.setFont(QFont("Consolas", 10))
        log_layout.addWidget(self.event_log)
        
        # Transcription view
        transcript_group = QGroupBox("实时转写")
        transcript_layout = QVBoxLayout(transcript_group)
        self.transcript_view = QTextEdit()
        self.transcript_view.setReadOnly(True)
        self.transcript_view.setFont(QFont("Microsoft YaHei", 11))
        transcript_layout.addWidget(self.transcript_view)
        
        # Layout assembly
        layout.addWidget(status_frame)
        layout.addLayout(btn_layout)
        layout.addWidget(log_group, stretch=1)
        layout.addWidget(transcript_group, stretch=2)
        
        self._update_controls()

    def _log(self, message: str, event_type: str = "info"):
        timestamp = _now()
        color = {
            "info": "#333",
            "event": "#0066cc",
            "stt": "#009933",
            "wakeword": "#ff6600",
            "error": "#cc0000",
        }.get(event_type, "#333")
        
        self.event_log.append(f'<span style="color: {color}">[{timestamp}] {message}</span>')

    def _update_controls(self):
        is_active = self.current_status == MEETING_STATUS_ACTIVE
        self.start_btn.setEnabled(not is_active)
        self.end_btn.setEnabled(is_active)
        
        if is_active:
            self.status_label.setText("状态: 会议进行中")
            self.status_label.setStyleSheet("font-weight: bold; font-size: 14px; color: #009900;")
        else:
            self.status_label.setText("状态: 未开始")
            self.status_label.setStyleSheet("font-weight: bold; font-size: 14px; color: #666;")

    @asyncSlot()
    async def _on_start_clicked(self):
        try:
            result = await self.client.create_meeting()
            if result.get("ok"):
                self.current_meeting_id = result["meeting_id"]
                self._log(f"会议已创建: {self.current_meeting_id}")
                
                # Enable meeting mode
                mode_result = await self.client.set_meeting_mode(self.current_meeting_id, "on")
                if mode_result.get("ok"):
                    self.current_status = MEETING_STATUS_ACTIVE
                    self._update_controls()
                    self._log("会议模式已开启", "event")
                    self.meeting_started.emit(self.current_meeting_id)
                    self.status_changed.emit("active")
                    
                    # Start polling
                    self._poll_timer.start(2000)  # Poll every 2 seconds
                else:
                    self._log(f"开启会议模式失败: {mode_result.get('error')}", "error")
            else:
                self._log(f"创建会议失败: {result.get('error')}", "error")
        except Exception as e:
            self._log(f"请求失败: {e}", "error")

    @asyncSlot()
    async def _on_end_clicked(self):
        if not self.current_meeting_id:
            return
        try:
            result = await self.client.set_meeting_mode(self.current_meeting_id, "off")
            if result.get("ok"):
                self._log("会议已结束", "event")
                self.current_status = MEETING_STATUS_IDLE
                self._poll_timer.stop()
                self._update_controls()
                self.meeting_ended.emit(self.current_meeting_id)
        except Exception as e:
            self._log(f"结束会议失败: {e}", "error")

    @asyncSlot()
    async def _poll_meeting_status(self):
        """Poll meeting timeline for new events"""
        if not self.current_meeting_id:
            return
        try:
            # This is a simplified polling approach
            # In production, would use WebSocket for real-time updates
            pass
        except Exception as e:
            self._log(f"轮询失败: {e}", "error")


class MeetingHistoryWidget(QWidget):
    """Meeting history list"""

    def __init__(self, client: MeetingClient, parent=None):
        super().__init__(parent)
        self.client = client
        self._build_ui()

    def _build_ui(self):
        layout = QVBoxLayout(self)
        
        # Header
        header = QHBoxLayout()
        header.addWidget(QLabel("历史会议"))
        self.refresh_btn = QPushButton("刷新")
        self.refresh_btn.clicked.connect(self._refresh)
        header.addWidget(self.refresh_btn)
        layout.addLayout(header)
        
        # Meeting list
        self.meeting_list = QListWidget()
        self.meeting_list.itemDoubleClicked.connect(self._on_item_double_clicked)
        layout.addWidget(self.meeting_list)

    @asyncSlot()
    async def _refresh(self):
        self.meeting_list.clear()
        try:
            result = await self.client.list_meetings()
            if result.get("ok"):
                for meeting in result.get("meetings", []):
                    meeting_id = meeting.get("meeting_id", "unknown")
                    status = meeting.get("status", "unknown")
                    created = meeting.get("created_at", "")[:19]
                    item = QListWidgetItem(f"{created} | {status} | {meeting_id[:16]}...")
                    item.setData(Qt.UserRole, meeting_id)
                    self.meeting_list.addItem(item)
        except Exception as e:
            print(f"Failed to refresh meeting list: {e}")

    @asyncSlot()
    async def _on_item_double_clicked(self, item: QListWidgetItem):
        meeting_id = item.data(Qt.UserRole)
        print(f"Selected meeting: {meeting_id}")
        # TODO: Open meeting detail view


class MeetingConsoleWindow(QMainWindow):
    """Main window for meeting console"""

    def __init__(self, cfg: dict):
        super().__init__()
        self.settings = cfg
        self._meeting_client: Optional[MeetingClient] = None

        self.setWindowTitle("Meeting Console - Voice Assistant Bridge V2")
        self.resize(1000, 700)
        self._build_ui()

    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        
        layout = QHBoxLayout(central)
        
        # Left: Meeting console
        self.console = MeetingConsoleWidget(self._get_client())
        self.console.meeting_started.connect(self._on_meeting_started)
        self.console.meeting_ended.connect(self._on_meeting_ended)
        
        # Right: History panel
        right_panel = QWidget()
        right_layout = QVBoxLayout(right_panel)
        self.history = MeetingHistoryWidget(self._get_client())
        right_layout.addWidget(self.history)
        
        # Splitter
        splitter = QSplitter(Qt.Horizontal)
        splitter.addWidget(self.console)
        splitter.addWidget(right_panel)
        splitter.setSizes([700, 300])
        
        layout.addWidget(splitter)
        
        # Initial refresh
        QTimer.singleShot(500, lambda: asyncio.create_task(self.history._refresh()))

    def _get_client(self) -> MeetingClient:
        if self._meeting_client is None:
            base_url = self.settings.get("local_gateway_url", "http://127.0.0.1:8765")
            self._meeting_client = MeetingClient(base_url)
        return self._meeting_client

    def _on_meeting_started(self, meeting_id: str):
        self.statusBar().showMessage(f"Meeting started: {meeting_id}")

    def _on_meeting_ended(self, meeting_id: str):
        self.statusBar().showMessage(f"Meeting ended: {meeting_id}")
        asyncio.create_task(self.history._refresh())

    def closeEvent(self, event):
        if self._meeting_client:
            asyncio.create_task(self._meeting_client.close())
        super().closeEvent(event)


def main():
    cfg = load_config()
    app = QApplication(sys.argv)
    window = MeetingConsoleWindow(cfg)
    window.show()

    loop = QEventLoop(app)
    asyncio.set_event_loop(loop)
    with loop:
        loop.run_forever()


if __name__ == "__main__":
    main()
