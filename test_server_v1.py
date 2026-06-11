#!/usr/bin/env python3
"""Tests for V1 server retry classification helpers."""

import tempfile
import unittest
from pathlib import Path

from aiohttp import FormData, web
from aiohttp.test_utils import TestClient, TestServer

from server import (
    STATUS_FAILED,
    STATUS_WAITING_OPENCLAW,
    Store,
    VoiceAssistantServer,
    is_non_retriable_openclaw_error,
    now_iso,
)


PNG_BYTES = (
    b"\x89PNG\r\n\x1a\n"
    b"\x00\x00\x00\rIHDR"
    b"\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00"
    b"\x90wS\xde"
    b"\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x01\x01\x01\x00"
    b"\x18\xdd\x8d\xb0"
    b"\x00\x00\x00\x00IEND\xaeB`\x82"
)


class TestOpenClawRetryClassification(unittest.TestCase):
    def test_plugin_runtime_gateway_context_error_is_non_retriable(self):
        err = (
            'openclaw http 500: {"ok":false,"error":"Plugin runtime subagent methods are only '
            'available during a gateway request."}'
        )
        self.assertTrue(is_non_retriable_openclaw_error(err))

    def test_http_400_is_non_retriable(self):
        self.assertTrue(is_non_retriable_openclaw_error("openclaw http 400: bad request"))

    def test_http_429_remains_retriable(self):
        self.assertFalse(is_non_retriable_openclaw_error("openclaw http 429: too many requests"))

    def test_http_503_remains_retriable(self):
        self.assertFalse(is_non_retriable_openclaw_error("openclaw http 503: service unavailable"))

    def test_timeout_remains_retriable(self):
        self.assertFalse(is_non_retriable_openclaw_error("timeout while connecting to openclaw"))


class TestForwardTaskFastFail(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.server = VoiceAssistantServer(port=0)
        old_store = self.server.store
        self.server.store = Store(Path(self.tmpdir.name) / "state.db")
        old_store.close()

    async def asyncTearDown(self):
        self.server.store.close()
        if hasattr(self.server.meeting_store, "close"):
            self.server.meeting_store.close()
        self.tmpdir.cleanup()

    async def test_non_retriable_openclaw_http_500_fails_immediately(self):
        message_id = "msg-fast-fail"
        now = now_iso()
        self.server.store.create(
            {
                "message_id": message_id,
                "client_id": "test-client",
                "session_id": "test-session",
                "turn_id": "turn-fast-fail",
                "source": "android",
                "text": "probe",
                "status": STATUS_WAITING_OPENCLAW,
                "decision": "forward_openclaw",
                "decision_reason": "test",
                "decision_confidence": 1.0,
                "local_reply": "queued",
                "final_reply": None,
                "retry_count": 0,
                "max_retries": 5,
                "timeout_sec": 30,
                "last_error": None,
                "created_at": now,
                "updated_at": now,
            }
        )

        calls = 0

        async def _fake_chat(_text, _session_id, _message_id, _timeout_sec):
            nonlocal calls
            calls += 1
            raise RuntimeError(
                'openclaw http 500: {"ok":false,"error":"Plugin runtime subagent methods are only available during a gateway request."}'
            )

        self.server.openclaw.chat = _fake_chat

        await self.server._forward_task(message_id)
        row = self.server.store.get(message_id)

        self.assertEqual(calls, 1)
        self.assertIsNotNone(row)
        self.assertEqual(row["status"], STATUS_FAILED)
        self.assertEqual(int(row["retry_count"]), 1)
        self.assertIn("Plugin runtime subagent methods", row["last_error"])


class TestArtifactApi(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.server = VoiceAssistantServer(port=0)
        old_store = self.server.store
        self.server.store = Store(Path(self.tmpdir.name) / "state.db")
        old_store.close()
        self.server.artifacts_dir = Path(self.tmpdir.name) / "artifacts"
        self.server.openclaw.base_url = ""
        self.client = TestClient(TestServer(self.server.create_app()))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.server.store.close()
        if hasattr(self.server.meeting_store, "close"):
            self.server.meeting_store.close()
        self.tmpdir.cleanup()

    async def test_upload_get_and_link_image_artifact(self):
        form = FormData()
        form.add_field("artifact_type", "image")
        form.add_field("client_id", "android-phone")
        form.add_field("session_id", "daily-agent-test")
        form.add_field("source", "android")
        form.add_field("file", PNG_BYTES, filename="camera.png", content_type="image/png")

        upload_resp = await self.client.post("/v1/artifacts", data=form)
        self.assertEqual(200, upload_resp.status)
        upload = await upload_resp.json()
        self.assertTrue(upload["ok"])
        self.assertEqual("image", upload["type"])
        self.assertEqual("image/png", upload["mime_type"])
        artifact_id = upload["artifact_id"]

        get_resp = await self.client.get(f"/v1/artifacts/{artifact_id}")
        self.assertEqual(200, get_resp.status)
        self.assertEqual(PNG_BYTES, await get_resp.read())

        row, deduped = await self.server.submit(
            text="请分析这张图片。",
            client_id="android-phone",
            session_id="daily-agent-test",
            source="android",
            artifacts=[{"artifact_id": artifact_id, "type": "image"}],
            message_id="msg-image-link",
        )

        self.assertFalse(deduped)
        self.assertEqual(STATUS_FAILED, row["status"])
        linked = self.server.store.message_artifacts("msg-image-link")
        self.assertEqual(1, len(linked))
        self.assertEqual(artifact_id, linked[0]["artifact_id"])

    async def test_rejects_non_image_content_for_image_artifact(self):
        form = FormData()
        form.add_field("artifact_type", "image")
        form.add_field("client_id", "android-phone")
        form.add_field("session_id", "daily-agent-test")
        form.add_field("file", b"not an image", filename="note.txt", content_type="text/plain")

        resp = await self.client.post("/v1/artifacts", data=form)

        self.assertEqual(400, resp.status)
        payload = await resp.json()
        self.assertFalse(payload["ok"])
        self.assertIn("image content", payload["error"])

    async def test_upload_get_and_link_audio_artifact(self):
        audio_bytes = b"\x00\x00\x00\x18ftypM4A \x00\x00\x00\x00M4A mp42isom"
        form = FormData()
        form.add_field("artifact_type", "audio")
        form.add_field("client_id", "android-phone")
        form.add_field("session_id", "daily-agent-test")
        form.add_field("source", "android")
        form.add_field("file", audio_bytes, filename="recording.m4a", content_type="audio/mp4")

        upload_resp = await self.client.post("/v1/artifacts", data=form)
        self.assertEqual(200, upload_resp.status)
        upload = await upload_resp.json()
        self.assertTrue(upload["ok"])
        self.assertEqual("audio", upload["type"])
        self.assertEqual("audio/mp4", upload["mime_type"])
        artifact_id = upload["artifact_id"]

        get_resp = await self.client.get(f"/v1/artifacts/{artifact_id}")
        self.assertEqual(200, get_resp.status)
        self.assertEqual(audio_bytes, await get_resp.read())

        row, deduped = await self.server.submit(
            text="录音已保存并上传为音频附件。当前不会伪造转写内容。",
            client_id="android-phone",
            session_id="daily-agent-test",
            source="android",
            artifacts=[{"artifact_id": artifact_id, "type": "audio"}],
            message_id="msg-audio-link",
        )

        self.assertFalse(deduped)
        self.assertEqual(STATUS_FAILED, row["status"])
        linked = self.server.store.message_artifacts("msg-audio-link")
        self.assertEqual(1, len(linked))
        self.assertEqual(artifact_id, linked[0]["artifact_id"])
        self.assertEqual("audio", linked[0]["artifact_type"])

    async def test_audio_transcription_returns_text_only(self):
        async def fake_transcribe(audio_data):
            self.assertEqual(b"pcm", audio_data)
            return "小助手，测试。"

        self.server.transcribe_audio = fake_transcribe

        resp = await self.client.post("/v1/audio/transcriptions", data=b"pcm")

        self.assertEqual(200, resp.status)
        payload = await resp.json()
        self.assertTrue(payload["ok"])
        self.assertEqual("小助手，测试。", payload["text"])

    async def test_audio_transcription_rejects_empty_audio(self):
        resp = await self.client.post("/v1/audio/transcriptions", data=b"")

        self.assertEqual(400, resp.status)
        payload = await resp.json()
        self.assertFalse(payload["ok"])
        self.assertEqual("empty_audio", payload["error"])

    async def test_audio_transcription_reports_stt_failed(self):
        async def fake_transcribe(_audio_data):
            return ""

        self.server.transcribe_audio = fake_transcribe

        resp = await self.client.post("/v1/audio/transcriptions", data=b"pcm")

        self.assertEqual(400, resp.status)
        payload = await resp.json()
        self.assertFalse(payload["ok"])
        self.assertEqual("stt_failed", payload["error"])

    async def test_image_analysis_uses_openclaw_chat_completions_image_input(self):
        form = FormData()
        form.add_field("artifact_type", "image")
        form.add_field("client_id", "android-phone")
        form.add_field("session_id", "daily-agent-test")
        form.add_field("file", PNG_BYTES, filename="camera.png", content_type="image/png")
        upload_resp = await self.client.post("/v1/artifacts", data=form)
        upload = await upload_resp.json()
        artifact = self.server.store.get_artifact(upload["artifact_id"])
        self.assertIsNotNone(artifact)

        received = {}

        async def handle_chat(request):
            received["headers"] = dict(request.headers)
            received["body"] = await request.json()
            return web.json_response(
                {
                    "choices": [
                        {
                            "message": {
                                "role": "assistant",
                                "content": "视觉OK",
                            }
                        }
                    ]
                }
            )

        upstream_app = web.Application()
        upstream_app.router.add_post("/v1/chat/completions", handle_chat)
        upstream = TestServer(upstream_app)
        await upstream.start_server()
        try:
            self.server.openclaw_image_api_url = str(upstream.make_url("")).rstrip("/")
            self.server.openclaw_image_analyze_path = "/v1/chat/completions"
            self.server.openclaw_image_token = "test-token"
            self.server.openclaw_image_agent_id = "vision-agent"
            text = await self.server._analyze_image_artifacts(
                {
                    "message_id": "msg-vision",
                    "session_id": "daily-agent-test",
                    "text": "请识别图片。",
                },
                [artifact],
                timeout=5,
            )
        finally:
            await upstream.close()

        self.assertEqual("视觉OK", text)
        self.assertEqual("Bearer test-token", received["headers"].get("Authorization"))
        self.assertEqual("vision-agent", received["headers"].get("x-openclaw-agent-id"))
        content = received["body"]["messages"][0]["content"]
        self.assertEqual("text", content[0]["type"])
        self.assertEqual("image_url", content[1]["type"])
        self.assertTrue(content[1]["image_url"]["url"].startswith("data:image/png;base64,"))


if __name__ == "__main__":
    unittest.main()
