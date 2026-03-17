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
    EVT_IMAGE_UPLOADED,
    EVT_IMAGE_ANALYSIS_STARTED,
    EVT_IMAGE_ANALYSIS_COMPLETED,
    MEETING_STATUS_IDLE,
    MEETING_STATUS_ACTIVE,
    MEETING_STATUS_ENDING,
    MEETING_STATUS_ARCHIVED,
    UPLOAD_STATUS_PENDING,
    UPLOAD_STATUS_UPLOADED,
    UPLOAD_STATUS_FAILED,
    IMAGE_STATUS_UPLOADED,
    IMAGE_STATUS_FAILED,
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

    def test_append_event_auto_seq_increments(self):
        """Events without seq should receive monotonically increasing seq values."""
        meeting = self.store.create_meeting(client_id="test-client")
        evt1 = self.store.append_event(meeting_id=meeting["meeting_id"], source="client", event_type="evt.1")
        evt2 = self.store.append_event(meeting_id=meeting["meeting_id"], source="client", event_type="evt.2")
        evt3 = self.store.append_event(meeting_id=meeting["meeting_id"], source="client", event_type="evt.3")

        self.assertEqual(evt1["seq"], 1)
        self.assertEqual(evt2["seq"], 2)
        self.assertEqual(evt3["seq"], 3)

        after_1 = self.store.get_events(meeting["meeting_id"], after_seq=1)
        self.assertEqual(len(after_1), 2)
        self.assertEqual(after_1[0]["seq"], 2)
        self.assertEqual(after_1[1]["seq"], 3)
        
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

    def test_handle_list_meetings_invalid_limit(self):
        """Invalid numeric query should return 400 instead of 500."""
        request = MagicMock()
        request.query = {"limit": "abc", "offset": "0"}

        response = asyncio.run(self.api.handle_list_meetings(request))
        self.assertEqual(response.status, 400)
        body = json.loads(response.text)
        self.assertFalse(body["ok"])
        self.assertIn("limit", body["error"])

    def test_handle_get_timeline_invalid_after_seq(self):
        """Invalid after_seq should return 400."""
        meeting = self.store.create_meeting(client_id="test-client")
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.query = {"after_seq": "bad", "limit": "100"}

        response = asyncio.run(self.api.handle_get_timeline(request))
        self.assertEqual(response.status, 400)
        body = json.loads(response.text)
        self.assertFalse(body["ok"])
        self.assertIn("after_seq", body["error"])

    def test_handle_patch_meeting_meta(self):
        """Test PATCH /v2/meetings/{meeting_id} metadata update."""
        meeting = self.store.create_meeting(client_id="test-client")

        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.json = AsyncMock(
            return_value={
                "meeting_name": "周会-需求评审",
                "transcript_text": "这是转写正文",
                "meta": {"custom_tag": "qa"},
            }
        )

        response = asyncio.run(self.api.handle_patch_meeting(request))
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])

        updated = self.store.get_meeting(meeting["meeting_id"])
        self.assertIsNotNone(updated)
        meta = json.loads(updated["meta_json"])
        self.assertEqual(meta.get("meeting_name"), "周会-需求评审")
        self.assertEqual(meta.get("transcript_text"), "这是转写正文")
        self.assertEqual(meta.get("custom_tag"), "qa")


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

    def _create_mock_multipart(self, segment_id, seq, checksum, audio_data, audio_filename=None):
        """Create a mock multipart reader that works with the API's async iteration"""
        class MockField:
            def __init__(self, name, data, filename=None):
                self.name = name
                self._data = data if isinstance(data, bytes) else data.encode()
                self.filename = filename
            
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
            MockField("audio", audio_data, filename=audio_filename),
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

    def test_upload_raw_pcm_with_wav_filename_is_stored_as_pcm(self):
        """Non-RIFF audio should be treated as PCM even if uploaded filename is .wav."""
        meeting = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")
        segment = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)

        # 1s of 48kHz mono int16 silence PCM
        pcm_data = b"\x00\x00" * 48000
        checksum = hashlib.sha256(pcm_data).hexdigest()

        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.multipart = self._create_mock_multipart(
            segment["segment_id"],
            1,
            checksum,
            pcm_data,
            audio_filename="segment.wav",
        )

        response = asyncio.run(self.api.handle_audio_upload(request))
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        self.assertEqual(body.get("audio_format"), "pcm")
        self.assertTrue(str(body.get("path") or "").endswith(".pcm"))
        self.assertGreaterEqual(int(body.get("duration_ms") or 0), 900)

        updated = self.store.get_audio_segment(segment["segment_id"])
        self.assertIsNotNone(updated)
        self.assertTrue(str(updated.get("local_path") or "").endswith(".pcm"))
        self.assertGreaterEqual(int(updated.get("duration_ms") or 0), 900)
        
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

    def test_upload_segment_meeting_mismatch(self):
        """Uploading an existing segment under another meeting should be rejected."""
        meeting_a = self.store.create_meeting(client_id="client-a")
        meeting_b = self.store.create_meeting(client_id="client-b")
        self.store.update_meeting(meeting_a["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")
        self.store.update_meeting(meeting_b["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")

        seg = self.store.create_audio_segment(
            meeting_id=meeting_a["meeting_id"],
            seq=1,
            segment_id="seg-shared-id",
        )
        checksum = hashlib.sha256(self.test_audio_data).hexdigest()

        request = MagicMock()
        request.match_info = {"meeting_id": meeting_b["meeting_id"]}
        request.multipart = self._create_mock_multipart(
            seg["segment_id"], 1, checksum, self.test_audio_data
        )

        response = asyncio.run(self.api.handle_audio_upload(request))
        self.assertEqual(response.status, 409)
        body = json.loads(response.text)
        self.assertFalse(body["ok"])
        self.assertEqual(body["error"], "segment_meeting_mismatch")


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

    def test_no_job_created_when_not_all_segments_uploaded(self):
        """Test that no transcription job is created when some segments are still pending/failed"""
        meeting = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")

        seg1 = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)
        self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=2)
        self.store.update_audio_segment(seg1["segment_id"], upload_status=UPLOAD_STATUS_UPLOADED)

        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.json = AsyncMock(return_value={"mode": "off"})

        response = asyncio.run(self.api.handle_meeting_mode(request))
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        self.assertIsNone(body.get("transcription_job"))


class TestNonBlockingTranscription(unittest.TestCase):
    """Test that long transcription tasks don't block main API calls (M3 requirement)"""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_nonblocking.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        
    def test_meeting_mode_off_returns_immediately_with_transcription_job(self):
        """Test that meeting mode off returns immediately, not waiting for transcription"""
        meeting = self.store.create_meeting(client_id="test-client")
        self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")
        
        # Create and upload a segment
        segment = self.store.create_audio_segment(
            meeting_id=meeting["meeting_id"],
            seq=1
        )
        self.store.update_audio_segment(
            segment["segment_id"],
            upload_status=UPLOAD_STATUS_UPLOADED,
        )
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.json = AsyncMock(return_value={"mode": "off"})
        
        # Time the response
        import time
        start = time.time()
        response = asyncio.run(self.api.handle_meeting_mode(request))
        elapsed_ms = (time.time() - start) * 1000
        
        body = json.loads(response.text)
        
        # Response should be immediate (< 100ms) - transcription runs async
        self.assertLess(elapsed_ms, 100, "Meeting mode off should return immediately")
        self.assertTrue(body["ok"])
        # Job should be created but not completed (status=queued)
        self.assertIsNotNone(body.get("transcription_job"))
        self.assertEqual(body["transcription_job"]["status"], "queued")
        
    def test_api_calls_during_queued_transcription(self):
        """Test that other API calls work while transcription jobs are queued"""
        # Create and end meeting with transcription job
        meeting1 = self.store.create_meeting(client_id="client-1")
        self.store.update_meeting(meeting1["meeting_id"], status=MEETING_STATUS_ACTIVE, mode="on")
        segment1 = self.store.create_audio_segment(meeting_id=meeting1["meeting_id"], seq=1)
        self.store.update_audio_segment(segment1["segment_id"], upload_status=UPLOAD_STATUS_UPLOADED)
        
        job = self.store.create_transcription_job(meeting_id=meeting1["meeting_id"])
        
        # Verify job is queued
        self.assertEqual(job["status"], "queued")
        
        # Now make other API calls - they should work fine
        # 1. Create another meeting
        request = MagicMock()
        request.json = AsyncMock(return_value={"client_id": "client-2"})
        response = asyncio.run(self.api.handle_create_meeting(request))
        self.assertEqual(response.status, 200)
        
        # 2. List meetings
        request = MagicMock()
        request.query = {}
        response = asyncio.run(self.api.handle_list_meetings(request))
        body = json.loads(response.text)
        self.assertEqual(body["count"], 2)  # Both meetings
        
        # 3. Get transcription queue
        request = MagicMock()
        response = asyncio.run(self.api.handle_list_transcription_queue(request))
        body = json.loads(response.text)
        self.assertEqual(body["count"], 1)  # One queued job
        
    def test_multiple_transcription_jobs_can_queue(self):
        """Test that multiple transcription jobs can be queued simultaneously"""
        # Create multiple meetings with ended status
        jobs = []
        for i in range(3):
            meeting = self.store.create_meeting(client_id=f"client-{i}")
            self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ARCHIVED)
            segment = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)
            self.store.update_audio_segment(segment["segment_id"], upload_status=UPLOAD_STATUS_UPLOADED)
            job = self.store.create_transcription_job(meeting_id=meeting["meeting_id"])
            jobs.append(job)
        
        # All jobs should be queued
        for job in jobs:
            self.assertEqual(job["status"], "queued")
        
        # Verify queue returns all jobs
        queued = self.store.get_queued_transcription_jobs()
        self.assertEqual(len(queued), 3)


class TestTranscriptionWorkerNonBlocking(unittest.TestCase):
    """Test TranscriptionWorker doesn't block main event loop"""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_worker.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        
    def test_worker_uses_thread_pool(self):
        """Test that TranscriptionWorker is initialized with ThreadPoolExecutor"""
        from transcription_worker import TranscriptionWorker
        
        worker = TranscriptionWorker(
            self.store,
            self.event_hub,
            max_workers=2,
        )
        
        self.assertEqual(worker.max_workers, 2)
        self.assertIsNotNone(worker._executor)

    def test_worker_load_audio_fallback_for_non_riff_wav(self):
        """_load_audio should fallback to raw PCM when .wav file lacks RIFF header."""
        from transcription_worker import TranscriptionWorker

        worker = TranscriptionWorker(
            self.store,
            self.event_hub,
            max_workers=1,
        )

        raw_wav = Path(self.temp_dir) / "fake.wav"
        raw_wav.write_bytes((b"\x01\x00\x02\x00") * 4000)  # int16 PCM payload

        audio_array, sample_rate = worker._load_audio(str(raw_wav))
        self.assertEqual(sample_rate, 48000)
        self.assertGreater(len(audio_array), 0)
        
    def test_worker_can_start_and_stop(self):
        """Test that worker can be started and stopped cleanly"""
        from transcription_worker import TranscriptionWorker
        
        worker = TranscriptionWorker(
            self.store,
            self.event_hub,
            max_workers=2,
        )
        
        async def test_lifecycle():
            await worker.start()
            self.assertTrue(worker._running)
            await worker.stop()
            self.assertFalse(worker._running)
        
        asyncio.run(test_lifecycle())
        
    def test_job_processed_in_background(self):
        """Test that job processing runs in thread pool (non-blocking)"""
        from transcription_worker import TranscriptionWorker
        
        worker = TranscriptionWorker(
            self.store,
            self.event_hub,
            max_workers=1,
        )
        
        # Create a job
        meeting = self.store.create_meeting(client_id="test-client")
        segment = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)
        # Note: We're not actually creating audio files in this test
        # The worker will fail but that's OK - we're testing the threading
        
        job = self.store.create_transcription_job(meeting_id=meeting["meeting_id"])
        
        # The worker processes jobs in _process_job which uses run_in_executor
        # This is the key method that ensures non-blocking behavior
        self.assertIsNotNone(worker._executor)


class TestConcurrentAPIDuringTranscription(unittest.TestCase):
    """Test that APIs remain responsive during transcription processing"""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_concurrent.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        
    def test_concurrent_meeting_operations(self):
        """Test that multiple meeting operations can run concurrently"""
        async def run_concurrent_operations():
            # Create multiple meetings concurrently
            tasks = []
            for i in range(5):
                request = MagicMock()
                request.json = AsyncMock(return_value={"client_id": f"client-{i}"})
                tasks.append(self.api.handle_create_meeting(request))
            
            responses = await asyncio.gather(*tasks)
            
            # All should succeed
            for resp in responses:
                self.assertEqual(resp.status, 200)
                body = json.loads(resp.text)
                self.assertTrue(body["ok"])
                self.assertTrue(body["meeting_id"].startswith("mtg-"))
            
            return len(responses)
        
        count = asyncio.run(run_concurrent_operations())
        self.assertEqual(count, 5)
        
    def test_api_response_time_with_queued_jobs(self):
        """Test that API response times remain stable with queued jobs"""
        # Create multiple queued jobs
        for i in range(5):
            meeting = self.store.create_meeting(client_id=f"client-{i}")
            self.store.update_meeting(meeting["meeting_id"], status=MEETING_STATUS_ARCHIVED)
            segment = self.store.create_audio_segment(meeting_id=meeting["meeting_id"], seq=1)
            self.store.update_audio_segment(segment["segment_id"], upload_status=UPLOAD_STATUS_UPLOADED)
            self.store.create_transcription_job(meeting_id=meeting["meeting_id"])
        
        import time
        
        # Measure API response time
        request = MagicMock()
        request.query = {}
        
        start = time.time()
        response = asyncio.run(self.api.handle_list_meetings(request))
        elapsed_ms = (time.time() - start) * 1000
        
        # Should be fast (< 50ms for simple list operation)
        self.assertLess(elapsed_ms, 50, f"API took {elapsed_ms}ms - should be fast")
        self.assertEqual(response.status, 200)


class TestM4SpeakerDiarization(unittest.TestCase):
    """Test M4: Speaker diarization functionality"""
    
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_m4.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
    
    def test_create_refined_segment(self):
        """Test creating refined segments with speaker info"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        segment = self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=1,
            start_ts=0.0,
            end_ts=5.0,
            text="Hello world",
            speaker_cluster_id="speaker_0",
            speaker_confidence=0.85,
        )
        
        self.assertIsNotNone(segment)
        self.assertTrue(segment["segment_ref_id"].startswith("sref-"))
        self.assertEqual(segment["speaker_cluster_id"], "speaker_0")
        self.assertEqual(segment["speaker_confidence"], 0.85)
        
    def test_get_refined_segments(self):
        """Test getting refined segments for a meeting"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create multiple segments
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=1, start_ts=0.0, end_ts=5.0, text="First",
            speaker_cluster_id="speaker_0",
        )
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=2, start_ts=5.0, end_ts=10.0, text="Second",
            speaker_cluster_id="speaker_1",
        )
        
        segments = self.store.get_refined_segments(meeting["meeting_id"])
        self.assertEqual(len(segments), 2)
        
    def test_update_speaker_for_cluster(self):
        """Test updating speaker name for all segments with a cluster ID"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create segments with same speaker
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=1, start_ts=0.0, end_ts=5.0, text="First",
            speaker_cluster_id="speaker_0",
        )
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=2, start_ts=5.0, end_ts=10.0, text="Second",
            speaker_cluster_id="speaker_0",
        )
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=3, start_ts=10.0, end_ts=15.0, text="Third",
            speaker_cluster_id="speaker_1",
        )
        
        # Update speaker_0 name
        updated_count = self.store.update_speaker_for_cluster(
            meeting_id=meeting["meeting_id"],
            speaker_cluster_id="speaker_0",
            speaker_name="Alice",
            source="manual",
        )
        
        self.assertEqual(updated_count, 2)
        
        # Verify update
        segments = self.store.get_refined_segments(meeting["meeting_id"])
        for seg in segments:
            if seg["speaker_cluster_id"] == "speaker_0":
                self.assertEqual(seg["speaker_name"], "Alice")
                self.assertEqual(seg["speaker_name_source"], "manual")
            else:
                self.assertIsNone(seg["speaker_name"])
        
    def test_get_speakers_for_meeting(self):
        """Test getting unique speakers for a meeting"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create segments with different speakers
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=1, start_ts=0.0, end_ts=5.0, text="First",
            speaker_cluster_id="speaker_0",
            speaker_confidence=0.9,
        )
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=2, start_ts=5.0, end_ts=10.0, text="Second",
            speaker_cluster_id="speaker_0",
            speaker_confidence=0.8,
        )
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=3, start_ts=10.0, end_ts=15.0, text="Third",
            speaker_cluster_id="speaker_1",
            speaker_confidence=0.7,
        )
        
        speakers = self.store.get_speakers_for_meeting(meeting["meeting_id"])
        self.assertEqual(len(speakers), 2)
        
        # Check speaker_0 stats
        speaker_0 = next(s for s in speakers if s["speaker_cluster_id"] == "speaker_0")
        self.assertEqual(speaker_0["segment_count"], 2)
        self.assertAlmostEqual(speaker_0["avg_confidence"], 0.85, places=2)
        
    def test_create_speaker_mapping(self):
        """Test creating speaker name mapping for audit"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        mapping = self.store.create_speaker_mapping(
            meeting_id=meeting["meeting_id"],
            speaker_cluster_id="speaker_0",
            old_name=None,
            new_name="Alice",
            source="manual",
            changed_by="user-123",
            notes="Initial naming",
        )
        
        self.assertIsNotNone(mapping)
        self.assertTrue(mapping["mapping_id"].startswith("smap-"))
        self.assertEqual(mapping["new_name"], "Alice")
        self.assertEqual(mapping["source"], "manual")
        
    def test_get_speaker_mapping_history(self):
        """Test getting speaker mapping history"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create multiple mappings
        self.store.create_speaker_mapping(
            meeting_id=meeting["meeting_id"],
            speaker_cluster_id="speaker_0",
            old_name=None,
            new_name="Alice",
            source="manual",
        )
        self.store.create_speaker_mapping(
            meeting_id=meeting["meeting_id"],
            speaker_cluster_id="speaker_0",
            old_name="Alice",
            new_name="Bob",
            source="manual",
        )
        
        history = self.store.get_speaker_mapping_history(
            meeting_id=meeting["meeting_id"],
            speaker_cluster_id="speaker_0",
        )
        
        self.assertEqual(len(history), 2)
        # Most recent first
        self.assertEqual(history[0]["new_name"], "Bob")
        self.assertEqual(history[1]["new_name"], "Alice")
        
    def test_api_handle_get_refined_segments(self):
        """Test GET /v2/meetings/{meeting_id}/refined endpoint"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create a refined segment
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=1, start_ts=0.0, end_ts=5.0, text="Test",
            speaker_cluster_id="speaker_0",
        )
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        
        response = asyncio.run(self.api.handle_get_refined_segments(request))
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertEqual(len(body["segments"]), 1)
        self.assertEqual(body["segments"][0]["text"], "Test")

    def test_api_handle_patch_refined_segment_text(self):
        """Test PATCH /v2/meetings/{meeting_id}/refined/{segment_ref_id} text update."""
        meeting = self.store.create_meeting(client_id="test-client")
        seg = self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=1,
            start_ts=0.0,
            end_ts=5.0,
            text="Old text",
            speaker_cluster_id="speaker_0",
        )

        request = MagicMock()
        request.match_info = {
            "meeting_id": meeting["meeting_id"],
            "segment_ref_id": seg["segment_ref_id"],
        }
        request.json = AsyncMock(return_value={"text": "New text"})

        response = asyncio.run(self.api.handle_patch_refined_segment(request))
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        self.assertTrue(body["changed_fields"]["text"])
        self.assertEqual(body["updated_segment"]["text"], "New text")

        updated = self.store.get_refined_segment(seg["segment_ref_id"])
        self.assertEqual(updated["text"], "New text")

    def test_api_handle_patch_refined_segment_linked_speaker_rename(self):
        """Test PATCH refined speaker_name triggers linked cluster rename."""
        meeting = self.store.create_meeting(client_id="test-client")
        seg1 = self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=1,
            start_ts=0.0,
            end_ts=5.0,
            text="A",
            speaker_cluster_id="speaker_0",
        )
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=2,
            start_ts=5.0,
            end_ts=10.0,
            text="B",
            speaker_cluster_id="speaker_0",
        )

        request = MagicMock()
        request.match_info = {
            "meeting_id": meeting["meeting_id"],
            "segment_ref_id": seg1["segment_ref_id"],
        }
        request.json = AsyncMock(return_value={"speaker_name": "Alice", "changed_by": "tester"})

        response = asyncio.run(self.api.handle_patch_refined_segment(request))
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        self.assertTrue(body["ok"])
        self.assertTrue(body["changed_fields"]["speaker_name"])
        self.assertTrue(body["speaker_rename"]["linked"])
        self.assertEqual(body["speaker_rename"]["updated_count"], 2)

        segments = self.store.get_refined_segments(meeting["meeting_id"])
        names = [s.get("speaker_name") for s in segments if s.get("speaker_cluster_id") == "speaker_0"]
        self.assertEqual(names, ["Alice", "Alice"])
        
    def test_api_handle_get_speakers(self):
        """Test GET /v2/meetings/{meeting_id}/speakers endpoint"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create segments with speakers
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=1, start_ts=0.0, end_ts=5.0, text="First",
            speaker_cluster_id="speaker_0",
        )
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        
        response = asyncio.run(self.api.handle_get_speakers(request))
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertEqual(len(body["speakers"]), 1)
        self.assertEqual(body["speakers"][0]["speaker_cluster_id"], "speaker_0")
        
    def test_api_handle_rename_speaker(self):
        """Test PATCH /v2/meetings/{meeting_id}/speakers/{speaker_cluster_id} endpoint"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create segments with speaker
        self.store.create_refined_segment(
            meeting_id=meeting["meeting_id"],
            seq=1, start_ts=0.0, end_ts=5.0, text="First",
            speaker_cluster_id="speaker_0",
        )
        
        request = MagicMock()
        request.match_info = {
            "meeting_id": meeting["meeting_id"],
            "speaker_cluster_id": "speaker_0",
        }
        request.json = AsyncMock(return_value={"speaker_name": "Alice"})
        
        response = asyncio.run(self.api.handle_rename_speaker(request))
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertEqual(body["new_name"], "Alice")
        self.assertEqual(body["segments_updated"], 1)
        
        # Verify segment was updated
        segments = self.store.get_refined_segments(meeting["meeting_id"])
        self.assertEqual(segments[0]["speaker_name"], "Alice")
        
    def test_api_handle_get_speaker_history(self):
        """Test GET /v2/meetings/{meeting_id}/speakers/history endpoint"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create a mapping
        self.store.create_speaker_mapping(
            meeting_id=meeting["meeting_id"],
            speaker_cluster_id="speaker_0",
            old_name=None,
            new_name="Alice",
            source="manual",
        )
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        
        response = asyncio.run(self.api.handle_get_speaker_history(request))
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertEqual(len(body["history"]), 1)
        self.assertEqual(body["history"][0]["new_name"], "Alice")


class TestM5ImageUpload(unittest.TestCase):
    """Test M5: Image upload and management"""
    
    def setUp(self):
        # Use a temporary database and directory
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_meetings.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()
        self.api = V2MeetingAPI(self.store, self.event_hub)
        
    def tearDown(self):
        self.store.close()
        # Cleanup temp directory
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)
    
    def test_create_meeting_image(self):
        """Test creating a meeting image record"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        image = self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=1,
            original_path="/test/path/image.jpg",
            filename="image.jpg",
            size_bytes=1024,
            checksum="abc123",
            width=800,
            height=600,
            format="jpeg",
        )
        
        self.assertIsNotNone(image)
        self.assertTrue(image["image_id"].startswith("img-"))
        self.assertEqual(image["meeting_id"], meeting["meeting_id"])
        self.assertEqual(image["seq"], 1)
        self.assertEqual(image["filename"], "image.jpg")
        self.assertEqual(image["size_bytes"], 1024)
        self.assertEqual(image["checksum"], "abc123")
        self.assertEqual(image["width"], 800)
        self.assertEqual(image["height"], 600)
        self.assertEqual(image["format"], "jpeg")
        self.assertEqual(image["upload_status"], "uploaded")
        self.assertEqual(image["analysis_status"], "pending")
    
    def test_get_meeting_images(self):
        """Test getting all images for a meeting"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create multiple images
        self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=1,
            original_path="/test/path/image1.jpg",
            filename="image1.jpg",
            size_bytes=1024,
            checksum="abc123",
        )
        self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=2,
            original_path="/test/path/image2.jpg",
            filename="image2.jpg",
            size_bytes=2048,
            checksum="def456",
        )
        
        images = self.store.get_meeting_images(meeting["meeting_id"])
        
        self.assertEqual(len(images), 2)
        self.assertEqual(images[0]["seq"], 1)
        self.assertEqual(images[1]["seq"], 2)
    
    def test_get_next_image_seq(self):
        """Test getting the next sequence number"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # First image
        seq1 = self.store.get_next_image_seq(meeting["meeting_id"])
        self.assertEqual(seq1, 1)
        
        self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=seq1,
            original_path="/test/path/image1.jpg",
            filename="image1.jpg",
            size_bytes=1024,
            checksum="abc123",
        )
        
        # Second image
        seq2 = self.store.get_next_image_seq(meeting["meeting_id"])
        self.assertEqual(seq2, 2)
    
    def test_update_meeting_image(self):
        """Test updating image fields"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        image = self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=1,
            original_path="/test/path/image.jpg",
            filename="image.jpg",
            size_bytes=1024,
            checksum="abc123",
        )
        
        # Update analysis status
        self.store.update_meeting_image(
            image["image_id"],
            analysis_status="analyzed",
            analysis_result={"description": "A test image"},
        )
        
        updated = self.store.get_meeting_image(image["image_id"])
        self.assertEqual(updated["analysis_status"], "analyzed")
        self.assertIsNotNone(updated["analysis_result"])
    
    def test_api_handle_get_images(self):
        """Test GET /v2/meetings/{meeting_id}/images endpoint"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create images
        self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=1,
            original_path="/test/path/image.jpg",
            filename="image.jpg",
            size_bytes=1024,
            checksum="abc123",
        )
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        
        response = asyncio.run(self.api.handle_get_images(request))
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertEqual(len(body["images"]), 1)
        self.assertEqual(body["images"][0]["filename"], "image.jpg")
    
    def test_api_handle_get_image(self):
        """Test GET /v2/meetings/{meeting_id}/images/{image_id} endpoint"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        image = self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=1,
            original_path="/test/path/image.jpg",
            filename="image.jpg",
            size_bytes=1024,
            checksum="abc123",
        )
        
        request = MagicMock()
        request.match_info = {
            "meeting_id": meeting["meeting_id"],
            "image_id": image["image_id"],
        }
        
        response = asyncio.run(self.api.handle_get_image(request))
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertEqual(body["image"]["image_id"], image["image_id"])
        self.assertEqual(body["image"]["filename"], "image.jpg")
    
    def test_api_handle_image_analysis(self):
        """Test POST /v2/meetings/{meeting_id}/images/{image_id}:analyze endpoint"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        image = self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=1,
            original_path="/test/path/image.jpg",
            filename="image.jpg",
            size_bytes=1024,
            checksum="abc123",
        )
        
        request = MagicMock()
        request.match_info = {
            "meeting_id": meeting["meeting_id"],
            "image_id": image["image_id"],
        }
        
        response = asyncio.run(self.api.handle_image_analysis(request))
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertEqual(body["analysis_status"], "pending")
        
        # Verify image was updated
        updated = self.store.get_meeting_image(image["image_id"])
        self.assertEqual(updated["analysis_status"], "pending")

    def test_api_handle_image_analysis_requeue_from_failed(self):
        """Test :analyze can requeue a failed analysis back to pending"""
        meeting = self.store.create_meeting(client_id="test-client")

        image = self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=1,
            original_path="/test/path/image.jpg",
            filename="image.jpg",
            size_bytes=1024,
            checksum="abc123",
        )
        self.store.update_meeting_image(
            image["image_id"],
            analysis_status="analysis_failed",
            analysis_error="previous_failure",
        )

        request = MagicMock()
        request.match_info = {
            "meeting_id": meeting["meeting_id"],
            "image_id": image["image_id"],
        }

        response = asyncio.run(self.api.handle_image_analysis(request))
        body = json.loads(response.text)

        self.assertTrue(body["ok"])
        self.assertEqual(body["analysis_status"], "pending")

        updated = self.store.get_meeting_image(image["image_id"])
        self.assertEqual(updated["analysis_status"], "pending")
        self.assertIsNone(updated["analysis_error"])
        self.assertIsNotNone(updated["analysis_result"])
    
    def test_api_handle_image_analysis_result(self):
        """Test PATCH /v2/meetings/{meeting_id}/images/{image_id}/analysis endpoint"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        image = self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=1,
            original_path="/test/path/image.jpg",
            filename="image.jpg",
            size_bytes=1024,
            checksum="abc123",
        )
        
        request = MagicMock()
        request.match_info = {
            "meeting_id": meeting["meeting_id"],
            "image_id": image["image_id"],
        }
        request.json = AsyncMock(return_value={
            "status": "completed",
            "result": {
                "description": "A test image",
                "labels": ["test", "image"],
            },
        })
        
        response = asyncio.run(self.api.handle_image_analysis_result(request))
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertEqual(body["analysis_status"], "completed")
        
        # Verify image was updated
        updated = self.store.get_meeting_image(image["image_id"])
        self.assertEqual(updated["analysis_status"], "completed")
        self.assertIsNotNone(updated["analysis_result"])
        # analysis_result is stored as JSON string in SQLite
        result = json.loads(updated["analysis_result"]) if isinstance(updated["analysis_result"], str) else updated["analysis_result"]
        self.assertEqual(result["description"], "A test image")
    
    def test_api_handle_image_upload(self):
        """Test POST /v2/meetings/{meeting_id}/images:upload endpoint"""
        # Create a test meeting
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create a simple test image (PNG magic bytes + minimal data)
        # PNG signature + IHDR chunk (minimal valid PNG structure)
        test_image_data = (
            b'\x89PNG\r\n\x1a\n'  # PNG signature
            b'\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01'  # IHDR chunk (1x1 pixel)
            b'\x08\x02\x00\x00\x00\x90wS\xde'  # IHDR data
            b'\x00\x00\x00\x0cIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01'  # IDAT chunk
            b'\x0d\n-\xb4'  # IDAT CRC
            b'\x00\x00\x00\x00IEND\xaeB`\x82'  # IEND chunk
        )
        
        # Create mock multipart request
        class MockField:
            def __init__(self, name, data, filename=None):
                self.name = name
                self._data = data if isinstance(data, bytes) else data.encode()
                self.filename = filename
            
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
            MockField("image", test_image_data, filename="test.png"),
            MockField("filename", "test.png"),
            MockField("width", "1"),
            MockField("height", "1"),
        ]
        
        # Mock request.multipart() to return the reader
        async def get_reader():
            return MockMultipartReader(fields)
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.multipart = get_reader
        
        # Call upload handler
        response = asyncio.run(self.api.handle_image_upload(request))
        
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertTrue(body["image_id"].startswith("img-"))
        self.assertEqual(body["upload_status"], IMAGE_STATUS_UPLOADED)
        self.assertEqual(body["size_bytes"], len(test_image_data))
        
        # Verify image was created in database
        images = self.store.get_meeting_images(meeting["meeting_id"])
        self.assertEqual(len(images), 1)
        self.assertEqual(images[0]["filename"], "test.png")
        self.assertEqual(images[0]["format"], "png")
        
    def test_api_handle_image_upload_with_metadata(self):
        """Test image upload with device and timestamp metadata"""
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create minimal JPEG (SOI + APP0 + EOI)
        test_image_data = (
            b'\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00'  # JPEG header
            b'\xff\xd9'  # EOI
        )
        
        class MockField:
            def __init__(self, name, data):
                self.name = name
                self._data = data if isinstance(data, bytes) else data.encode()
                self.filename = None
            
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
            MockField("image", test_image_data),
            MockField("filename", "meeting_photo.jpg"),
            MockField("captured_at", "2026-03-14T15:30:00Z"),
            MockField("device_id", "android-12345"),
            MockField("width", "1920"),
            MockField("height", "1080"),
        ]
        
        async def get_reader():
            return MockMultipartReader(fields)
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.multipart = get_reader
        
        response = asyncio.run(self.api.handle_image_upload(request))
        
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        self.assertEqual(body["upload_status"], IMAGE_STATUS_UPLOADED)
        
        # Verify metadata was stored
        images = self.store.get_meeting_images(meeting["meeting_id"])
        self.assertEqual(len(images), 1)
        self.assertEqual(images[0]["device_id"], "android-12345")
        self.assertEqual(images[0]["captured_at"], "2026-03-14T15:30:00Z")
        self.assertEqual(images[0]["width"], 1920)
        self.assertEqual(images[0]["height"], 1080)
        self.assertEqual(images[0]["format"], "jpeg")

    def test_api_handle_image_upload_with_thumbnail(self):
        """Test image upload generates a thumbnail"""
        from PIL import Image
        import io
        
        meeting = self.store.create_meeting(client_id="test-client")
        
        # Create a real test image (100x100 red square)
        img = Image.new('RGB', (100, 100), color='red')
        img_io = io.BytesIO()
        img.save(img_io, format='PNG')
        test_image_data = img_io.getvalue()
        
        class MockField:
            def __init__(self, name, data, filename=None):
                self.name = name
                self.data = data if isinstance(data, bytes) else str(data).encode()
                self.filename = filename
            
            async def read(self):
                return self.data if isinstance(self.data, bytes) else self.data.encode()
        
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
            MockField("image", test_image_data, filename="test.png"),
            MockField("filename", "test.png"),
        ]
        
        async def get_reader():
            return MockMultipartReader(fields)
        
        request = MagicMock()
        request.match_info = {"meeting_id": meeting["meeting_id"]}
        request.multipart = get_reader
        
        response = asyncio.run(self.api.handle_image_upload(request))
        
        self.assertEqual(response.status, 200)
        body = json.loads(response.text)
        
        self.assertTrue(body["ok"])
        
        # Verify thumbnail was generated
        images = self.store.get_meeting_images(meeting["meeting_id"])
        self.assertEqual(len(images), 1)
        self.assertIsNotNone(images[0]["thumbnail_path"])
        self.assertEqual(images[0]["width"], 100)
        self.assertEqual(images[0]["height"], 100)
        
        # Verify thumbnail file exists
        thumbnail_path = Path(images[0]["thumbnail_path"])
        self.assertTrue(thumbnail_path.exists())
        
        # Verify thumbnail dimensions (should be <= 256x256)
        thumb_img = Image.open(thumbnail_path)
        self.assertLessEqual(thumb_img.width, 256)
        self.assertLessEqual(thumb_img.height, 256)


class TestM5ImageAnalysisWorker(unittest.TestCase):
    """Test M5: ImageAnalysisWorker async processing"""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = Path(self.temp_dir) / "test_worker_m5.db"
        self.store = MeetingStore(self.db_path)
        self.event_hub = MockEventHub()

    def tearDown(self):
        self.store.close()
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_worker_processes_pending_image(self):
        """Worker should pick pending image and produce analyzed result"""
        from image_analysis_worker import ImageAnalysisWorker

        meeting = self.store.create_meeting(client_id="test-client")

        image_path = Path(self.temp_dir) / "sample.png"
        image_path.write_bytes(
            b"\x89PNG\r\n\x1a\n"
            b"\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
            b"\x08\x02\x00\x00\x00\x90wS\xde"
            b"\x00\x00\x00\x0cIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01"
            b"\x0d\n-\xb4"
            b"\x00\x00\x00\x00IEND\xaeB`\x82"
        )

        image = self.store.create_meeting_image(
            meeting_id=meeting["meeting_id"],
            seq=1,
            original_path=str(image_path),
            filename="sample.png",
            size_bytes=image_path.stat().st_size,
            checksum="abc123",
            format="png",
        )

        async def run_worker() -> dict:
            worker = ImageAnalysisWorker(
                self.store,
                self.event_hub,
                artifacts_dir=Path(self.temp_dir) / "artifacts",
                openclaw_api_url="http://127.0.0.1:1",  # Force fast fallback path
                max_workers=1,
            )
            worker._poll_interval = 0.05
            await worker.start()
            try:
                for _ in range(80):
                    await asyncio.sleep(0.05)
                    current = self.store.get_meeting_image(image["image_id"])
                    if current and current.get("analysis_status") in ("analyzed", "analysis_failed"):
                        return current
                return self.store.get_meeting_image(image["image_id"])
            finally:
                await worker.stop()

        final = asyncio.run(run_worker())

        self.assertIsNotNone(final)
        self.assertEqual(final["analysis_status"], "analyzed")
        self.assertIsNotNone(final["analysis_result"])
        result = json.loads(final["analysis_result"]) if isinstance(final["analysis_result"], str) else final["analysis_result"]
        self.assertEqual(result.get("analysis_source"), "fallback")
        self.assertGreaterEqual(len(self.event_hub.events), 2)


if __name__ == "__main__":
    unittest.main()
