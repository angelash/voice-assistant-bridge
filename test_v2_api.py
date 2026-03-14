#!/usr/bin/env python3
"""
Voice Assistant Bridge - V2 API Tests

Tests for:
- Meeting creation and mode toggle
- Audio upload with checksum verification
- Upload state machine
- Event streaming
"""

import asyncio
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

# Add parent directory to path for imports
import sys
sys.path.insert(0, str(Path(__file__).parent))

from meeting import (
    MeetingStore,
    EVT_MEETING_MODE_ON,
    EVT_MEETING_MODE_OFF,
    EVT_AUDIO_SEGMENT_UPLOADED,
    EVT_AUDIO_SEGMENT_UPLOAD_FAILED,
    MEETING_STATUS_IDLE,
    MEETING_STATUS_ACTIVE,
    MEETING_STATUS_ENDING,
    MEETING_STATUS_ARCHIVED,
    UPLOAD_STATUS_PENDING,
    UPLOAD_STATUS_UPLOADED,
    UPLOAD_STATUS_FAILED,
)
from v2_api import V2MeetingAPI


class MockEventHub:
    """Mock event hub for testing"""
    
    def __init__(self):
        self.events = []
        
    async def publish(self, event):
        self.events.append(event)


class TestMeetingStore(unittest.TestCase):
    """Test MeetingStore database operations"""
    
    def setUp(self):
        # Use a temporary database
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_meetings.db"
        self.store = MeetingStore(self.db_path)
        
    def tearDown(self):
        self.store.close()
        # Cleanup temp directory
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        
    def test_create_meeting(self):
        """Test meeting creation"""
        meeting = self.store.create_meeting(
            client_id="test-client",
            session_id="test-session",
            meta={"key": "value"}
        )
        
        self.assertIsNotNone(meeting)
        self.assertTrue(meeting["meeting_id"].startswith("mtg-"))
        self.assertEqual(meeting["client_id"], "test-client")
        self.assertEqual(meeting["status"], MEETING_STATUS_IDLE)
        
    def test_get_meeting(self):
        """Test getting a meeting by ID"""
        created = self.store.create_meeting(client_id="test-client")
        meeting = self.store.get_meeting(created["meeting_id"])
        
        self.assertIsNotNone(meeting)
        self.assertEqual(meeting["meeting_id"], created["meeting_id"])
        
    def test_update_meeting(self):
        """Test updating meeting fields"""
        created = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(
            created["meeting_id"],
            status=MEETING_STATUS_ACTIVE,
            mode="on"
        )
        
        updated = self.store.get_meeting(created["meeting_id"])
        self.assertEqual(updated["status"], MEETING_STATUS_ACTIVE)
        self.assertEqual(updated["mode"], "on")
        
    def test_append_event(self):
        """Test appending events"""
        meeting = self.store.create_meeting(client_id="test-client")
        event = self.store.append_event(
            meeting_id=meeting["meeting_id"],
            source="client",
            event_type=EVT_MEETING_MODE_ON,
            payload={"test": "data"}
        )
        
        self.assertIsNotNone(event)
        self.assertTrue(event["event_id"].startswith("evt-"))
        self.assertEqual(event["event_type"], EVT_MEETING_MODE_ON)
        
    def test_create_audio_segment(self):
        """Test creating audio segment records"""
        meeting = self.store.create_meeting(client_id="test-client")
        segment = self.store.create_audio_segment(
            meeting_id=meeting["meeting_id"],
            seq=1
        )
        
        self.assertIsNotNone(segment)
        self.assertTrue(segment["segment_id"].startswith("seg-"))
        self.assertEqual(segment["seq"], 1)
        self.assertEqual(segment["upload_status"], UPLOAD_STATUS_PENDING)
        
    def test_update_audio_segment_upload_status(self):
        """Test updating segment upload status"""
        meeting = self.store.create_meeting(client_id="test-client")
        segment = self.store.create_audio_segment(
            meeting_id=meeting["meeting_id"],
            seq=1
        )
        
        # Update to uploaded
        self.store.update_audio_segment(
            segment["segment_id"],
            upload_status=UPLOAD_STATUS_UPLOADED,
            checksum="abc123",
            size_bytes=1024
        )
        
        updated = self.store.get_audio_segment(segment["segment_id"])
        self.assertEqual(updated["upload_status"], UPLOAD_STATUS_UPLOADED)
        self.assertEqual(updated["checksum"], "abc123")
        self.assertEqual(updated["size_bytes"], 1024)
        
    def test_get_pending_audio_segments(self):
        """Test getting pending segments"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create multiple segments
        self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)
        self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=2)
        seg3 = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=3)
        
        # Mark one as uploaded
        self.store.update_audio_segment(seg3["segment_id"], upload_status=UPLOAD_STATUS_UPLOADED)
        
        pending = self.store.get_pending_audio_segments(meeting["meeting_id"])
        self.assertEqual(len(pending), 2)
        
    def test_get_failed_audio_segments(self):
        """Test getting failed segments for retry"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        seg1 = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)
        self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=2)
        
        # Mark one as failed
        self.store.update_audio_segment(
            seg1["segment_id"],
            upload_status=UPLOAD_STATUS_FAILED,
            upload_error="checksum_mismatch"
        )
        
        failed = self.store.get_failed_audio_segments(meeting["meeting_id"])
        self.assertEqual(len(failed), 1)
        self.assertEqual(failed[0]["upload_error"], "checksum_mismatch")


class TestV2MeetingAPI(unittest.TestCase):
    """Test V2 API endpoints"""
    
    def setUp(self):
        # Create temp database
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_api.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        
    def test_handle_create_meeting(self):
        """Test POST /v2/meetings"""
        # Create a mock request
        request = MagicMock()
        request.json = AsyncMock(return_value={
            "client_id": "test-client",
            "session_id": "test-session"
        })
        
        # Call the handler
        response = asyncio.run(self.api.handle_create_meeting(request))
        
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        self.assertTrue(body["meeting_id"].startswith("mtg-"))
        
    def test_handle_meeting_mode_on(self):
        """Test POST /v2/meetings/{id}/mode - mode on"""
        # Create meeting first
        meeting = self.store.create_meeting(client_id="test-client")
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.json = AsyncMock(return_value={"mode": "on"})
        
        response = asyncio.run(self.api.handle_meeting_mode(request))
        
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        self.assertEqual(body["status"], MEETING_STATUS_ACTIVE)
        
    def test_handle_meeting_mode_off(self):
        """Test POST /v2/meetings/{id}/mode - mode off"""
        # Create and start meeting
        meeting = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.json = AsyncMock(return_value={"mode": "off"})
        
        response = asyncio.run(self.api.handle_meeting_mode(request))
        
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        # Meeting transitions directly to ARCHIVED after ending
        self.assertEqual(body["status"], MEETING_STATUS_ARCHIVED)


class TestAudioUpload(unittest.TestCase):
    """Test audio upload with checksum verification"""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_upload.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
        # Create test audio data
        self.test_audio_data = b"RIFF" + b"\x00" * 100  # Fake WAV header
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _create_mock_multipart(self, segment_id, seq, checksum, audio_data):
        """Create a mock multipart reader that works with the API's async iteration"""
        class MockField:
            def __init__(self, name, data):
                self.name = name
                self._data = data if isinstance(data, bytes) else data.encode()
            
            async def read(self):
                return self._data
        
        class MockMultipartReader:
            def __init__(self, fields):
                self._fields = fields
                self._index = 0
            
            def __aiter__(self):
                return self
            
            async def __anext__(self):
                if self._index >= len(self._fields):
                    raise StopAsyncIteration
                field = self._fields[self._index]
                self._index += 1
                return field
        
        fields = [
            MockField("segment_id", segment_id),
            MockField("seq", str(seq)),
            MockField("checksum", checksum),
            MockField("audio", audio_data),
        ]
        
        # Return an async function that returns the reader (since API does `await request.multipart()`)
        async def get_reader():
            return MockMultipartReader(fields)
        
        return get_reader
        
    def test_upload_with_valid_checksum(self):
        """Test audio upload with correct checksum"""
        # Setup: create meeting and segment
        meeting = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")
        
        segment = self.store.create_audio_segment(
            meeting_id=meeting["meeting_id"],
            seq=1
        )
        
        # Compute correct checksum
        checksum = hashlib.sha256(self.test_audio_data).hexdigest()
        
        # Mock multipart request
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.multipart = self._create_mock_multipart(
            segment["segment_id"], 1, checksum, self.test_audio_data
        )
        
        # Call upload handler
        response = asyncio.run(self.api.handle_audio_upload(request))
        
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        self.assertEqual(body["upload_status"], "uploaded")
        self.assertTrue(body["checksum_verified"])
        
        # Verify segment updated
        updated = self.store.get_audio_segment(segment["segment_id"])
        self.assertEqual(updated["upload_status"], UPLOAD_STATUS_UPLOADED)
        
    def test_upload_with_invalid_checksum(self):
        """Test audio upload with wrong checksum"""
        # Setup
        meeting = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")
        
        segment = self.store.create_audio_segment(
            meeting_id=meeting["meeting_id"],
            seq=1
        )
        
        # Wrong checksum
        wrong_checksum = "0" * 64
        
        # Mock multipart request
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.multipart = self._create_mock_multipart(
            segment["segment_id"], 1, wrong_checksum, self.test_audio_data
        )
        
        response = asyncio.run(self.api.handle_audio_upload(request))
        
        self.assertEqual(response.status, 400)
        body = json.loads(response.text)
        self.assertFalse(body["ok"])
        self.assertEqual(body["error"], "checksum_mismatch")
        
        # Verify segment marked as failed
        updated = self.store.get_audio_segment(segment["segment_id"])
        self.assertEqual(updated["upload_status"], UPLOAD_STATUS_FAILED)
        self.assertEqual(updated["upload_error"], "checksum_mismatch")


class TestUploadManifest(unittest.TestCase):
    """Test upload manifest and pending endpoints"""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_manifest.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        
    def test_get_upload_manifest(self):
        """Test GET /v2/meetings/{id}/audio/manifest"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create segments with different statuses
        seg1 = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)
        seg2 = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=2)
        seg3 = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=3)
        
        self.store.update_audio_segment(seg1["segment_id"], upload_status=UPLOAD_STATUS_UPLOADED)
        self.store.update_audio_segment(seg3["segment_id"], upload_status=UPLOAD_STATUS_FAILED, upload_error="test")
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        
        response = asyncio.run(self.api.handle_get_upload_manifest(request))
        
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        
        manifest = body["manifest"]
        self.assertEqual(manifest["total_segments"], 3)
        self.assertEqual(manifest["uploaded_count"], 1)
        self.assertEqual(manifest["pending_count"], 1)
        self.assertEqual(manifest["failed_count"], 1)
        
    def test_get_pending_uploads(self):
        """Test GET /v2/meetings/{id}/audio/pending"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        seg1 = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)
        seg2 = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=2)
        
        self.store.update_audio_segment(seg2["segment_id"], upload_status=UPLOAD_STATUS_FAILED)
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        
        response = asyncio.run(self.api.handle_get_pending_uploads(request))
        
        body = json.loads(response.text)
        self.assertEqual(body["pending_count"], 1)
        self.assertEqual(body["failed_count"], 1)
        self.assertEqual(body["total_needs_upload"], 2)
        
    def test_reset_failed_uploads(self):
        """Test POST /v2/meetings/{id}/audio:reset-failed"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        seg1 = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)
        self.store.update_audio_segment(seg1["segment_id"], upload_status=UPLOAD_STATUS_FAILED)
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        
        response = asyncio.run(self.api.handle_reset_failed_upload(request))
        
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        self.assertEqual(body["reset_count"], 1)
        
        # Verify segment is now pending
        updated = self.store.get_audio_segment(seg1["segment_id"])
        self.assertEqual(updated["upload_status"], UPLOAD_STATUS_PENDING)


if __name__ == "__main__":
    unittest.main()


class TestTranscriptionJobs(unittest.TestCase):
    """Test M3 transcription job functionality"""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_transcription.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        
    def test_create_transcription_job(self):
        """Test creating a transcription job"""
        meeting = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(meeting["meeting_id"], status="ENDING")
        
        job = self.store.create_transcription_job(meeting_id=meeting["meeting_id"])
        
        self.assertIsNotNone(job)
        self.assertTrue(job["job_id"].startswith("job-"))
        self.assertEqual(job["meeting_id"], meeting["meeting_id"])
        self.assertEqual(job["status"], "queued")
        
    def test_get_transcription_job(self):
        """Test getting a transcription job by ID"""
        meeting = self.store.create_meeting(client_id="test-client")
        job = self.store.create_transcription_job(meeting_id=meeting["meeting_id"])
        
        retrieved = self.store.get_transcription_job(job["job_id"])
        
        self.assertIsNotNone(retrieved)
        self.assertEqual(retrieved["job_id"], job["job_id"])
        
    def test_update_transcription_job_progress(self):
        """Test updating transcription job progress"""
        meeting = self.store.create_meeting(client_id="test-client")
        job = self.store.create_transcription_job(meeting_id=meeting["meeting_id"])
        
        self.store.update_transcription_job(
            job["job_id"],
            status="running",
            progress_percent=50,
        )
        
        updated = self.store.get_transcription_job(job["job_id"])
        self.assertEqual(updated["status"], "running")
        self.assertEqual(updated["progress_percent"], 50)
        
    def test_get_queued_transcription_jobs(self):
        """Test getting queued transcription jobs"""
        meeting1 = self.store.create_meeting(client_id="test-client-1")
        meeting2 = self.store.create_meeting(client_id="test-client-2")
        
        job1 = self.store.create_transcription_job(meeting_id=meeting1["meeting_id"])
        job2 = self.store.create_transcription_job(meeting_id=meeting2["meeting_id"])
        
        # Mark one as running
        self.store.update_transcription_job(job1["job_id"], status="running")
        
        queued = self.store.get_queued_transcription_jobs()
        
        self.assertEqual(len(queued), 1)
        self.assertEqual(queued[0]["job_id"], job2["job_id"])
        
    def test_auto_create_job_on_meeting_end(self):
        """Test that transcription job is auto-created when meeting ends with uploaded segments"""
        meeting = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")
        
        # Create and upload an audio segment
        segment = self.store.create_audio_segment(
            meeting_id=meeting["meeting_id"],
            seq=1
        )
        self.store.update_audio_segment(
            segment["segment_id"],
            upload_status=UPLOAD_STATUS_UPLOADED,
        )
        
        # End the meeting
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.json = AsyncMock(return_value={"mode": "off"})
        
        response = asyncio.run(self.api.handle_meeting_mode(request))
        
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        
        # Check that transcription job was created
        self.assertIsNotNone(body.get("transcription_job"))
        self.assertTrue(body["transcription_job"]["job_id"].startswith("job-"))
        
    def test_no_job_created_without_uploaded_segments(self):
        """Test that no transcription job is created when meeting ends without uploaded segments"""
        meeting = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")
        
        # End the meeting without any uploaded segments
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.json = AsyncMock(return_value={"mode": "off"})
        
        response = asyncio.run(self.api.handle_meeting_mode(request))
        
        body = json.loads(response.text)
        self.assertIsNone(body.get("transcription_job"))
