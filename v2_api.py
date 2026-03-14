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
    MEETING_STATUS_IDLE,
    MEETING_STATUS_PREP,
    MEETING_STATUS_ACTIVE,
    MEETING_STATUS_ENDING,
    build_event_envelope,
    now_iso,
)

logger = logging.getLogger(__name__)


class V2MeetingAPI:
    """V2 Meeting API handler."""

    def __init__(self, store: MeetingStore, event_hub: Any):
        self.store = store
        self.event_hub = event_hub

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

            # After brief processing, mark as archived
            self.store.update_meeting(meeting_id, status=MEETING_STATUS_ENDING)

            return web.json_response({
                "ok": True,
                "meeting_id": meeting_id,
                "status": MEETING_STATUS_ENDING,
                "mode": "off",
                "ended_at": ts,
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
