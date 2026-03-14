"""
Voice Assistant Bridge - Meeting Mode Support (V2)

Implements:
- Meeting sessions management
- Event streaming
- Audio segments tracking
"""

from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

# Meeting status constants
MEETING_STATUS_IDLE = "IDLE"
MEETING_STATUS_PREP = "MEETING_PREP"
MEETING_STATUS_ACTIVE = "MEETING_ACTIVE"
MEETING_STATUS_ENDING = "MEETING_ENDING"
MEETING_STATUS_ARCHIVED = "MEETING_ARCHIVED"
MEETING_STATUS_REFINING = "MEETING_REFINING"
MEETING_STATUS_READY = "MEETING_READY"

MEETING_TERMINAL_STATUSES = {MEETING_STATUS_ARCHIVED, MEETING_STATUS_READY}

# Event type constants
EVT_MEETING_MODE_ON = "meeting.mode_on"
EVT_MEETING_MODE_OFF = "meeting.mode_off"
EVT_AUDIO_SEGMENT_STARTED = "audio.segment.started"
EVT_AUDIO_SEGMENT_SEALED = "audio.segment.sealed"
EVT_AUDIO_SEGMENT_UPLOADED = "audio.segment.uploaded"
EVT_AUDIO_SEGMENT_UPLOAD_FAILED = "audio.segment.upload_failed"
EVT_STT_PARTIAL = "stt.partial"
EVT_STT_FINAL = "stt.final"
EVT_STT_SEGMENT_CLOSED = "stt.segment_closed"
EVT_WAKEWORD_DETECTED = "wakeword.detected"
EVT_WAKEWORD_CMD_WINDOW_STARTED = "wakeword.command_window.started"
EVT_WAKEWORD_CMD_WINDOW_ENDED = "wakeword.command_window.ended"
EVT_WAKEWORD_COOLDOWN_STARTED = "wakeword.cooldown.started"
EVT_WAKEWORD_COOLDOWN_ENDED = "wakeword.cooldown.ended"

# Upload status constants
UPLOAD_STATUS_PENDING = "pending"
UPLOAD_STATUS_UPLOADED = "uploaded"
UPLOAD_STATUS_FAILED = "failed"
UPLOAD_STATUS_UPLOADING = "uploading"

# Transcription job status constants
JOB_STATUS_QUEUED = "queued"
JOB_STATUS_RUNNING = "running"
JOB_STATUS_SUCCESS = "success"
JOB_STATUS_FAILED = "failed"
JOB_STATUS_CANCELLED = "cancelled"

# Transcription event types
EVT_TRANSCRIPTION_STARTED = "transcription.started"
EVT_TRANSCRIPTION_COMPLETED = "transcription.completed"
EVT_TRANSCRIPTION_FAILED = "transcription.failed"


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


class MeetingStore:
    """SQLite-backed store for meeting sessions and events."""

    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        self._init_tables()

    def _init_tables(self) -> None:
        """Create tables if not exist."""
        with self.lock:
            # Meeting sessions
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS meeting_sessions (
                    meeting_id TEXT PRIMARY KEY,
                    client_id TEXT NOT NULL,
                    session_id TEXT,
                    status TEXT NOT NULL DEFAULT 'IDLE',
                    mode TEXT DEFAULT NULL,
                    started_at TEXT,
                    ended_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    meta_json TEXT
                )
            """)
            # Meeting events
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS meeting_events (
                    event_id TEXT PRIMARY KEY,
                    meeting_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    seq INTEGER,
                    ts_client TEXT,
                    ts_server TEXT NOT NULL,
                    payload TEXT,
                    created_at TEXT NOT NULL
                )
            """)
            # Audio segments
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS audio_segments (
                    segment_id TEXT PRIMARY KEY,
                    meeting_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    local_path TEXT,
                    checksum TEXT,
                    size_bytes INTEGER,
                    duration_ms INTEGER,
                    started_at TEXT,
                    sealed_at TEXT,
                    upload_status TEXT DEFAULT 'pending',
                    uploaded_at TEXT,
                    upload_error TEXT,
                    created_at TEXT NOT NULL
                )
            """)
            # Transcription jobs (M3)
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS transcription_jobs (
                    job_id TEXT PRIMARY KEY,
                    meeting_id TEXT NOT NULL,
                    engine TEXT NOT NULL DEFAULT 'faster-whisper',
                    model TEXT DEFAULT 'small',
                    status TEXT NOT NULL DEFAULT 'queued',
                    progress_percent INTEGER DEFAULT 0,
                    output_path TEXT,
                    revision INTEGER DEFAULT 1,
                    error_message TEXT,
                    started_at TEXT,
                    completed_at TEXT,
                    created_at TEXT NOT NULL
                )
            """)
            # Indexes
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_meeting_events_meeting ON meeting_events(meeting_id, ts_server)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_meeting_events_type ON meeting_events(event_type)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_meeting_sessions_status ON meeting_sessions(status)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_audio_segments_meeting ON audio_segments(meeting_id, seq)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_audio_segments_upload ON audio_segments(upload_status)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_transcription_jobs_meeting ON transcription_jobs(meeting_id)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_transcription_jobs_status ON transcription_jobs(status)")
            self.conn.commit()

    @staticmethod
    def _dict(row: sqlite3.Row) -> dict[str, Any]:
        return {k: row[k] for k in row.keys()}

    # --- Meeting Sessions ---
    
    def create_meeting(
        self,
        *,
        client_id: str,
        session_id: Optional[str] = None,
        meta: Optional[dict] = None,
    ) -> dict[str, Any]:
        """Create a new meeting session."""
        meeting_id = f"mtg-{uuid.uuid4().hex}"
        ts = now_iso()
        row = {
            "meeting_id": meeting_id,
            "client_id": client_id,
            "session_id": session_id,
            "status": MEETING_STATUS_IDLE,
            "mode": None,
            "started_at": None,
            "ended_at": None,
            "created_at": ts,
            "updated_at": ts,
            "meta_json": json.dumps(meta, ensure_ascii=False) if meta else None,
        }
        with self.lock:
            cols = ", ".join(row.keys())
            vals = ", ".join("?" for _ in row)
            self.conn.execute(f"INSERT INTO meeting_sessions ({cols}) VALUES ({vals})", list(row.values()))
            self.conn.commit()
        return row

    def get_meeting(self, meeting_id: str) -> Optional[dict[str, Any]]:
        """Get meeting by ID."""
        with self.lock:
            row = self.conn.execute(
                "SELECT * FROM meeting_sessions WHERE meeting_id=?", (meeting_id,)
            ).fetchone()
        return self._dict(row) if row else None

    def update_meeting(self, meeting_id: str, **fields: Any) -> None:
        """Update meeting fields."""
        if not fields:
            return
        fields["updated_at"] = now_iso()
        keys = list(fields.keys())
        sets = ", ".join(f"{k}=?" for k in keys)
        args = [fields[k] for k in keys] + [meeting_id]
        with self.lock:
            self.conn.execute(f"UPDATE meeting_sessions SET {sets} WHERE meeting_id=?", args)
            self.conn.commit()

    def list_meetings(
        self,
        *,
        status: Optional[str] = None,
        client_id: Optional[str] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> list[dict[str, Any]]:
        """List meetings with optional filters."""
        with self.lock:
            query = "SELECT * FROM meeting_sessions WHERE 1=1"
            args: list[Any] = []
            if status:
                query += " AND status=?"
                args.append(status)
            if client_id:
                query += " AND client_id=?"
                args.append(client_id)
            query += " ORDER BY created_at DESC LIMIT ? OFFSET ?"
            args.extend([limit, offset])
            rows = self.conn.execute(query, args).fetchall()
        return [self._dict(r) for r in rows]

    def get_active_meeting(self, client_id: str) -> Optional[dict[str, Any]]:
        """Get the currently active meeting for a client."""
        with self.lock:
            row = self.conn.execute(
                "SELECT * FROM meeting_sessions WHERE client_id=? AND status IN (?, ?) ORDER BY created_at DESC LIMIT 1",
                (client_id, MEETING_STATUS_PREP, MEETING_STATUS_ACTIVE),
            ).fetchone()
        return self._dict(row) if row else None

    # --- Meeting Events ---

    def append_event(
        self,
        *,
        meeting_id: str,
        source: str,
        event_type: str,
        seq: Optional[int] = None,
        ts_client: Optional[str] = None,
        payload: Optional[dict] = None,
    ) -> dict[str, Any]:
        """Append an event to the meeting event log."""
        event_id = f"evt-{uuid.uuid4().hex}"
        ts_server = now_iso()
        ts = now_iso()
        row = {
            "event_id": event_id,
            "meeting_id": meeting_id,
            "source": source,
            "event_type": event_type,
            "seq": seq,
            "ts_client": ts_client,
            "ts_server": ts_server,
            "payload": json.dumps(payload, ensure_ascii=False) if payload else None,
            "created_at": ts,
        }
        with self.lock:
            cols = ", ".join(row.keys())
            vals = ", ".join("?" for _ in row)
            self.conn.execute(f"INSERT INTO meeting_events ({cols}) VALUES ({vals})", list(row.values()))
            self.conn.commit()
        return row

    def get_events(
        self,
        meeting_id: str,
        *,
        event_type: Optional[str] = None,
        after_seq: Optional[int] = None,
        limit: int = 100,
    ) -> list[dict[str, Any]]:
        """Get events for a meeting."""
        with self.lock:
            query = "SELECT * FROM meeting_events WHERE meeting_id=?"
            args: list[Any] = [meeting_id]
            if event_type:
                query += " AND event_type=?"
                args.append(event_type)
            if after_seq is not None:
                query += " AND seq > ?"
                args.append(after_seq)
            query += " ORDER BY seq ASC LIMIT ?"
            args.append(limit)
            rows = self.conn.execute(query, args).fetchall()
        return [self._dict(r) for r in rows]

    # --- Audio Segments ---

    def create_audio_segment(
        self,
        *,
        meeting_id: str,
        seq: int,
        segment_id: Optional[str] = None,
        local_path: Optional[str] = None,
    ) -> dict[str, Any]:
        """Create an audio segment record."""
        segment_id = segment_id or f"seg-{uuid.uuid4().hex}"
        ts = now_iso()
        row = {
            "segment_id": segment_id,
            "meeting_id": meeting_id,
            "seq": seq,
            "local_path": local_path,
            "checksum": None,
            "size_bytes": None,
            "duration_ms": None,
            "started_at": ts,
            "sealed_at": None,
            "upload_status": "pending",
            "uploaded_at": None,
            "upload_error": None,
            "created_at": ts,
        }
        with self.lock:
            cols = ", ".join(row.keys())
            vals = ", ".join("?" for _ in row)
            self.conn.execute(f"INSERT INTO audio_segments ({cols}) VALUES ({vals})", list(row.values()))
            self.conn.commit()
        return row

    def update_audio_segment(self, segment_id: str, **fields: Any) -> None:
        """Update audio segment fields."""
        if not fields:
            return
        keys = list(fields.keys())
        sets = ", ".join(f"{k}=?" for k in keys)
        args = [fields[k] for k in keys] + [segment_id]
        with self.lock:
            self.conn.execute(f"UPDATE audio_segments SET {sets} WHERE segment_id=?", args)
            self.conn.commit()

    def get_audio_segments(self, meeting_id: str) -> list[dict[str, Any]]:
        """Get all audio segments for a meeting."""
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM audio_segments WHERE meeting_id=? ORDER BY seq ASC",
                (meeting_id,),
            ).fetchall()
        return [self._dict(r) for r in rows]

    def get_audio_segment(self, segment_id: str) -> Optional[dict[str, Any]]:
        """Get a single audio segment by ID."""
        with self.lock:
            row = self.conn.execute(
                "SELECT * FROM audio_segments WHERE segment_id=?",
                (segment_id,),
            ).fetchone()
        return self._dict(row) if row else None

    def get_pending_audio_segments(self, meeting_id: str) -> list[dict[str, Any]]:
        """Get pending (not yet uploaded) audio segments."""
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM audio_segments WHERE meeting_id=? AND upload_status='pending' ORDER BY seq ASC",
                (meeting_id,),
            ).fetchall()
        return [self._dict(r) for r in rows]

    def get_failed_audio_segments(self, meeting_id: str) -> list[dict[str, Any]]:
        """Get failed audio segments for retry."""
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM audio_segments WHERE meeting_id=? AND upload_status='failed' ORDER BY seq ASC",
                (meeting_id,),
            ).fetchall()
        return [self._dict(r) for r in rows]

    # --- Transcription Jobs (M3) ---

    def create_transcription_job(
        self,
        *,
        meeting_id: str,
        engine: str = "faster-whisper",
        model: str = "small",
    ) -> dict[str, Any]:
        """Create a transcription job for a meeting."""
        job_id = f"job-{uuid.uuid4().hex}"
        ts = now_iso()
        row = {
            "job_id": job_id,
            "meeting_id": meeting_id,
            "engine": engine,
            "model": model,
            "status": JOB_STATUS_QUEUED,
            "progress_percent": 0,
            "output_path": None,
            "revision": 1,
            "error_message": None,
            "started_at": None,
            "completed_at": None,
            "created_at": ts,
        }
        with self.lock:
            cols = ", ".join(row.keys())
            vals = ", ".join("?" for _ in row)
            self.conn.execute(f"INSERT INTO transcription_jobs ({cols}) VALUES ({vals})", list(row.values()))
            self.conn.commit()
        return row

    def get_transcription_job(self, job_id: str) -> Optional[dict[str, Any]]:
        """Get a transcription job by ID."""
        with self.lock:
            row = self.conn.execute(
                "SELECT * FROM transcription_jobs WHERE job_id=?",
                (job_id,),
            ).fetchone()
        return self._dict(row) if row else None

    def get_transcription_jobs_for_meeting(self, meeting_id: str) -> list[dict[str, Any]]:
        """Get all transcription jobs for a meeting."""
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM transcription_jobs WHERE meeting_id=? ORDER BY created_at DESC",
                (meeting_id,),
            ).fetchall()
        return [self._dict(r) for r in rows]

    def get_latest_transcription_job(self, meeting_id: str) -> Optional[dict[str, Any]]:
        """Get the latest transcription job for a meeting."""
        with self.lock:
            row = self.conn.execute(
                "SELECT * FROM transcription_jobs WHERE meeting_id=? ORDER BY created_at DESC LIMIT 1",
                (meeting_id,),
            ).fetchone()
        return self._dict(row) if row else None

    def get_queued_transcription_jobs(self, limit: int = 10) -> list[dict[str, Any]]:
        """Get queued transcription jobs for processing."""
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM transcription_jobs WHERE status=? ORDER BY created_at ASC LIMIT ?",
                (JOB_STATUS_QUEUED, limit),
            ).fetchall()
        return [self._dict(r) for r in rows]

    def update_transcription_job(self, job_id: str, **fields: Any) -> None:
        """Update transcription job fields."""
        if not fields:
            return
        keys = list(fields.keys())
        sets = ", ".join(f"{k}=?" for k in keys)
        args = [fields[k] for k in keys] + [job_id]
        with self.lock:
            self.conn.execute(f"UPDATE transcription_jobs SET {sets} WHERE job_id=?", args)
            self.conn.commit()

    def close(self) -> None:
        with self.lock:
            self.conn.close()


def build_event_envelope(
    event: dict[str, Any],
    *,
    schema_version: str = "v2",
) -> dict[str, Any]:
    """Build the V2 event envelope format."""
    payload = None
    if event.get("payload"):
        try:
            payload = json.loads(event["payload"])
        except (json.JSONDecodeError, TypeError):
            payload = event["payload"]

    return {
        "schema_version": schema_version,
        "event_id": event["event_id"],
        "meeting_id": event["meeting_id"],
        "source": event["source"],
        "event_type": event["event_type"],
        "seq": event.get("seq"),
        "ts_client": event.get("ts_client"),
        "ts_server": event["ts_server"],
        "payload": payload,
    }
