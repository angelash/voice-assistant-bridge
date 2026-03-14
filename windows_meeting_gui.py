#!/usr/bin/env python3
"""
Voice Assistant Bridge - Windows Meeting Console UI

Provides:
- Meeting mode controls (start/end meeting)
- Real-time transcription view (dual-track: live + stable)
- Event stream display
- Meeting history list
- Backup status panel (M2)
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

    # M3: Transcription job API methods
    
    async def get_transcription_jobs(self, meeting_id: str) -> dict:
        """Get transcription jobs for a meeting."""
        session = await self._get_session()
        async with session.get(
            f"{self.base_url}/v2/meetings/{meeting_id}/transcription",
        ) as resp:
            return await resp.json()
    
    async def get_transcription_queue(self) -> dict:
        """Get all queued and running transcription jobs."""
        session = await self._get_session()
        async with session.get(
            f"{self.base_url}/v2/transcription/queue",
        ) as resp:
            return await resp.json()
    
    async def get_transcription_job(self, job_id: str) -> dict:
        """Get a specific transcription job status."""
        session = await self._get_session()
        async with session.get(
            f"{self.base_url}/v2/transcription/{job_id}",
        ) as resp:
            return await resp.json()
    
    async def run_transcription(self, meeting_id: str, model: str = "small") -> dict:
        """Start a transcription job for a meeting."""
        session = await self._get_session()
        async with session.post(
            f"{self.base_url}/v2/meetings/{meeting_id}/transcription:run",
            json={"model": model},
        ) as resp:
            return await resp.json()

    # M5: Image API methods
    
    async def get_images(self, meeting_id: str) -> dict:
        """Get all images for a meeting."""
        session = await self._get_session()
        async with session.get(
            f"{self.base_url}/v2/meetings/{meeting_id}/images",
        ) as resp:
            return await resp.json()
    
    async def get_image(self, meeting_id: str, image_id: str) -> dict:
        """Get a specific image."""
        session = await self._get_session()
        async with session.get(
            f"{self.base_url}/v2/meetings/{meeting_id}/images/{image_id}",
        ) as resp:
            return await resp.json()
    
    async def get_image_file(self, meeting_id: str, image_id: str) -> bytes:
        """Get image file content."""
        session = await self._get_session()
        async with session.get(
            f"{self.base_url}/v2/meetings/{meeting_id}/images/{image_id}/file",
        ) as resp:
            return await resp.read()
    
    async def trigger_image_analysis(self, meeting_id: str, image_id: str) -> dict:
        """Trigger image analysis for a specific image."""
        session = await self._get_session()
        async with session.post(
            f"{self.base_url}/v2/meetings/{meeting_id}/images/{image_id}:analyze",
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


class BackupStatusWidget(QWidget):
    """M2: Backup status panel showing upload progress and history"""
    
    def __init__(self, client: MeetingClient, parent=None):
        super().__init__(parent)
        self.client = client
        self._build_ui()

    def _build_ui(self):
        layout = QVBoxLayout(self)
        
        # Header
        header = QHBoxLayout()
        header.addWidget(QLabel("备份状态"))
        self.refresh_btn = QPushButton("刷新")
        self.refresh_btn.clicked.connect(self._refresh)
        header.addWidget(self.refresh_btn)
        layout.addLayout(header)
        
        # Status summary
        self.status_label = QLabel("等待刷新...")
        self.status_label.setStyleSheet("font-size: 12px; color: #666;")
        layout.addWidget(self.status_label)
        
        # Progress bar for current meeting
        self.progress_label = QLabel("当前会议备份:")
        self.progress_bar = QLabel("N/A")
        layout.addWidget(self.progress_label)
        layout.addWidget(self.progress_bar)
        
        # Segment list
        self.segment_list = QListWidget()
        self.segment_list.setMaximumHeight(150)
        layout.addWidget(QLabel("音频分片:"))
        layout.addWidget(self.segment_list)

    @asyncSlot()
    async def _refresh(self):
        try:
            # Get recent meetings
            result = await self.client.list_meetings(limit=10)
            if not result.get("ok"):
                self.status_label.setText("获取会议列表失败")
                return
            
            meetings = result.get("meetings", [])
            if not meetings:
                self.status_label.setText("暂无会议记录")
                return
            
            # Get upload manifest for most recent meeting
            for meeting in meetings:
                meeting_id = meeting.get("meeting_id")
                if not meeting_id:
                    continue
                
                # Try to get upload manifest
                manifest_result = await self._get_upload_manifest(meeting_id)
                if manifest_result:
                    self._update_ui(manifest_result)
                    return
            
            self.status_label.setText("暂无可备份会议")
            
        except Exception as e:
            self.status_label.setText(f"刷新失败: {e}")

    async def _get_upload_manifest(self, meeting_id: str) -> dict:
        """Get upload manifest for a meeting"""
        session = self.client._session
        if session is None:
            session = await self.client._get_session()
        
        try:
            async with session.get(
                f"{self.client.base_url}/v2/meetings/{meeting_id}/audio/manifest",
            ) as resp:
                if resp.status == 200:
                    return await resp.json()
        except Exception:
            pass
        return None

    def _update_ui(self, manifest_result: dict):
        manifest = manifest_result.get("manifest", {})
        
        total = manifest.get("total_segments", 0)
        uploaded = manifest.get("uploaded_count", 0)
        pending = manifest.get("pending_count", 0)
        failed = manifest.get("failed_count", 0)
        
        self.status_label.setText(
            f"总计: {total} 分片 | 已上传: {uploaded} | 待传: {pending} | 失败: {failed}"
        )
        
        # Update progress
        if total > 0:
            percent = uploaded * 100 // total
            self.progress_bar.setText(f"{uploaded}/{total} ({percent}%)")
            self.progress_bar.setStyleSheet(
                f"color: {'green' if percent == 100 else 'blue'};"
            )
        else:
            self.progress_bar.setText("无分片")
        
        # Update segment list
        self.segment_list.clear()
        for seg in manifest.get("segments", []):
            seq = seg.get("seq", "?")
            status = seg.get("upload_status", "unknown")
            size_kb = (seg.get("size_bytes") or 0) // 1024
            
            status_icon = {
                "uploaded": "✅",
                "pending": "⏳",
                "failed": "❌",
                "uploading": "📤",
            }.get(status, "❓")
            
            item_text = f"{status_icon} Seg {seq}: {status} ({size_kb}KB)"
            item = QListWidgetItem(item_text)
            
            if status == "failed":
                error = seg.get("upload_error", "")
                item.setToolTip(f"错误: {error}")
            
            self.segment_list.addItem(item)


class TranscriptionTaskWidget(QWidget):
    """M3: Transcription task queue panel"""
    
    def __init__(self, client: MeetingClient, parent=None):
        super().__init__(parent)
        self.client = client
        self._build_ui()
        
        # Auto-refresh timer
        self._refresh_timer = QTimer(self)
        self._refresh_timer.timeout.connect(self._on_refresh_timer)
        self._refresh_timer.start(5000)  # Refresh every 5 seconds
    
    def _build_ui(self):
        layout = QVBoxLayout(self)
        
        # Title
        title = QLabel("📝 加精任务队列")
        title.setFont(QFont("Microsoft YaHei", 11, QFont.Bold))
        layout.addWidget(title)
        
        # Task list
        self.task_list = QListWidget()
        self.task_list.setMaximumHeight(120)
        layout.addWidget(self.task_list)
        
        # Status label
        self.status_label = QLabel("无任务")
        self.status_label.setStyleSheet("color: #666; font-size: 11px;")
        layout.addWidget(self.status_label)
    
    def _on_refresh_timer(self):
        asyncio.create_task(self._refresh())
    
    async def _refresh(self):
        try:
            result = await self.client.get_transcription_queue()
            if result.get("ok"):
                jobs = result.get("queued_jobs", [])
                self._update_ui(jobs)
        except Exception as e:
            self.status_label.setText(f"刷新失败: {e}")
    
    def _update_ui(self, jobs: list):
        self.task_list.clear()
        
        if not jobs:
            self.status_label.setText("无任务")
            return
        
        for job in jobs:
            job_id = job.get("job_id", "unknown")[:12]
            meeting_id = job.get("meeting_id", "unknown")[:12]
            status = job.get("status", "unknown")
            progress = job.get("progress_percent", 0)
            
            status_icon = {
                "queued": "⏳",
                "running": "🔄",
                "success": "✅",
                "failed": "❌",
            }.get(status, "❓")
            
            item_text = f"{status_icon} {job_id}... | {meeting_id}... | {status} ({progress}%)"
            item = QListWidgetItem(item_text)
            
            if status == "running":
                item.setForeground(QColor("#2196F3"))
            elif status == "success":
                item.setForeground(QColor("#4CAF50"))
            elif status == "failed":
                item.setForeground(QColor("#F44336"))
            
            self.task_list.addItem(item)
        
        self.status_label.setText(f"{len(jobs)} 个任务")


class SpeakerPanelWidget(QWidget):
    """M4: Speaker panel with rename functionality"""
    
    def __init__(self, client: MeetingClient, parent=None):
        super().__init__(parent)
        self.client = client
        self._current_meeting_id: Optional[str] = None
        self._build_ui()
        
        # Auto-refresh timer
        self._refresh_timer = QTimer(self)
        self._refresh_timer.timeout.connect(self._on_refresh_timer)
        self._refresh_timer.start(10000)  # Refresh every 10 seconds

    def _build_ui(self):
        layout = QVBoxLayout(self)
        
        # Title
        title = QLabel("👥 说话人")
        title.setFont(QFont("Microsoft YaHei", 11, QFont.Bold))
        layout.addWidget(title)
        
        # Speaker list
        self.speaker_list = QListWidget()
        self.speaker_list.setMaximumHeight(150)
        self.speaker_list.itemDoubleClicked.connect(self._on_speaker_double_clicked)
        layout.addWidget(self.speaker_list)
        
        # Rename button
        self.rename_btn = QPushButton("重命名")
        self.rename_btn.clicked.connect(self._on_rename_clicked)
        layout.addWidget(self.rename_btn)
        
        # Status label
        self.status_label = QLabel("无说话人数据")
        self.status_label.setStyleSheet("color: #666; font-size: 11px;")
        layout.addWidget(self.status_label)
    
    def set_meeting(self, meeting_id: Optional[str]):
        """Set the current meeting ID for speaker operations."""
        self._current_meeting_id = meeting_id
        if meeting_id:
            asyncio.create_task(self._refresh())
    
    def _on_refresh_timer(self):
        if self._current_meeting_id:
            asyncio.create_task(self._refresh())
    
    async def _refresh(self):
        if not self._current_meeting_id:
            return
        
        try:
            result = await self._get_speakers(self._current_meeting_id)
            if result.get("ok"):
                speakers = result.get("speakers", [])
                self._update_ui(speakers)
        except Exception as e:
            self.status_label.setText(f"刷新失败: {e}")
    
    async def _get_speakers(self, meeting_id: str) -> dict:
        """Get speakers for a meeting."""
        session = self.client._session
        if session is None:
            session = await self.client._get_session()
        
        async with session.get(
            f"{self.client.base_url}/v2/meetings/{meeting_id}/speakers",
        ) as resp:
            return await resp.json()
    
    def _update_ui(self, speakers: list):
        self.speaker_list.clear()
        
        if not speakers:
            self.status_label.setText("无说话人数据")
            return
        
        for speaker in speakers:
            speaker_id = speaker.get("speaker_cluster_id", "unknown")
            name = speaker.get("speaker_name", "")
            segment_count = speaker.get("segment_count", 0)
            confidence = speaker.get("avg_confidence", 0) or 0
            
            display_name = name if name else speaker_id.replace("speaker_", "说话人 ")
            confidence_pct = int(confidence * 100)
            
            item_text = f"🎤 {display_name} ({segment_count}段, {confidence_pct}%)"
            item = QListWidgetItem(item_text)
            item.setData(Qt.UserRole, {
                "speaker_cluster_id": speaker_id,
                "speaker_name": name,
                "segment_count": segment_count,
            })
            
            self.speaker_list.addItem(item)
        
        self.status_label.setText(f"{len(speakers)} 位说话人")
    
    def _on_speaker_double_clicked(self, item: QListWidgetItem):
        self._do_rename(item)
    
    def _on_rename_clicked(self):
        item = self.speaker_list.currentItem()
        if item:
            self._do_rename(item)
    
    def _do_rename(self, item: QListWidgetItem):
        from PySide6.QtWidgets import QInputDialog
        
        data = item.data(Qt.UserRole)
        speaker_id = data.get("speaker_cluster_id")
        current_name = data.get("speaker_name", "")
        
        text, ok = QInputDialog.getText(
            self,
            "重命名说话人",
            f"输入新名称 ({speaker_id}):",
            text=current_name,
        )
        
        if ok and text.strip():
            asyncio.create_task(self._rename_speaker(speaker_id, text.strip()))
    
    async def _rename_speaker(self, speaker_cluster_id: str, new_name: str):
        if not self._current_meeting_id:
            return
        
        try:
            session = self.client._session
            if session is None:
                session = await self.client._get_session()
            
            async with session.patch(
                f"{self.client.base_url}/v2/meetings/{self._current_meeting_id}/speakers/{speaker_cluster_id}",
                json={"speaker_name": new_name},
            ) as resp:
                result = await resp.json()
            
            if result.get("ok"):
                self.status_label.setText(f"已重命名为: {new_name}")
                await self._refresh()
            else:
                self.status_label.setText(f"重命名失败: {result.get('error')}")
        except Exception as e:
            self.status_label.setText(f"重命名失败: {e}")


class ImagePanelWidget(QWidget):
    """M5: Image panel for viewing uploaded images and analysis results"""
    
    def __init__(self, client: MeetingClient, parent=None):
        super().__init__(parent)
        self.client = client
        self._current_meeting_id: Optional[str] = None
        self._build_ui()
        
        # Auto-refresh timer
        self._refresh_timer = QTimer(self)
        self._refresh_timer.timeout.connect(self._on_refresh_timer)
        self._refresh_timer.start(15000)  # Refresh every 15 seconds

    def _build_ui(self):
        layout = QVBoxLayout(self)
        
        # Title
        title = QLabel("📷 会议图片")
        title.setFont(QFont("Microsoft YaHei", 11, QFont.Bold))
        layout.addWidget(title)
        
        # Image list
        self.image_list = QListWidget()
        self.image_list.setMaximumHeight(150)
        self.image_list.itemDoubleClicked.connect(self._on_image_double_clicked)
        layout.addWidget(self.image_list)
        
        # Buttons
        btn_layout = QHBoxLayout()
        self.refresh_btn = QPushButton("刷新")
        self.refresh_btn.clicked.connect(self._on_refresh_clicked)
        self.upload_btn = QPushButton("上传")
        self.upload_btn.clicked.connect(self._on_upload_clicked)
        btn_layout.addWidget(self.refresh_btn)
        btn_layout.addWidget(self.upload_btn)
        layout.addLayout(btn_layout)
        
        # Status label
        self.status_label = QLabel("无图片")
        self.status_label.setStyleSheet("color: #666; font-size: 11px;")
        layout.addWidget(self.status_label)
    
    def set_meeting(self, meeting_id: Optional[str]):
        """Set the current meeting ID for image operations."""
        self._current_meeting_id = meeting_id
        if meeting_id:
            asyncio.create_task(self._refresh())
    
    def _on_refresh_timer(self):
        if self._current_meeting_id:
            asyncio.create_task(self._refresh())
    
    def _on_refresh_clicked(self):
        if self._current_meeting_id:
            asyncio.create_task(self._refresh())
    
    async def _refresh(self):
        if not self._current_meeting_id:
            return
        
        try:
            result = await self._get_images(self._current_meeting_id)
            if result.get("ok"):
                images = result.get("images", [])
                self._update_ui(images)
        except Exception as e:
            self.status_label.setText(f"刷新失败: {e}")
    
    async def _get_images(self, meeting_id: str) -> dict:
        """Get images for a meeting."""
        session = self.client._session
        if session is None:
            session = await self.client._get_session()
        
        async with session.get(
            f"{self.client.base_url}/v2/meetings/{meeting_id}/images",
        ) as resp:
            return await resp.json()
    
    def _update_ui(self, images: list):
        self.image_list.clear()
        
        if not images:
            self.status_label.setText("无图片")
            return
        
        for img in images:
            image_id = img.get("image_id", "unknown")
            seq = img.get("seq", 0)
            filename = img.get("filename", "unknown")
            size_kb = (img.get("size_bytes") or 0) // 1024
            analysis_status = img.get("analysis_status", "pending")
            width = img.get("width")
            height = img.get("height")
            
            # Analysis status icon
            analysis_icon = {
                "pending": "⏳",
                "analyzing": "🔄",
                "analyzed": "✅",
                "analysis_failed": "❌",
            }.get(analysis_status, "❓")
            
            # Format display
            dimensions = f"{width}x{height}" if width and height else ""
            item_text = f"{analysis_icon} #{seq}: {filename[:20]} ({size_kb}KB)"
            if dimensions:
                item_text += f" [{dimensions}]"
            
            item = QListWidgetItem(item_text)
            item.setData(Qt.UserRole, {
                "image_id": image_id,
                "filename": filename,
                "seq": seq,
                "analysis_status": analysis_status,
            })
            
            self.image_list.addItem(item)
        
        self.status_label.setText(f"{len(images)} 张图片")
    
    def _on_image_double_clicked(self, item: QListWidgetItem):
        data = item.data(Qt.UserRole)
        image_id = data.get("image_id")
        if image_id and self._current_meeting_id:
            asyncio.create_task(self._view_image(image_id))
    
    async def _view_image(self, image_id: str):
        """Open image in default viewer or show in dialog."""
        if not self._current_meeting_id:
            return
        
        try:
            session = self.client._session
            if session is None:
                session = await self.client._get_session()
            
            # Get image details
            async with session.get(
                f"{self.client.base_url}/v2/meetings/{self._current_meeting_id}/images/{image_id}",
            ) as resp:
                result = await resp.json()
            
            if result.get("ok"):
                img = result.get("image", {})
                self._show_image_dialog(img)
            else:
                self.status_label.setText(f"获取图片失败: {result.get('error')}")
        except Exception as e:
            self.status_label.setText(f"查看失败: {e}")
    
    def _show_image_dialog(self, img: dict):
        """Show image details in a dialog."""
        from PySide6.QtWidgets import QDialog, QDialogButtonBox, QVBoxLayout, QLabel as QLabel2
        
        dialog = QDialog(self)
        dialog.setWindowTitle(f"图片详情 - {img.get('filename', 'unknown')}")
        dialog.setMinimumSize(400, 300)
        
        layout = QVBoxLayout(dialog)
        
        # Image info
        info_parts = [
            f"<b>文件名:</b> {img.get('filename', 'unknown')}",
            f"<b>序列:</b> {img.get('seq', 0)}",
            f"<b>大小:</b> {(img.get('size_bytes') or 0) // 1024} KB",
            f"<b>尺寸:</b> {img.get('width', '?')} x {img.get('height', '?')}",
            f"<b>格式:</b> {img.get('format', 'unknown')}",
            f"<b>分析状态:</b> {img.get('analysis_status', 'pending')}",
        ]
        
        if img.get('captured_at'):
            info_parts.append(f"<b>拍摄时间:</b> {img.get('captured_at')}")
        
        if img.get('device_id'):
            info_parts.append(f"<b>设备:</b> {img.get('device_id')}")
        
        # Analysis result
        analysis_result = img.get('analysis_result')
        if analysis_result:
            info_parts.append("<hr>")
            info_parts.append("<b>分析结果:</b>")
            if isinstance(analysis_result, dict):
                for key, value in analysis_result.items():
                    if isinstance(value, (str, int, float, bool)):
                        info_parts.append(f"  {key}: {value}")
                    elif isinstance(value, list):
                        info_parts.append(f"  {key}: {', '.join(str(v) for v in value[:5])}")
            else:
                info_parts.append(f"  {analysis_result}")
        
        info_label = QLabel2("<br>".join(info_parts))
        info_label.setWordWrap(True)
        layout.addWidget(info_label)
        
        # Buttons
        buttons = QDialogButtonBox(QDialogButtonBox.Close)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        
        dialog.exec()
    
    def _on_upload_clicked(self):
        """Upload image for current meeting."""
        if not self._current_meeting_id:
            self.status_label.setText("请先开始会议")
            return
        
        from PySide6.QtWidgets import QFileDialog
        
        file_path, _ = QFileDialog.getOpenFileName(
            self,
            "选择图片",
            "",
            "Images (*.png *.jpg *.jpeg *.webp);;All Files (*)",
        )
        
        if file_path:
            asyncio.create_task(self._upload_image(file_path))
    
    async def _upload_image(self, file_path: str):
        """Upload an image file."""
        if not self._current_meeting_id:
            return
        
        try:
            import os
            from pathlib import Path
            
            # Read file
            p = Path(file_path)
            filename = p.name
            
            with open(file_path, "rb") as f:
                data = f.read()
            
            # Build multipart form
            import aiohttp
            session = self.client._session
            if session is None:
                session = await self.client._get_session()
            
            # Create form data
            form = aiohttp.FormData()
            form.add_field("image", data, filename=filename, content_type="application/octet-stream")
            form.add_field("filename", filename.encode())
            
            async with session.post(
                f"{self.client.base_url}/v2/meetings/{self._current_meeting_id}/images:upload",
                data=form,
            ) as resp:
                result = await resp.json()
            
            if result.get("ok"):
                self.status_label.setText(f"已上传: {filename}")
                await self._refresh()
            else:
                self.status_label.setText(f"上传失败: {result.get('error')}")
        except Exception as e:
            self.status_label.setText(f"上传失败: {e}")


class RefinedTranscriptWidget(QWidget):
    """M4: Refined transcript view with speaker names"""
    
    def __init__(self, client: MeetingClient, parent=None):
        super().__init__(parent)
        self.client = client
        self._current_meeting_id: Optional[str] = None
        self._build_ui()

    def _build_ui(self):
        layout = QVBoxLayout(self)
        
        # Title
        header = QHBoxLayout()
        title = QLabel("📄 稳定稿")
        title.setFont(QFont("Microsoft YaHei", 11, QFont.Bold))
        header.addWidget(title)
        
        self.refresh_btn = QPushButton("刷新")
        self.refresh_btn.clicked.connect(self._on_refresh_clicked)
        header.addWidget(self.refresh_btn)
        layout.addLayout(header)
        
        # Transcript view
        self.transcript_view = QTextEdit()
        self.transcript_view.setReadOnly(True)
        self.transcript_view.setFont(QFont("Microsoft YaHei", 10))
        layout.addWidget(self.transcript_view)
        
        # Status
        self.status_label = QLabel("无转写数据")
        self.status_label.setStyleSheet("color: #666; font-size: 11px;")
        layout.addWidget(self.status_label)
    
    def set_meeting(self, meeting_id: Optional[str]):
        """Set the current meeting ID."""
        self._current_meeting_id = meeting_id
        if meeting_id:
            asyncio.create_task(self._refresh())
    
    def _on_refresh_clicked(self):
        if self._current_meeting_id:
            asyncio.create_task(self._refresh())
    
    async def _refresh(self):
        if not self._current_meeting_id:
            return
        
        try:
            session = self.client._session
            if session is None:
                session = await self.client._get_session()
            
            async with session.get(
                f"{self.client.base_url}/v2/meetings/{self._current_meeting_id}/refined",
            ) as resp:
                result = await resp.json()
            
            if result.get("ok"):
                segments = result.get("segments", [])
                self._update_ui(segments)
            else:
                self.status_label.setText(f"获取失败: {result.get('error')}")
        except Exception as e:
            self.status_label.setText(f"刷新失败: {e}")
    
    def _update_ui(self, segments: list):
        self.transcript_view.clear()
        
        if not segments:
            self.status_label.setText("无转写数据")
            return
        
        # Build formatted transcript
        html_parts = []
        for seg in segments:
            speaker_name = seg.get("speaker_name") or seg.get("speaker_cluster_id", "").replace("speaker_", "说话人 ")
            text = seg.get("text", "")
            start_ts = seg.get("start_ts", 0)
            
            # Format timestamp
            mins = int(start_ts // 60)
            secs = int(start_ts % 60)
            timestamp = f"[{mins:02d}:{secs:02d}]"
            
            # Format line with speaker
            line = f'<span style="color: #666;">{timestamp}</span> <b style="color: #2196F3;">{speaker_name}:</b> {text}'
            html_parts.append(line)
        
        self.transcript_view.setHtml("<br>".join(html_parts))
        self.status_label.setText(f"{len(segments)} 段转写")


class MeetingConsoleWindow(QMainWindow):
    """Main window for meeting console"""

    def __init__(self, cfg: dict):
        super().__init__()
        self.settings = cfg
        self._meeting_client: Optional[MeetingClient] = None

        self.setWindowTitle("Meeting Console - Voice Assistant Bridge V2")
        self.resize(1400, 800)
        self._build_ui()

    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        
        layout = QHBoxLayout(central)
        
        # Left: Meeting console
        self.console = MeetingConsoleWidget(self._get_client())
        self.console.meeting_started.connect(self._on_meeting_started)
        self.console.meeting_ended.connect(self._on_meeting_ended)
        
        # Middle panel: Speaker panel and Refined transcript
        middle_panel = QWidget()
        middle_layout = QVBoxLayout(middle_panel)
        
        # M4: Speaker panel
        self.speaker_panel = SpeakerPanelWidget(self._get_client())
        middle_layout.addWidget(self.speaker_panel)
        
        # M4: Refined transcript view
        self.refined_transcript = RefinedTranscriptWidget(self._get_client())
        middle_layout.addWidget(self.refined_transcript, stretch=1)
        
        # Right panel: History, Backup status, Transcription tasks, and Images
        right_panel = QWidget()
        right_layout = QVBoxLayout(right_panel)
        
        # History panel
        self.history = MeetingHistoryWidget(self._get_client())
        right_layout.addWidget(self.history)
        
        # M2: Backup status panel
        self.backup_status = BackupStatusWidget(self._get_client())
        right_layout.addWidget(self.backup_status)
        
        # M3: Transcription task panel
        self.transcription_tasks = TranscriptionTaskWidget(self._get_client())
        right_layout.addWidget(self.transcription_tasks)
        
        # M5: Image panel
        self.image_panel = ImagePanelWidget(self._get_client())
        right_layout.addWidget(self.image_panel)
        
        # Splitter
        splitter = QSplitter(Qt.Horizontal)
        splitter.addWidget(self.console)
        splitter.addWidget(middle_panel)
        splitter.addWidget(right_panel)
        splitter.setSizes([400, 500, 300])
        
        layout.addWidget(splitter)
        
        # Initial refresh
        QTimer.singleShot(500, lambda: asyncio.create_task(self._initial_refresh()))

    async def _initial_refresh(self):
        await self.history._refresh()
        await self.backup_status._refresh()
        await self.transcription_tasks._refresh()
        await self.image_panel._refresh()

    def _get_client(self) -> MeetingClient:
        if self._meeting_client is None:
            base_url = self.settings.get("local_gateway_url", "http://127.0.0.1:8765")
            self._meeting_client = MeetingClient(base_url)
        return self._meeting_client

    def _on_meeting_started(self, meeting_id: str):
        self.statusBar().showMessage(f"Meeting started: {meeting_id}")
        # Update speaker panel with current meeting
        self.speaker_panel.set_meeting(meeting_id)
        self.refined_transcript.set_meeting(meeting_id)
        # M5: Update image panel
        self.image_panel.set_meeting(meeting_id)

    def _on_meeting_ended(self, meeting_id: str):
        self.statusBar().showMessage(f"Meeting ended: {meeting_id}")
        asyncio.create_task(self._refresh_after_meeting())

    async def _refresh_after_meeting(self):
        await self.history._refresh()
        await self.backup_status._refresh()
        await self.transcription_tasks._refresh()
        # Refresh speaker panel and transcript for the ended meeting
        if self.console.current_meeting_id:
            self.speaker_panel.set_meeting(self.console.current_meeting_id)
            self.refined_transcript.set_meeting(self.console.current_meeting_id)
            # M5: Refresh image panel
            self.image_panel.set_meeting(self.console.current_meeting_id)

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
