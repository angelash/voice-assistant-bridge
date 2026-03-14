"""
Voice Assistant Bridge - Stability & Performance Tests (M6)

Tests for:
- Long meeting stability (60+ minutes)
- Weak network recovery
- Interrupted upload recovery
- Concurrent meeting isolation
- Storage pressure handling
"""

import asyncio
import hashlib
import json
import os
import tempfile
import time
import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import sys
sys.path.insert(0, str(Path(__file__).parent))

from meeting import (
    MeetingStore,
    EVT_MEETING_MODE_ON,
    EVT_MEETING_MODE_OFF,
    EVT_AUDIO_SEGMENT_UPLOADED,
    UPLOAD_STATUS_PENDING,
    UPLOAD_STATUS_UPLOADED,
    UPLOAD_STATUS_FAILED,
    MEETING_STATUS_ACTIVE,
    MEETING_STATUS_ARCHIVED,
    MEETING_STATUS_READY,
    JOB_STATUS_QUEUED,
    JOB_STATUS_SUCCESS,
    MEETING_TERMINAL_STATUSES,
)
from v2_api import V2MeetingAPI
from cleanup_guard import CleanupGuard, CleanupConfig


class MockEventHub:
    """Mock event hub for testing."""
    
    def __init__(self):
        self.events = []
        self._lock = asyncio.Lock()
    
    async def publish(self, event):
        async with self._lock:
            self.events.append(event)


class TestLongMeetingStability(unittest.TestCase):
    """Test stability for long meetings (60+ minutes)."""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_meetings.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
    
    def test_simulated_60min_meeting(self):
        """Simulate a 60-minute meeting with many segments."""
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        # Simulate 120 segments (30s each = 60 minutes)
        num_segments = 120
        
        start_time = time.time()
        
        for i in range(num_segments):
            # Create segment
            segment = self.store.create_audio_segment(
                meeting_id=meeting_id,
                seq=i,
            )
            
            # Mark as uploaded
            self.store.update_audio_segment(
                segment["segment_id"],
                upload_status=UPLOAD_STATUS_UPLOADED,
                uploaded_at=datetime.now(timezone.utc).isoformat(),
                checksum=f"checksum_{i}",
                size_bytes=1024 * 100,  # 100KB each
            )
        
        elapsed = time.time() - start_time
        
        # Verify all segments
        segments = self.store.get_audio_segments(meeting_id)
        self.assertEqual(len(segments), num_segments)
        
        # Check performance
        print(f"\nCreated {num_segments} segments in {elapsed:.2f}s")
        self.assertLess(elapsed, 10.0, "Should handle 120 segments in <10s")
    
    def test_large_event_log(self):
        """Test handling of large event logs."""
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        # Create many events
        num_events = 1000
        start_time = time.time()
        
        for i in range(num_events):
            self.store.append_event(
                meeting_id=meeting_id,
                source="test",
                event_type="test.event",
                seq=i,
                payload={"index": i, "data": "x" * 100},
            )
        
        elapsed = time.time() - start_time
        
        # Query events
        events = self.store.get_events(meeting_id, limit=num_events)
        self.assertEqual(len(events), num_events)
        
        print(f"\nCreated {num_events} events in {elapsed:.2f}s")
        self.assertLess(elapsed, 20.0, "Should handle 1000 events in <20s")
    
    def test_memory_usage_during_long_meeting(self):
        """Test that memory doesn't grow unbounded during long meetings."""
        import tracemalloc
        
        tracemalloc.start()
        
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        initial_mem = tracemalloc.get_traced_memory()[0]
        
        # Simulate many operations
        for i in range(500):
            segment = self.store.create_audio_segment(
                meeting_id=meeting_id,
                seq=i,
            )
            self.store.append_event(
                meeting_id=meeting_id,
                source="test",
                event_type="audio.segment",
                seq=i,
            )
        
        final_mem = tracemalloc.get_traced_memory()[0]
        tracemalloc.stop()
        
        # Memory should not grow more than 50MB
        growth_mb = (final_mem - initial_mem) / (1024 * 1024)
        print(f"\nMemory growth: {growth_mb:.2f} MB")
        self.assertLess(growth_mb, 50, "Memory should not grow >50MB for 500 segments")


class TestNetworkRecovery(unittest.TestCase):
    """Test recovery from network issues."""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_meetings.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
    
    def test_upload_retry_queue(self):
        """Test that failed uploads can be retried."""
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        # Create segments with failed uploads
        for i in range(5):
            segment = self.store.create_audio_segment(
                meeting_id=meeting_id,
                seq=i,
            )
            self.store.update_audio_segment(
                segment["segment_id"],
                upload_status=UPLOAD_STATUS_FAILED,
                upload_error="Network timeout",
            )
        
        # Get failed segments for retry
        failed = self.store.get_failed_audio_segments(meeting_id)
        self.assertEqual(len(failed), 5)
        
        # Simulate retry success
        for seg in failed:
            self.store.update_audio_segment(
                seg["segment_id"],
                upload_status=UPLOAD_STATUS_UPLOADED,
                uploaded_at=datetime.now(timezone.utc).isoformat(),
                upload_error=None,
            )
        
        # Verify no more failed segments
        remaining = self.store.get_failed_audio_segments(meeting_id)
        self.assertEqual(len(remaining), 0)
    
    def test_interrupted_upload_state(self):
        """Test recovery from interrupted uploads."""
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        # Create segments with pending uploads (simulating crash during upload)
        for i in range(10):
            segment = self.store.create_audio_segment(
                meeting_id=meeting_id,
                seq=i,
            )
            if i < 5:
                # First 5 were uploaded
                self.store.update_audio_segment(
                    segment["segment_id"],
                    upload_status=UPLOAD_STATUS_UPLOADED,
                    uploaded_at=datetime.now(timezone.utc).isoformat(),
                )
            # Last 5 remain pending
        
        # On recovery, find pending segments
        pending = self.store.get_pending_audio_segments(meeting_id)
        self.assertEqual(len(pending), 5)
        
        # Verify we can distinguish pending from uploaded
        all_segments = self.store.get_audio_segments(meeting_id)
        uploaded = [s for s in all_segments if s["upload_status"] == UPLOAD_STATUS_UPLOADED]
        self.assertEqual(len(uploaded), 5)
    
    def test_network_disconnect_simulation(self):
        """Simulate network disconnect during meeting."""
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        # Simulate segments being created during "offline" period
        offline_segments = []
        for i in range(10):
            segment = self.store.create_audio_segment(
                meeting_id=meeting_id,
                seq=i,
            )
            # During offline, uploads fail
            if i < 5:
                self.store.update_audio_segment(
                    segment["segment_id"],
                    upload_status=UPLOAD_STATUS_FAILED,
                    upload_error="Connection refused",
                )
                offline_segments.append(segment)
            else:
                # After recovery, uploads succeed
                self.store.update_audio_segment(
                    segment["segment_id"],
                    upload_status=UPLOAD_STATUS_UPLOADED,
                    uploaded_at=datetime.now(timezone.utc).isoformat(),
                )
        
        # After "recovery", retry failed uploads
        failed = self.store.get_failed_audio_segments(meeting_id)
        self.assertEqual(len(failed), 5)
        
        # Simulate successful retry
        for seg in failed:
            self.store.update_audio_segment(
                seg["segment_id"],
                upload_status=UPLOAD_STATUS_UPLOADED,
                uploaded_at=datetime.now(timezone.utc).isoformat(),
                upload_error=None,
            )
        
        # Verify all recovered
        final_failed = self.store.get_failed_audio_segments(meeting_id)
        self.assertEqual(len(final_failed), 0)


class TestConcurrentMeetings(unittest.TestCase):
    """Test concurrent meeting isolation."""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_meetings.db"
        self.store = MeetingStore(self.db_path)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
    
    def test_immediate_next_meeting(self):
        """Test starting next meeting immediately after ending previous."""
        client_id = "test-client"
        
        # Meeting 1
        meeting1 = self.store.create_meeting(client_id=client_id)
        self.store.update_meeting(
            meeting1["meeting_id"],
            status=MEETING_STATUS_ACTIVE,
            started_at=datetime.now(timezone.utc).isoformat(),
        )
        
        # End meeting 1
        self.store.update_meeting(
            meeting1["meeting_id"],
            status=MEETING_STATUS_ARCHIVED,
            ended_at=datetime.now(timezone.utc).isoformat(),
        )
        
        # Immediately start meeting 2
        meeting2 = self.store.create_meeting(client_id=client_id)
        self.store.update_meeting(
            meeting2["meeting_id"],
            status=MEETING_STATUS_ACTIVE,
            started_at=datetime.now(timezone.utc).isoformat(),
        )
        
        # Verify isolation
        self.assertNotEqual(meeting1["meeting_id"], meeting2["meeting_id"])
        
        # Check active meeting returns meeting 2
        active = self.store.get_active_meeting(client_id)
        self.assertEqual(active["meeting_id"], meeting2["meeting_id"])
    
    def test_segment_isolation(self):
        """Test that segments don't leak between meetings."""
        client_id = "test-client"
        
        # Create two meetings
        meeting1 = self.store.create_meeting(client_id=client_id)
        meeting2 = self.store.create_meeting(client_id=client_id)
        
        # Add segments to each
        for i in range(5):
            self.store.create_audio_segment(
                meeting_id=meeting1["meeting_id"],
                seq=i,
            )
        
        for i in range(3):
            self.store.create_audio_segment(
                meeting_id=meeting2["meeting_id"],
                seq=i,
            )
        
        # Verify isolation
        segments1 = self.store.get_audio_segments(meeting1["meeting_id"])
        segments2 = self.store.get_audio_segments(meeting2["meeting_id"])
        
        self.assertEqual(len(segments1), 5)
        self.assertEqual(len(segments2), 3)
        
        # Verify all segments belong to correct meeting
        for s in segments1:
            self.assertEqual(s["meeting_id"], meeting1["meeting_id"])
        for s in segments2:
            self.assertEqual(s["meeting_id"], meeting2["meeting_id"])


class TestCleanupGuard(unittest.TestCase):
    """Test the 7-day cleanup guard."""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_meetings.db"
        self.artifacts_dir = Path(self.temp_dir) / "artifacts" / "meetings"
        self.artifacts_dir.mkdir(parents=True, exist_ok=True)
        self.store = MeetingStore(self.db_path)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
    
    def test_only_uploaded_data_eligible(self):
        """Test that only uploaded data is eligible for cleanup."""
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        # Create old meeting
        old_time = datetime.now(timezone.utc) - timedelta(days=10)
        self.store.update_meeting(
            meeting_id,
            status=MEETING_STATUS_ARCHIVED,
            ended_at=old_time.isoformat(),
            started_at=old_time.isoformat(),
        )
        
        # Create segments: some uploaded, some not
        for i in range(5):
            segment = self.store.create_audio_segment(
                meeting_id=meeting_id,
                seq=i,
            )
            if i < 3:
                # Uploaded
                self.store.update_audio_segment(
                    segment["segment_id"],
                    upload_status=UPLOAD_STATUS_UPLOADED,
                    uploaded_at=old_time.isoformat(),
                )
            else:
                # Pending (not uploaded)
                self.store.update_audio_segment(
                    segment["segment_id"],
                    upload_status=UPLOAD_STATUS_PENDING,
                )
        
        # Run cleanup
        config = CleanupConfig(
            retention_days=7,
            dry_run=True,  # Don't actually delete
        )
        guard = CleanupGuard(self.store, self.artifacts_dir, config)
        stats = guard.run_cleanup()
        
        # Meeting should NOT be deleted because not all segments uploaded
        # But segments should be scanned
        self.assertGreater(stats.audio_scanned, 0)
        self.assertEqual(stats.meetings_deleted, 0)
    
    def test_fully_uploaded_meeting_cleanup(self):
        """Test cleanup of fully uploaded meeting."""
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        # Create old meeting with terminal status
        old_time = datetime.now(timezone.utc) - timedelta(days=10)
        self.store.update_meeting(
            meeting_id,
            status=MEETING_STATUS_ARCHIVED,
            ended_at=old_time.isoformat(),
            started_at=old_time.isoformat(),
        )
        
        # All segments uploaded
        for i in range(5):
            segment = self.store.create_audio_segment(
                meeting_id=meeting_id,
                seq=i,
            )
            self.store.update_audio_segment(
                segment["segment_id"],
                upload_status=UPLOAD_STATUS_UPLOADED,
                uploaded_at=old_time.isoformat(),
            )
        
        # Create meeting folder
        meeting_dir = self.artifacts_dir / meeting_id
        meeting_dir.mkdir(parents=True, exist_ok=True)
        (meeting_dir / "test.txt").write_text("test")
        
        # Run cleanup (not dry-run to verify actual deletion)
        config = CleanupConfig(
            retention_days=7,
            dry_run=False,
        )
        guard = CleanupGuard(self.store, self.artifacts_dir, config)
        stats = guard.run_cleanup()
        
        # Meeting should be eligible (scanned)
        self.assertEqual(stats.meetings_scanned, 1)
        # Verify meeting was deleted
        self.assertEqual(stats.meetings_deleted, 1)
        # Verify folder was removed
        self.assertFalse(meeting_dir.exists())
    
    def test_audit_log_written(self):
        """Test that audit log is written for deletions."""
        audit_path = Path(self.temp_dir) / "audit.jsonl"
        
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        old_time = datetime.now(timezone.utc) - timedelta(days=10)
        self.store.update_meeting(
            meeting_id,
            status=MEETING_STATUS_ARCHIVED,
            ended_at=old_time.isoformat(),
        )
        
        # All segments uploaded
        segment = self.store.create_audio_segment(
            meeting_id=meeting_id,
            seq=0,
        )
        self.store.update_audio_segment(
            segment["segment_id"],
            upload_status=UPLOAD_STATUS_UPLOADED,
            uploaded_at=old_time.isoformat(),
        )
        
        # Create meeting folder with audio
        meeting_dir = self.artifacts_dir / meeting_id
        meeting_dir.mkdir(parents=True, exist_ok=True)
        audio_file = meeting_dir / "test.wav"
        audio_file.write_bytes(b"fake audio data")
        
        # Update segment with local path
        self.store.update_audio_segment(
            segment["segment_id"],
            local_path=str(audio_file),
        )
        
        # Run cleanup (NOT dry run)
        config = CleanupConfig(
            retention_days=7,
            dry_run=False,
            audit_log_path=audit_path,
        )
        guard = CleanupGuard(self.store, self.artifacts_dir, config)
        guard.run_cleanup()
        
        # Check audit log exists
        self.assertTrue(audit_path.exists(), f"Audit log not found at {audit_path}")
        
        # Read audit log
        with open(audit_path, "r") as f:
            lines = f.readlines()
        
        # Should have audit entries
        self.assertGreater(len(lines), 0, "Audit log should have entries")


class TestStoragePressure(unittest.TestCase):
    """Test handling of storage pressure."""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_meetings.db"
        self.store = MeetingStore(self.db_path)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
    
    def test_many_meetings_query_performance(self):
        """Test query performance with many meetings."""
        # Create many meetings
        num_meetings = 100
        for _ in range(num_meetings):
            self.store.create_meeting(client_id="test-client")
        
        # Query all meetings
        start = time.time()
        meetings = self.store.list_meetings(limit=num_meetings)
        elapsed = time.time() - start
        
        self.assertEqual(len(meetings), num_meetings)
        print(f"\nQueried {num_meetings} meetings in {elapsed:.3f}s")
        self.assertLess(elapsed, 1.0, "Should query 100 meetings in <1s")
    
    def test_database_size_with_many_segments(self):
        """Test database size doesn't grow unbounded."""
        meeting = self.store.create_meeting(client_id="test-client")
        meeting_id = meeting["meeting_id"]
        
        # Create many segments
        for i in range(1000):
            segment = self.store.create_audio_segment(
                meeting_id=meeting_id,
                seq=i,
            )
            self.store.append_event(
                meeting_id=meeting_id,
                source="test",
                event_type="test.event",
                seq=i,
                payload={"data": "x" * 50},
            )
        
        # Check database size
        db_size = self.db_path.stat().st_size
        size_mb = db_size / (1024 * 1024)
        
        print(f"\nDatabase size: {size_mb:.2f} MB for 1000 segments+events")
        self.assertLess(size_mb, 50, "Database should be <50MB for 1000 segments")


if __name__ == "__main__":
    # Run with verbose output
    unittest.main(verbosity=2)
