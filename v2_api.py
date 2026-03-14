"""
Voice Assistant Bridge - V2 Meeting API Routes

Provides:
- POST /v2/meetings - Create meeting
- POST /v2/meetings/{meeting_id}/mode - Toggle meeting mode on/off
- GET /v2/events/stream - WebSocket event stream
- GET /v2/meetings - List meetings
- GET /v2/meetings/{meeting_id} - Get meeting details
"""

from __future__ import annotations

import logging
from typing import Any, Optional

from aiohttp import web

from meeting import (
    MeetingStore,
    EVT_MEETING_MODE_ON,
    EVT_MEETING_MODE_OFF,
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

    def register_routes(self, app: web.Application) -> None:
        """Register V2 API routes."""
        app.router.add_post("/v2/meetings", self.handle_create_meeting)
        app.router.add_post("/v2/meetings/{meeting_id}/mode", self.handle_meeting_mode)
        app.router.add_get("/v2/meetings", self.handle_list_meetings)
        app.router.add_get("/v2/meetings/{meeting_id}", self.handle_get_meeting)
        app.router.add_get("/v2/meetings/{meeting_id}/timeline", self.handle_get_timeline)
        app.router.add_post("/v2/meetings/{meeting_id}/events:batch", self.handle_events_batch)
