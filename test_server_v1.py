#!/usr/bin/env python3
"""Tests for V1 server retry classification helpers."""

import tempfile
import unittest
from pathlib import Path

from server import (
    STATUS_FAILED,
    STATUS_WAITING_OPENCLAW,
    Store,
    VoiceAssistantServer,
    is_non_retriable_openclaw_error,
    now_iso,
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


if __name__ == "__main__":
    unittest.main()
