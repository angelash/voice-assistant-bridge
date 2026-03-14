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

# M4: Speaker diarization event types
EVT_SPEAKER_IDENTIFIED = "speaker.identified"
EVT_SPEAKER_RENAMED = "speaker.renamed"

# M4: Speaker name source constants
SPEAKER_SOURCE_DIARIZATION = "diarization"  # Auto-detected by diarization
SPEAKER_SOURCE_MANUAL = "manual"  # Manually assigned
SPEAKER_SOURCE_HISTORY = "history"  # Reused from historical mapping

# M5: Image upload event types
EVT_IMAGE_UPLOADED = "image.uploaded"
EVT_IMAGE_UPLOAD_FAILED = "image.upload_failed"
EVT_IMAGE_ANALYSIS_STARTED = "image.analysis.started"
EVT_IMAGE_ANALYSIS_COMPLETED = "image.analysis.completed"
EVT_IMAGE_ANALYSIS_FAILED = "image.analysis.failed"

# M5: Image upload status constants
IMAGE_STATUS_UPLOADING = "uploading"
IMAGE_STATUS_UPLOADED = "uploaded"
IMAGE_STATUS_FAILED = "failed"
IMAGE_STATUS_ANALYZING = "analyzing"
IMAGE_STATUS_ANALYZED = "analyzed"
IMAGE_STATUS_ANALYSIS_FAILED = "analysis_failed"


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
            # M4: Refined meeting segments with speaker info
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS meeting_segments_refined (
                    segment_ref_id TEXT PRIMARY KEY,
                    meeting_id TEXT NOT NULL,
                    audio_segment_id TEXT,
                    seq INTEGER NOT NULL,
                    start_ts REAL NOT NULL,
                    end_ts REAL NOT NULL,
                    text TEXT NOT NULL,
                    speaker_cluster_id TEXT,
                    speaker_confidence REAL,
                    speaker_name TEXT,
                    speaker_name_source TEXT,
                    revision INTEGER DEFAULT 1,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """)
            # M4: Speaker name mappings (audit history)
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS speaker_name_mappings (
                    mapping_id TEXT PRIMARY KEY,
                    meeting_id TEXT NOT NULL,
                    speaker_cluster_id TEXT NOT NULL,
                    old_name TEXT,
                    new_name TEXT NOT NULL,
                    source TEXT NOT NULL,
                    changed_by TEXT,
                    changed_at TEXT NOT NULL,
                    notes TEXT
                )
            """)
            # M5: Meeting images (original image uploads with analysis)
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS meeting_images (
                    image_id TEXT PRIMARY KEY,
                    meeting_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    original_path TEXT NOT NULL,
                    thumbnail_path TEXT,
                    filename TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    checksum TEXT NOT NULL,
                    width INTEGER,
                    height INTEGER,
                    format TEXT,
                    device_id TEXT,
                    captured_at TEXT,
                    uploaded_at TEXT NOT NULL,
                    upload_status TEXT DEFAULT 'uploaded',
                    analysis_status TEXT DEFAULT 'pending',
                    analysis_result TEXT,
                    analysis_error TEXT,
                    analysis_at TEXT,
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
            # M4 indexes
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_refined_segments_meeting ON meeting_segments_refined(meeting_id, seq)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_refined_segments_speaker ON meeting_segments_refined(meeting_id, speaker_cluster_id)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_speaker_mappings_meeting ON speaker_name_mappings(meeting_id)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_speaker_mappings_cluster ON speaker_name_mappings(speaker_cluster_id)")
            # M5 indexes
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_meeting_images_meeting ON meeting_images(meeting_id, seq)")
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_meeting_images_status ON meeting_images(upload_status, analysis_status)")
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

    # --- M4: Refined Meeting Segments ---

    def create_refined_segment(
        self,
        *,
        meeting_id: str,
        seq: int,
        start_ts: float,
        end_ts: float,
        text: str,
        audio_segment_id: Optional[str] = None,
        speaker_cluster_id: Optional[str] = None,
        speaker_confidence: Optional[float] = None,
        speaker_name: Optional[str] = None,
        speaker_name_source: Optional[str] = None,
        revision: int = 1,
    ) -> dict[str, Any]:
        """Create a refined segment with speaker info."""
        segment_ref_id = f"sref-{uuid.uuid4().hex}"
        ts = now_iso()
        row = {
            "segment_ref_id": segment_ref_id,
            "meeting_id": meeting_id,
            "audio_segment_id": audio_segment_id,
            "seq": seq,
            "start_ts": start_ts,
            "end_ts": end_ts,
            "text": text,
            "speaker_cluster_id": speaker_cluster_id,
            "speaker_confidence": speaker_confidence,
            "speaker_name": speaker_name,
            "speaker_name_source": speaker_name_source,
            "revision": revision,
            "created_at": ts,
            "updated_at": ts,
        }
        with self.lock:
            cols = ", ".join(row.keys())
            vals = ", ".join("?" for _ in row)
            self.conn.execute(f"INSERT INTO meeting_segments_refined ({cols}) VALUES ({vals})", list(row.values()))
            self.conn.commit()
        return row

    def get_refined_segments(self, meeting_id: str) -> list[dict[str, Any]]:
        """Get all refined segments for a meeting."""
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM meeting_segments_refined WHERE meeting_id=? ORDER BY seq ASC",
                (meeting_id,),
            ).fetchall()
        return [self._dict(r) for r in rows]

    def get_refined_segment(self, segment_ref_id: str) -> Optional[dict[str, Any]]:
        """Get a single refined segment by ID."""
        with self.lock:
            row = self.conn.execute(
                "SELECT * FROM meeting_segments_refined WHERE segment_ref_id=?",
                (segment_ref_id,),
            ).fetchone()
        return self._dict(row) if row else None

    def update_refined_segment(self, segment_ref_id: str, **fields: Any) -> None:
        """Update refined segment fields."""
        if not fields:
            return
        fields["updated_at"] = now_iso()
        keys = list(fields.keys())
        sets = ", ".join(f"{k}=?" for k in keys)
        args = [fields[k] for k in keys] + [segment_ref_id]
        with self.lock:
            self.conn.execute(f"UPDATE meeting_segments_refined SET {sets} WHERE segment_ref_id=?", args)
            self.conn.commit()

    def update_speaker_for_cluster(
        self,
        meeting_id: str,
        speaker_cluster_id: str,
        speaker_name: str,
        source: str = "manual",
    ) -> int:
        """Update speaker name for all segments with a given cluster ID."""
        ts = now_iso()
        with self.lock:
            cursor = self.conn.execute(
                "UPDATE meeting_segments_refined SET speaker_name=?, speaker_name_source=?, updated_at=? WHERE meeting_id=? AND speaker_cluster_id=?",
                (speaker_name, source, ts, meeting_id, speaker_cluster_id),
            )
            self.conn.commit()
        return cursor.rowcount

    def get_speakers_for_meeting(self, meeting_id: str) -> list[dict[str, Any]]:
        """Get unique speakers with their segment counts for a meeting."""
        with self.lock:
            rows = self.conn.execute(
                """
                SELECT 
                    speaker_cluster_id,
                    speaker_name,
                    speaker_name_source,
                    COUNT(*) as segment_count,
                    AVG(speaker_confidence) as avg_confidence,
                    MIN(start_ts) as first_appearance,
                    MAX(end_ts) as last_appearance
                FROM meeting_segments_refined
                WHERE meeting_id=?
                GROUP BY speaker_cluster_id
                ORDER BY first_appearance ASC
                """,
                (meeting_id,),
            ).fetchall()
        return [self._dict(r) for r in rows]

    def clear_refined_segments(self, meeting_id: str) -> None:
        """Clear all refined segments for a meeting (for re-processing)."""
        with self.lock:
            self.conn.execute(
                "DELETE FROM meeting_segments_refined WHERE meeting_id=?",
                (meeting_id,),
            )
            self.conn.commit()

    # --- M4: Speaker Name Mappings (Audit History) ---

    def create_speaker_mapping(
        self,
        *,
        meeting_id: str,
        speaker_cluster_id: str,
        old_name: Optional[str],
        new_name: str,
        source: str = "manual",
        changed_by: Optional[str] = None,
        notes: Optional[str] = None,
    ) -> dict[str, Any]:
        """Create a speaker name mapping record for audit."""
        mapping_id = f"smap-{uuid.uuid4().hex}"
        ts = now_iso()
        row = {
            "mapping_id": mapping_id,
            "meeting_id": meeting_id,
            "speaker_cluster_id": speaker_cluster_id,
            "old_name": old_name,
            "new_name": new_name,
            "source": source,
            "changed_by": changed_by,
            "changed_at": ts,
            "notes": notes,
        }
        with self.lock:
            cols = ", ".join(row.keys())
            vals = ", ".join("?" for _ in row)
            self.conn.execute(f"INSERT INTO speaker_name_mappings ({cols}) VALUES ({vals})", list(row.values()))
            self.conn.commit()
        return row

    def get_speaker_mapping_history(
        self,
        meeting_id: str,
        speaker_cluster_id: Optional[str] = None,
    ) -> list[dict[str, Any]]:
        """Get speaker name mapping history for a meeting."""
        with self.lock:
            if speaker_cluster_id:
                rows = self.conn.execute(
                    "SELECT * FROM speaker_name_mappings WHERE meeting_id=? AND speaker_cluster_id=? ORDER BY changed_at DESC",
                    (meeting_id, speaker_cluster_id),
                ).fetchall()
            else:
                rows = self.conn.execute(
                    "SELECT * FROM speaker_name_mappings WHERE meeting_id=? ORDER BY changed_at DESC",
                    (meeting_id,),
                ).fetchall()
        return [self._dict(r) for r in rows]

    def get_latest_speaker_name(self, meeting_id: str, speaker_cluster_id: str) -> Optional[str]:
        """Get the latest speaker name for a cluster from mapping history."""
        with self.lock:
            row = self.conn.execute(
                "SELECT new_name FROM speaker_name_mappings WHERE meeting_id=? AND speaker_cluster_id=? ORDER BY changed_at DESC LIMIT 1",
                (meeting_id, speaker_cluster_id),
            ).fetchone()
        return row["new_name"] if row else None

    # --- M5: Meeting Images ---

    def create_meeting_image(
        self,
        *,
        meeting_id: str,
        seq: int,
        original_path: str,
        filename: str,
        size_bytes: int,
        checksum: str,
        width: Optional[int] = None,
        height: Optional[int] = None,
        format: Optional[str] = None,
        device_id: Optional[str] = None,
        captured_at: Optional[str] = None,
        thumbnail_path: Optional[str] = None,
    ) -> dict[str, Any]:
        """Create a meeting image record."""
        image_id = f"img-{uuid.uuid4().hex}"
        ts = now_iso()
        row = {
            "image_id": image_id,
            "meeting_id": meeting_id,
            "seq": seq,
            "original_path": original_path,
            "thumbnail_path": thumbnail_path,
            "filename": filename,
            "size_bytes": size_bytes,
            "checksum": checksum,
            "width": width,
            "height": height,
            "format": format,
            "device_id": device_id,
            "captured_at": captured_at,
            "uploaded_at": ts,
            "upload_status": IMAGE_STATUS_UPLOADED,
            "analysis_status": "pending",
            "analysis_result": None,
            "analysis_error": None,
            "analysis_at": None,
            "created_at": ts,
        }
        with self.lock:
            cols = ", ".join(row.keys())
            vals = ", ".join("?" for _ in row)
            self.conn.execute(f"INSERT INTO meeting_images ({cols}) VALUES ({vals})", list(row.values()))
            self.conn.commit()
        return row

    def get_meeting_image(self, image_id: str) -> Optional[dict[str, Any]]:
        """Get a single meeting image by ID."""
        with self.lock:
            row = self.conn.execute(
                "SELECT * FROM meeting_images WHERE image_id=?",
                (image_id,),
            ).fetchone()
        result = self._dict(row) if row else None
        # Deserialize analysis_result JSON
        if result and result.get("analysis_result"):
            try:
                result["analysis_result"] = json.loads(result["analysis_result"])
            except (json.JSONDecodeError, TypeError):
                pass
        return result

    def get_meeting_images(self, meeting_id: str) -> list[dict[str, Any]]:
        """Get all images for a meeting."""
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM meeting_images WHERE meeting_id=? ORDER BY seq ASC",
                (meeting_id,),
            ).fetchall()
        results = [self._dict(r) for r in rows]
        # Deserialize analysis_result JSON for each image
        for r in results:
            if r.get("analysis_result"):
                try:
                    r["analysis_result"] = json.loads(r["analysis_result"])
                except (json.JSONDecodeError, TypeError):
                    pass
        return results

    def update_meeting_image(self, image_id: str, **fields: Any) -> None:
        """Update meeting image fields."""
        if not fields:
            return
        
        # Convert analysis_result to JSON if it's a dict
        if "analysis_result" in fields and isinstance(fields["analysis_result"], dict):
            fields["analysis_result"] = json.dumps(fields["analysis_result"], ensure_ascii=False)
        
        keys = list(fields.keys())
        sets = ", ".join(f"{k}=?" for k in keys)
        args = [fields[k] for k in keys] + [image_id]
        with self.lock:
            self.conn.execute(f"UPDATE meeting_images SET {sets} WHERE image_id=?", args)
            self.conn.commit()

    def get_next_image_seq(self, meeting_id: str) -> int:
        """Get the next sequence number for an image in a meeting."""
        with self.lock:
            row = self.conn.execute(
                "SELECT MAX(seq) as max_seq FROM meeting_images WHERE meeting_id=?",
                (meeting_id,),
            ).fetchone()
        return (row["max_seq"] or 0) + 1

    def get_pending_analysis_images(self, limit: int = 10) -> list[dict[str, Any]]:
        """Get images pending analysis (for background processing)."""
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM meeting_images WHERE analysis_status='pending' ORDER BY created_at ASC LIMIT ?",
                (limit,),
            ).fetchall()
        return [self._dict(r) for r in rows]

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
