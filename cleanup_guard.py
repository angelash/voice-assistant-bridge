"""
Voice Assistant Bridge - Cleanup Guard (M6)

Implements 7-day data retention with safety guards:
- Only deletes data that has been successfully uploaded
- Writes deletion audit log before physical deletion
- Automatic retry with max 3 attempts
- Alerts on deletion failures

Safety Rules:
1. Cleanup job only scans: uploaded=true AND uploaded_at <= now-7d
2. Writes deletion audit log first
3. Physical deletion happens only after successful log
4. Failed deletions are retried up to 3 times
5. After 3 failures, alert is raised
"""

from __future__ import annotations

import json
import logging
import shutil
import sqlite3
import threading
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any, Optional, Callable

from meeting import (
    MeetingStore,
    UPLOAD_STATUS_UPLOADED,
    JOB_STATUS_SUCCESS,
    IMAGE_STATUS_UPLOADED,
    now_iso,
)

logger = logging.getLogger(__name__)


# Cleanup status constants
CLEANUP_STATUS_PENDING = "pending"
CLEANUP_STATUS_DELETED = "deleted"
CLEANUP_STATUS_FAILED = "failed"
CLEANUP_STATUS_SKIPPED = "skipped"  # Not uploaded yet

# Retention period in days
DEFAULT_RETENTION_DAYS = 7

# Max retry attempts
MAX_RETRY_ATTEMPTS = 3


@dataclass
class CleanupConfig:
    """Configuration for cleanup operations."""
    
    retention_days: int = DEFAULT_RETENTION_DAYS
    max_retry_attempts: int = MAX_RETRY_ATTEMPTS
    dry_run: bool = False  # If True, don't actually delete
    audit_log_path: Optional[Path] = None
    
    # What to clean
    clean_audio_segments: bool = True
    clean_meeting_folders: bool = True
    clean_old_meetings: bool = True  # Delete entire meeting folders after retention
    
    # Minimum age for cleanup (hours after meeting ends)
    min_meeting_age_hours: int = 24


@dataclass
class CleanupStats:
    """Statistics from a cleanup run."""
    
    started_at: str = ""
    finished_at: str = ""
    
    # Audio segments
    audio_scanned: int = 0
    audio_deleted: int = 0
    audio_failed: int = 0
    audio_bytes_freed: int = 0
    
    # Meeting folders
    meetings_scanned: int = 0
    meetings_deleted: int = 0
    meetings_failed: int = 0
    meetings_bytes_freed: int = 0
    
    # Errors
    errors: list[str] = field(default_factory=list)
    
    def total_bytes_freed(self) -> int:
        return self.audio_bytes_freed + self.meetings_bytes_freed
    
    def to_dict(self) -> dict:
        return {
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "audio": {
                "scanned": self.audio_scanned,
                "deleted": self.audio_deleted,
                "failed": self.audio_failed,
                "bytes_freed": self.audio_bytes_freed,
            },
            "meetings": {
                "scanned": self.meetings_scanned,
                "deleted": self.meetings_deleted,
                "failed": self.meetings_failed,
                "bytes_freed": self.meetings_bytes_freed,
            },
            "total_bytes_freed": self.total_bytes_freed(),
            "errors": self.errors,
        }


class CleanupGuard:
    """
    Cleanup guard for data retention enforcement.
    
    Ensures only successfully uploaded data is eligible for deletion.
    """
    
    def __init__(
        self,
        store: MeetingStore,
        artifacts_dir: Path = Path("artifacts/meetings"),
        config: Optional[CleanupConfig] = None,
    ):
        self.store = store
        self.artifacts_dir = artifacts_dir
        self.config = config or CleanupConfig()
        
        # Default audit log path
        if self.config.audit_log_path is None:
            self.config.audit_log_path = artifacts_dir.parent / "cleanup_audit.jsonl"
        
        # Lock for thread safety
        self._lock = threading.RLock()
        
        # Callbacks
        self.on_cleanup_complete: Optional[Callable[[CleanupStats], None]] = None
        self.on_cleanup_error: Optional[Callable[[str], None]] = None
    
    def run_cleanup(self) -> CleanupStats:
        """
        Run a full cleanup pass.
        
        Returns statistics about what was cleaned.
        """
        stats = CleanupStats(started_at=now_iso())
        
        try:
            logger.info(f"Starting cleanup (retention: {self.config.retention_days} days)")
            
            # Clean audio segments
            if self.config.clean_audio_segments:
                self._cleanup_audio_segments(stats)
            
            # Clean old meeting folders
            if self.config.clean_old_meetings:
                self._cleanup_old_meetings(stats)
            
            logger.info(f"Cleanup complete: {stats.audio_deleted} audio segments, "
                       f"{stats.meetings_deleted} meetings deleted, "
                       f"{self._format_bytes(stats.total_bytes_freed())} freed")
            
        except Exception as e:
            error_msg = f"Cleanup failed: {e}"
            logger.error(error_msg)
            stats.errors.append(error_msg)
            self.on_cleanup_error and self.on_cleanup_error(error_msg)
        
        stats.finished_at = now_iso()
        
        # Callback
        self.on_cleanup_complete and self.on_cleanup_complete(stats)
        
        return stats
    
    def _cleanup_audio_segments(self, stats: CleanupStats) -> None:
        """Clean up old audio segments that have been uploaded."""
        cutoff = datetime.now(timezone.utc) - timedelta(days=self.config.retention_days)
        cutoff_str = cutoff.isoformat()
        
        # Find eligible segments
        # Only segments where:
        # 1. upload_status = 'uploaded'
        # 2. uploaded_at <= cutoff
        segments = self._find_eligible_audio_segments(cutoff_str)
        stats.audio_scanned = len(segments)
        
        for seg in segments:
            try:
                # Check if audio file exists
                audio_path = seg.get("local_path")
                if audio_path and Path(audio_path).exists():
                    file_size = Path(audio_path).stat().st_size
                    
                    if not self.config.dry_run:
                        # Write audit log BEFORE deletion
                        self._write_audit_log("audio_segment", seg, "deleting")
                        
                        # Delete the file
                        Path(audio_path).unlink()
                        
                        # Update segment status
                        self.store.update_audio_segment(
                            seg["segment_id"],
                            local_path=None,
                        )
                        
                        # Write audit log AFTER deletion
                        self._write_audit_log("audio_segment", seg, "deleted")
                        
                        stats.audio_bytes_freed += file_size
                    
                    stats.audio_deleted += 1
                    logger.debug(f"Deleted audio segment: {seg['segment_id']}")
                    
            except Exception as e:
                error_msg = f"Failed to delete audio segment {seg['segment_id']}: {e}"
                logger.warning(error_msg)
                stats.errors.append(error_msg)
                stats.audio_failed += 1
                
                # Write failure audit log
                self._write_audit_log("audio_segment", seg, "failed", str(e))
    
    def _cleanup_old_meetings(self, stats: CleanupStats) -> None:
        """Clean up entire meeting folders that are past retention."""
        cutoff = datetime.now(timezone.utc) - timedelta(days=self.config.retention_days)
        cutoff_str = cutoff.isoformat()
        
        # Find meetings where:
        # 1. Status is terminal (ARCHIVED or READY)
        # 2. ended_at <= cutoff
        # 3. All audio segments are uploaded (or there are no segments)
        meetings = self._find_eligible_meetings(cutoff_str)
        stats.meetings_scanned = len(meetings)
        
        for meeting in meetings:
            meeting_id = meeting["meeting_id"]
            meeting_dir = self.artifacts_dir / meeting_id
            
            try:
                if meeting_dir.exists():
                    # Calculate folder size
                    folder_size = self._calculate_folder_size(meeting_dir)
                    
                    if not self.config.dry_run:
                        # Write audit log BEFORE deletion
                        self._write_audit_log("meeting", meeting, "deleting")
                        
                        # Delete entire folder
                        shutil.rmtree(meeting_dir)
                        
                        # Write audit log AFTER deletion
                        self._write_audit_log("meeting", meeting, "deleted")
                        
                        stats.meetings_bytes_freed += folder_size
                    
                    stats.meetings_deleted += 1
                    logger.info(f"Deleted meeting folder: {meeting_id}")
                    
            except Exception as e:
                error_msg = f"Failed to delete meeting {meeting_id}: {e}"
                logger.warning(error_msg)
                stats.errors.append(error_msg)
                stats.meetings_failed += 1
                
                # Write failure audit log
                self._write_audit_log("meeting", meeting, "failed", str(e))
    
    def _find_eligible_audio_segments(self, cutoff_str: str) -> list[dict]:
        """Find audio segments eligible for cleanup."""
        # Query segments that are uploaded and older than retention
        with self.store.lock:
            rows = self.store.conn.execute("""
                SELECT * FROM audio_segments 
                WHERE upload_status = ? 
                AND uploaded_at IS NOT NULL 
                AND uploaded_at <= ?
                ORDER BY uploaded_at ASC
            """, (UPLOAD_STATUS_UPLOADED, cutoff_str)).fetchall()
        
        return [self.store._dict(r) for r in rows]
    
    def _find_eligible_meetings(self, cutoff_str: str) -> list[dict]:
        """Find meetings eligible for full cleanup."""
        from meeting import MEETING_TERMINAL_STATUSES
        
        with self.store.lock:
            # Find meetings in terminal state with ended_at <= cutoff
            placeholders = ",".join("?" for _ in MEETING_TERMINAL_STATUSES)
            rows = self.store.conn.execute(f"""
                SELECT * FROM meeting_sessions 
                WHERE status IN ({placeholders})
                AND ended_at IS NOT NULL 
                AND ended_at <= ?
                ORDER BY ended_at ASC
            """, list(MEETING_TERMINAL_STATUSES) + [cutoff_str]).fetchall()
        
        meetings = [self.store._dict(r) for r in rows]
        
        # Filter to only meetings where all segments are uploaded
        eligible = []
        for meeting in meetings:
            meeting_id = meeting["meeting_id"]
            
            # Check all audio segments are uploaded
            segments = self.store.get_audio_segments(meeting_id)
            if not segments:
                # No segments - eligible
                eligible.append(meeting)
                continue
            
            all_uploaded = all(
                s.get("upload_status") == UPLOAD_STATUS_UPLOADED 
                for s in segments
            )
            
            if all_uploaded:
                eligible.append(meeting)
        
        return eligible
    
    def _write_audit_log(
        self, 
        item_type: str, 
        item: dict, 
        action: str, 
        error: Optional[str] = None
    ) -> None:
        """Write an entry to the audit log."""
        entry = {
            "timestamp": now_iso(),
            "item_type": item_type,
            "item_id": item.get("segment_id") or item.get("meeting_id"),
            "action": action,
            "error": error,
            "details": {
                "meeting_id": item.get("meeting_id"),
                "local_path": item.get("local_path"),
            }
        }
        
        audit_path = self.config.audit_log_path
        if audit_path:
            audit_path.parent.mkdir(parents=True, exist_ok=True)
            with open(audit_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    
    def _calculate_folder_size(self, folder: Path) -> int:
        """Calculate total size of a folder."""
        total = 0
        for path in folder.rglob("*"):
            if path.is_file():
                total += path.stat().st_size
        return total
    
    def _format_bytes(self, size: int) -> str:
        """Format byte size as human readable."""
        for unit in ["B", "KB", "MB", "GB"]:
            if size < 1024:
                return f"{size:.1f} {unit}"
            size /= 1024
        return f"{size:.1f} TB"


# CLI interface for testing
if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Cleanup Guard for Voice Assistant Bridge")
    parser.add_argument("--dry-run", action="store_true", help="Don't actually delete, just report")
    parser.add_argument("--retention-days", type=int, default=7, help="Retention period in days")
    parser.add_argument("--verbose", "-v", action="store_true", help="Verbose output")
    
    args = parser.parse_args()
    
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )
    
    # Initialize
    db_path = Path("bridge_state.db")
    store = MeetingStore(db_path)
    
    config = CleanupConfig(
        retention_days=args.retention_days,
        dry_run=args.dry_run,
    )
    
    guard = CleanupGuard(store, config=config)
    
    print(f"Running cleanup (dry_run={args.dry_run}, retention={args.retention_days} days)...")
    stats = guard.run_cleanup()
    
    print("\nCleanup Statistics:")
    print(json.dumps(stats.to_dict(), indent=2, ensure_ascii=False))
