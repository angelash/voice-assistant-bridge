"""
Voice Assistant Bridge - V2 Meeting API Routes

Provides:
- POST /v2/meetings - Create meeting
- POST /v2/meetings/{meeting_id}/mode - Toggle meeting mode on/off
- GET /v2/events/stream - WebSocket event stream
- GET /v2/meetings - List meetings
- GET /v2/meetings/{meeting_id} - Get meeting details
- POST /v2/meetings/{meeting_id}/audio:upload - Upload audio segment (M2)
"""

from __future__ import annotations

import hashlib
import logging
import os
from pathlib import Path
from typing import Any, Optional

from aiohttp import web

from meeting import (
    MeetingStore,
    EVT_MEETING_MODE_ON,
    EVT_MEETING_MODE_OFF,
    EVT_AUDIO_SEGMENT_UPLOADED,
    EVT_AUDIO_SEGMENT_UPLOAD_FAILED,
    EVT_TRANSCRIPTION_STARTED,
    EVT_TRANSCRIPTION_COMPLETED,
    EVT_TRANSCRIPTION_FAILED,
    EVT_SPEAKER_IDENTIFIED,
    EVT_SPEAKER_RENAMED,
    EVT_IMAGE_UPLOADED,
    EVT_IMAGE_UPLOAD_FAILED,
    EVT_IMAGE_ANALYSIS_STARTED,
    EVT_IMAGE_ANALYSIS_COMPLETED,
    EVT_IMAGE_ANALYSIS_FAILED,
    MEETING_STATUS_IDLE,
    MEETING_STATUS_PREP,
    MEETING_STATUS_ACTIVE,
    MEETING_STATUS_ENDING,
    MEETING_STATUS_ARCHIVED,
    JOB_STATUS_QUEUED,
    JOB_STATUS_RUNNING,
    JOB_STATUS_SUCCESS,
    JOB_STATUS_FAILED,
    SPEAKER_SOURCE_MANUAL,
    IMAGE_STATUS_UPLOADED,
    IMAGE_STATUS_FAILED,
    build_event_envelope,
    now_iso,
)

logger = logging.getLogger(__name__)


class V2MeetingAPI:
    """V2 Meeting API handler."""

    def __init__(self, store: MeetingStore, event_hub: Any, transcription_worker: Any = None):
        self.store = store
        self.event_hub = event_hub
        self.transcription_worker = transcription_worker

    async def handle_create_meeting(self, request: web.Request) -> web.Response:
        """POST /v2/meetings - Create a new meeting session."""
        try:
            data = await request.json()
        except Exception:
            return web.json_response({"ok": False, "error": "invalid_json"}, status=400)

        client_id = (data.get("client_id") or "unknown-client").strip()
        session_id = data.get("session_id")
        meta = data.get("meta")

        # Check if there's already an active meeting
        active = self.store.get_active_meeting(client_id)
        if active:
            return web.json_response({
                "ok": False,
                "error": "active_meeting_exists",
                "message": "An active meeting already exists for this client",
                "active_meeting_id": active["meeting_id"],
            }, status=409)

        meeting = self.store.create_meeting(
            client_id=client_id,
            session_id=session_id,
            meta=meta,
        )

        return web.json_response({
            "ok": True,
            "meeting_id": meeting["meeting_id"],
            "client_id": meeting["client_id"],
            "session_id": meeting.get("session_id"),
            "status": meeting["status"],
            "created_at": meeting["created_at"],
        })

    async def handle_meeting_mode(self, request: web.Request) -> web.Response:
        """POST /v2/meetings/{meeting_id}/mode - Toggle meeting mode on/off."""
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        try:
            data = await request.json()
        except Exception:
            return web.json_response({"ok": False, "error": "invalid_json"}, status=400)

        mode = (data.get("mode") or "").strip().lower()
        if mode not in ("on", "off"):
            return web.json_response({
                "ok": False,
                "error": "mode must be 'on' or 'off'",
            }, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        current_status = meeting.get("status")

        if mode == "on":
            if current_status not in (MEETING_STATUS_IDLE, MEETING_STATUS_PREP):
                # Already active or in another state
                return web.json_response({
                    "ok": True,
                    "meeting_id": meeting_id,
                    "status": current_status,
                    "mode": "on",
                    "message": "Meeting already active",
                })

            # Start meeting
            ts = now_iso()
            self.store.update_meeting(
                meeting_id,
                status=MEETING_STATUS_ACTIVE,
                mode="on",
                started_at=ts,
            )

            # Emit event
            event = self.store.append_event(
                meeting_id=meeting_id,
                source="system",
                event_type=EVT_MEETING_MODE_ON,
                payload={"started_at": ts},
            )
            await self.event_hub.publish(build_event_envelope(event))

            return web.json_response({
                "ok": True,
                "meeting_id": meeting_id,
                "status": MEETING_STATUS_ACTIVE,
                "mode": "on",
                "started_at": ts,
            })

        else:  # mode == "off"
            if current_status != MEETING_STATUS_ACTIVE:
                return web.json_response({
                    "ok": True,
                    "meeting_id": meeting_id,
                    "status": current_status,
                    "mode": "off",
                    "message": "Meeting not active",
                })

            # End meeting
            ts = now_iso()
            self.store.update_meeting(
                meeting_id,
                status=MEETING_STATUS_ENDING,
                mode="off",
                ended_at=ts,
            )

            # Emit event
            event = self.store.append_event(
                meeting_id=meeting_id,
                source="system",
                event_type=EVT_MEETING_MODE_OFF,
                payload={"ended_at": ts},
            )
            await self.event_hub.publish(build_event_envelope(event))

            # M3: Auto-create transcription job when meeting ends
            transcription_job = None
            segments = self.store.get_audio_segments(meeting_id)
            uploaded_segments = [s for s in segments if s.get("upload_status") == "uploaded"]
            
            if uploaded_segments:
                # Check for existing job
                existing = self.store.get_latest_transcription_job(meeting_id)
                if not existing or existing.get("status") in (JOB_STATUS_FAILED, JOB_STATUS_CANCELLED):
                    job = self.store.create_transcription_job(meeting_id=meeting_id)
                    transcription_job = {
                        "job_id": job["job_id"],
                        "status": job["status"],
                    }
                    logger.info(f"Auto-created transcription job {job['job_id']} for meeting {meeting_id}")
                elif existing:
                    transcription_job = {
                        "job_id": existing["job_id"],
                        "status": existing["status"],
                        "existing": True,
                    }

            # Mark as archived
            self.store.update_meeting(meeting_id, status=MEETING_STATUS_ARCHIVED)

            return web.json_response({
                "ok": True,
                "meeting_id": meeting_id,
                "status": MEETING_STATUS_ARCHIVED,
                "mode": "off",
                "ended_at": ts,
                "transcription_job": transcription_job,
            })

    async def handle_list_meetings(self, request: web.Request) -> web.Response:
        """GET /v2/meetings - List meetings."""
        status = request.query.get("status")
        client_id = request.query.get("client_id")
        limit = int(request.query.get("limit", "50"))
        offset = int(request.query.get("offset", "0"))

        meetings = self.store.list_meetings(
            status=status,
            client_id=client_id,
            limit=min(limit, 100),
            offset=offset,
        )

        return web.json_response({
            "ok": True,
            "meetings": meetings,
            "count": len(meetings),
        })

    async def handle_get_meeting(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id} - Get meeting details."""
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        # Get audio segments
        segments = self.store.get_audio_segments(meeting_id)

        return web.json_response({
            "ok": True,
            "meeting": meeting,
            "audio_segments": segments,
        })

    async def handle_get_timeline(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/timeline - Get meeting event timeline."""
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        after_seq = request.query.get("after_seq")
        after_seq_int = int(after_seq) if after_seq else None
        limit = int(request.query.get("limit", "100"))

        events = self.store.get_events(
            meeting_id,
            after_seq=after_seq_int,
            limit=min(limit, 500),
        )

        envelopes = [build_event_envelope(e) for e in events]

        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "events": envelopes,
            "count": len(envelopes),
        })

    async def handle_events_batch(self, request: web.Request) -> web.Response:
        """POST /v2/meetings/{meeting_id}/events:batch - Append multiple events."""
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        try:
            data = await request.json()
        except Exception:
            return web.json_response({"ok": False, "error": "invalid_json"}, status=400)

        events_data = data.get("events", [])
        if not isinstance(events_data, list):
            return web.json_response({"ok": False, "error": "events must be a list"}, status=400)

        # Check meeting exists
        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        created_events = []
        for i, evt in enumerate(events_data):
            if not isinstance(evt, dict):
                continue
            event_type = evt.get("event_type")
            if not event_type:
                continue
            event = self.store.append_event(
                meeting_id=meeting_id,
                source=evt.get("source", "client"),
                event_type=event_type,
                seq=evt.get("seq"),
                ts_client=evt.get("ts_client"),
                payload=evt.get("payload"),
            )
            envelope = build_event_envelope(event)
            created_events.append(envelope)
            await self.event_hub.publish(envelope)

        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "created_count": len(created_events),
            "events": created_events,
        })

    async def handle_audio_upload(self, request: web.Request) -> web.Response:
        """POST /v2/meetings/{meeting_id}/audio:upload - Upload audio segment.
        
        M2: Audio upload with checksum verification and state management.
        
        Request body (multipart/form-data):
        - segment_id: Segment identifier
        - seq: Segment sequence number
        - checksum: SHA256 checksum of the audio data
        - audio: Audio file content
        
        Returns:
        - ok: True/False
        - segment_id: The uploaded segment ID
        - upload_status: pending/uploaded/failed
        - checksum_verified: Boolean
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        # Check meeting exists
        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        try:
            reader = await request.multipart()
            
            segment_id = None
            seq = None
            checksum_expected = None
            audio_data = None
            
            async for field in reader:
                if field.name == "segment_id":
                    segment_id = (await field.read()).decode("utf-8").strip()
                elif field.name == "seq":
                    seq = int((await field.read()).decode("utf-8"))
                elif field.name == "checksum":
                    checksum_expected = (await field.read()).decode("utf-8").strip().lower()
                elif field.name == "audio":
                    audio_data = await field.read()
            
            # Validate required fields
            if not segment_id:
                return web.json_response({"ok": False, "error": "segment_id required"}, status=400)
            if audio_data is None:
                return web.json_response({"ok": False, "error": "audio data required"}, status=400)
            if seq is None:
                return web.json_response({"ok": False, "error": "seq required"}, status=400)
            
            # Ensure segment record exists (create if not)
            existing_segment = self.store.get_audio_segment(segment_id)
            if not existing_segment:
                self.store.create_audio_segment(
                    meeting_id=meeting_id,
                    seq=seq,
                    segment_id=segment_id,
                )
            
            # Compute checksum
            checksum_actual = hashlib.sha256(audio_data).hexdigest()
            checksum_verified = checksum_expected is None or checksum_actual == checksum_expected
            
            if not checksum_verified:
                logger.warning(f"Checksum mismatch for segment {segment_id}: expected {checksum_expected}, got {checksum_actual}")
                
                # Update segment state to failed
                self.store.update_audio_segment(
                    segment_id,
                    upload_status="failed",
                    upload_error="checksum_mismatch",
                )
                
                # Emit upload failed event
                event = self.store.append_event(
                    meeting_id=meeting_id,
                    source="server",
                    event_type=EVT_AUDIO_SEGMENT_UPLOAD_FAILED,
                    payload={
                        "segment_id": segment_id,
                        "seq": seq,
                        "error": "checksum_mismatch",
                        "expected": checksum_expected,
                        "actual": checksum_actual,
                    },
                )
                await self.event_hub.publish(build_event_envelope(event))
                
                return web.json_response({
                    "ok": False,
                    "error": "checksum_mismatch",
                    "segment_id": segment_id,
                    "checksum_verified": False,
                }, status=400)
            
            # Save audio file
            artifacts_dir = Path("artifacts/meetings") / meeting_id / "audio" / "raw"
            artifacts_dir.mkdir(parents=True, exist_ok=True)
            
            audio_filename = f"{segment_id}.wav"
            audio_path = artifacts_dir / audio_filename
            
            with open(audio_path, "wb") as f:
                f.write(audio_data)
            
            # Update segment state to uploaded
            self.store.update_audio_segment(
                segment_id,
                local_path=str(audio_path),
                checksum=checksum_actual,
                size_bytes=len(audio_data),
                upload_status="uploaded",
                uploaded_at=now_iso(),
            )
            
            # Emit upload success event
            event = self.store.append_event(
                meeting_id=meeting_id,
                source="server",
                event_type=EVT_AUDIO_SEGMENT_UPLOADED,
                payload={
                    "segment_id": segment_id,
                    "seq": seq,
                    "size_bytes": len(audio_data),
                    "checksum": checksum_actual,
                    "path": str(audio_path),
                },
            )
            await self.event_hub.publish(build_event_envelope(event))
            
            logger.info(f"Audio segment uploaded: {segment_id}, size={len(audio_data)}, checksum={checksum_actual[:16]}...")
            
            return web.json_response({
                "ok": True,
                "segment_id": segment_id,
                "upload_status": "uploaded",
                "checksum_verified": True,
                "size_bytes": len(audio_data),
                "path": str(audio_path),
            })
            
        except Exception as e:
            logger.error(f"Audio upload failed: {e}")
            return web.json_response({
                "ok": False,
                "error": str(e),
            }, status=500)

    async def handle_get_upload_manifest(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/audio/manifest - Get upload manifest.
        
        M2: Returns the upload status of all audio segments for a meeting.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        segments = self.store.get_audio_segments(meeting_id)
        
        # Build upload manifest
        manifest = {
            "meeting_id": meeting_id,
            "total_segments": len(segments),
            "uploaded_count": sum(1 for s in segments if s.get("upload_status") == "uploaded"),
            "pending_count": sum(1 for s in segments if s.get("upload_status") == "pending"),
            "failed_count": sum(1 for s in segments if s.get("upload_status") == "failed"),
            "segments": [
                {
                    "segment_id": s["segment_id"],
                    "seq": s["seq"],
                    "upload_status": s.get("upload_status", "pending"),
                    "size_bytes": s.get("size_bytes"),
                    "checksum": s.get("checksum"),
                    "uploaded_at": s.get("uploaded_at"),
                    "upload_error": s.get("upload_error"),
                }
                for s in segments
            ],
        }
        
        return web.json_response({
            "ok": True,
            "manifest": manifest,
        })

    async def handle_get_pending_uploads(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/audio/pending - Get pending uploads.
        
        M2: Returns segments that need to be uploaded (for retry queue).
        Includes both pending and failed segments.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        pending = self.store.get_pending_audio_segments(meeting_id)
        failed = self.store.get_failed_audio_segments(meeting_id)
        
        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "pending_segments": pending,
            "failed_segments": failed,
            "pending_count": len(pending),
            "failed_count": len(failed),
            "total_needs_upload": len(pending) + len(failed),
        })

    async def handle_reset_failed_upload(self, request: web.Request) -> web.Response:
        """POST /v2/meetings/{meeting_id}/audio:reset-failed - Reset failed uploads for retry.
        
        M2: Resets failed segments back to pending status so they can be retried.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        failed = self.store.get_failed_audio_segments(meeting_id)
        reset_count = 0
        
        for segment in failed:
            self.store.update_audio_segment(
                segment["segment_id"],
                upload_status="pending",
                upload_error=None,
            )
            reset_count += 1
        
        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "reset_count": reset_count,
            "message": f"Reset {reset_count} failed segments to pending status",
        })

    # --- M3: Transcription Jobs ---

    async def handle_create_transcription_job(self, request: web.Request) -> web.Response:
        """POST /v2/meetings/{meeting_id}/transcription:run - Create a transcription job.
        
        M3: Creates a new transcription job for the meeting audio.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        try:
            data = await request.json()
        except Exception:
            data = {}

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        # Check if meeting has ended
        if meeting.get("status") not in (MEETING_STATUS_ENDING, MEETING_STATUS_ARCHIVED):
            return web.json_response({
                "ok": False,
                "error": "meeting_not_ended",
                "message": "Can only run transcription on ended meetings",
            }, status=400)

        # Check for existing job
        existing = self.store.get_latest_transcription_job(meeting_id)
        if existing and existing.get("status") in (JOB_STATUS_QUEUED, JOB_STATUS_RUNNING):
            return web.json_response({
                "ok": False,
                "error": "job_in_progress",
                "existing_job_id": existing["job_id"],
                "existing_status": existing["status"],
            }, status=409)

        # Create new job
        engine = data.get("engine", "faster-whisper")
        model = data.get("model", "small")
        
        job = self.store.create_transcription_job(
            meeting_id=meeting_id,
            engine=engine,
            model=model,
        )

        logger.info(f"Created transcription job {job['job_id']} for meeting {meeting_id}")

        return web.json_response({
            "ok": True,
            "job_id": job["job_id"],
            "meeting_id": meeting_id,
            "status": job["status"],
            "engine": engine,
            "model": model,
        })

    async def handle_get_transcription_jobs(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/transcription - Get transcription jobs for a meeting.
        
        M3: Returns all transcription jobs for a meeting.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        jobs = self.store.get_transcription_jobs_for_meeting(meeting_id)

        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "jobs": jobs,
            "count": len(jobs),
        })

    async def handle_get_transcription_job(self, request: web.Request) -> web.Response:
        """GET /v2/transcription/{job_id} - Get transcription job status.
        
        M3: Returns the status of a specific transcription job.
        """
        job_id = request.match_info.get("job_id", "").strip()
        if not job_id:
            return web.json_response({"ok": False, "error": "job_id required"}, status=400)

        job = self.store.get_transcription_job(job_id)
        if not job:
            return web.json_response({"ok": False, "error": "job_not_found"}, status=404)

        return web.json_response({
            "ok": True,
            "job": job,
        })

    async def handle_cancel_transcription_job(self, request: web.Request) -> web.Response:
        """POST /v2/transcription/{job_id}:cancel - Cancel a queued transcription job.
        
        M3: Cancels a queued (not yet running) transcription job.
        """
        job_id = request.match_info.get("job_id", "").strip()
        if not job_id:
            return web.json_response({"ok": False, "error": "job_id required"}, status=400)

        job = self.store.get_transcription_job(job_id)
        if not job:
            return web.json_response({"ok": False, "error": "job_not_found"}, status=404)

        if job["status"] != JOB_STATUS_QUEUED:
            return web.json_response({
                "ok": False,
                "error": "cannot_cancel",
                "message": f"Cannot cancel job with status {job['status']}",
            }, status=400)

        self.store.update_transcription_job(job_id, status="cancelled")

        return web.json_response({
            "ok": True,
            "job_id": job_id,
            "status": "cancelled",
        })

    async def handle_list_transcription_queue(self, request: web.Request) -> web.Response:
        """GET /v2/transcription/queue - List transcription job queue.
        
        M3: Returns all queued and running transcription jobs.
        """
        queued = self.store.get_queued_transcription_jobs(limit=50)
        
        return web.json_response({
            "ok": True,
            "queued_jobs": queued,
            "count": len(queued),
        })

    # --- M4: Refined Segments and Speaker Management ---

    async def handle_get_refined_segments(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/refined - Get refined segments with speaker info.
        
        M4: Returns all refined segments for a meeting.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        segments = self.store.get_refined_segments(meeting_id)
        
        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "segments": segments,
            "count": len(segments),
        })

    async def handle_get_speakers(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/speakers - Get speakers for a meeting.
        
        M4: Returns unique speakers with segment counts and timing.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        speakers = self.store.get_speakers_for_meeting(meeting_id)
        
        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "speakers": speakers,
            "count": len(speakers),
        })

    async def handle_rename_speaker(self, request: web.Request) -> web.Response:
        """PATCH /v2/meetings/{meeting_id}/speakers/{speaker_cluster_id} - Rename a speaker.
        
        M4: Renames a speaker and updates all associated segments.
        Records the change in audit history.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        speaker_cluster_id = request.match_info.get("speaker_cluster_id", "").strip()
        
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)
        if not speaker_cluster_id:
            return web.json_response({"ok": False, "error": "speaker_cluster_id required"}, status=400)

        try:
            data = await request.json()
        except Exception:
            return web.json_response({"ok": False, "error": "invalid_json"}, status=400)

        new_name = (data.get("speaker_name") or "").strip()
        if not new_name:
            return web.json_response({
                "ok": False,
                "error": "speaker_name required",
            }, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        # Get current speaker name
        speakers = self.store.get_speakers_for_meeting(meeting_id)
        speaker_info = next((s for s in speakers if s["speaker_cluster_id"] == speaker_cluster_id), None)
        if not speaker_info:
            return web.json_response({
                "ok": False,
                "error": "speaker_not_found",
                "message": f"Speaker {speaker_cluster_id} not found in meeting",
            }, status=404)

        old_name = speaker_info.get("speaker_name")

        # Update all segments with this speaker
        updated_count = self.store.update_speaker_for_cluster(
            meeting_id=meeting_id,
            speaker_cluster_id=speaker_cluster_id,
            speaker_name=new_name,
            source=SPEAKER_SOURCE_MANUAL,
        )

        # Create audit record
        mapping = self.store.create_speaker_mapping(
            meeting_id=meeting_id,
            speaker_cluster_id=speaker_cluster_id,
            old_name=old_name,
            new_name=new_name,
            source=SPEAKER_SOURCE_MANUAL,
            changed_by=data.get("changed_by"),
            notes=data.get("notes"),
        )

        # Emit event
        event = self.store.append_event(
            meeting_id=meeting_id,
            source="api",
            event_type=EVT_SPEAKER_RENAMED,
            payload={
                "speaker_cluster_id": speaker_cluster_id,
                "old_name": old_name,
                "new_name": new_name,
                "segments_updated": updated_count,
                "mapping_id": mapping["mapping_id"],
            },
        )
        await self.event_hub.publish(build_event_envelope(event))

        logger.info(f"Renamed speaker {speaker_cluster_id} to '{new_name}' in meeting {meeting_id} ({updated_count} segments)")

        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "speaker_cluster_id": speaker_cluster_id,
            "old_name": old_name,
            "new_name": new_name,
            "segments_updated": updated_count,
            "mapping_id": mapping["mapping_id"],
        })

    async def handle_get_speaker_history(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/speakers/history - Get speaker rename history.
        
        M4: Returns the audit log of speaker name changes.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        speaker_cluster_id = request.match_info.get("speaker_cluster_id")
        
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        history = self.store.get_speaker_mapping_history(
            meeting_id=meeting_id,
            speaker_cluster_id=speaker_cluster_id,
        )

        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "history": history,
            "count": len(history),
        })

    # --- M5: Image Upload ---

    async def handle_image_upload(self, request: web.Request) -> web.Response:
        """POST /v2/meetings/{meeting_id}/images:upload - Upload an image.
        
        M5: Upload original image with metadata preservation.
        
        Request body (multipart/form-data):
        - image: Image file content (required)
        - filename: Original filename (optional)
        - captured_at: Capture timestamp ISO string (optional)
        - device_id: Device identifier (optional)
        - width: Image width in pixels (optional)
        - height: Image height in pixels (optional)
        - format: Image format (jpeg/png/webp, optional)
        
        Returns:
        - ok: True/False
        - image_id: The uploaded image ID
        - upload_status: uploaded/failed
        - checksum_verified: Boolean
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        # Check meeting exists
        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        try:
            reader = await request.multipart()
            
            image_data = None
            filename = None
            captured_at = None
            device_id = None
            width = None
            height = None
            image_format = None
            
            async for field in reader:
                if field.name == "image":
                    image_data = await field.read()
                    # Try to extract filename from content-disposition
                    if field.filename:
                        filename = field.filename
                elif field.name == "filename":
                    filename = (await field.read()).decode("utf-8").strip()
                elif field.name == "captured_at":
                    captured_at = (await field.read()).decode("utf-8").strip()
                elif field.name == "device_id":
                    device_id = (await field.read()).decode("utf-8").strip()
                elif field.name == "width":
                    width = int((await field.read()).decode("utf-8"))
                elif field.name == "height":
                    height = int((await field.read()).decode("utf-8"))
                elif field.name == "format":
                    image_format = (await field.read()).decode("utf-8").strip().lower()
            
            # Validate required fields
            if image_data is None:
                return web.json_response({"ok": False, "error": "image data required"}, status=400)
            
            # Compute checksum
            checksum = hashlib.sha256(image_data).hexdigest()
            
            # Determine format from data if not provided
            if not image_format:
                # Simple format detection from magic bytes
                if image_data[:8] == b'\x89PNG\r\n\x1a\n':
                    image_format = "png"
                elif image_data[:2] == b'\xff\xd8':
                    image_format = "jpeg"
                elif image_data[:4] == b'RIFF' and image_data[8:12] == b'WEBP':
                    image_format = "webp"
                else:
                    image_format = "unknown"
            
            # Default filename if not provided
            if not filename:
                ext = image_format if image_format != "unknown" else "bin"
                filename = f"image_{checksum[:8]}.{ext}"
            
            # Get next sequence number
            seq = self.store.get_next_image_seq(meeting_id)
            
            # Save original image
            artifacts_dir = Path("artifacts/meetings") / meeting_id / "images" / "original"
            artifacts_dir.mkdir(parents=True, exist_ok=True)
            
            # Use checksum-based filename to avoid duplicates
            ext = filename.rsplit(".", 1)[-1] if "." in filename else image_format
            image_filename = f"{seq:04d}_{checksum[:16]}.{ext}"
            image_path = artifacts_dir / image_filename
            
            with open(image_path, "wb") as f:
                f.write(image_data)
            
            # Create image record
            image = self.store.create_meeting_image(
                meeting_id=meeting_id,
                seq=seq,
                original_path=str(image_path),
                filename=filename,
                size_bytes=len(image_data),
                checksum=checksum,
                width=width,
                height=height,
                format=image_format,
                device_id=device_id,
                captured_at=captured_at,
            )
            
            # Emit upload success event
            event = self.store.append_event(
                meeting_id=meeting_id,
                source="server",
                event_type=EVT_IMAGE_UPLOADED,
                payload={
                    "image_id": image["image_id"],
                    "seq": seq,
                    "filename": filename,
                    "size_bytes": len(image_data),
                    "checksum": checksum,
                    "format": image_format,
                    "width": width,
                    "height": height,
                    "path": str(image_path),
                },
            )
            await self.event_hub.publish(build_event_envelope(event))
            
            logger.info(f"Image uploaded: {image['image_id']}, size={len(image_data)}, checksum={checksum[:16]}...")
            
            return web.json_response({
                "ok": True,
                "image_id": image["image_id"],
                "seq": seq,
                "upload_status": IMAGE_STATUS_UPLOADED,
                "size_bytes": len(image_data),
                "checksum": checksum,
                "path": str(image_path),
            })
            
        except Exception as e:
            logger.error(f"Image upload failed: {e}")
            return web.json_response({
                "ok": False,
                "error": str(e),
            }, status=500)

    async def handle_get_images(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/images - Get all images for a meeting.
        
        M5: Returns all uploaded images with metadata.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        images = self.store.get_meeting_images(meeting_id)
        
        return web.json_response({
            "ok": True,
            "meeting_id": meeting_id,
            "images": images,
            "count": len(images),
        })

    async def handle_get_image(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/images/{image_id} - Get image details.
        
        M5: Returns details of a specific image including analysis results.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        image_id = request.match_info.get("image_id", "").strip()
        
        if not meeting_id:
            return web.json_response({"ok": False, "error": "meeting_id required"}, status=400)
        if not image_id:
            return web.json_response({"ok": False, "error": "image_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        image = self.store.get_meeting_image(image_id)
        if not image or image["meeting_id"] != meeting_id:
            return web.json_response({"ok": False, "error": "image_not_found"}, status=404)
        
        return web.json_response({
            "ok": True,
            "image": image,
        })

    async def handle_serve_image(self, request: web.Request) -> web.Response:
        """GET /v2/meetings/{meeting_id}/images/{image_id}/file - Serve image file.
        
        M5: Returns the actual image file content.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        image_id = request.match_info.get("image_id", "").strip()
        
        if not meeting_id or not image_id:
            return web.json_response({"ok": False, "error": "meeting_id and image_id required"}, status=400)

        image = self.store.get_meeting_image(image_id)
        if not image or image["meeting_id"] != meeting_id:
            return web.json_response({"ok": False, "error": "image_not_found"}, status=404)

        image_path = Path(image["original_path"])
        if not image_path.exists():
            return web.json_response({"ok": False, "error": "file_not_found"}, status=404)
        
        # Determine content type
        fmt = image.get("format") or "jpeg"
        content_type = {
            "jpeg": "image/jpeg",
            "jpg": "image/jpeg",
            "png": "image/png",
            "webp": "image/webp",
        }.get(fmt, "application/octet-stream")
        
        with open(image_path, "rb") as f:
            data = f.read()
        
        return web.Response(
            body=data,
            content_type=content_type,
            headers={
                "Cache-Control": "public, max-age=86400",
                "Content-Disposition": f'inline; filename="{image["filename"]}"',
            },
        )

    async def handle_image_analysis(self, request: web.Request) -> web.Response:
        """POST /v2/meetings/{meeting_id}/images/{image_id}:analyze - Trigger image analysis.
        
        M5: Triggers OpenClaw image analysis for a specific image.
        This is a stub implementation that marks analysis as pending.
        Actual OpenClaw integration would be done via background worker.
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        image_id = request.match_info.get("image_id", "").strip()
        
        if not meeting_id or not image_id:
            return web.json_response({"ok": False, "error": "meeting_id and image_id required"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        image = self.store.get_meeting_image(image_id)
        if not image or image["meeting_id"] != meeting_id:
            return web.json_response({"ok": False, "error": "image_not_found"}, status=404)

        # Emit analysis started event
        event = self.store.append_event(
            meeting_id=meeting_id,
            source="server",
            event_type=EVT_IMAGE_ANALYSIS_STARTED,
            payload={
                "image_id": image_id,
                "seq": image["seq"],
                "original_path": image["original_path"],
            },
        )
        await self.event_hub.publish(build_event_envelope(event))
        
        # Update status to analyzing
        self.store.update_meeting_image(image_id, analysis_status="analyzing")
        
        # TODO: Integrate with OpenClaw image-analysis
        # For now, create a stub result that indicates the integration point
        stub_result = {
            "status": "pending",
            "message": "Image analysis queued. OpenClaw integration pending.",
            "image_id": image_id,
            "queued_at": now_iso(),
        }
        
        # Store the stub result
        self.store.update_meeting_image(
            image_id,
            analysis_status="analyzing",
            analysis_result=stub_result,
        )
        
        logger.info(f"Image analysis queued: {image_id}")
        
        return web.json_response({
            "ok": True,
            "image_id": image_id,
            "analysis_status": "analyzing",
            "message": "Analysis queued. OpenClaw integration pending.",
        })

    async def handle_image_analysis_result(self, request: web.Request) -> web.Response:
        """PATCH /v2/meetings/{meeting_id}/images/{image_id}/analysis - Update analysis result.
        
        M5: Updates the analysis result for an image (called by OpenClaw worker).
        """
        meeting_id = request.match_info.get("meeting_id", "").strip()
        image_id = request.match_info.get("image_id", "").strip()
        
        if not meeting_id or not image_id:
            return web.json_response({"ok": False, "error": "meeting_id and image_id required"}, status=400)

        try:
            data = await request.json()
        except Exception:
            return web.json_response({"ok": False, "error": "invalid_json"}, status=400)

        meeting = self.store.get_meeting(meeting_id)
        if not meeting:
            return web.json_response({"ok": False, "error": "meeting_not_found"}, status=404)

        image = self.store.get_meeting_image(image_id)
        if not image or image["meeting_id"] != meeting_id:
            return web.json_response({"ok": False, "error": "image_not_found"}, status=404)

        status = data.get("status", "completed")
        result = data.get("result", {})
        error = data.get("error")
        
        update_fields = {
            "analysis_status": status,
            "analysis_result": result,
            "analysis_at": now_iso(),
        }
        
        if error:
            update_fields["analysis_error"] = error
        
        self.store.update_meeting_image(image_id, **update_fields)
        
        # Emit appropriate event
        event_type = EVT_IMAGE_ANALYSIS_COMPLETED if status == "completed" else EVT_IMAGE_ANALYSIS_FAILED
        event = self.store.append_event(
            meeting_id=meeting_id,
            source="openclaw",
            event_type=event_type,
            payload={
                "image_id": image_id,
                "status": status,
                "result": result,
                "error": error,
            },
        )
        await self.event_hub.publish(build_event_envelope(event))
        
        logger.info(f"Image analysis updated: {image_id}, status={status}")
        
        return web.json_response({
            "ok": True,
            "image_id": image_id,
            "analysis_status": status,
        })

    def register_routes(self, app: web.Application) -> None:
        """Register V2 API routes."""
        app.router.add_post("/v2/meetings", self.handle_create_meeting)
        app.router.add_post("/v2/meetings/{meeting_id}/mode", self.handle_meeting_mode)
        app.router.add_get("/v2/meetings", self.handle_list_meetings)
        app.router.add_get("/v2/meetings/{meeting_id}", self.handle_get_meeting)
        app.router.add_get("/v2/meetings/{meeting_id}/timeline", self.handle_get_timeline)
        app.router.add_post("/v2/meetings/{meeting_id}/events:batch", self.handle_events_batch)
        # M2 routes - Audio upload and manifest
        app.router.add_post("/v2/meetings/{meeting_id}/audio:upload", self.handle_audio_upload)
        app.router.add_get("/v2/meetings/{meeting_id}/audio/manifest", self.handle_get_upload_manifest)
        app.router.add_get("/v2/meetings/{meeting_id}/audio/pending", self.handle_get_pending_uploads)
        app.router.add_post("/v2/meetings/{meeting_id}/audio:reset-failed", self.handle_reset_failed_upload)
        # M3 routes - Transcription jobs
        app.router.add_post("/v2/meetings/{meeting_id}/transcription:run", self.handle_create_transcription_job)
        app.router.add_get("/v2/meetings/{meeting_id}/transcription", self.handle_get_transcription_jobs)
        app.router.add_get("/v2/transcription/{job_id}", self.handle_get_transcription_job)
        app.router.add_post("/v2/transcription/{job_id}:cancel", self.handle_cancel_transcription_job)
        app.router.add_get("/v2/transcription/queue", self.handle_list_transcription_queue)
        # M4 routes - Refined segments and speaker management
        app.router.add_get("/v2/meetings/{meeting_id}/refined", self.handle_get_refined_segments)
        app.router.add_get("/v2/meetings/{meeting_id}/speakers", self.handle_get_speakers)
        app.router.add_patch("/v2/meetings/{meeting_id}/speakers/{speaker_cluster_id}", self.handle_rename_speaker)
        app.router.add_get("/v2/meetings/{meeting_id}/speakers/history", self.handle_get_speaker_history)
        # M5 routes - Image upload and analysis
        app.router.add_post("/v2/meetings/{meeting_id}/images:upload", self.handle_image_upload)
        app.router.add_get("/v2/meetings/{meeting_id}/images", self.handle_get_images)
        app.router.add_get("/v2/meetings/{meeting_id}/images/{image_id}", self.handle_get_image)
        app.router.add_get("/v2/meetings/{meeting_id}/images/{image_id}/file", self.handle_serve_image)
        app.router.add_post("/v2/meetings/{meeting_id}/images/{image_id}:analyze", self.handle_image_analysis)
        app.router.add_patch("/v2/meetings/{meeting_id}/images/{image_id}/analysis", self.handle_image_analysis_result)
